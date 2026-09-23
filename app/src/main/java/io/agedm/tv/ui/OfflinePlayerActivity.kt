package io.agedm.tv.ui

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.ui.PlayerView
import io.agedm.tv.AgeTvApplication
import io.agedm.tv.data.AgeLinks
import io.agedm.tv.data.AgeRoute
import io.agedm.tv.data.OfflineEpisode
import io.agedm.tv.data.PlaybackRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Offline playback deliberately has no upstream data source or online detail dependency. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OfflinePlayerActivity : AppCompatActivity() {
    private val app get() = application as AgeTvApplication
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private var episode: OfflineEpisode? = null
    private var loadJob: Job? = null
    private var progressJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PlayerView(this)
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        loadJob = lifecycleScope.launch {
            try {
                val id = intent.getStringExtra("download_id") ?: error("未找到下载")
                val download = withContext(Dispatchers.IO) { app.offlineDownloads.get(id) }
                check(download?.state == Download.STATE_COMPLETED) { "该集尚未下载完成或已被删除" }
                val metadata = OfflineEpisode.decode(download!!.request)
                episode = metadata
                val record = app.playbackStore.getRecord(metadata.animeId)
                player = ExoPlayer.Builder(this@OfflinePlayerActivity).build().also { p ->
                    view.player = p
                    p.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) saveProgress()
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            Toast.makeText(this@OfflinePlayerActivity, "离线文件不可用，请删除后重新下载", Toast.LENGTH_LONG).show()
                        }
                    })
                    p.setMediaSource(DownloadHelper.createMediaSource(download.request, app.offlineDownloads.offlineDataSource()))
                    if (record != null && !record.completed && record.sourceKey == metadata.sourceKey && record.episodeIndex == metadata.episodeIndex) {
                        p.seekTo(record.positionMs)
                    }
                    p.setPlaybackSpeed(app.playbackStore.getPlaybackSpeed())
                    p.prepare()
                    p.play()
                }
                view.requestFocus()
                progressJob = lifecycleScope.launch {
                    while (isActive) { delay(5000); saveProgress() }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(this@OfflinePlayerActivity, error.message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onPause() { player?.pause(); super.onPause() }

    override fun onStop() {
        loadJob?.cancel()
        progressJob?.cancel()
        saveProgress()
        view.player = null
        player?.release()
        player = null
        super.onStop()
    }

    private fun saveProgress() {
        val p = player ?: return
        val e = episode ?: return
        if (p.playbackState != Player.STATE_READY && p.playbackState != Player.STATE_ENDED) return
        val completed = p.playbackState == Player.STATE_ENDED
        app.playbackStore.saveRecord(PlaybackRecord(e.animeId, e.title,
            AgeLinks.buildWebUrl(AgeRoute.Detail(e.animeId)), e.sourceKey, e.sourceLabel,
            e.episodeIndex, e.episodeLabel, if (completed) 0 else p.currentPosition.coerceAtLeast(0),
            p.duration.coerceAtLeast(0), System.currentTimeMillis(), completed))
    }
}
