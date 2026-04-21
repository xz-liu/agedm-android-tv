package io.agedm.tv

import android.app.Application
import android.graphics.Bitmap
import io.agedm.tv.data.AppStorageDatabase
import io.agedm.tv.data.AgeRepository
import io.agedm.tv.data.BangumiAccountService
import io.agedm.tv.data.BangumiAccountStore
import io.agedm.tv.data.ContentCache
import io.agedm.tv.data.LinkCastManager
import io.agedm.tv.data.PersistentJsonStore
import io.agedm.tv.data.PlaybackStore
import io.agedm.tv.ui.createQrBitmap
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AgeTvApplication : Application() {

    private val _castQr = MutableStateFlow<Bitmap?>(null)
    val castQr: StateFlow<Bitmap?> = _castQr

    override fun onCreate() {
        super.onCreate()
        Thread {
            val url = linkCastManager.ensureStarted() ?: return@Thread
            _castQr.value = createQrBitmap(url)
        }.start()
    }

    val playbackStore: PlaybackStore by lazy { PlaybackStore(this) }

    val bangumiAccountStore: BangumiAccountStore by lazy { BangumiAccountStore(this) }

    private val storageDatabase: AppStorageDatabase by lazy { AppStorageDatabase.get(this) }

    val contentCache: ContentCache by lazy {
        ContentCache(
            database = storageDatabase,
            legacyDir = File(cacheDir, "content"),
        ).also { it.evictExpired() }
    }

    val metadataStore: PersistentJsonStore by lazy {
        PersistentJsonStore(
            database = storageDatabase,
            legacyDir = File(filesDir, "metadata"),
        )
    }

    val ageRepository: AgeRepository by lazy {
        AgeRepository(
            cache = contentCache,
            persistentStore = metadataStore,
        )
    }

    val bangumiAccountService: BangumiAccountService by lazy {
        BangumiAccountService(
            repository = ageRepository,
            playbackStore = playbackStore,
            store = bangumiAccountStore,
        )
    }

    val linkCastManager: LinkCastManager by lazy { LinkCastManager(ageRepository, bangumiAccountService) }
}
