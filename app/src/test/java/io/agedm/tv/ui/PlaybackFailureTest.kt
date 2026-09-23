package io.agedm.tv.ui

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], application = android.app.Application::class)
class PlaybackFailureTest {
    @Test fun decoderFailureIsNotReportedAsMissingDownload() {
        val error = PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        val message = playbackFailureMessage(error, true)
        assertTrue(message.contains("解码")); assertFalse(message.contains("缓存缺少"))
        assertTrue(message.contains(error.errorCodeName))
    }
    @Test fun missingCacheAndNetworkHaveDifferentRecoveryAdvice() {
        assertTrue(playbackFailureMessage(PlaybackException("missing", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED), true).contains("在线播放"))
        assertTrue(playbackFailureMessage(PlaybackException("timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT), false).contains("网络"))
    }
}
