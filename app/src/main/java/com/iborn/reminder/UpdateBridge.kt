package com.iborn.reminder

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Manual GitHub Releases updater. The APK is downloaded only after the user
 * explicitly chooses the install action in the app.
 */
class UpdateBridge(
    private val activity: Activity,
    private val emit: (String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val repoApi = "https://api.github.com/repos/ibornco/reminder3.0/releases/latest"
    private var latestApkUrl: String? = null
    private var downloadId: Long = -1L

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            installDownloadedApk()
        }
    }

    init {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(downloadReceiver, filter)
        }
    }

    @JavascriptInterface
    fun checkForUpdate() {
        emitStatus("checking", "در حال بررسی نسخه‌های منتشرشده…")
        executor.execute {
            try {
                val connection = (URL(repoApi).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Reminder-Android-Updater")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val release = JSONObject(body)
                val tag = release.optString("tag_name").removePrefix("v").removePrefix("V")
                val assets = release.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val item = assets.getJSONObject(i)
                        if (item.optString("name").endsWith(".apk", true)) {
                            apk = item.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (apk.isNullOrBlank()) {
                    emitStatus("error", "برای آخرین Release فایل APK پیوست نشده است.")
                    return@execute
                }
                val current = currentVersionName().trim().removePrefix("v").removePrefix("V")
                latestApkUrl = apk
                if (compareVersions(tag, current) > 0) {
                    emitStatus("available", "نسخه $tag منتشر شده است. نسخه نصب‌شده: $current")
                } else {
                    emitStatus("latest", "برنامه به‌روز است. نسخه فعلی: $current")
                }
            } catch (e: Exception) {
                emitStatus("error", "بررسی به‌روزرسانی ناموفق بود. اتصال اینترنت و انتشار عمومی GitHub را بررسی کنید.")
            }
        }
    }

    @JavascriptInterface
    fun downloadAndInstall() {
        val url = latestApkUrl
        if (url.isNullOrBlank()) {
            emitStatus("error", "ابتدا بررسی به‌روزرسانی را انجام دهید.")
            return
        }
        activity.runOnUiThread {
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle("به‌روزرسانی Reminder")
                    .setDescription("در حال دانلود نسخه جدید برنامه")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "Reminder-update.apk")
                downloadId = (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                emitStatus("downloading", "دانلود شروع شد. پس از پایان، نصب نسخه جدید باز می‌شود.")
            } catch (e: Exception) {
                emitStatus("error", "شروع دانلود ممکن نشد. دوباره تلاش کنید.")
            }
        }
    }

    private fun installDownloadedApk() {
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(downloadId)
        if (uri == null) {
            emitStatus("error", "دانلود کامل نشد. دوباره تلاش کنید.")
            return
        }
        activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                emitStatus("permission", "برای نصب نسخه جدید، اجازه نصب برنامه از این منبع را فعال کنید.")
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + activity.packageName))
                activity.startActivity(intent)
                return@runOnUiThread
            }
            try {
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(install)
                emitStatus("installing", "نصب‌کننده اندروید باز شد.")
            } catch (e: Exception) {
                emitStatus("error", "باز کردن نصب‌کننده ممکن نشد.")
            }
        }
    }

    private fun currentVersionName(): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            activity.packageManager.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }
        info.versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }

    private fun compareVersions(a: String, b: String): Int {
        fun parts(s: String) = Regex("\\d+").findAll(s).map { it.value.toLongOrNull() ?: 0L }.toList()
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val left = x.getOrElse(i) { 0L }
            val right = y.getOrElse(i) { 0L }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    private fun emitStatus(status: String, message: String) {
        val js = "window.onReminderUpdateStatus && window.onReminderUpdateStatus(" +
            JSONObject.quote(status) + "," + JSONObject.quote(message) + ")"
        activity.runOnUiThread { emit(js) }
    }

    fun dispose() {
        try { activity.unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
        executor.shutdownNow()
    }
}
