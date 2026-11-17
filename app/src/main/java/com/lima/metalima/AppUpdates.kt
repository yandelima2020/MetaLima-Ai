package com.lima.metalima

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val REPO = "yandelima2020/MetaLima-Ai"
private const val RETRY_INTERVAL = 30 * 60 * 1000L
private const val APK_TYPE = "application/vnd.android.package-archive"
internal const val RELEASES_URL = "https://github.com/$REPO/releases"

private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("""v?(\d{1,5})\.(\d{1,5})\.(\d{1,5})""")
        fun parse(tag: String): Version? {
            val match = pattern.matchEntire(tag) ?: return null
            val (major, minor, patch) = match.destructured
            return Version(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

private data class AppUpdate(val version: Version, val download: String, val digest: String?)

internal enum class UpdateStage { CHECKING, AVAILABLE, CURRENT, DOWNLOADING, VERIFYING, READY, FAILED }

internal class AppUpdates(private val activity: MainActivity) {
    private val prefs = activity.getSharedPreferences("github-updates", Context.MODE_PRIVATE)
    private val manager = activity.getSystemService(DownloadManager::class.java)
    private var release: AppUpdate? by mutableStateOf(null)
    private var readyUri: Uri? = null
    var showNotice by mutableStateOf(false)
        private set
    var downloadId by mutableLongStateOf(prefs.getLong("download-id", -1L))
        private set
    var stage by mutableStateOf(if (downloadId < 0) UpdateStage.CHECKING else UpdateStage.DOWNLOADING)
        private set
    var progress by mutableIntStateOf(-1)
        private set
    val version get() = release?.version?.toString()

    suspend fun refresh() {
        if (downloadId >= 0) return
        stage = UpdateStage.CHECKING
        release = withContext(Dispatchers.IO) { checkForUpdate(activity) }
        stage = if (release == null) UpdateStage.CURRENT else UpdateStage.AVAILABLE
        showNotice = release != null
    }

    fun dismiss() { showNotice = false }

    fun beginDownload() {
        val next = release ?: return
        if (downloadId >= 0) return
        dismiss()
        val name = "MetaLima-Ai-${next.version}.apk"
        try {
            val request = DownloadManager.Request(Uri.parse(next.download))
                .setTitle(name)
                .setMimeType(APK_TYPE)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            val id = manager.enqueue(request)
            prefs.edit().putLong("download-id", id).putString("download-name", name)
                .putString("download-digest", next.digest).remove("install-offered").apply()
            progress = -1
            stage = UpdateStage.DOWNLOADING
            downloadId = id
        } catch (_: RuntimeException) {
            stage = UpdateStage.FAILED
        }
    }

    suspend fun awaitInstaller(id: Long): Boolean {
        val name = prefs.getString("download-name", null) ?: return fail(id)
        val target = Version.parse(name.removePrefix("MetaLima-Ai-").removeSuffix(".apk")) ?: return fail(id)
        val current = installedVersion(activity) ?: return fail(id)
        if (target <= current) {
            clearDownload(id)
            stage = UpdateStage.CHECKING
            return false
        }
        while (downloadId == id) {
            val record = withContext(Dispatchers.IO) {
                runCatching {
                    manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val percent = if (total > 0) (done * 100 / total).coerceIn(0, 100).toInt() else -1
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) to percent
                    }
                }.getOrNull()
            } ?: return fail(id)
            when (record.first) {
                DownloadManager.STATUS_FAILED -> return fail(id)
                DownloadManager.STATUS_SUCCESSFUL -> {
                    if (readyUri == null) {
                        stage = UpdateStage.VERIFYING
                        val uri = withContext(Dispatchers.IO) {
                            val folder = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@withContext null
                            val file = File(folder, name)
                            runCatching {
                                if (isValidUpdate(activity, file, prefs.getString("download-digest", null))) manager.getUriForDownloadedFile(id)
                                else null
                            }.getOrNull()
                        } ?: return fail(id)
                        readyUri = uri
                    }
                    stage = UpdateStage.READY
                    if (prefs.getBoolean("install-offered", false)) return false
                    prefs.edit().putBoolean("install-offered", true).apply()
                    return true
                }
                else -> progress = record.second
            }
            delay(1_000)
        }
        return false
    }

    private fun fail(id: Long): Boolean {
        clearDownload(id)
        stage = UpdateStage.FAILED
        return false
    }

    private fun clearDownload(id: Long) {
        runCatching { manager.remove(id) }
        prefs.edit().remove("download-id").remove("download-name").remove("download-digest").remove("install-offered").apply()
        readyUri = null
        downloadId = -1L
    }

    fun install() {
        val uri = readyUri ?: return
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { fail(downloadId) }
    }
}

@Composable internal fun rememberAppUpdates(activity: MainActivity): Pair<AppUpdates, () -> Unit> {
    val updates = remember(activity) { AppUpdates(activity) }
    val scope = rememberCoroutineScope()
    val allowInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (activity.packageManager.canRequestPackageInstalls()) updates.install()
    }
    fun install() {
        if (activity.packageManager.canRequestPackageInstalls()) updates.install()
        else allowInstall.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
    }
    val id = updates.downloadId
    LaunchedEffect(id < 0) {
        if (id < 0) activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (updates.stage != UpdateStage.FAILED) updates.refresh()
        }
    }
    LaunchedEffect(id) {
        if (id >= 0) activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (updates.awaitInstaller(id)) install()
        }
    }
    return updates to {
        when (updates.stage) {
            UpdateStage.AVAILABLE -> updates.beginDownload()
            UpdateStage.READY -> install()
            UpdateStage.CURRENT, UpdateStage.FAILED -> scope.launch { updates.refresh() }
            else -> Unit
        }
    }
}

@Composable internal fun UpdateControls(updates: AppUpdates, action: () -> Unit) {
    when (updates.stage) {
        UpdateStage.AVAILABLE -> {
            Text(stringResource(R.string.update_title))
            Button(onClick = action, colors = ButtonDefaults.buttonColors(containerColor = Lime)) {
                Text(stringResource(R.string.update_now, updates.version.orEmpty()))
            }
        }
        UpdateStage.DOWNLOADING -> {
            Text(stringResource(R.string.update_downloading))
            val progress = updates.progress
            if (progress >= 0) LinearProgressIndicator(progress = { progress / 100f }) else LinearProgressIndicator()
        }
        UpdateStage.VERIFYING -> Text(stringResource(R.string.update_verifying))
        UpdateStage.READY -> Button(onClick = action) { Text(stringResource(R.string.update_install)) }
        UpdateStage.CHECKING -> Text(stringResource(R.string.update_checking))
        else -> {
            if (updates.stage == UpdateStage.FAILED) Text(stringResource(R.string.update_failed))
            OutlinedButton(onClick = action) { Text(stringResource(R.string.update_check)) }
        }
    }
}

@Composable internal fun UpdatePrompt(updates: AppUpdates, action: () -> Unit) {
    val version = updates.version ?: return
    if (!updates.showNotice) return
    AlertDialog(onDismissRequest = updates::dismiss,
        title = { Text(stringResource(R.string.update_title)) },
        text = { Text(stringResource(R.string.update_description, version)) },
        confirmButton = { TextButton(onClick = action) { Text(stringResource(R.string.update_download)) } },
        dismissButton = { TextButton(onClick = updates::dismiss) { Text(stringResource(R.string.update_later)) } })
}

@Suppress("DEPRECATION")
private fun isValidUpdate(context: Context, file: File, digest: String?): Boolean {
    if (!file.isFile) return false
    if (digest != null) {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(bytes)
                if (read < 0) break
                sha.update(bytes, 0, read)
            }
        }
        if ("sha256:" + sha.digest().joinToString("") { "%02x".format(it) } != digest) return false
    }
    val packages = context.packageManager
    val current = packages.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val candidate = packages.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
    val signing = current.signingInfo?.apkContentsSigners?.toSet() ?: return false
    return candidate.packageName == current.packageName && candidate.longVersionCode > current.longVersionCode &&
        candidate.signingInfo?.apkContentsSigners?.toSet() == signing
}

internal fun openGithubRelease(activity: MainActivity, url: String = RELEASES_URL) {
    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun checkForUpdate(context: Context): AppUpdate? {
    val installed = installedVersion(context) ?: return null
    val prefs = context.getSharedPreferences("github-updates", Context.MODE_PRIVATE)
    fun cached(): AppUpdate? {
        val version = Version.parse(prefs.getString("version", "").orEmpty()) ?: return null
        return if (version > installed) updateFor(version, prefs.getString("download", "").orEmpty(), prefs.getString("digest", null)) else null
    }
    val now = System.currentTimeMillis()
    if (now < prefs.getLong("retry-after", 0L)) return cached()
    try {
        val connection = URI("https://api.github.com/repos/$REPO/releases?per_page=12").toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            prefs.getString("etag", null)?.let { connection.setRequestProperty("If-None-Match", it) }
            when (connection.responseCode) {
                200 -> {
                    val releases = connection.inputStream.bufferedReader().use { JSONArray(it.readText()) }
                    val update = newestApk(releases)
                    prefs.edit().putString("etag", connection.getHeaderField("ETag"))
                        .putString("version", update?.version?.toString()).putString("download", update?.download)
                        .putString("digest", update?.digest).apply()
                }
                403, 429 -> {
                    val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()?.takeIf { it in 1..86_400 }
                    prefs.edit().putLong("retry-after", now + (retryAfter?.times(1000) ?: RETRY_INTERVAL)).apply()
                }
            }
        } finally { connection.disconnect() }
    } catch (stop: CancellationException) {
        throw stop
    } catch (_: Exception) {
        // An offline check leaves the last release available.
    }
    return cached()
}

@Suppress("DEPRECATION")
private fun installedVersion(context: Context): Version? =
    Version.parse(context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())

private fun newestApk(releases: JSONArray): AppUpdate? {
    var best: AppUpdate? = null
    for (index in 0 until releases.length()) {
        val release = releases.optJSONObject(index) ?: continue
        if (release.optBoolean("draft")) continue
        val version = Version.parse(release.optString("tag_name")) ?: continue
        if (best?.let { version <= it.version } == true) continue
        val assets = release.optJSONArray("assets") ?: continue
        for (assetIndex in 0 until assets.length()) {
            val asset = assets.optJSONObject(assetIndex) ?: continue
            if (asset.optString("state") != "uploaded" || asset.optLong("size") <= 0) continue
            val update = updateFor(version, asset.optString("browser_download_url"), asset.optString("digest")) ?: continue
            best = update
            break
        }
    }
    return best
}

private fun updateFor(version: Version, download: String, digest: String?): AppUpdate? {
    val expected = "$RELEASES_URL/download/v$version/MetaLima-Ai-$version.apk"
    return if (download == expected) AppUpdate(version, download,
        digest?.takeIf { it.matches(Regex("sha256:[0-9a-f]{64}")) }) else null
}
