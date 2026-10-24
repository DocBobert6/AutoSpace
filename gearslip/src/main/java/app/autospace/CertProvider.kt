package app.autospace

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Supplies the certificate the phone presents to the head unit.
 *
 * FOSS posture: this code ships with NO certificate. Whoever wants to test the trusted-cert
 * path supplies their own. Sources, in priority order:
 *
 *  1. **Imported** in the app (Settings > Certificate): a PKCS#12 file plus its password,
 *     copied into the app's private storage.
 *  2. **Downloaded** from the public opencardev/aasdk repository. Fetched only when the
 *     user asks; the app never ships or hosts the certificate.
 *  3. **Staged over adb** into the app's external files directory, the way the spike has
 *     always done it. Still honoured, so existing setups keep working untouched:
 *
 *       openssl pkcs12 -export -in headunit.crt -inkey headunit.key \
 *           -name phone -passout pass:aaspike -out phone.p12
 *       adb push phone.p12 /sdcard/Android/data/app.autospace/files/phone.p12
 *
 *     Default password is "aaspike" (kept as-is so an already-staged phone.p12 doesn't need
 *     regenerating); a `phone.pass` file next to it overrides it.
 *  4. A freshly generated self-signed cert, which a real head unit rejects (see
 *     SPIKE_FINDINGS.md).
 */
object CertProvider {
    private val log = GearslipLog.tagged("CERT")

    private const val P12_NAME = "phone.p12"
    private const val PASS_NAME = "phone.pass"
    private const val DEFAULT_PASSWORD = "aaspike"
    private const val IMPORT_DIR = "identity"

    enum class Kind { IMPORTED, DOWNLOADED, ADB_STAGED, SELF_SIGNED }

    class Identity(
        val keyStore: KeyStore,
        val certificate: X509Certificate,
        val password: CharArray,
        val source: String,
        val kind: Kind = Kind.SELF_SIGNED,
    )

    /**
     * The identity for the next connection. A newly imported or pushed cert takes effect on the
     * next connect: see [loadFromFiles] for how the cache notices.
     */
    fun load(context: Context): Identity = loadFromFiles(context) ?: run {
        val generated = SelfSignedCert.generate()
        Identity(
            generated.keyStore,
            generated.certificate,
            SelfSignedCert.PASSWORD,
            "self-signed (generated) - a real head unit rejects this",
            Kind.SELF_SIGNED,
        )
    }

    /**
     * The active identity without the cost of generating a self-signed key: null means "nothing
     * supplied, a self-signed one would be generated". For the UI, which only wants to describe it.
     */
    fun loadSupplied(context: Context): Identity? = loadFromFiles(context)

    /**
     * Unlocking a PKCS#12 file takes about a second on a Pixel 6, which a head unit waiting for
     * its first reply may not wait out. So the unlocked identity is kept, keyed on the files it
     * could come from: importing, downloading, removing or pushing a cert changes the key.
     */
    @Volatile private var cached: Pair<String, Identity>? = null

    private fun sourcesKey(context: Context): String {
        val dir = context.getExternalFilesDir(null)
        val files = listOf(
            importedFile(context, P12_NAME),
            downloadedFile(context, P12_NAME),
            dir?.let { File(it, P12_NAME) },
            dir?.let { File(it, PASS_NAME) },
        )
        return files.joinToString("|") { f -> f?.let { "${it.path}:${it.lastModified()}:${it.length()}" } ?: "-" }
    }

    /** Unlocks the certificate ahead of time, so the first connection doesn't wait on it. */
    fun warm(context: Context) {
        runCatching { loadFromFiles(context) }
    }

    private fun loadFromFiles(context: Context): Identity? {
        val key = sourcesKey(context)
        cached?.let { (k, identity) -> if (k == key) return identity }
        val identity = unlockFromFiles(context) ?: return null
        cached = key to identity
        return identity
    }

    private fun unlockFromFiles(context: Context): Identity? {
        val imported = importedFile(context, P12_NAME)
        if (imported.isFile) {
            runCatching {
                return loadP12(imported, importedPassword(context), "imported certificate", Kind.IMPORTED)
            }.onFailure { log.e("could not load the imported certificate - trying the next source", it) }
        }

        val downloaded = downloadedFile(context, P12_NAME)
        if (downloaded.isFile) {
            runCatching {
                return loadP12(downloaded, DEFAULT_PASSWORD, "downloaded certificate", Kind.DOWNLOADED)
            }.onFailure { log.e("could not load the downloaded certificate - trying the next source", it) }
        }

        val dir = context.getExternalFilesDir(null)
        val staged = dir?.let { File(it, P12_NAME) }
        if (dir != null && staged != null && staged.isFile) {
            runCatching {
                val passFile = File(dir, PASS_NAME)
                val password = if (passFile.isFile) {
                    passFile.readText().lineSequence().firstOrNull().orEmpty().trim()
                } else {
                    DEFAULT_PASSWORD
                }
                return loadP12(staged, password, "$P12_NAME staged over adb", Kind.ADB_STAGED)
            }.onFailure { log.e("could not load $P12_NAME - falling back to self-signed", it) }
        }
        return null
    }

    /**
     * Validates [uri] as a PKCS#12 identity with [password] and, only if it is good, makes it
     * the active certificate. A wrong password or a file with no private key throws and
     * leaves whatever was active before untouched.
     */
    fun importFrom(context: Context, uri: Uri, password: String): Identity {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("could not open the selected file")

        val staging = File(context.cacheDir, "import-$P12_NAME")
        try {
            staging.writeBytes(bytes)
            val identity = loadP12(staging, password, "imported certificate", Kind.IMPORTED)

            val target = importedFile(context, P12_NAME)
            target.parentFile?.mkdirs()
            staging.copyTo(target, overwrite = true)
            importedFile(context, PASS_NAME).writeText(password)
            log.i("imported a certificate: ${identity.certificate.subjectX500Principal}")
            return identity
        } finally {
            staging.delete()
        }
    }

    fun hasImported(context: Context): Boolean = importedFile(context, P12_NAME).isFile

    /** Whether a downloaded certificate is stored (may or may not also be imported). */
    fun hasDownloaded(context: Context): Boolean = downloadedFile(context, P12_NAME).isFile

    /** Drops the imported identity; the next source in line (downloaded, adb-staged, then self-signed) takes over. */
    fun removeImported(context: Context) {
        importedFile(context, P12_NAME).delete()
        importedFile(context, PASS_NAME).delete()
        log.i("removed the imported certificate")
    }

    /** Drops the downloaded identity. */
    fun removeDownloaded(context: Context) {
        downloadedFile(context, P12_NAME).delete()
        downloadedFile(context, PASS_NAME).delete()
        log.i("removed the downloaded certificate")
    }

    /** Whether any certificate is available (imported, downloaded, or adb-staged). */
    fun hasAnyCert(context: Context): Boolean =
        hasImported(context) || hasDownloaded(context) || hasAdbStaged(context)

    private fun hasAdbStaged(context: Context): Boolean =
        context.getExternalFilesDir(null)?.let { File(it, P12_NAME).isFile } == true

    /**
     * Validates raw PKCS#12 bytes with [password] and, if good, writes them as the imported
     * identity. Used when the app assembles a certificate in memory (e.g. from a download)
     * rather than importing from a file picker.
     */
    fun importFromBytes(context: Context, p12Bytes: ByteArray, password: String): Identity {
        val staging = File(context.cacheDir, "import-$P12_NAME")
        try {
            staging.writeBytes(p12Bytes)
            val identity = loadP12(staging, password, "imported certificate", Kind.IMPORTED)

            val target = importedFile(context, P12_NAME)
            target.parentFile?.mkdirs()
            staging.copyTo(target, overwrite = true)
            importedFile(context, PASS_NAME).writeText(password)
            log.i("imported a certificate from bytes: ${identity.certificate.subjectX500Principal}")
            return identity
        } finally {
            staging.delete()
        }
    }

    /**
     * Downloads the publicly available head unit certificate from the opencardev/aasdk
     * repository, converts it from PEM to PKCS#12, and stores it ready for use.
     * Runs synchronously (caller is responsible for threading). Throws on any failure.
     */
    fun downloadAndImport(context: Context): Identity {
        val download = CertDownloader.fetch()
        val result = PemKeyParser.build(download.certificatePem, download.privateKeyPem)

        val target = downloadedFile(context, P12_NAME)
        target.parentFile?.mkdirs()
        val p12Password = DEFAULT_PASSWORD
        val keyStore = result.keyStore
        val p12Bytes = ByteArrayOutputStream().use { stream ->
            keyStore.store(stream, p12Password.toCharArray())
            stream.toByteArray()
        }
        target.writeBytes(p12Bytes)
        downloadedFile(context, PASS_NAME).writeText(p12Password)

        log.i("downloaded and stored certificate: ${result.certificate.subjectX500Principal}")
        return Identity(keyStore, result.certificate, p12Password.toCharArray(),
            "downloaded certificate (opencardev/aasdk)", Kind.DOWNLOADED)
    }

    // The imported files live in the app's private internal storage (not the external files
    // dir), so other apps cannot read the key, and `adb uninstall` clearing it is expected.
    private fun importedFile(context: Context, name: String) = File(File(context.filesDir, IMPORT_DIR), name)

    private fun downloadedFile(context: Context, name: String) =
        File(File(context.filesDir, "$IMPORT_DIR-downloaded"), name)

    private fun importedPassword(context: Context): String =
        importedFile(context, PASS_NAME).takeIf { it.isFile }?.readText().orEmpty()

    private fun loadP12(file: File, password: String, label: String, kind: Kind): Identity {
        val chars = password.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        file.inputStream().use { keyStore.load(it, chars) }

        val alias = keyStore.aliases().asSequence().firstOrNull { keyStore.isKeyEntry(it) }
            ?: throw IllegalStateException("the file contains no private-key entry")
        val certificate = keyStore.getCertificate(alias) as X509Certificate

        return Identity(keyStore, certificate, chars, "$label (alias=$alias)", kind)
    }
}
