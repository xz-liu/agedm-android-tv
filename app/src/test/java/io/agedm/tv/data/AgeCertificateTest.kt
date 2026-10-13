package io.agedm.tv.data

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.*
import java.time.Instant
import java.util.Date
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class AgeCertificateTest {
    private val certificates = CertificateFactory.getInstance("X.509")
    private val root = File("src/main/res/raw/sslcom_tls_root_2022_rsa.pem").inputStream().use {
        certificates.generateCertificate(it) as X509Certificate
    }
    private fun fixture(name: String) = javaClass.getResourceAsStream("/tls/$name")!!.use {
        certificates.generateCertificate(it) as X509Certificate
    }
    private val chain get() = certificates.generateCertPath(listOf(
        fixture("agedm_leaf_20261012.pem"), fixture("sslcom_issuing_r1.pem")))
    private fun parameters(anchors: Set<TrustAnchor>, date: String = "2026-10-13T12:00:00Z") = PKIXParameters(anchors).apply {
        isRevocationEnabled = false
        this.date = Date.from(Instant.parse(date)) // Fixture validity must not depend on the test date.
    }

    @Test fun officialRootMatchesPublishedFingerprintAndValidatesObservedAgeChain() {
        val digest = MessageDigest.getInstance("SHA-256").digest(root.encoded).joinToString("") { "%02x".format(it) }
        assertEquals("8faf7d2e2cb4709bb8e0b33666bf75a5dd45b5de480f8ea8d4bfe6bebc17f2ed", digest)
        assertTrue(root.basicConstraints >= 0)
        root.verify(root.publicKey)
        CertPathValidator.getInstance("PKIX").validate(chain, parameters(setOf(TrustAnchor(root, null))))
    }

    @Test fun missingRootReproducesValidationFailureAndExpiredCertificatesRemainRejected() {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val oldAnchors = factory.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
            .filterNot { it.publicKey.encoded.contentEquals(root.publicKey.encoded) }.map { TrustAnchor(it, null) }.toSet()
        assertTrue(oldAnchors.isNotEmpty())
        assertThrows(CertPathValidatorException::class.java) {
            CertPathValidator.getInstance("PKIX").validate(chain, parameters(oldAnchors))
        }
        assertThrows(CertPathValidatorException::class.java) {
            CertPathValidator.getInstance("PKIX").validate(chain,
                parameters(setOf(TrustAnchor(root, null)), "2028-01-01T00:00:00Z"))
        }
    }

    @Test fun additionalTrustIsScopedToAgeAndPreservesSystemRootsAndLocalQrHttp() {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/network_security_config.xml"))
        val domains = xml.getElementsByTagName("domain")
        assertEquals(1, domains.length)
        val domain = domains.item(0) as Element
        assertEquals("agedm.io", domain.textContent)
        assertEquals("true", domain.getAttribute("includeSubdomains"))
        val config = domain.parentNode as Element
        assertFalse(config.hasAttribute("cleartextTrafficPermitted"))
        val roots = config.getElementsByTagName("certificates")
        assertEquals(setOf("system", "@raw/sslcom_tls_root_2022_rsa"),
            (0 until roots.length).map { (roots.item(it) as Element).getAttribute("src") }.toSet())
        assertEquals(roots.length, xml.getElementsByTagName("certificates").length)
        assertEquals("true", (xml.getElementsByTagName("base-config").item(0) as Element).getAttribute("cleartextTrafficPermitted"))
    }
}
