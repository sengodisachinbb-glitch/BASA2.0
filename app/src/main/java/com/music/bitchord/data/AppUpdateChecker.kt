package com.music.bitchord.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    const val CACHE_SUBDIR = "updates"

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

    /** Triggered when an update notification or intent requests showing the update dialog. */
    private val _showDialogTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val showDialogTrigger = _showDialogTrigger.asSharedFlow()

    fun triggerShowUpdateDialog() {
        _showDialogTrigger.tryEmit(Unit)
    }

    /** Set from the UI thread when the user cancels; polled between network reads. */
    @Volatile
    private var downloadCancelled = false

    fun isDownloadCancelled(): Boolean = downloadCancelled

    fun setDownloadCancelled(cancelled: Boolean) {
        downloadCancelled = cancelled
    }

    fun setDownloadState(state: DownloadState) {
        _download.value = state
    }

    suspend fun check(context: Context? = null) = withContext(Dispatchers.IO) {
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
                if (context != null && !AppUpdateService.isRunning) {
                    val dir = File(context.cacheDir, CACHE_SUBDIR)
                    val target = File(dir, "basa-$latest.apk")
                    val readyMarker = File(dir, "basa-$latest.apk.ready")
                    if (target.exists() && readyMarker.exists() && target.length() > 0) {
                        _download.value = DownloadState.Ready(target)
                    }
                }
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
     * Wipes any APK left over from an old run. Preserves an in-progress or completed
     * download matching the current available version so background updates survive restarts.
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        if (AppUpdateService.isRunning) return@withContext
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        val files = dir.listFiles() ?: return@withContext
        val latest = _available.value?.version
        for (file in files) {
            if (latest != null && file.name == "basa-$latest.apk" && file.length() > 0) {
                val marker = File(dir, "basa-$latest.apk.ready")
                if (marker.exists()) {
                    _download.value = DownloadState.Ready(file)
                    continue
                }
            }
            if (latest != null && file.name == "basa-$latest.apk.ready") {
                continue
            }
            file.delete()
        }
    }

    /**
     * Finds the APK asset for this device architecture or universal fallback.
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
            val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: ""
            name.endsWith(".apk", ignoreCase = true) &&
                name.contains("universal", ignoreCase = true) &&
                asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
        } ?: assets.firstOrNull { asset ->
            val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: ""
            name.endsWith(".apk", ignoreCase = true) &&
                asset["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
        }

        targetAsset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /**
     * Starts the background update service so the download runs in a foreground service
     * with wake lock, continuing even if the app closes or the mobile sleeps.
     */
    fun startDownload(context: Context) {
        val info = _available.value ?: return
        val url = info.apkUrl ?: return
        downloadCancelled = false
        _download.value = DownloadState.Downloading(0f)
        val intent = Intent(context, AppUpdateService::class.java).apply {
            action = AppUpdateService.ACTION_START
            putExtra(AppUpdateService.EXTRA_URL, url)
            putExtra(AppUpdateService.EXTRA_VERSION, info.version)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Backward-compatible coroutine helper that triggers the background download service.
     */
    suspend fun downloadApk(context: Context) {
        startDownload(context)
    }

    /** Stops an in-flight download and cancels the background update service. */
    @JvmOverloads
    fun cancelDownload(context: Context? = null) {
        downloadCancelled = true
        _download.value = DownloadState.Idle
        context?.let { ctx ->
            val intent = Intent(ctx, AppUpdateService::class.java).apply {
                action = AppUpdateService.ACTION_CANCEL
            }
            ctx.startService(intent)
        }
    }

    /** Back to square one after a failure, so the dialog offers Download again. */
    fun resetDownload() {
        _download.value = DownloadState.Idle
    }

    /**
     * Builds the Intent to hand a downloaded APK to the system package installer.
     */
    fun getInstallIntent(context: Context, file: File): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK,
            )
    }

    /**
     * Hands a downloaded APK to the system installer.
     *
     * Sideloaded apps need the user's blessing per app ("install unknown apps");
     * without it the installer intent silently does nothing on most ROMs, so
     * the user is sent to that one switch first and taps Install again after.
     * Some TV builds have no such settings screen; there the installer is
     * launched anyway and shows its own "not allowed" prompt with a way in.
     */
    fun installApk(context: Context, file: File) {
        runCatching {
            val intent = getInstallIntent(context, file)
            context.startActivity(intent)
        }
    }

    /** A version split into its numeric dotted parts and whether it carries a "-suffix" (e.g. "-beta2"). */
    private data class ParsedVersion(val parts: List<Int>, val isPreRelease: Boolean)

    private fun parseVersion(raw: String): ParsedVersion {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return ParsedVersion(base.split(".").map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /**
     * Numeric, dot-separated comparison — "1.10" outranks "1.9" — with one
     * extra rule: a "-betaN" build (see the debug build type's
     * `versionNameSuffix` in app/build.gradle.kts) is treated as older than a
     * plain release of the same numbers, since the beta by definition predates
     * the tag it was testing toward. Without this, a beta and the release it
     * matches compare equal and testers never get nudged onto the real build.
     */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = parseVersion(latest)
        val c = parseVersion(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.isPreRelease && !l.isPreRelease
    }
}
