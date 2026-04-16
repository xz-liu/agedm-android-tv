package io.agedm.tv

import android.app.Application
import io.agedm.tv.data.AgeRepository
import io.agedm.tv.data.LinkCastManager
import io.agedm.tv.data.PlaybackStore

class AgeTvApplication : Application() {

    val playbackStore: PlaybackStore by lazy { PlaybackStore(this) }

    val ageRepository: AgeRepository by lazy { AgeRepository() }

    val linkCastManager: LinkCastManager by lazy { LinkCastManager() }
}
