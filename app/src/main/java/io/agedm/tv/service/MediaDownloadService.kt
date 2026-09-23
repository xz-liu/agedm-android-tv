package io.agedm.tv.service

import android.app.Notification
import android.app.PendingIntent
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.R
import io.agedm.tv.ui.MainActivity

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaDownloadService : DownloadService(1001, 1000L, "offline_downloads", R.string.download_channel, 0) {
    override fun getDownloadManager() = (application as AgeTvApplication).offlineDownloads.manager
    override fun getScheduler(): Scheduler? = null
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val intent = PendingIntent.getActivity(this, 0, MainActivity.createDownloadsIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return DownloadNotificationHelper(this, "offline_downloads").buildProgressNotification(
            this, android.R.drawable.stat_sys_download, intent, "动画离线下载", downloads, notMetRequirements)
    }

    // Android 15 limits background dataSync services to six hours.
    override fun onTimeout(startId: Int, fgsType: Int) {
        downloadManager.pauseDownloads()
        stopSelf()
    }
}
