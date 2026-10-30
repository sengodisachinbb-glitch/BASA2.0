package com.music.bitchord.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File

/**
 * BitChord ships as a sideloaded APK off GitHub Releases rather than through
 * a store, so there's nothing to push an update notice on its own — this
 * polls the repo's "latest release" once per launch and compares its tag
 * against the running build.
 *
 * The update itself is also handled here: the release's `.apk` asset is
 * downloaded into the app's cache and handed to the system package installer,
 * so the whole round trip stays inside the app instead of bouncing out to a
 * browser.
 */
object AppUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        /** The release's own Markdown body, shown as this update's "what's new". */
        val notes: String?,
    )

    private const val CACHE_SUBDIR = "updates"

    fun getReleaseApiUrl(): String? {
        val repo = com.music.bitchord.data.settings.AppSettings.getEffectiveUpdateRepo()
        if (repo.isBlank() || repo.contains("YOUR_GITHUB_USERNAME", ignoreCase = true)) {
            return null
        }
        return "https://api.github.com/repos/$repo/releases/latest"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    /** Where this update's APK download currently stands, for the dialog's progress row. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()

    /** Set from the UI thread when the user cancels; polled between network reads. */
    @Volatile
    private var downloadCancelled = false

    suspend fun check() = withContext(Dispatchers.IO) {
        val apiUrl = getReleaseApiUrl() ?: return@withContext
        runCatching {
            val request = Request.Builder().url(apiUrl).build()
            val body = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            } ?: return@runCatching
            val release = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
            val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: ""
            val apkUrl = apkAssetUrl(release)
            val rawNotes = release["body"]?.jsonPrimitive?.contentOrNull
            val notes = sanitizeWhatsNew(rawNotes)
            val latest = tag.removePrefix("v")
            if (isNewer(latest, BuildConfig.VERSION_NAME) && apkUrl != null) {
                _available.value = UpdateInfo(latest, url, apkUrl, notes)
            }
        }
    }

    /**
     * Cleans up release notes to only extract "What's New" content (features and changelog),
     * stripping out GitHub repo/PR/commit links, Discord support details, banners, and
     * BitChord references.
     */
    fun sanitizeWhatsNew(rawNotes: String?): String? {
        if (rawNotes.isNullOrBlank()) return null

        val lines = rawNotes.lines()
        val keptLines = mutableListOf<String>()
        var skippingSection = false

        for (line in lines) {
            val trimmed = line.trim()

            // Detect section headers that should be excluded entirely
            if (trimmed.startsWith("#")) {
                val headerText = trimmed.trimStart('#').trim().lowercase()
                if (headerText.startsWith("install") ||
                    headerText.startsWith("download") ||
                    headerText.startsWith("desktop") ||
                    headerText.startsWith("asset") ||
                    headerText.startsWith("contributor") ||
                    headerText.startsWith("support") ||
                    headerText.contains("discord") ||
                    headerText.contains("github")
                ) {
                    skippingSection = true
                    continue
                } else {
                    skippingSection = false
                }
            }

            if (skippingSection) continue

            // Strip markdown images (e.g. ![BitChord banner](...))
            if (trimmed.startsWith("![") && trimmed.contains("](") && trimmed.endsWith(")")) {
                continue
            }

            // Strip Discord links, server invites, or support channel lines
            if (trimmed.contains("discord.gg", ignoreCase = true) ||
                trimmed.contains("discord.com/invite", ignoreCase = true) ||
                trimmed.contains("support channel", ignoreCase = true) ||
                trimmed.equals("discord", ignoreCase = true)
            ) {
                continue
            }

            // Strip GitHub links, commit diffs, or PR changelog details
            if (trimmed.contains("github.com", ignoreCase = true) ||
                trimmed.startsWith("Full list of changes", ignoreCase = true) ||
                trimmed.startsWith("Full changelog", ignoreCase = true) ||
                trimmed.contains("pull request", ignoreCase = true) ||
                trimmed.contains("git commit", ignoreCase = true)
            ) {
                continue
            }

            // Strip installation or OS section lines if appearing without a header
            if (trimmed.startsWith("**Android", ignoreCase = true) ||
                trimmed.startsWith("**iOS", ignoreCase = true) ||
                trimmed.startsWith("**macOS", ignoreCase = true) ||
                trimmed.startsWith("**Windows", ignoreCase = true) ||
                trimmed.startsWith("**Linux", ignoreCase = true)
            ) {
                continue
            }

            // Strip standalone binary / archive filenames
            if (trimmed.endsWith(".apk", ignoreCase = true) ||
                trimmed.endsWith(".apk`", ignoreCase = true) ||
                trimmed.endsWith(".dmg", ignoreCase = true) ||
                trimmed.endsWith(".dmg`", ignoreCase = true) ||
                trimmed.endsWith(".exe", ignoreCase = true) ||
                trimmed.endsWith(".deb", ignoreCase = true) ||
                trimmed.endsWith(".rpm", ignoreCase = true)
            ) {
                continue
            }

            // Remove inline markdown links pointing to github or discord
            var cleanLine = line
                .replace(Regex("\\[([^\\]]+)\\]\\(https?://github\\.com[^)]*\\)"), "$1")
                .replace(Regex("\\[([^\\]]+)\\]\\(https?://discord[^)]*\\)"), "$1")
                .replace(Regex("\\(\\s*https?://github\\.com/\\S+\\s*\\)"), "")
                .replace(Regex("\\(\\s*https?://discord\\.(?:gg|com)/\\S+\\s*\\)"), "")
                .replace(Regex("https?://github\\.com/\\S+"), "")
                .replace(Regex("https?://discord\\.(?:gg|com)/\\S+"), "")

            // Replace any occurrence of "BitChord" or "bitchord" with "BASA"
            cleanLine = cleanLine
                .replace("BitChord", "BASA", ignoreCase = false)
                .replace("bitchord", "basa", ignoreCase = false)
                .replace("BITCHORD", "BASA", ignoreCase = false)

            keptLines.add(cleanLine)
        }

        val result = keptLines.joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        return if (result.isBlank()) null else result
    }

    /**
     * Wipes any APK left over from a previous run. Called once at cold start
     * so a downloaded update is only ever "Install Now" for the session that
     * downloaded it — the next launch starts clean rather than trying to work
     * out whether a leftover file is still good.
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, CACHE_SUBDIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * The release usually carries `.apk` files; selects the best matching APK
     * for the device's architecture or universal fallback.
     */
    private fun apkAssetUrl(release: JsonObject): String? = runCatching {
        val assets = release["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?: emptyList()

        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""

        val targetAsset = assets.firstOrNull { asset ->
            val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: ""
            name.contains("basa", ignoreCase = true) &&
                name.endsWith(".apk", ignoreCase = true) &&
                (primaryAbi.isNotEmpty() && name.contains(primaryAbi, ignoreCase = true)) &&
                asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
        } ?: assets.firstOrNull { asset ->
            val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: ""
            name.contains("basa", ignoreCase = true) &&
                name.endsWith(".apk", ignoreCase = true) &&
                name.contains("universal", ignoreCase = true) &&
                asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
        } ?: assets.firstOrNull { asset ->
            val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: ""
            name.contains("basa", ignoreCase = true) &&
                name.endsWith(".apk", ignoreCase = true) &&
                asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
        } ?: assets.firstOrNull { asset ->
            val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: ""
            name.endsWith(".apk", ignoreCase = true) &&
                (primaryAbi.isNotEmpty() && name.contains(primaryAbi, ignoreCase = true)) &&
                asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
        } ?: assets.firstOrNull { asset ->
            asset["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true &&
                asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
        }

        targetAsset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /**
     * Streams the current update's APK into the app cache, reporting progress
     * through [download]. A finished file survives a cancelled dialog: until
     * the state is reset, "Install Now" comes straight back without a second
     * download.
     */
    suspend fun downloadApk(context: Context): Unit = withContext(Dispatchers.IO) {
        val info = _available.value ?: return@withContext
        val url = info.apkUrl ?: return@withContext
        downloadCancelled = false
        _download.value = DownloadState.Downloading(0f)

        runCatching {
            val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            // Drop anything left over from an earlier attempt.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "basa-${info.version}.apk")

            val request = Request.Builder().url(url).build()
            Http.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            if (downloadCancelled) {
                                _download.value = DownloadState.Idle
                                return@withContext
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            total?.let {
                                _download.value =
                                    DownloadState.Downloading((readTotal.toFloat() / it).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            _download.value = DownloadState.Ready(target)
        }.onFailure { error ->
            _download.value = if (downloadCancelled) {
                DownloadState.Idle
            } else {
                DownloadState.Failed(error.message ?: "Download failed")
            }
        }
    }

    /** Stops an in-flight download; the next read loop sees this and bails. */
    fun cancelDownload() {
        downloadCancelled = true
    }

    /** Back to square one after a failure, so the dialog offers Download again. */
    fun resetDownload() {
        _download.value = DownloadState.Idle
    }

    /**
     * Hands a downloaded APK to the system installer.
     *
     * Sideloaded apps need the user's blessing per app ("install unknown apps");
     * without it the installer intent silently does nothing on most ROMs, so
     * the user is sent to that one switch first and taps Install again after.
     */
    fun installApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
        )
    }

    /** Numeric, dot-separated comparison — "1.10" outranks "1.9". */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
