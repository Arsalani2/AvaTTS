package com.example.service.queue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import java.util.Locale

class TtsQueueService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val projectName = intent?.getStringExtra(EXTRA_PROJECT_NAME) ?: "پروژه صوتی"
        val currentChunk = intent?.getIntExtra(EXTRA_CURRENT_CHUNK, 0) ?: 0
        val totalChunks = intent?.getIntExtra(EXTRA_TOTAL_CHUNKS, 0) ?: 0
        val completedChunks = intent?.getIntExtra(EXTRA_COMPLETED_CHUNKS, 0) ?: 0
        val keyName = intent?.getStringExtra(EXTRA_KEY_NAME) ?: ""
        // BUG-38: نام گویندهٔ خالی دیگر به «گوگل» سخت‌کدشده فرو نمی‌ریزد؛
        // مقدار واقعی از سوی engine با project.selectedVoice ارسال می‌شود
        val speakerName = intent?.getStringExtra(EXTRA_SPEAKER_NAME)?.takeIf { it.isNotBlank() } ?: ""
        val downloadedBytes = intent?.getLongExtra(EXTRA_DOWNLOADED_BYTES, 0L) ?: 0L
        val errorCount = intent?.getIntExtra(EXTRA_ERROR_COUNT, 0) ?: 0
        val cooldownSec = intent?.getIntExtra(EXTRA_COOLDOWN_SEC, -1) ?: -1

        when (action) {
            ACTION_START, ACTION_UPDATE -> {
                val notification = buildProgressNotification(
                    context = this,
                    projectName = projectName,
                    currentChunk = currentChunk,
                    totalChunks = totalChunks,
                    completedChunks = completedChunks,
                    keyName = keyName,
                    speakerName = speakerName,
                    downloadedBytes = downloadedBytes,
                    errorCount = errorCount,
                    cooldownSec = cooldownSec
                )
                startForeground(NOTIFICATION_ID_FOREGROUND, notification)
            }
            ACTION_STOP -> {
                // BUG-01: «توقف صف» از اعلان، صف واقعی را هم متوقف می‌کند (یکی از سه مسیر مجاز لغو)؛
                // پایان طبیعی صف هم از همین مسیر سرویس را خاموش می‌کند
                QueueEngineHolder.peek()?.cancelQueue()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تبدیل صوت و پردازش صف",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش روند تبدیل متن به صوت در پس‌زمینه"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)

            val completeChannel = NotificationChannel(
                COMPLETE_CHANNEL_ID,
                "اعلان پایان تبدیل صوتی",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "نمایش نتیجه نهایی تبدیل پروژه‌ها"
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(completeChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "avatts_queue_channel"
        const val COMPLETE_CHANNEL_ID = "avatts_complete_channel"
        const val NOTIFICATION_ID_FOREGROUND = 1001
        const val NOTIFICATION_ID_COMPLETE = 1002

        const val ACTION_START = "com.example.service.queue.ACTION_START"
        const val ACTION_UPDATE = "com.example.service.queue.ACTION_UPDATE"
        const val ACTION_STOP = "com.example.service.queue.ACTION_STOP"

        const val EXTRA_PROJECT_NAME = "extra_project_name"
        const val EXTRA_CURRENT_CHUNK = "extra_current_chunk"
        const val EXTRA_TOTAL_CHUNKS = "extra_total_chunks"
        const val EXTRA_COMPLETED_CHUNKS = "extra_completed_chunks"
        const val EXTRA_KEY_NAME = "extra_key_name"
        const val EXTRA_SPEAKER_NAME = "extra_speaker_name"
        const val EXTRA_DOWNLOADED_BYTES = "extra_downloaded_bytes"
        const val EXTRA_ERROR_COUNT = "extra_error_count"
        const val EXTRA_COOLDOWN_SEC = "extra_cooldown_sec"

        // BUG-14: از بدنهٔ کلاس به companion منتقل شد تا updateProgress هم بدون نمونهٔ سرویس
        // بتواند نوتیفیکیشن پیشرفت را بسازد
        private fun buildProgressNotification(
            context: Context,
            projectName: String,
            currentChunk: Int,
            totalChunks: Int,
            completedChunks: Int,
            keyName: String,
            speakerName: String,
            downloadedBytes: Long,
            errorCount: Int,
            cooldownSec: Int
        ): Notification {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val downloadedStr = if (downloadedBytes >= 1024 * 1024) {
                String.format(Locale.US, "%.2f MB", downloadedBytes.toDouble() / (1024 * 1024))
            } else {
                "${downloadedBytes / 1024} KB"
            }

            val title = "AvaTTS: $projectName"
            val summaryLine = if (cooldownSec > 0) {
                "در حال خنک‌سازی کلیدها (انتظار $cooldownSec ثانیه)..."
            } else if (totalChunks > 0) {
                "بخش $currentChunk از $totalChunks (تکمیل: $completedChunks | خطا: $errorCount)"
            } else {
                "در حال آماده‌سازی صف تبدیل..."
            }

            val bigText = StringBuilder().apply {
                append("🟢 پروژه: $projectName\n")
                append("⚪ بخش در حال انجام: بخش $currentChunk از $totalChunks\n")
                append("🟢 بخش‌های تکمیل‌شده: $completedChunks\n")
                if (errorCount > 0) {
                    append("🔴 خطاهای ثبت‌شده: $errorCount\n")
                }
                // BUG-38: خط گوینده فقط وقتی نام واقعی موجود است نمایش داده می‌شود
                if (speakerName.isNotBlank()) {
                    append("🎙 گوینده: $speakerName\n")
                }
                append("📦 دیتای دانلود شده: $downloadedStr\n")
                if (keyName.isNotBlank()) {
                    append("🔑 کلید فعال: $keyName")
                }
            }.toString()

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(summaryLine)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setColor(Color.parseColor("#10B981"))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            // BUG-01: دکمهٔ «توقف صف» در اعلان — لغو صف بدون باز کردن اپ (اکشن ACTION_STOP)
            val stopIntent = Intent(context, TtsQueueService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                context,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "توقف صف", stopPendingIntent)

            if (totalChunks > 0 && cooldownSec <= 0) {
                builder.setProgress(totalChunks, completedChunks, false)
            } else if (cooldownSec > 0) {
                builder.setProgress(0, 0, true)
            }

            return builder.build()
        }

        fun start(context: Context, projectName: String, totalChunks: Int, speaker: String = "") {
            val intent = Intent(context, TtsQueueService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROJECT_NAME, projectName)
                putExtra(EXTRA_TOTAL_CHUNKS, totalChunks)
                // BUG-38: نام گویندهٔ خالی ارسال نمی‌شود تا اعلان مقدار واقعی را نشان دهد
                if (speaker.isNotBlank()) {
                    putExtra(EXTRA_SPEAKER_NAME, speaker)
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun updateProgress(
            context: Context,
            projectName: String,
            currentChunk: Int,
            totalChunks: Int,
            completedChunks: Int,
            keyName: String,
            speakerName: String = "",
            downloadedBytes: Long = 0L,
            errorCount: Int = 0,
            cooldownSec: Int = -1
        ) {
            // BUG-14: قبلاً هر تیک پیشرفت با startForegroundService(ACTION_UPDATE) سرویس را
            // دوباره استارت می‌کرد — هم اسپم غیرضروری start بود و هم در پس‌زمینه به
            // IllegalStateException/محدودیت‌های استارت سرویس برمی‌خورد. حالا فقط نوتیفیکیشن
            // موجود با شناسهٔ NOTIFICATION_ID_FOREGROUND آپدیت می‌شود.
            val notification = buildProgressNotification(
                context = context,
                projectName = projectName,
                currentChunk = currentChunk,
                totalChunks = totalChunks,
                completedChunks = completedChunks,
                keyName = keyName,
                speakerName = speakerName,
                downloadedBytes = downloadedBytes,
                errorCount = errorCount,
                cooldownSec = cooldownSec
            )
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_FOREGROUND, notification)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            // BUG-02: stopService از پس‌زمینه مجاز است — startService با ACTION_STOP در اندروید ۸+
            // وقتی اپ پس‌زمینه است IllegalStateException می‌داد (بلعیده می‌شد) و اعلان ongoing
            // «در حال پردازش» تا هارد‌ری‌استارت باقی می‌ماند.
            try {
                context.stopService(Intent(context, TtsQueueService::class.java))
                android.util.Log.w("TtsQueueService", "Queue service stop requested")
            } catch (e: Exception) {
                android.util.Log.w("TtsQueueService", "Failed to stop queue service", e)
            }
        }

        fun showCompletionNotification(
            context: Context,
            projectName: String,
            totalChunks: Int,
            completedChunks: Int,
            errorCount: Int,
            speaker: String
        ) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val completeChannel = NotificationChannel(
                    COMPLETE_CHANNEL_ID,
                    "اعلان پایان تبدیل صوتی",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "نمایش نتیجه نهایی تبدیل پروژه‌ها"
                    enableVibration(true)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(completeChannel)
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = if (errorCount == 0) "✅ تبدیل موفق: $projectName" else "⚠️ پایان تبدیل با خطا: $projectName"
            val body = "🟢 پروژه: $projectName\n" +
                    "🟢 بخش‌های تکمیل‌شده: $completedChunks از $totalChunks\n" +
                    (if (errorCount > 0) "🔴 تعداد خطاها: $errorCount\n" else "") +
                    "🎙 گوینده: $speaker"

            val notification = NotificationCompat.Builder(context, COMPLETE_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText("$completedChunks از $totalChunks بخش انجام شد.${if (errorCount > 0) " (خطا: $errorCount)" else ""}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setColor(if (errorCount == 0) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()

            manager.notify(NOTIFICATION_ID_COMPLETE, notification)
        }
    }
}
