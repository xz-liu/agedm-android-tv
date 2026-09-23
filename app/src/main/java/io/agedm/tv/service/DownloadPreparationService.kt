package io.agedm.tv.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.offline.Download
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.data.PreparationState
import io.agedm.tv.data.QueuedEpisode
import io.agedm.tv.ui.MainActivity
import io.agedm.tv.ui.WebStreamResolver
import kotlinx.coroutines.*

/** Fills available transfer slots; resolves URLs only when they can start downloading. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadPreparationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as AgeTvApplication
    private val store get() = app.offlineDownloads
    private var resolver: WebStreamResolver? = null
    private var worker: Job? = null
    private var preparing: Job? = null
    private var preparingId: String? = null
    private var lastMessage = ""

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "批量下载", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("正在检查下载队列"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        preparingId?.let { id ->
            if (store.queue.all().none { it.id == id && it.state == PreparationState.PREPARING }) preparing?.cancel()
        }
        if (worker?.isActive != true) worker = scope.launch {
            try {
                while (isActive) {
                    val task = store.queue.all().firstOrNull { it.state == PreparationState.WAITING } ?: break
                    if (android.os.StatFs(filesDir.path).availableBytes < 256L * 1024 * 1024) {
                        store.queue.update(store.queue.all().filter { it.state == PreparationState.WAITING }
                            .mapTo(mutableSetOf()) { it.id }, PreparationState.PAUSED, "剩余空间不足，请先清理下载")
                        break
                    }
                    val existing = store.get(task.id)
                    if (existing?.state == Download.STATE_FAILED) {
                        androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload(
                            this@DownloadPreparationService, MediaDownloadService::class.java, task.id, false)
                        delay(500)
                        continue
                    }
                    if (existing != null && existing.state != Download.STATE_REMOVING) {
                        store.queue.update(setOf(task.id), PreparationState.TRANSFERRED)
                        continue
                    }
                    if (!store.manager.isInitialized || store.manager.notMetRequirements != 0 ||
                        existing?.state == Download.STATE_REMOVING || store.availablePreparationSlots() == 0) {
                        updateNotification(if (store.manager.notMetRequirements != 0) "等待网络，已保留下载队列" else "并行上限 ${store.manager.maxParallelDownloads} 集，有空位后自动开始")
                        delay(1000)
                        continue
                    }
                    store.queue.update(setOf(task.id), PreparationState.PREPARING)
                    preparingId = task.id
                    preparing = launch { prepare(task) }
                    preparing?.join()
                    preparingId = null
                    preparing = null
                }
            } finally { stopSelf() }
        }
        return START_NOT_STICKY
    }

    private suspend fun prepare(task: QueuedEpisode) {
        try {
            updateNotification("正在准备 ${task.title} · ${task.episodeLabel}")
            val parser = resolver ?: WebStreamResolver(this, null, scope, app.ageRepository).also { resolver = it }
            val stream = parser.resolve(task.detail(), task.source(), task.episode())
            currentCoroutineContext().ensureActive()
            if (store.queue.all().none { it.id == task.id && it.state == PreparationState.PREPARING }) return
            store.enqueue(task.detail(), task.source(), task.episode(), stream)
            // Persist handoff only after Media3 has stored its request.
            withTimeout(10_000L) { while (store.get(task.id) == null) delay(100) }
            store.queue.update(setOf(task.id), PreparationState.TRANSFERRED)
        } catch (error: TimeoutCancellationException) {
            fail(task, "下载准备超时")
        } catch (error: CancellationException) {
            if (store.queue.all().any { it.id == task.id && it.state == PreparationState.PREPARING }) {
                store.queue.update(setOf(task.id), PreparationState.WAITING)
            }
            throw error
        } catch (error: Exception) {
            fail(task, error.message ?: "片源解析失败")
        }
    }

    private fun fail(task: QueuedEpisode, message: String) {
        if (store.queue.all().any { it.id == task.id && it.state == PreparationState.PREPARING }) {
            store.queue.update(setOf(task.id), PreparationState.FAILED, message)
        }
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("动画下载队列")
        .setContentText(message).setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 1, MainActivity.createDownloadsIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()

    private fun updateNotification(message: String) {
        if (lastMessage == message) return
        lastMessage = message
        // Update the service's required foreground notification, including when optional
        // notification permission has not been granted on Android 13+.
        startForeground(NOTIFICATION_ID, notification(message))
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        store.queue.update(store.queue.all().filter { it.state == PreparationState.WAITING || it.state == PreparationState.PREPARING }
            .mapTo(mutableSetOf()) { it.id }, PreparationState.PAUSED)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        resolver?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "download_preparation"
        private const val NOTIFICATION_ID = 1002
        @Volatile private var running = false
        fun wake(context: Context) { ContextCompat.startForegroundService(context, Intent(context, DownloadPreparationService::class.java)) }
        fun syncIfRunning(context: Context) { if (running) context.startService(Intent(context, DownloadPreparationService::class.java)) }
    }
}
