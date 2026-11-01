package app.autospace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Audit round R12 (cleanup/dedup) pins: the one pure JVM seam this round
 * creates — [GearslipLog.deepestCause], the shared cause-chain walk that
 * [GearslipLog.rootCause] and `SessionReport.codeOf` both use now instead
 * of their own inline walks (audit §2 CLEAN-N05). Everything else in
 * this round has no JVM seam: the HTTP helper needs a network, the
 * location helper needs a Context, the key-slot helpers are private,
 * the renames are private helpers, the log lines need a live log, and
 * the `!!` restyles need Compose or a mic — so those rest on CI green
 * plus the device checks, and what CAN be pinned here is pinned here.
 */
class CleanupDedupTest {

    @Test
    fun deepestCauseReturnsSingleThrowableItself() {
        val t = RuntimeException("boom")
        assertSame(t, GearslipLog.deepestCause(t))
    }

    @Test
    fun deepestCauseWalksToTheDeepestCause() {
        val root = IllegalStateException("root cause")
        val middle = RuntimeException("middle", root)
        val top = RuntimeException("top", middle)
        assertSame(root, GearslipLog.deepestCause(top))
    }

    @Test
    fun deepestCauseStopsOnASelfLoopInsteadOfSpinning() {
        val loop = object : RuntimeException("loop") {
            override val cause: Throwable get() = this
        }
        assertSame(loop, GearslipLog.deepestCause(loop))
    }

    @Test
    fun rootCauseFormatsTopPlusDeepestCause() {
        val root = IllegalStateException("bad_certificate")
        val top = RuntimeException("handshake failed", root)
        assertEquals(
            "RuntimeException: handshake failed <- IllegalStateException: bad_certificate",
            GearslipLog.rootCause(top),
        )
    }

    @Test
    fun rootCauseOfSingleThrowableHasNoArrow() {
        val t = RuntimeException("boom")
        assertEquals("RuntimeException: boom", GearslipLog.rootCause(t))
    }
}
