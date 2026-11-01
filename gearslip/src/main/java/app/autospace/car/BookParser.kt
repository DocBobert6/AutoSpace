package app.autospace.car

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * r161: the bookshelf's EPUB parser, ported from Mariner's BookParsers EPUB
 * path only. There is deliberately no legacy-format code in this
 * file: this build is EPUB-only by standing order, and every import entry
 * refuses anything else before the parser is ever called.
 *
 * Parsing is lossy by design, exactly as in Mariner: the book becomes
 * reflowed plain text plus inline images. CSS, publisher fonts, fixed
 * layout, tables used as layout, footnotes and endnotes as links, SVG
 * text, MathML, audio and video, media overlays, and JavaScript do not
 * survive, and the reader must not pretend otherwise.
 *
 * Image bytes are pulled from the zip and tokenised into chapter text as
 * a paragraph holding an image marker; the reader renders the image in
 * place of that paragraph. The cover is the declared cover reference, a
 * cover-image manifest item, or the guide cover reference, following the
 * chain when the reference points at a wrapper page.
 *
 * On the JVM unit-test classpath android.text.Html is a stub, so
 * [htmlToText] falls back to a tag strip there; on the device it renders
 * through Html as Mariner does. Tests therefore assert structure (titles,
 * chapter splits, image tokens), not Html's exact whitespace.
 */
data class BookChapter(val title: String, val text: String)

data class ParsedBook(
    val title: String,
    val author: String,
    val chapters: List<BookChapter>,
    val cover: ByteArray? = null,
    val images: Map<String, ByteArray> = emptyMap(),
)

/** A piece of a chapter paragraph, for display. */
sealed interface BookPart {
    data class Text(val text: String) : BookPart
    data class Image(val token: String) : BookPart
}

private val imageTokenRegex = Regex("""\[\[img:(\w+)]]""")

/** Remove every image token from [text] (speech and the like). */
fun stripImageTokens(text: String): String =
    imageTokenRegex.replace(text, "")
        .replace(Regex("""\s{2,}"""), " ").trim()

/**
 * Split chapter paragraphs into display parts: a token anywhere in a
 * paragraph becomes its own [BookPart.Image], with the surrounding text
 * kept, in order.
 */
fun readerParts(paragraphs: List<String>): List<BookPart> {
    val out = ArrayList<BookPart>()
    for (para in paragraphs) {
        var rest = para
        while (true) {
            val m = imageTokenRegex.find(rest) ?: break
            val before = rest.substring(0, m.range.first).trim()
            if (before.isNotEmpty()) out += BookPart.Text(before)
            out += BookPart.Image(m.groupValues[1])
            rest = rest.substring(m.range.last + 1)
        }
        val tail = rest.trim()
        if (tail.isNotEmpty()) out += BookPart.Text(tail)
    }
    return out
}

object EpubParser {

    /** Hostile-book guards, Mariner's shape. */
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 768L * 1024 * 1024

    /** Chapter splitting budget, Mariner's shape. */
    const val MAX_CHAPTER_CHARS = 3600

    const val DRM_MESSAGE = "This book is DRM-protected and can't be opened here"

    /**
     * Parse an EPUB file. Throws IllegalStateException with a readable
     * message for a malformed package, an over-cap entry, or a
     * DRM-protected book; anything else throws as a zip or IO failure
     * and the import layer reports it generically.
     */
    fun parse(file: File): ParsedBook {
        ZipFile(file).use { zip ->
            // DRM first: the EPUB path has no encryption handling at all,
            // so an encrypted book would otherwise fail or garble
            // silently. Presence of the encryption document is the
            // refusal signal, exactly as the brief specifies.
            if (zip.getEntry("META-INF/encryption.xml") != null) {
                throw IllegalStateException(DRM_MESSAGE)
            }
            return parseEpub(zip, file.nameWithoutExtension)
        }
    }

    private data class ManifestItem(
        val href: String,
        val mediaType: String = "",
        val properties: String = "",
    )

    private fun parseXml(text: String): org.w3c.dom.Document {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // No external entities or DTDs: an EPUB must never make the
        // parser reach outside the file.
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder()
            .parse(java.io.ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    }

    /** All descendant elements whose tag is [local] (prefix ignored). */
    private fun org.w3c.dom.Element.allNamed(local: String): List<org.w3c.dom.Element> {
        val out = mutableListOf<org.w3c.dom.Element>()
        val all = getElementsByTagName("*")
        for (i in 0 until all.length) {
            val el = all.item(i) as? org.w3c.dom.Element ?: continue
            if (el.tagName == local || el.tagName.endsWith(":$local")) out.add(el)
        }
        return out
    }

    private fun org.w3c.dom.Element.firstNamed(local: String): org.w3c.dom.Element? =
        allNamed(local).firstOrNull()

    private fun decodePath(path: String): String =
        runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)

    private fun normalizeZipPath(path: String): String {
        val parts = mutableListOf<String>()
        for (p in path.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    private fun readCapped(input: java.io.InputStream, cap: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > cap) {
                throw IllegalStateException("EPUB entry too large")
            }
        }
        return out.toByteArray()
    }

    private fun parseEpub(zip: ZipFile, fallbackTitle: String): ParsedBook {
        var totalReadBytes = 0L
        fun readEntryBytes(name: String): ByteArray? {
            // EPUB hrefs arrive URL-encoded, sometimes with fragments;
            // the zip stores real file names. Try the raw name and the
            // decoded name, exact and case-insensitively: a miss here
            // silently dropped whole chapters in Mariner ("partial"
            // imports).
            val decoded = decodePath(name)
            for (candidate in listOf(name, decoded).distinct()) {
                val e = zip.getEntry(candidate) ?: zip.entries().asSequence()
                    .firstOrNull { it.name.equals(candidate, ignoreCase = true) }
                if (e != null) {
                    // The declared size is checked first, then the read
                    // itself is capped, so a lying header cannot smuggle
                    // a zip bomb past the limit.
                    if (e.size > MAX_ENTRY_BYTES) {
                        throw IllegalStateException("EPUB entry too large: ${e.name}")
                    }
                    val bytes = readCapped(zip.getInputStream(e), MAX_ENTRY_BYTES)
                    totalReadBytes += bytes.size
                    if (totalReadBytes > MAX_TOTAL_BYTES) {
                        throw IllegalStateException("EPUB expands beyond the size limit")
                    }
                    return bytes
                }
            }
            return null
        }
        fun readEntry(name: String): String? =
            readEntryBytes(name)?.toString(Charsets.UTF_8)

        val container = readEntry("META-INF/container.xml")
            ?: throw IllegalStateException("Not an EPUB (no container.xml)")
        val opfPath = Regex("""full-path="([^"]+)"""").find(container)?.groupValues?.get(1)
            ?: throw IllegalStateException("Not an EPUB (no rootfile)")
        val opf = readEntry(opfPath) ?: throw IllegalStateException("Missing OPF")
        val opfDir = opfPath.substringBeforeLast('/', "")

        var title = fallbackTitle
        var author = ""
        val manifest = mutableMapOf<String, ManifestItem>()
        var spineIds: List<String> = emptyList()
        var coverId: String? = null
        var navId: String? = null
        var ncxId: String? = null

        val opfDoc = runCatching { parseXml(opf) }.getOrNull()
        if (opfDoc != null) {
            val root = opfDoc.documentElement
            root.firstNamed("metadata")?.let { md ->
                md.firstNamed("title")?.textContent?.trim()
                    ?.takeIf { it.isNotBlank() }?.let { title = it }
                md.firstNamed("creator")?.textContent?.trim()?.let { author = it }
                md.allNamed("meta").firstOrNull { it.getAttribute("name") == "cover" }
                    ?.getAttribute("content")?.takeIf { it.isNotBlank() }
                    ?.let { coverId = it }
            }
            root.firstNamed("manifest")?.allNamed("item")?.forEach { item ->
                val id = item.getAttribute("id")
                val href = item.getAttribute("href")
                if (id.isNotBlank() && href.isNotBlank()) {
                    manifest[id] = ManifestItem(
                        href,
                        item.getAttribute("media-type"),
                        item.getAttribute("properties"),
                    )
                }
            }
            root.firstNamed("spine")?.let { spine ->
                spineIds = spine.allNamed("itemref")
                    .map { it.getAttribute("idref") }.filter { it.isNotBlank() }
                spine.getAttribute("toc").takeIf { it.isNotBlank() }?.let { ncxId = it }
            }
            for ((id, item) in manifest) {
                if (item.properties.contains("cover-image")) coverId = id
                if (item.properties.split(' ').any { it == "nav" }) navId = id
                if (item.mediaType == "application/x-dtbncx+xml" && ncxId == null) {
                    ncxId = id
                }
            }
        } else {
            // Regex fallback for packages whose OPF won't parse.
            title = Regex("""<dc:title[^>]*>([^<]+)</dc:title>""").find(opf)
                ?.groupValues?.get(1)?.trim() ?: title
            author = Regex("""<dc:creator[^>]*>([^<]+)</dc:creator>""").find(opf)
                ?.groupValues?.get(1)?.trim() ?: ""
            Regex("""<item\s+[^>]*id="([^"]+)"[^>]*href="([^"]+)"""").findAll(opf).forEach {
                manifest[it.groupValues[1]] = ManifestItem(it.groupValues[2])
            }
            Regex("""<item\s+[^>]*href="([^"]+)"[^>]*id="([^"]+)"""").findAll(opf).forEach {
                val id = it.groupValues[2]
                if (id !in manifest) manifest[id] = ManifestItem(it.groupValues[1])
            }
            Regex("""<item\s+[^>]*id='([^']+)'[^>]*href='([^']+)'""").findAll(opf).forEach {
                if (it.groupValues[1] !in manifest) {
                    manifest[it.groupValues[1]] = ManifestItem(it.groupValues[2])
                }
            }
            Regex("""<item\s+[^>]*href='([^']+)'[^>]*id='([^']+)'""").findAll(opf).forEach {
                val id = it.groupValues[2]
                if (id !in manifest) manifest[id] = ManifestItem(it.groupValues[1])
            }
            spineIds = Regex("""<itemref\s+[^>]*idref="([^"]+)"""").findAll(opf)
                .map { it.groupValues[1] }.toList()
        }

        fun resolvePath(baseDir: String, href: String): String {
            val clean = href.substringBefore('#')
            val joined = when {
                clean.startsWith("/") -> clean.removePrefix("/")
                baseDir.isEmpty() -> clean
                else -> "$baseDir/$clean"
            }
            return decodePath(normalizeZipPath(joined))
        }

        // Table of contents: EPUB 3 nav, then EPUB 2 NCX. Keys are
        // resolved, decoded zip paths (fragment-free); first label
        // for a target wins.
        val toc = mutableMapOf<String, String>()
        fun addToc(baseDir: String, href: String, label: String) {
            val clean = label.replace(Regex("""\s+"""), " ").trim()
            if (clean.isEmpty() || href.isBlank()) return
            val key = resolvePath(baseDir, href)
            if (key !in toc) toc[key] = clean.take(120)
        }
        navId?.let { manifest[it] }?.let { navItem ->
            val navPath = resolvePath(opfDir, navItem.href)
            val navText = readEntry(navPath)
            val navDoc = navText?.let { runCatching { parseXml(it) }.getOrNull() }
            if (navDoc != null) {
                val navs = navDoc.documentElement.allNamed("nav")
                val tocNav = navs.firstOrNull {
                    it.getAttribute("epub:type").contains("toc")
                } ?: navs.firstOrNull()
                tocNav?.allNamed("a")?.forEach { a ->
                    addToc(navPath.substringBeforeLast('/', ""), a.getAttribute("href"), a.textContent ?: "")
                }
            }
        }
        ncxId?.let { manifest[it] }?.let { ncxItem ->
            val ncxPath = resolvePath(opfDir, ncxItem.href)
            val ncxText = readEntry(ncxPath)
            val ncxDoc = ncxText?.let { runCatching { parseXml(it) }.getOrNull() }
            if (ncxDoc != null) {
                val ncxDir = ncxPath.substringBeforeLast('/', "")
                ncxDoc.documentElement.allNamed("navPoint").forEach { point ->
                    val label = point.firstNamed("text")?.textContent ?: ""
                    val src = point.firstNamed("content")?.getAttribute("src") ?: ""
                    addToc(ncxDir, src, label)
                }
            }
        }

        // Cover image bytes. The declared reference does not always
        // point at the image itself: some packages point it at the
        // cover wrapper page, and some name the cover only in the
        // guide. Follow the chain until real image bytes turn up.
        fun looksLikeImage(b: ByteArray?): Boolean {
            if (b == null || b.size < 4) return false
            val jpeg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
            val png = b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
                b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()
            val gif = b[0] == 0x47.toByte() && b[1] == 0x49.toByte() &&
                b[2] == 0x46.toByte()
            return jpeg || png || gif
        }
        fun imageFromXhtml(xhtml: ByteArray, baseDir: String): ByteArray? {
            val s = xhtml.toString(Charsets.UTF_8)
            val tag = Regex("<(?:img|image)\\b[^>]*>", RegexOption.IGNORE_CASE)
                .find(s)?.value ?: return null
            val src = Regex("(?:src|xlink:href|href)\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1)
                ?: Regex("(?:src|xlink:href|href)\\s*=\\s*'([^']+)'", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1) ?: return null
            val bytes = readEntryBytes(resolvePath(baseDir, src))
            return if (looksLikeImage(bytes)) bytes else null
        }
        fun coverBytesFor(href: String): ByteArray? {
            val path = resolvePath(opfDir, href)
            val bytes = readEntryBytes(path) ?: return null
            val declaredImage = manifest.values.any {
                it.mediaType.startsWith("image/") &&
                    resolvePath(opfDir, it.href) == path
            }
            if (declaredImage || looksLikeImage(bytes)) return bytes
            return imageFromXhtml(bytes, path.substringBeforeLast('/', ""))
        }
        var coverBytes = coverId?.let { manifest[it]?.href }?.let { coverBytesFor(it) }
        if (coverBytes == null) {
            val guideHref = opfDoc?.documentElement?.firstNamed("guide")
                ?.allNamed("reference")
                ?.firstOrNull { it.getAttribute("type") == "cover" }
                ?.getAttribute("href")?.takeIf { it.isNotBlank() }
                ?: Regex("<reference[^>]*type=\"cover\"[^>]*href=\"([^\"]+)\"")
                    .find(opf)?.groupValues?.get(1)
                ?: Regex("<reference[^>]*href=\"([^\"]+)\"[^>]*type=\"cover\"")
                    .find(opf)?.groupValues?.get(1)
            if (guideHref != null) coverBytes = coverBytesFor(guideHref)
        }

        // Chapters in spine order (manifest fallback kept).
        val orderedHrefs = spineIds.mapNotNull { manifest[it]?.href }
            .ifEmpty {
                manifest.values.map { it.href }.filter { h ->
                    val clean = h.lowercase().substringBefore('#')
                    clean.endsWith(".xhtml") || clean.endsWith(".html") || clean.endsWith(".htm")
                }
            }
        val images = mutableMapOf<String, ByteArray>()
        var imageSeq = 0
        val imgTag = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
        val svgImageTag = Regex("""<image\b[^>]*>""", RegexOption.IGNORE_CASE)
        val srcAttr = Regex("""(?:src|xlink:href|href)\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        val srcAttrSq = Regex("""(?:src|xlink:href|href)\s*=\s*'([^']+)'""", RegexOption.IGNORE_CASE)

        val chapters = mutableListOf<BookChapter>()
        var index = 0
        for (rawHref in orderedHrefs) {
            val path = resolvePath(opfDir, rawHref)
            val html = readEntry(path) ?: continue
            val chapterDir = path.substringBeforeLast('/', "")
            fun markImages(tagged: String): String {
                var out = imgTag.replace(tagged) { m ->
                    val src = srcAttr.find(m.value)?.groupValues?.get(1)
                        ?: srcAttrSq.find(m.value)?.groupValues?.get(1) ?: ""
                    val bytes = if (src.isBlank()) null
                        else readEntryBytes(resolvePath(chapterDir, src))
                    if (bytes == null) "" else {
                        val token = "img${imageSeq++}"
                        images[token] = bytes
                        "\n\n[[img:$token]]\n\n"
                    }
                }
                out = svgImageTag.replace(out) { m ->
                    val src = srcAttr.find(m.value)?.groupValues?.get(1)
                        ?: srcAttrSq.find(m.value)?.groupValues?.get(1) ?: ""
                    val bytes = if (src.isBlank()) null
                        else readEntryBytes(resolvePath(chapterDir, src))
                    if (bytes == null) "" else {
                        val token = "img${imageSeq++}"
                        images[token] = bytes
                        "\n\n[[img:$token]]\n\n"
                    }
                }
                return out
            }
            val text = htmlToText(markImages(html))
            if (text.isBlank()) continue
            index++
            val chTitle = toc[path]
                ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
                ?: Regex("""<h[12][^>]*>(.*?)</h[12]>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(html)?.groupValues?.get(1)?.let { htmlToText(it) }?.takeIf { it.isNotBlank() }
                ?: "Chapter $index"
            chapters.add(BookChapter(chTitle.take(120), text))
        }
        // Cover-wrapper pages: a spine item that is only the cover
        // image plus scraps of furniture text reads as the image
        // alone. And where a file opens by printing the book's title
        // twice (its own h1 plus h2), one of them goes.
        val coverRef = coverBytes
        val cleaned = chapters.map { ch ->
            val paras = ch.text.split("\n\n")
            val imgParas = paras.filter { it.contains("[[img:") }
            val textChars = paras.filterNot { it.contains("[[img:") }
                .sumOf { it.trim().length }
            var body = ch.text
            val carriesCover = coverRef != null && imgParas.any { p ->
                Regex("""\[\[img:(\w+)\]\]""").findAll(p).any { m ->
                    images[m.groupValues[1]]?.contentEquals(coverRef) == true
                }
            }
            if (imgParas.isNotEmpty() && textChars < 300 && carriesCover) {
                body = imgParas.joinToString("\n\n")
            } else {
                val lines = body.lines().toMutableList()
                val nonBlank = lines.withIndex().filter { it.value.isNotBlank() }
                if (nonBlank.size >= 2) {
                    val head = nonBlank[0].value.trim()
                    val next = nonBlank[1].value.trim()
                    if (head.isNotEmpty() && head.equals(next, ignoreCase = true) &&
                        (head.equals(title, ignoreCase = true) ||
                            head.equals(ch.title, ignoreCase = true))
                    ) {
                        lines.removeAt(nonBlank[1].index)
                        body = lines.joinToString("\n").trim()
                    }
                }
            }
            if (body == ch.text) ch else BookChapter(ch.title, body)
        }
        val split = splitLongChapters(cleaned)
        if (split.isEmpty()) throw IllegalStateException("EPUB had no readable chapters")
        return ParsedBook(title, author, split, coverBytes, images)
    }

    /**
     * Split chapters longer than [maxChars] at block boundaries, so a
     * book whose spine items are huge still pages honestly in the
     * reader. Deterministic: the same input always splits the same
     * way, so saved chapter positions stay meaningful. Follow-on
     * parts are titled "Name (2)", "Name (3)", and so on.
     */
    fun splitLongChapters(
        chapters: List<BookChapter>,
        maxChars: Int = MAX_CHAPTER_CHARS,
    ): List<BookChapter> {
        val out = mutableListOf<BookChapter>()
        for (ch in chapters) {
            if (ch.text.length <= maxChars) {
                out.add(ch)
                continue
            }
            val blocks = ch.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val parts = mutableListOf<String>()
            val cur = StringBuilder()
            for (b in blocks) {
                if (cur.isNotEmpty() && cur.length + b.length + 2 > maxChars) {
                    parts.add(cur.toString())
                    cur.clear()
                }
                if (cur.isNotEmpty()) cur.append("\n\n")
                cur.append(b)
            }
            if (cur.isNotEmpty()) parts.add(cur.toString())
            if (parts.size <= 1) {
                out.add(ch)
                continue
            }
            parts.forEachIndexed { i, partText ->
                out.add(
                    BookChapter(
                        if (i == 0) ch.title else "${ch.title} (${i + 1})",
                        partText,
                    ),
                )
            }
        }
        return out
    }

    /**
     * Strip a chapter document to reflowed text: script and style go,
     * block ends become line breaks, then Html renders the rest (or a
     * plain tag strip on the JVM test classpath, where Html is a stub).
     */
    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("""<script[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("""<style[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("""<(br|/p|/div|/h[1-6]|/li|/tr)[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        val rendered = runCatching {
            @Suppress("DEPRECATION")
            android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        }.getOrElse {
            s.replace(Regex("""<[^>]+>"""), " ")
        }
        return rendered
            .replace(Regex("""[ \t\x0B\f]+"""), " ")
            .replace(Regex("""\n[ ]+"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
