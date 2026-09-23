package io.agedm.tv.ui

import androidx.media3.common.PlaybackException

internal fun playbackFailureMessage(error: PlaybackException, local: Boolean): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "电视无法解码当前音视频，可切换播放源或清晰度。"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "视频封装或播放清单解析失败，可重新解析或切换播放源。"
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接失败，请检查网络后重试。"
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "片源服务器拒绝或暂时无法提供视频，可重新解析地址。"
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> if (local) "下载缓存缺少数据或读取失败，可先在线播放本集，也可重新下载。" else "视频数据读取失败，可重试或切换播放源。"
    else -> if (local) "本地播放失败，可先在线播放本集或切换播放源。" else "播放失败，可重试或切换播放源。"
} + "\n错误码：${error.errorCodeName}"
