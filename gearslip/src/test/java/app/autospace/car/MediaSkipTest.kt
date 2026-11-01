package app.autospace.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r156 whole-token skip rule, without a microphone or a media session: pins
 * the pure token test [AssistantCommands.mediaCommand] uses, so a noise
 * transcript that merely contains "skip" or "next" inside another word can
 * never spend the turn — and pins the barge-in toggle's default, on.
 */
class MediaSkipTest {

    @Test
    fun wholeWordSkipAndNextMatch() {
        assertTrue(AssistantCommands.isSkipCommand("skip"))
        assertTrue(AssistantCommands.isSkipCommand("next"))
        assertTrue(AssistantCommands.isSkipCommand("skip this song"))
        assertTrue(AssistantCommands.isSkipCommand("please skip"))
        assertTrue(AssistantCommands.isSkipCommand("skip it"))
        assertTrue(AssistantCommands.isSkipCommand("go to the next song"))
        assertTrue(AssistantCommands.isSkipCommand("next song"))
    }

    @Test
    fun substringsNeverMatch() {
        assertFalse(AssistantCommands.isSkipCommand("skipping"))
        assertFalse(AssistantCommands.isSkipCommand("skipper"))
        assertFalse(AssistantCommands.isSkipCommand("nextdoor"))
        assertFalse(AssistantCommands.isSkipCommand("unskippable"))
        assertFalse(AssistantCommands.isSkipCommand("i keep skipping this song"))
        assertFalse(AssistantCommands.isSkipCommand(""))
    }

    @Test
    fun bargeInDefaultsOn() {
        assertTrue(CarSettings.bargeIn.value)
    }
}
