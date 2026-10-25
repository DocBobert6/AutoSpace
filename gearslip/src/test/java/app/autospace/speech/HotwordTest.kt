package app.autospace.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The hotword matcher, without a microphone: the phrase must start the utterance.
 * Nothing here touches audio, models, or settings, so it runs on the plain JVM.
 */
class HotwordTest {

    @Test
    fun exactPhrasesTrigger() {
        assertEquals(HotwordListener.PHRASE_MAIN, HotwordListener.findHotword("hey autospace"))
        assertEquals(HotwordListener.PHRASE_ALIAS, HotwordListener.findHotword("hey gearslip"))
    }

    @Test
    fun phraseFollowedByACommandTriggers() {
        assertEquals(
            HotwordListener.PHRASE_MAIN,
            HotwordListener.findHotword("hey autospace navigate to the nearest gas station"),
        )
    }

    @Test
    fun splitBrandNamesTrigger() {
        // A speech model spells brand names as common words: "AutoSpace" comes
        // out as "auto space", "Gearslip" as "gear slip". Both must trigger.
        assertEquals(HotwordListener.PHRASE_MAIN, HotwordListener.findHotword("hey auto space"))
        assertEquals(
            HotwordListener.PHRASE_MAIN,
            HotwordListener.findHotword("hey auto space navigate to the nearest gas station"),
        )
        assertEquals(HotwordListener.PHRASE_ALIAS, HotwordListener.findHotword("hey gear slip"))
    }

    @Test
    fun splitLookalikesNeverTrigger() {
        assertNull(HotwordListener.findHotword("hey auto spacex"))
        assertNull(HotwordListener.findHotword("hey auto spacecraft"))
        assertNull(HotwordListener.findHotword("hey gear slipstream"))
    }

    @Test
    fun bareHeyNeverTriggers() {
        assertNull(HotwordListener.findHotword("hey"))
        assertNull(HotwordListener.findHotword("hey there"))
        assertNull(HotwordListener.findHotword("hey auto"))
    }

    @Test
    fun phraseBuriedMidSentenceNeverTriggers() {
        assertNull(HotwordListener.findHotword("i said hey autospace"))
        assertNull(HotwordListener.findHotword("okay hey gearslip"))
    }

    @Test
    fun lookalikesNeverTrigger() {
        assertNull(HotwordListener.findHotword("hey autospacex"))
        assertNull(HotwordListener.findHotword("hey gearslip2"))
        assertNull(HotwordListener.findHotword(""))
    }
}
