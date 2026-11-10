package app.autospace

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** r221 (audit P-04): the reverse log-tail read, pinned against
 *  readLines().takeLast(500) — the behaviour it replaces. */
class CrashReporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeText(name: String, text: String): File =
        tmp.newFile(name).apply { writeBytes(text.toByteArray(StandardCharsets.UTF_8)) }

    @Test
    fun readTailMatchesReadLines() {
        val lines = (1..100).map { "line $it" }
        val file = writeText("a.log", lines.joinToString("\n") + "\n")
        assertEquals(lines, CrashReporter.readTail(file))
    }

    @Test
    fun readTailWithoutTrailingNewline() {
        val file = writeText("b.log", "a\nb\nc")
        assertEquals(listOf("a", "b", "c"), CrashReporter.readTail(file))
    }

    @Test
    fun readTailKeepsOnlyLast500() {
        val lines = (1..600).map { "line $it" }
        val file = writeText("c.log", lines.joinToString("\n") + "\n")
        assertEquals(lines.takeLast(500), CrashReporter.readTail(file))
    }

    @Test
    fun readTailEmptyFile() {
        val file = writeText("d.log", "")
        assertEquals(emptyList<String>(), CrashReporter.readTail(file))
    }

    @Test
    fun readTailSingleNewlineIsOneEmptyLine() {
        val file = writeText("e.log", "\n")
        assertEquals(listOf(""), CrashReporter.readTail(file))
    }

    @Test
    fun readTailTwoNewlinesAreTwoEmptyLines() {
        val file = writeText("f.log", "\n\n")
        assertEquals(listOf("", ""), CrashReporter.readTail(file))
    }

    @Test
    fun readTailSingleLongLineAcrossChunks() {
        val line = "x".repeat(20_000)
        val file = writeText("g.log", line + "\nsecond\n")
        assertEquals(listOf(line, "second"), CrashReporter.readTail(file))
    }

    @Test
    fun readTailStripsCarriageReturn() {
        val file = writeText("h.log", "a\r\nb\r\n")
        assertEquals(listOf("a", "b"), CrashReporter.readTail(file))
    }

    @Test
    fun readTailMissingFileIsEmpty() {
        assertEquals(emptyList<String>(), CrashReporter.readTail(File(tmp.root, "nope.log")))
    }

    @Test
    fun readTailMultiByteCharSplitAcrossChunkBoundary() {
        // "é" (2 UTF-8 bytes) straddling the chunk edge: the file is
        // 16,383 bytes, so the backward read splits it at byte 8191 —
        // the first byte of "é" lands in the earlier chunk, the second
        // in the later one. The line must still decode whole.
        val prefix = "a".repeat(8190)
        val suffix = "b".repeat(8190)
        val file = writeText("i.log", prefix + "é" + suffix + "\n")
        assertEquals(listOf(prefix + "é" + suffix), CrashReporter.readTail(file))
    }
}
