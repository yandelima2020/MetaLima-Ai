/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.lima.metalima.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque

/** Feeds complete compressed access units to MediaCodec, with an independent preview queue. */
class HevcDecoder(private val delayedFrame: () -> Unit = {}) {
  private data class Frame(val bytes: ByteArray, val pts: Long, val sync: Boolean, val arrived: Long)
  private val incoming = ArrayDeque<Frame>()
  private val inputIndices = ArrayDeque<Int>()
  private val presentationTimes = linkedMapOf<Long, Long>()
  private val failedDecoders = mutableSetOf<String>()
  private val lock = Any()
  private lateinit var surface: Surface
  private var width = 0
  private var height = 0
  @Volatile private var thread: HandlerThread? = null
  @Volatile private var handler: Handler? = null
  private var codec: MediaCodec? = null
  @Volatile private var enabled = true
  private var awaitingKeyframe = true
  private var restartAtKeyframe = false
  private var recoveryCsd: ByteArray? = null
  private val decode = Runnable { feed() }

  fun start(width: Int, height: Int, surface: Surface) {
    this.width = width
    this.height = height
    this.surface = surface
    val worker = HandlerThread("MetaLimaPreview", Process.THREAD_PRIORITY_VIDEO).apply { start() }
    thread = worker
    handler = Handler(worker.looper)
  }

  fun decodeFrame(data: ByteArray, presentationTimeUs: Long, config: () -> ByteArray?) {
    val sync = isHevcKeyFrame(data)
    val now = SystemClock.elapsedRealtime()
    synchronized(lock) {
      if (!enabled) return
      if (incoming.size >= 10 || incoming.peekFirst()?.let { now - it.arrived > 380 } == true) {
        delayedFrame()
        incoming.clear()
        awaitingKeyframe = true
        restartAtKeyframe = true
      }
      if (awaitingKeyframe) {
        if (!sync) return
        recoveryCsd = config() ?: return
        awaitingKeyframe = false
      }
      incoming.addLast(Frame(data, presentationTimeUs, sync, now))
    }
    handler?.let { worker ->
      worker.removeCallbacks(decode)
      worker.post(decode)
    }
  }

  fun stop() {
    enabled = false
    synchronized(lock) { incoming.clear() }
    val worker = handler
    handler = null
    val oldThread = thread
    thread = null
    worker?.removeCallbacks(decode)
    worker?.post {
      releaseCodec()
      oldThread?.quit()
    }
  }

  private fun feed() {
    if (!enabled) return
    val config = synchronized(lock) {
      if (awaitingKeyframe || incoming.isEmpty()) return
      if (codec == null || restartAtKeyframe) {
        restartAtKeyframe = false
        recoveryCsd
      } else null
    }
    if (config != null) {
      releaseCodec()
      codec = openCodec(config)
      if (codec == null) {
        waitForKeyframe()
        return
      }
    }
    val active = codec ?: return
    while (enabled && inputIndices.isNotEmpty()) {
      val frame = synchronized(lock) {
        if (awaitingKeyframe || restartAtKeyframe) null else incoming.pollFirst()
      } ?: return
      val index = inputIndices.removeFirst()
      try {
        val buffer = checkNotNull(active.getInputBuffer(index))
        buffer.clear()
        buffer.put(frame.bytes)
        presentationTimes[frame.pts] = frame.arrived
        if (presentationTimes.size > 64) presentationTimes.remove(presentationTimes.keys.first())
        active.queueInputBuffer(index, 0, frame.bytes.size, frame.pts, if (frame.sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
      } catch (e: Exception) {
        Log.w(TAG, "Could not decode a preview frame", e)
        waitForKeyframe()
        releaseCodec()
        return
      }
    }
  }

  private fun openCodec(csd: ByteArray): MediaCodec? {
    val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
    val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
      .filter { info -> !info.isEncoder && info.name !in BLOCKED_DECODERS && info.name !in failedDecoders &&
        info.supportedTypes.any { it.equals(mime, true) } }
      .sortedByDescending { it.isHardwareAccelerated }
    for (info in codecs) {
      if (!enabled) return null
      val candidate = runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull() ?: continue
      try {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
          setByteBuffer("csd-0", ByteBuffer.wrap(csd))
          setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 256 * 1024))
          if (info.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
          }
        }
        candidate.setCallback(callback, handler)
        candidate.configure(format, surface, null, 0)
        candidate.start()
        Log.d(TAG, "Preview decoder: ${info.name}")
        return candidate
      } catch (e: Exception) {
        runCatching { candidate.release() }
        Log.w(TAG, "Could not use ${info.name}", e)
      }
    }
    return null
  }

  private val callback = object : MediaCodec.Callback() {
    override fun onInputBufferAvailable(source: MediaCodec, index: Int) {
      if (source != codec || !enabled) return
      inputIndices.addLast(index)
      feed()
    }

    override fun onOutputBufferAvailable(source: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
      if (source != codec || !enabled) return
      val arrival = presentationTimes.remove(info.presentationTimeUs)
      val late = arrival != null && SystemClock.elapsedRealtime() - arrival >= 350
      if (late && info.size > 0) delayedFrame()
      val render = info.size > 0 && !late
      runCatching { source.releaseOutputBuffer(index, render) }
    }

    override fun onError(source: MediaCodec, error: MediaCodec.CodecException) {
      if (source != codec || !enabled) return
      Log.w(TAG, "Preview codec failed", error)
      if (!error.isTransient) failedDecoders.add(source.name)
      waitForKeyframe()
      releaseCodec()
    }

    override fun onOutputFormatChanged(source: MediaCodec, format: MediaFormat) = Unit
  }

  private fun waitForKeyframe() = synchronized(lock) {
    awaitingKeyframe = true
    incoming.clear()
  }

  private fun releaseCodec() {
    val previous = codec
    codec = null
    inputIndices.clear()
    presentationTimes.clear()
    previous?.let { runCatching { it.release() } }
  }

  private companion object {
    const val TAG = "HevcDecoder"
    val BLOCKED_DECODERS = setOf("OMX.Exynos.hevc.dec", "c2.mtk.hevc.decoder")
  }
}
