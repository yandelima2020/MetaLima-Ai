package com.lima.metalima

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val REPO = "yandelima2020/MetaLima-Ai"
private const val RETRY_INTERVAL = 30 * 60 * 1000L
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

private data class AppUpdate(val version: Version, val download: String)

@Composable internal fun UpdatePrompt(activity: MainActivity) {
    var offered by remember { mutableStateOf<AppUpdate?>(null) }
    LaunchedEffect(activity) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            offered = withContext(Dispatchers.IO) { checkForUpdate(activity) }
        }
    }
    val release = offered ?: return
    fun dismiss() { offered = null }
    AlertDialog(onDismissRequest = ::dismiss,
        title = { Text(stringResource(R.string.update_title)) },
        text = { Text(stringResource(R.string.update_description, release.version.toString())) },
        confirmButton = {
            TextButton(onClick = {
                openGithubRelease(activity, release.download)
                dismiss()
            }) { Text(stringResource(R.string.update_download)) }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text(stringResource(R.string.update_later)) } })
}

internal fun openGithubRelease(activity: MainActivity, url: String = RELEASES_URL) {
    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun checkForUpdate(context: Context): AppUpdate? {
    @Suppress("DEPRECATION")
    val installed = Version.parse(context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()) ?: return null
    val prefs = context.getSharedPreferences("github-updates", Context.MODE_PRIVATE)
    fun cached(): AppUpdate? {
        val version = Version.parse(prefs.getString("version", "").orEmpty()) ?: return null
        return if (version > installed) updateFor(version, prefs.getString("download", "").orEmpty()) else null
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
                        .putString("version", update?.version?.toString()).putString("download", update?.download).apply()
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
            val update = updateFor(version, asset.optString("browser_download_url")) ?: continue
            best = update
            break
        }
    }
    return best
}

private fun updateFor(version: Version, download: String): AppUpdate? {
    val expected = "$RELEASES_URL/download/v$version/MetaLima-Ai-$version.apk"
    return if (download == expected) AppUpdate(version, download) else null
}
