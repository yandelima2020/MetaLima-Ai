package com.lima.metalima

import android.content.Context
import android.opengl.GLES20.*
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Removes projection letterboxing before framing the fullscreen glasses preview. */
internal class ProjectionFraming(
    capturedAspect: Float, private val videoAspect: Float, private val framing: VideoFraming
) : BaseFilterRender() {
    @Volatile var capturedAspect = capturedAspect
    private var program = 0
    private var position = 0
    private var source = 0
    private var captured = 0
    private var contain = 0
    private var cover = 0
    private var blur = 0

    init {
        squareVertex = ByteBuffer.allocateDirect(8 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
        squareVertex.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
    }

    override fun initGlFilter(context: Context) {
        program = GlUtil.createProgram(VERTEX, FRAGMENT)
        check(program != 0)
        position = glGetAttribLocation(program, "aPosition")
        source = glGetUniformLocation(program, "uFrame")
        captured = glGetUniformLocation(program, "uCaptured")
        contain = glGetUniformLocation(program, "uContain")
        cover = glGetUniformLocation(program, "uCover")
        blur = glGetUniformLocation(program, "uBlur")
    }

    override fun drawFilter() {
        glUseProgram(program)
        squareVertex.position(0)
        glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, squareVertex)
        glEnableVertexAttribArray(position)
        val outputAspect = width.toFloat() / height
        val screenAspect = capturedAspect
        val ratio = (videoAspect.takeIf { it > 0f } ?: screenAspect) / outputAspect
        glUniform2f(captured, minOf(1f, screenAspect / outputAspect), minOf(1f, outputAspect / screenAspect))
        glUniform2f(contain, minOf(1f, ratio), minOf(1f, 1f / ratio))
        glUniform2f(cover, minOf(1f, 1f / ratio), minOf(1f, ratio))
        glUniform1i(blur, if (framing == VideoFraming.BLUR) 1 else 0)
        glUniform1i(source, 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, previousTexId)
    }

    override fun disableResources() = glDisableVertexAttribArray(position)

    override fun release() = glDeleteProgram(program)
}

private const val VERTEX = """
    attribute vec2 aPosition;
    varying vec2 vPosition;
    void main() {
        gl_Position = vec4(aPosition, 0.0, 1.0);
        vPosition = aPosition * 0.5;
    }
"""

private const val FRAGMENT = """
    precision mediump float;
    varying vec2 vPosition;
    uniform sampler2D uFrame;
    uniform vec2 uCaptured;
    uniform vec2 uContain;
    uniform vec2 uCover;
    uniform int uBlur;

    vec4 sampleFrame(vec2 point) {
        return texture2D(uFrame, 0.5 + clamp(point, -0.498, 0.498) * uCaptured);
    }

    void main() {
        vec2 source = vPosition * uCover;
        if (uBlur == 0) {
            gl_FragColor = sampleFrame(source);
            return;
        }
        vec2 foreground = vPosition / uContain;
        if (all(lessThanEqual(abs(foreground), vec2(0.5)))) {
            gl_FragColor = sampleFrame(foreground);
            return;
        }
        vec4 blurred = 4.0 * sampleFrame(source);
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                blurred += sampleFrame(source + vec2(float(x), float(y)) * 0.024);
            }
        }
        gl_FragColor = vec4(blurred.rgb * (0.78 / 29.0), 1.0);
    }
"""
