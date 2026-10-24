package app.autospace

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Generates a self-signed RSA-2048 certificate - the thing under test.
 *
 * Written by hand rather than with BouncyCastle so the spike has no dependencies and no
 * second security provider sitting next to Conscrypt. A self-signed X.509 is a fixed
 * structure, so this is mechanical; the risk is a silent malformation, which
 * [generate] closes off by parsing its own output back through CertificateFactory and
 * verifying the signature before returning. A bad certificate therefore fails loudly on the
 * bench instead of looking like a head-unit rejection in the car.
 *
 * RSA-2048 matches aasdk's own key (cert/headunit.key) - see cert/README.md.
 */
object SelfSignedCert {

    const val ALIAS = "phone"
    val PASSWORD: CharArray = "spike".toCharArray()

    // Pre-encoded OIDs, so there is no OID encoder to get wrong.
    private val OID_SHA256_RSA = byteArrayOf(
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B
    )
    private val OID_CN = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
    private val OID_O = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x0A)

    class Result(val keyStore: KeyStore, val certificate: X509Certificate)

    fun generate(commonName: String = "Gearslip", organisation: String = "Gearslip"): Result {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86_400_000L)            // a day of slack for head-unit clock skew
        val notAfter = Date(now + 20L * 365 * 86_400_000L) // ~2046, still inside UTCTime's pre-2050 range

        val der = buildCertificate(keyPair, commonName, organisation, notBefore, notAfter)

        // Self-check: malformed DER throws here, on the bench, not in the car.
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        certificate.verify(keyPair.public)
        certificate.checkValidity()

        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, keyPair.private, PASSWORD, arrayOf(certificate))
        }
        return Result(keyStore, certificate)
    }

    private fun buildCertificate(
        keyPair: KeyPair,
        commonName: String,
        organisation: String,
        notBefore: Date,
        notAfter: Date,
    ): ByteArray {
        val algorithmId = seq(OID_SHA256_RSA, tlv(0x05, ByteArray(0))) // sha256WithRSAEncryption, NULL params
        val name = seq(rdn(OID_CN, commonName), rdn(OID_O, organisation))
        val serial = BigInteger(64, SecureRandom()).add(BigInteger.ONE)

        val tbs = seq(
            tlv(0xA0, integer(BigInteger.valueOf(2))), // [0] EXPLICIT version, v3
            integer(serial),
            algorithmId,
            name,
            seq(utcTime(notBefore), utcTime(notAfter)),
            name,
            keyPair.public.encoded,                    // already a DER SubjectPublicKeyInfo
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        return seq(tbs, algorithmId, bitString(signature))
    }

    // --- minimal DER writer ------------------------------------------------------------

    private fun length(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        var value = n
        val out = ArrayList<Byte>()
        while (value > 0) {
            out.add(0, (value and 0xFF).toByte())
            value = value ushr 8
        }
        return byteArrayOf((0x80 or out.size).toByte()) + out.toByteArray()
    }

    private fun tlv(tag: Int, body: ByteArray) = byteArrayOf(tag.toByte()) + length(body.size) + body

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.reduce { a, b -> a + b })

    private fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())

    private fun bitString(body: ByteArray) = tlv(0x03, byteArrayOf(0x00) + body)

    private fun rdn(oid: ByteArray, value: String) =
        tlv(0x31, seq(oid, tlv(0x0C, value.toByteArray(Charsets.UTF_8))))

    private fun utcTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return tlv(0x17, (format.format(date) + "Z").toByteArray(Charsets.US_ASCII))
    }
}
