package app.autospace.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r148: games immersive fullscreen, pinned at the pure seams.
 * The composable wiring itself (Expand publishing, Shrink
 * exiting, the Pause button opening the menu, the padding
 * following the published state) has no Compose UI test seam
 * in this tree — the games tests are pure JVM tests — so what
 * is pinned here is the state object the wiring drives and
 * the chrome rule CarUi applies to it. The wiring is stated
 * plainly in GamePlaybackScreen, CarUi, and GamesScreen.
 */
class GameImmersiveTest {

    @Test
    fun enterPublishesImmersive() {
        GameImmersiveState.exit()
        GameImmersiveState.enter()
        assertTrue(GameImmersiveState.immersive.value)
        GameImmersiveState.exit()
    }

    @Test
    fun exitClearsImmersive() {
        GameImmersiveState.enter()
        GameImmersiveState.exit()
        assertFalse(GameImmersiveState.immersive.value)
    }

    @Test
    fun exitIsIdempotentFromCleared() {
        GameImmersiveState.exit()
        GameImmersiveState.exit()
        assertFalse(GameImmersiveState.immersive.value)
    }

    @Test
    fun chromeHidesOnlyForGamesWhenImmersive() {
        assertTrue(gameImmersiveHidesChrome(screenIsGames = true, immersive = true))
        assertFalse(gameImmersiveHidesChrome(screenIsGames = true, immersive = false))
        assertFalse(gameImmersiveHidesChrome(screenIsGames = false, immersive = true))
        assertFalse(gameImmersiveHidesChrome(screenIsGames = false, immersive = false))
    }

    @Test
    fun leavingFullscreenRestoresChrome() {
        GameImmersiveState.enter()
        assertTrue(gameImmersiveHidesChrome(screenIsGames = true, immersive = GameImmersiveState.immersive.value))
        GameImmersiveState.exit()
        assertFalse(gameImmersiveHidesChrome(screenIsGames = true, immersive = GameImmersiveState.immersive.value))
    }
}
