package app.autospace.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * r161: EPUB parser, shelf codec, import gate, auto-shelf guard, and
 * reader position helpers. The parser runs on the JVM test classpath,
 * where android.text.Html is a stub, so [EpubParser.htmlToText] takes
 * its tag-strip fallback there; these tests assert structure (titles,
 * splits, tokens), not Html's exact whitespace.
 */
class BookEpubTest {

    private fun epubFile(entries: Map<String, ByteArray>): File {
        val dir = Files.createTempDirectory("booktest").toFile()
        val file = File(dir, "test.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }

    private fun text(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    private val container = text(
        """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
        <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
        </rootfiles></container>""",
    )

    private fun opf3(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>The Test Book</dc:title>
        <dc:creator>Jane Author</dc:creator>
        <meta name="cover" content="cover-img"/>
        </metadata>
        <manifest>
        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
        <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
        <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
        <item id="cover-img" href="cover.png" media-type="image/png"/>
        </manifest>
        <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
        </package>
    """.trimIndent()

    private fun navDoc(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <body><nav epub:type="toc"><ol>
        <li><a href="ch1.xhtml">First Part</a></li>
        <li><a href="ch2.xhtml">Second Part</a></li>
        </ol></nav></body></html>
    """.trimIndent()

    private fun chapter(title: String, body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head>
        <body><h1>$title</h1>$body</body></html>""".trimIndent()

    private fun standardEpub(): File = epubFile(
        mapOf(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to text(opf3()),
            "OEBPS/nav.xhtml" to text(navDoc()),
            "OEBPS/ch1.xhtml" to text(chapter("One", "<p>Hello world, this is chapter one.</p>")),
            "OEBPS/ch2.xhtml" to text(chapter("Two", "<p>Second chapter text goes here.</p>")),
            "OEBPS/cover.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4),
        ),
    )

    @Test
    fun parsesTitleAuthorSpineOrderAndTocTitles() {
        val book = EpubParser.parse(standardEpub())
        assertEquals("The Test Book", book.title)
        assertEquals("Jane Author", book.author)
        assertEquals(listOf("First Part", "Second Part"), book.chapters.map { it.title })
        assertTrue(book.chapters[0].text.contains("Hello world"))
        assertTrue(book.chapters[1].text.contains("Second chapter"))
        assertNotNull(book.cover)
        assertTrue(book.cover!!.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)))
    }

    @Test
    fun coverImagePropertyFindsCoverWithoutMeta() {
        val opf = opf3().replace(
            """<meta name="cover" content="cover-img"/>""",
            "",
        ).replace(
            """<item id="cover-img" href="cover.png" media-type="image/png"/>""",
            """<item id="cover-img" href="cover.png" media-type="image/png" properties="cover-image"/>""",
        )
        val file = epubFile(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to text(opf),
                "OEBPS/nav.xhtml" to text(navDoc()),
                "OEBPS/ch1.xhtml" to text(chapter("One", "<p>Text.</p>")),
                "OEBPS/ch2.xhtml" to text(chapter("Two", "<p>Text.</p>")),
                "OEBPS/cover.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9),
            ),
        )
        val book = EpubParser.parse(file)
        assertNotNull(book.cover)
        assertTrue(book.cover!!.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9)))
    }

    @Test
    fun splitsLongChapterAtBlockBoundaries() {
        val paragraphs = (1..50).joinToString("") { n ->
            "<p>Paragraph $n carries enough words to fill out the chapter body properly.</p>"
        }
        val file = epubFile(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to text(
                    opf3().replace(
                        """<item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>""",
                        "",
                    ).replace(
                        """<itemref idref="ch2"/>""",
                        "",
                    ).replace(
                        """<li><a href="ch2.xhtml">Second Part</a></li>""",
                        "",
                    ),
                ),
                "OEBPS/nav.xhtml" to text(navDoc().replace("""<li><a href="ch2.xhtml">Second Part</a></li>""", "").replace("First Part", "Long Chapter")),
                "OEBPS/ch1.xhtml" to text(chapter("Long Chapter", paragraphs)),
                "OEBPS/cover.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            ),
        )
        val book = EpubParser.parse(file)
        assertTrue("expected a split, got ${book.chapters.size}", book.chapters.size >= 2)
        assertEquals("Long Chapter", book.chapters[0].title)
        assertEquals("Long Chapter (2)", book.chapters[1].title)
        assertTrue(book.chapters.all { it.text.length <= 3600 })
    }

    @Test
    fun singleHugeBlockStaysWhole() {
        val oneBlock = "word ".repeat(1000)
        val chapters = EpubParser.splitLongChapters(
            listOf(BookChapter("Big", oneBlock)),
        )
        assertEquals(1, chapters.size)
        assertEquals("Big", chapters[0].title)
    }

    @Test
    fun imageTokensAndImagesMap() {
        val pic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 7, 7, 7)
        val file = epubFile(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to text(opf3()),
                "OEBPS/nav.xhtml" to text(navDoc()),
                "OEBPS/ch1.xhtml" to text(
                    chapter("Pic", "<p>Before</p><p><img src=\"pic.png\"/></p><p>After</p>"),
                ),
                "OEBPS/ch2.xhtml" to text(chapter("Two", "<p>Text.</p>")),
                "OEBPS/cover.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                "OEBPS/pic.png" to pic,
            ),
        )
        val book = EpubParser.parse(file)
        assertTrue(book.chapters[0].text.contains("[[img:img0]]"))
        assertTrue(book.images["img0"]!!.contentEquals(pic))
        val parts = readerParts(book.chapters[0].text.split("\n").map { it.trim() }.filter { it.isNotEmpty() })
        val imgIdx = parts.indexOf(BookPart.Image("img0"))
        assertTrue(imgIdx > 0)
        assertEquals(BookPart.Text("Before"), parts[imgIdx - 1])
        assertEquals(BookPart.Text("After"), parts[imgIdx + 1])
    }

    @Test
    fun entryCapRefused() {
        val big = ByteArray(65 * 1024 * 1024)
        val file = epubFile(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to text(opf3()),
                "OEBPS/nav.xhtml" to text(navDoc()),
                "OEBPS/ch1.xhtml" to big,
                "OEBPS/ch2.xhtml" to text(chapter("Two", "<p>Text.</p>")),
                "OEBPS/cover.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            ),
        )
        try {
            EpubParser.parse(file)
            fail("expected an over-cap refusal")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test
    fun malformedEpubRefused() {
        val noContainer = epubFile(mapOf("OEBPS/content.opf" to text(opf3())))
        try {
            EpubParser.parse(noContainer)
            fail("expected a container refusal")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Not an EPUB"))
        }
        val empty = epubFile(emptyMap())
        try {
            EpubParser.parse(empty)
            fail("expected an empty-zip refusal")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Not an EPUB"))
        }
    }

    @Test
    fun drmRefusedOnEncryptionDocument() {
        val file = epubFile(
            mapOf(
                "META-INF/container.xml" to container,
                "META-INF/encryption.xml" to text("<encryption/>"),
                "OEBPS/content.opf" to text(opf3()),
                "OEBPS/nav.xhtml" to text(navDoc()),
                "OEBPS/ch1.xhtml" to text(chapter("One", "<p>Text.</p>")),
                "OEBPS/ch2.xhtml" to text(chapter("Two", "<p>Text.</p>")),
            ),
        )
        try {
            EpubParser.parse(file)
            fail("expected a DRM refusal")
        } catch (e: IllegalStateException) {
            assertEquals(EpubParser.DRM_MESSAGE, e.message)
        }
    }

    @Test
    fun htmlToTextStripsScriptAndStyle() {
        val out = EpubParser.htmlToText(
            "<style>.hidden{display:none}</style><script>var secret = 1;</script><p>Visible words</p>",
        )
        assertFalse(out.contains("secret"))
        assertFalse(out.contains("hidden"))
        assertTrue(out.contains("Visible words"))
    }

    @Test
    fun importGateRefusesEverythingButEpub() {
        assertEquals(BookGate.EPUB, BookLibrary.gateForFileName("book.epub"))
        assertEquals(BookGate.EPUB, BookLibrary.gateForFileName("Book.EPUB"))
        assertEquals(BookGate.MOBI, BookLibrary.gateForFileName("book.mobi"))
        assertEquals(BookGate.MOBI, BookLibrary.gateForFileName("book.azw"))
        assertEquals(BookGate.MOBI, BookLibrary.gateForFileName("book.azw3"))
        assertEquals(BookGate.MOBI, BookLibrary.gateForFileName("book.prc"))
        assertEquals(BookGate.OTHER, BookLibrary.gateForFileName("book.txt"))
        assertEquals(BookGate.OTHER, BookLibrary.gateForFileName("book.pdf"))
        assertEquals(BookGate.OTHER, BookLibrary.gateForFileName("book"))
    }

    @Test
    fun recordCodecRoundTripAndMalformedSkipped() {
        val books = listOf(
            BookRecord("id-1", "Title", "Author", "123.epub", "EPUB", 3, 0.5f, 1700000000000L, "id-1-cover.img"),
            BookRecord("id-2", "No Cover", "", "124.epub", "EPUB", 0, 0f, 1700000000001L, null),
        )
        val parsed = BookLibrary.parse(BookLibrary.toJson(books))
        assertEquals(books, parsed)
        assertEquals(emptyList<BookRecord>(), BookLibrary.parse("not json"))
        assertEquals(
            listOf(books[0]),
            BookLibrary.parse("""[{"id":"id-1","title":"Title","author":"Author","filePath":"123.epub","format":"EPUB","chapterIndex":3,"scrollFraction":0.5,"addedAt":1700000000000,"coverPath":"id-1-cover.img"},{"id":1}]"""),
        )
    }

    @Test
    fun shelfCapRule() {
        assertTrue(BookLibrary.canAdd(499))
        assertFalse(BookLibrary.canAdd(500))
        assertFalse(BookLibrary.canAdd(501))
    }

    @Test
    fun autoShelfNamesCodecRoundTrip() {
        val names = listOf("a book.epub", "we\"ird\\name.epub", "plain.epub")
        assertEquals(names, BookLibrary.decodeNames(BookLibrary.encodeNames(names)))
        assertEquals(emptyList<String>(), BookLibrary.decodeNames("garbage"))
    }

    @Test
    fun autoShelfStoredKeyIsStable() {
        // The Settings row writes this exact string, and the download
        // completion receiver reads it; renaming it would orphan the
        // choice for everyone who already set it.
        assertEquals("car_epub_auto_shelf", BookLibrary.KEY_AUTO_SHELF)
    }

    @Test
    fun shouldAutoShelfMatrix() {
        fun check(
            on: Boolean = true,
            private: Boolean = false,
            state: BrowserStore.DownloadState = BrowserStore.DownloadState.COMPLETE,
            name: String = "book.epub",
            shelved: Boolean = false,
            expected: Boolean,
        ) {
            assertEquals(
                expected,
                BookAutoShelf.shouldAutoShelf(on, private, state, name, shelved),
            )
        }
        check(expected = true)
        check(on = false, expected = false)
        check(private = true, expected = false)
        check(state = BrowserStore.DownloadState.IN_PROGRESS, expected = false)
        check(state = BrowserStore.DownloadState.FAILED, expected = false)
        check(name = "book.pdf", expected = false)
        check(name = "book.mobi", expected = false)
        check(shelved = true, expected = false)
    }

    @Test
    fun readAloudChunkingRespectsSixHundredChars() {
        val text = stripImageTokens("First sentence here. [[img:img0]] Second sentence follows. " +
            "A much longer sentence that keeps going with more words to fill space. ".repeat(8))
        assertFalse(text.contains("[[img:"))
        val chunks = ArticleExtractor.sentenceChunks(text, 600)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= 600 })
        assertTrue(chunks.first().startsWith("First sentence here."))
    }

    @Test
    fun progressMappingRoundTrip() {
        val itemChapters = listOf(0, 0, 0, 1, 1, 2)
        assertEquals(1 to 1.0f, BookLibrary.progressForVisibleItem(itemChapters, 4))
        assertEquals(0 to 0.0f, BookLibrary.progressForVisibleItem(itemChapters, 0))
        assertEquals(0 to 0.5f, BookLibrary.progressForVisibleItem(itemChapters, 1))
        assertEquals(3, BookLibrary.itemIndexForProgress(itemChapters, 1, 0.5f))
        assertEquals(5, BookLibrary.itemIndexForProgress(itemChapters, 2, 0.9f))
        assertEquals(0, BookLibrary.itemIndexForProgress(itemChapters, 9, 0.5f))
        assertEquals(2, BookLibrary.itemIndexForProgress(itemChapters, 0, 2.0f))
        assertEquals(0 to 0f, BookLibrary.progressForVisibleItem(emptyList(), 0))
    }

    @Test
    fun parsedCacheCapsAtThree() {
        val book = ParsedBook("T", "A", emptyList())
        ParsedBookCache.put("a", book)
        ParsedBookCache.put("b", book)
        ParsedBookCache.put("c", book)
        ParsedBookCache.put("d", book)
        assertEquals(3, ParsedBookCache.size())
        assertNull(ParsedBookCache.get("a"))
        assertNotNull(ParsedBookCache.get("d"))
    }

    @Test
    fun stripImageTokensCollapsesWhitespace() {
        assertEquals(
            "Hello world",
            stripImageTokens("Hello [[img:img0]]  world"),
        )
    }
}
