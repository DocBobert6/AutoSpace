package app.autospace.ui

import app.autospace.update.UpdateChecker
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * r68 — the Home "Updates" row reuses UpdateRow, whose subtitle comes from [statusLine].
 * Only the pure seams are pinned here: the Idle line (which takes its debug flag as a
 * parameter because unit tests run as a debug build, so [statusLine] always takes the
 * debug branch there) and the non-Ready status lines. The Ready line without a car
 * session is pinned too — SessionStatus defaults to IDLE, so carConnected is false in
 * tests; the connected "unplug first" line is covered by the device list, not faked
 * here. The row's Compose itself stands on CI compile + the device list.
 */
class UpdateStatusLineTest {

    @Test
    fun idleLineDebugBuild() {
        assertEquals(
            "Debug builds check only when you tap Check now",
            idleStatusLine(autoCheck = true, debug = true),
        )
        assertEquals(
            "Debug builds check only when you tap Check now",
            idleStatusLine(autoCheck = false, debug = true),
        )
    }

    @Test
    fun idleLineAutoOffDoesNotClaimDailyChecks() {
        assertEquals("Automatic checks are off", idleStatusLine(autoCheck = false, debug = false))
    }

    @Test
    fun idleLineAutoOn() {
        assertEquals("AutoSpace checks once a day", idleStatusLine(autoCheck = true, debug = false))
    }

    @Test
    fun statusLinesNameStateHonestly() {
        assertEquals("Checking…", statusLine(UpdateChecker.State.Checking, autoCheck = true))
        assertEquals("You have the latest version", statusLine(UpdateChecker.State.UpToDate, autoCheck = true))
        assertEquals(
            "Version 0.4.25-alpha is out",
            statusLine(
                UpdateChecker.State.Available(
                    UpdateChecker.Release("0.4.25-alpha", "", 0L, null, ""),
                ),
                autoCheck = true,
            ),
        )
        assertEquals(
            "Couldn't reach GitHub. Check the connection and try again.",
            statusLine(
                UpdateChecker.State.Failed("Couldn't reach GitHub. Check the connection and try again."),
                autoCheck = true,
            ),
        )
    }

    @Test
    fun readyLineWithoutCarConnectedOffersInstall() {
        assertEquals(
            "Version 0.4.25-alpha is ready to install",
            statusLine(
                UpdateChecker.State.Ready(
                    UpdateChecker.Release("0.4.25-alpha", "", 0L, null, ""),
                    File("AutoSpace-0.4.25-alpha.apk"),
                ),
                autoCheck = true,
            ),
        )
    }
}
