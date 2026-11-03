package com.music.bitchord.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.music.bitchord.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.util.Locale

/**
 * Runs update APK downloads in a foreground service so the process stays alive
 * and continues downloading even if the app is closed, swiped away from recents,
 * or the phone screen is turned off / device sleeps.
 *
 * Holds a partial wake lock while downloading to ensure the CPU does not sleep
 * and network transfers complete uninterrupted. Shows progress in the notification shade.
 */
class AppUpdateService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var downloadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BASA:AppUpdateDownload")?.apply {
                setReferenceCounted(false)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelCurrentDownload()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val version = intent.getStringExtra(EXTRA_VERSION)
                if (url.isNullOrBlank() || version.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startDownload(url, version)
            }
            else -> {
                if (downloadJob == null || downloadJob?.isActive != true) {
                    val info = AppUpdateChecker.available.value
                    if (info?.apkUrl != null) {
                        startDownload(info.apkUrl, info.version)
                    } else {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(url: String, version: String) {
        if (downloadJob?.isActive == true) return

        isRunning = true
        promote(version)
        runCatching { wakeLock?.acquire(30 * 60 * 1000L) }

        downloadJob = scope.launch(Dispatchers.IO) {
            val dir = File(cacheDir, AppUpdateChecker.CACHE_SUBDIR).apply { mkdirs() }
            val target = File(dir, "basa-$version.apk")
            val readyMarker = File(dir, "basa-$version.apk.ready")
            val partFile = File(dir, "basa-$version.apk.part")

            // Clean up any old partial / leftover files
            dir.listFiles()?.forEach { file ->
                if (file.name != partFile.name) file.delete()
            }

            AppUpdateChecker.setDownloadState(AppUpdateChecker.DownloadState.Downloading(0f))

            var lastNotificationTime = 0L
            var lastReportedFraction = -1f

            val success = runCatching {
                val request = Request.Builder().url(url).build()
                Http.client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                    val body = response.body ?: error("Empty download body")
                    val total = body.contentLength().takeIf { it > 0 } ?: -1L

                    body.byteStream().use { input ->
                        partFile.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var readTotal = 0L

                            while (isActive) {
                                if (AppUpdateChecker.isDownloadCancelled()) {
                                    partFile.delete()
                                    AppUpdateChecker.setDownloadState(AppUpdateChecker.DownloadState.Idle)
                                    return@use
                                }

                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                readTotal += read

                                val fraction = if (total > 0) {
                                    (readTotal.toFloat() / total).coerceIn(0f, 1f)
                                } else 0f

                                AppUpdateChecker.setDownloadState(
                                    AppUpdateChecker.DownloadState.Downloading(fraction)
                                )

                                val now = System.currentTimeMillis()
                                if (now - lastNotificationTime > 500 || fraction - lastReportedFraction >= 0.02f) {
                                    lastNotificationTime = now
                                    lastReportedFraction = fraction
                                    updateProgressNotification(version, fraction, readTotal, total)
                                }
                            }
                        }
                    }
                }

                if (AppUpdateChecker.isDownloadCancelled()) {
                    partFile.delete()
                    false
                } else {
                    if (partFile.exists() && partFile.length() > 0) {
                        if (target.exists()) target.delete()
                        partFile.renameTo(target)
                        runCatching { readyMarker.createNewFile() }
                        true
                    } else {
                        error("Downloaded file is empty")
                    }
                }
            }

            safeReleaseWakeLock()
            isRunning = false

            if (success.isSuccess && success.getOrNull() == true) {
                AppUpdateChecker.setDownloadState(AppUpdateChecker.DownloadState.Ready(target))
                postReadyNotification(version, target)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            } else {
                if (AppUpdateChecker.isDownloadCancelled()) {
                    AppUpdateChecker.setDownloadState(AppUpdateChecker.DownloadState.Idle)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val errorMsg = success.exceptionOrNull()?.message ?: "Download failed"
                    AppUpdateChecker.setDownloadState(AppUpdateChecker.DownloadState.Failed(errorMsg))
                    postFailedNotification(version, errorMsg)
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
    }

    private fun cancelCurrentDownload() {
        AppUpdateChecker.setDownloadCancelled(true)
        downloadJob?.cancel()
        downloadJob = null
        safeReleaseWakeLock()
        isRunning = false
        val dir = File(cacheDir, AppUpdateChecker.CACHE_SUBDIR)
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        AppUpdateChecker.setDownloadState(AppUpdateChecker.DownloadState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun safeReleaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Foreground service continues downloading in background even if app is swiped away.
    }

    override fun onDestroy() {
        safeReleaseWakeLock()
        scope.cancel()
        isRunning = false
        super.onDestroy()
    }

    private fun promote(version: String) {
        val notification = buildProgressNotification(this, version, 0f, 0L, -1L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateProgressNotification(
        version: String,
        fraction: Float,
        readBytes: Long,
        totalBytes: Long,
    ) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = buildProgressNotification(this, version, fraction, readBytes, totalBytes)
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun postReadyNotification(version: String, file: File) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = buildReadyNotification(this, version, file)
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun postFailedNotification(version: String, error: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = buildFailedNotification(this, version, error)
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.update_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.music.bitchord.action.START_UPDATE_DOWNLOAD"
        const val ACTION_CANCEL = "com.music.bitchord.action.CANCEL_UPDATE_DOWNLOAD"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_VERSION = "extra_version"
        const val EXTRA_SHOW_UPDATE_DIALOG = "extra_show_update_dialog"

        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 2001

        @Volatile
        var isRunning: Boolean = false
            private set

        fun buildProgressNotification(
            context: Context,
            version: String,
            fraction: Float,
            bytesDownloaded: Long,
            totalBytes: Long,
        ): Notification {
            val percent = (fraction * 100).toInt().coerceIn(0, 100)
            val cancelIntent = PendingIntent.getService(
                context,
                1,
                Intent(context, AppUpdateService::class.java).apply { action = ACTION_CANCEL },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val openAppIntent = PendingIntent.getActivity(
                context,
                2,
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    putExtra(EXTRA_SHOW_UPDATE_DIALOG, true)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val contentText = if (totalBytes > 0) {
                val currentMb = bytesDownloaded / (1024f * 1024f)
                val totalMb = totalBytes / (1024f * 1024f)
                String.format(Locale.US, "%.1f MB / %.1f MB · %d%%", currentMb, totalMb, percent)
            } else {
                "$percent%"
            }

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.update_notification_title, version))
                .setContentText(contentText)
                .setProgress(100, percent, totalBytes <= 0)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, context.getString(R.string.cancel), cancelIntent)
                .build()
        }

        fun buildReadyNotification(
            context: Context,
            version: String,
            file: File,
        ): Notification {
            val installIntent = AppUpdateChecker.getInstallIntent(context, file)
            val pendingInstall = PendingIntent.getActivity(
                context,
                3,
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.update_notification_ready_title, version))
                .setContentText(context.getString(R.string.update_notification_ready_text))
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(pendingInstall)
                .addAction(0, context.getString(R.string.install_now), pendingInstall)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        }

        fun buildFailedNotification(
            context: Context,
            version: String,
            error: String,
        ): Notification {
            val openAppIntent = PendingIntent.getActivity(
                context,
                4,
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    putExtra(EXTRA_SHOW_UPDATE_DIALOG, true)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setContentTitle(context.getString(R.string.update_notification_failed_title))
                .setContentText(error)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        }
    }
}

