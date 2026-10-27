package app.autospace.car

import androidx.compose.ui.Alignment
import org.junit.Assert.assertEquals
import org.junit.Test

/** r69 mini-player docking: stored-choice parsing, experimental gate, trip-bar fallback, alignment mapping. */
class MediaCardDockTest {

    @Test
    fun storedEndParses() {
        assertEquals(MediaCardDock.END, mediaCardDockFromStored("END"))
    }

    @Test
    fun storedCenterParses() {
        assertEquals(MediaCardDock.CENTER, mediaCardDockFromStored("CENTER"))
    }

    @Test
    fun storedStartParses() {
        assertEquals(MediaCardDock.START, mediaCardDockFromStored("START"))
    }

    @Test
    fun blankStoredDefaultsToEnd() {
        assertEquals(MediaCardDock.END, mediaCardDockFromStored(""))
    }

    @Test
    fun unknownStoredDefaultsToEnd() {
        assertEquals(MediaCardDock.END, mediaCardDockFromStored("CORNER"))
    }

    @Test
    fun lowercaseStoredDefaultsToEnd() {
        assertEquals(MediaCardDock.END, mediaCardDockFromStored("start"))
    }

    @Test
    fun gateOffReturnsEndForStart() {
        assertEquals(MediaCardDock.END, mediaCardDockNow(false, MediaCardDock.START))
    }

    @Test
    fun gateOffReturnsEndForCenter() {
        assertEquals(MediaCardDock.END, mediaCardDockNow(false, MediaCardDock.CENTER))
    }

    @Test
    fun gateOnKeepsStart() {
        assertEquals(MediaCardDock.START, mediaCardDockNow(true, MediaCardDock.START))
    }

    @Test
    fun gateOnKeepsCenter() {
        assertEquals(MediaCardDock.CENTER, mediaCardDockNow(true, MediaCardDock.CENTER))
    }

    @Test
    fun startWithEtaStepsAsideToEnd() {
        assertEquals(MediaCardDock.END, effectiveMediaCardDock(true, MediaCardDock.START, true))
    }

    @Test
    fun centerWithEtaStaysCenter() {
        assertEquals(MediaCardDock.CENTER, effectiveMediaCardDock(true, MediaCardDock.CENTER, true))
    }

    @Test
    fun endWithEtaStaysEnd() {
        assertEquals(MediaCardDock.END, effectiveMediaCardDock(true, MediaCardDock.END, true))
    }

    @Test
    fun startWithoutEtaStaysStart() {
        assertEquals(MediaCardDock.START, effectiveMediaCardDock(true, MediaCardDock.START, false))
    }

    @Test
    fun gateOffBeatsEtaFallback() {
        assertEquals(MediaCardDock.END, effectiveMediaCardDock(false, MediaCardDock.START, true))
    }

    @Test
    fun endMapsToBottomEnd() {
        assertEquals(Alignment.BottomEnd, mediaCardDockAlignment(MediaCardDock.END))
    }

    @Test
    fun centerMapsToBottomCenter() {
        assertEquals(Alignment.BottomCenter, mediaCardDockAlignment(MediaCardDock.CENTER))
    }

    @Test
    fun startMapsToBottomStart() {
        assertEquals(Alignment.BottomStart, mediaCardDockAlignment(MediaCardDock.START))
    }
}
