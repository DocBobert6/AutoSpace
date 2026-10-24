package app.autospace

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the publicly available head unit certificate and key from the opencardev/aasdk
 * repository on GitHub. The app ships no certificates; it downloads them only when the
 * user chooses to, the same way a browser would.
 */
object CertDownloader {

    private val log = GearslipLog.tagged("DOWNL")

    private const val BASE = "https://raw.githubusercontent.com/opencardev/aasdk/main/cert"
    private const val CRT = "$BASE/headunit.crt"
    private const val KEY = "$BASE/headunit.key"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    class Downloaded(val certificatePem: String, val privateKeyPem: String)

    /** Fetches both files; throws with a readable message on any failure. */
    fun fetch(): Downloaded {
        log.i("fetching certificate from $BASE")
        val crt = fetch(CRT, "certificate")
        val key = fetch(KEY, "private key")
        log.i("downloaded certificate (${crt.length} bytes) and key (${key.length} bytes)")
        return Downloaded(crt, key)
    }

    private fun fetch(url: String, label: String): String {
        val body = try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var n: Int
                while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                out.toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            throw RuntimeException("could not download the $label: ${e.message}", e)
        }
        if (body.isBlank()) throw RuntimeException("the $label file is empty")
        return body
    }
}