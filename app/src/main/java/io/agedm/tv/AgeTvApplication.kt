package io.agedm.tv

import android.app.Application
import io.agedm.tv.data.AgeRepository
import io.agedm.tv.data.ContentCache
import io.agedm.tv.data.LinkCastManager
import io.agedm.tv.data.PlaybackStore
import java.io.File

class AgeTvApplication : Application() {

    val playbackStore: PlaybackStore by lazy { PlaybackStore(this) }

    val contentCache: ContentCache by lazy {
        ContentCache(File(cacheDir, "content")).also { it.evictExpired() }
    }

    val ageRepository: AgeRepository by lazy { AgeRepository(cache = contentCache) }

    val linkCastManager: LinkCastManager by lazy { LinkCastManager(ageRepository) }
}
