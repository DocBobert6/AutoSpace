package app.autospace

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec

/**
 * Converts a PEM-encoded X.509 certificate and PEM-encoded RSA PKCS#1 private key into a
 * PKCS#12 [KeyStore], using only standard JCA APIs — no BouncyCastle.
 *
 * The DER parsing of the PKCS#1 key is done by hand (matching the same approach used in
 * [SelfSignedCert] for writing). Android's Conscrypt [KeyFactory] only accepts PKCS#8,
 * so we translate PKCS#1 → [RSAPrivateCrtKeySpec] ourselves.
 */
object PemKeyParser {

    private const val CERT_HEADER = "-----BEGIN CERTIFICATE-----"
    private const val CERT_FOOTER = "-----END CERTIFICATE-----"
    private const val KEY_HEADER = "-----BEGIN RSA PRIVATE KEY-----"
    private const val KEY_FOOTER = "-----END RSA PRIVATE KEY-----"

    class Result(val keyStore: KeyStore, val certificate: X509Certificate)

    /**
     * Parses PEM strings for the certificate and private key, verifies they match, and
     * returns a PKCS#12 [KeyStore] ready for [CertProvider] to consume.
     */
    fun build(certificatePem: String, privateKeyPem: String, alias: String = "phone"): Result {
        val cert = parseCertificate(certificatePem)
        val keySpec = parsePrivateKey(privateKeyPem)

        val kf = KeyFactory.getInstance("RSA")
        val pubSpec = RSAPublicKeySpec(keySpec.spec.modulus, keySpec.spec.publicExponent)
        require(kf.generatePublic(pubSpec) == cert.publicKey) {
            "the certificate's public key does not match the private key"
        }

        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(alias, keySpec.getPrivate(), CharArray(0), arrayOf(cert))
        }
        return Result(keyStore, cert)
    }

    /** Strips PEM headers/footers and decodes the base64 body. */
    fun parseCertificate(pem: String): X509Certificate {
        val der = pemToDer(pem, CERT_HEADER, CERT_FOOTER)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    /** Parses an RSA PKCS#1 private key (ASN.1 DER) into a key spec. */
    fun parsePrivateKey(pem: String): KeySpec {
        val der = pemToDer(pem, KEY_HEADER, KEY_FOOTER)
        val integers = readPkcs1Sequence(der)
        require(integers.size == 9) { "PKCS#1 RSA key needs 9 integers, got ${integers.size}" }
        val spec = RSAPrivateCrtKeySpec(integers[1], integers[2], integers[3], integers[4], integers[5], integers[6], integers[7], integers[8])
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(spec)
        return KeySpec(spec, privateKey)
    }

    /** Holds both the spec and the constructed key for matching against a certificate's public key. */
    class KeySpec(val spec: RSAPrivateCrtKeySpec, private val privateKey: java.security.PrivateKey) {
        fun getPrivate() = privateKey
    }

    /** Strips headers and decodes base64. */
    private fun pemToDer(pem: String, header: String, footer: String): ByteArray {
        val start = pem.indexOf(header)
        val end = pem.indexOf(footer)
        if (start == -1 || end == -1 || end <= start) {
            throw IllegalArgumentException("not a valid PEM $header block")
        }
        val b64 = pem.substring(start + header.length, end)
            .replace(Regex("\\s+"), "")
        return java.util.Base64.getDecoder().decode(b64)
    }

    /**
     * Parses the inner SEQUENCE of an RSA PKCS#1 DER blob into a list of [BigInteger]s.
     * Throws if the ASN.1 structure is wrong, so caller gets a clear error rather than a
     * silent wrong key.
     */
    private fun readPkcs1Sequence(der: ByteArray): List<BigInteger> {
        var pos = 0
        fun readTag(): Int {
            require(pos < der.size) { "unexpected end of DER at tag" }
            return der[pos++].toInt() and 0xFF
        }
        fun readLength(): Int {
            require(pos < der.size) { "unexpected end of DER at length" }
            val b0 = der[pos++].toInt() and 0xFF
            if (b0 < 0x80) return b0
            val numBytes = b0 and 0x7F
            require(pos + numBytes <= der.size) { "truncated long-form length" }
            var result = 0
            repeat(numBytes) {
                result = (result shl 8) or (der[pos++].toInt() and 0xFF)
            }
            return result
        }

        val outerTag = readTag()
        require(outerTag == 0x30) { "expected SEQUENCE (0x30), got 0x${outerTag.toString(16)}" }
        val outerLen = readLength()
        require(pos + outerLen <= der.size) { "SEQUENCE length $outerLen exceeds remaining data" }

        val integers = mutableListOf<BigInteger>()
        while (pos < der.size) {
            val tag = readTag()
            require(tag == 0x02) { "expected INTEGER (0x02), got 0x${tag.toString(16)} at offset ${pos - 1}" }
            val len = readLength()
            require(pos + len <= der.size) { "INTEGER length $len exceeds remaining data" }
            integers.add(BigInteger(der.copyOfRange(pos, pos + len)))
            pos += len
        }
        require(integers.size == 9) { "PKCS#1 RSA key needs 9 integers, got ${integers.size}" }
        return integers
    }
}