package com.lima.metalima

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val MODEL = "vosk-model-small-es-0.42"
private const val SHA256 = "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"
private const val MIB = 1024 * 1024L

internal suspend fun spanishVoiceModel(context: Context): File = withContext(Dispatchers.IO) {
    val root = File(context.noBackupFilesDir, "speech-models").apply { mkdirs() }
    val model = File(root, MODEL)
    if (File(model, "am/final.mdl").isFile) return@withContext model

    val archive = File.createTempFile(MODEL, ".zip", root)
    val unpacked = File(root, "$MODEL-unpacking").apply { deleteRecursively(); mkdirs() }
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URI("https://alphacephei.com/vosk/models/$MODEL.zip").toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            check(connection.responseCode == 200)
            connection.inputStream.use { input -> archive.outputStream().use { output -> copyLimited(input, output, 48 * MIB, digest) } }
        } finally {
            connection.disconnect()
        }
        check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == SHA256)

        ZipFile(archive).use { zip ->
            var available = 100 * MIB
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                check(entry.name.startsWith("$MODEL/"))
                val file = File(unpacked, entry.name.removePrefix("$MODEL/")).canonicalFile
                check(file.path.startsWith(unpacked.canonicalPath + File.separator))
                file.parentFile?.mkdirs()
                available -= zip.getInputStream(entry).use { input -> file.outputStream().use { copyLimited(input, it, available) } }
            }
        }
        check(File(unpacked, "am/final.mdl").isFile)
        check(unpacked.renameTo(model))
        model
    } finally {
        archive.delete()
        unpacked.deleteRecursively()
    }
}

private suspend fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Long, digest: MessageDigest? = null): Long {
    val bytes = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(bytes)
        if (read == -1) return total
        total += read
        check(total <= maxBytes)
        digest?.update(bytes, 0, read)
        output.write(bytes, 0, read)
    }
}
