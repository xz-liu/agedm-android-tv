package io.agedm.tv.data

import java.security.cert.CertificateException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException

/** Do not expose Java certificate exception chains in the phone/TV search UI. */
internal fun networkFailureMessage(error: Throwable, fallback: String): String {
    val seen = mutableSetOf<Throwable>()
    var cause: Throwable? = error
    while (cause != null && seen.add(cause)) {
        if (cause is SSLException || cause is CertificateException || cause is CertPathValidatorException) {
            return "无法验证服务器证书，请检查电视日期时间，并更新客户端后重试。"
        }
        cause = cause.cause
    }
    return fallback
}
