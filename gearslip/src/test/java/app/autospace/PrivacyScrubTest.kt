package app.autospace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit round R1 (privacy scrub batch) pins: the crash detail scrub, the shared content
 * filter (crash tails, voice traces, and now the shareable log file), and the shared
 * loggable-URL helper. The log call sites themselves (WirelessLink redaction, shareableFile
 * applying the filter) have no JVM seam — they need a Context or a live log — so those rest
 * on the device checks; what CAN be pinned here is pinned here.
 */
class PrivacyScrubTest {

    @Test
    fun crashDetailScrubsKeyShapedTokenInFakeStackTrace() {
        val detail = "Thread: main\n\n" +
            "java.lang.RuntimeException: boom\n" +
            "\tat app.autospace.car.SomeCall.call(SomeCall.kt:42)\n" +
            "\tCaused by: java.io.IOException: key gsk_abc1234567890XYZ_abcdefg leaked\n"
        val scrubbed = CrashReporter.scrubbedDetail(detail)
        assertFalse(scrubbed.contains("gsk_abc1234567890XYZ_abcdefg"))
        assertTrue(scrubbed.contains("[api key redacted]"))
        assertTrue(scrubbed.contains("RuntimeException: boom"))
        assertTrue(scrubbed.contains("SomeCall.kt:42"))
    }

    @Test
    fun filterDropsContentKeysNetworkIdsAndAppUsageButKeepsDiagnostics() {
        val kept = CrashReporter.filterContentLines(
            listOf(
                "12:00:00.000 I APP   media status: -> track Some Song by Some Artist",
                "12:00:01.000 I APP   test message: hello world",
                "12:00:02.000 I APP   starting a session over Wi-Fi to 192.168.1.2",
                "12:00:03.000 E APP   something failed with token gsk_abc1234567890XYZ_abcdefg inside",
                "12:00:04.000 I APP   --- wireless start-up with MyCar (AA:BB:CC:DD:EE:FF) ---",
                "12:00:05.000 I APP   <- InfoResponse: ssid=\"HomeNet\" bssid=AA:BB:CC:DD:EE:FF security=3",
                "12:00:06.000 I APP   launched com.example.music on the phone",
                "12:00:07.000 I APP   mirror live, launching pending com.example.video",
                "12:00:08.000 I APP   no launch intent for com.example.maps",
                "12:00:09.000 I APP   mirror open: remembering com.example.maps as pending launch, requesting start",
                "12:00:10.000 I APP   media: paused com.example.music, com.example.podcasts: user paused",
                "12:00:11.000 I APP   host: app wants to start Intent { cmp=com.example.maps/.Main }",
                "12:00:12.000 I APP   car web page loaded: https://example.com/article",
                "12:00:13.000 I APP   Bluetooth: channel open",
                "12:00:14.000 W APP   Wi-Fi: lost <redacted> - Android dropped the car's network",
            ),
        )
        assertEquals(3, kept.size)
        assertTrue(kept.all {
            "Some Song" !in it && "gsk_" !in it && "AA:BB:CC:DD:EE:FF" !in it &&
                "HomeNet" !in it && "com.example" !in it
        })
        assertTrue(kept.any { "car web page loaded" in it })
        assertTrue(kept.any { "Bluetooth: channel open" in it })
        assertTrue(kept.any { "Wi-Fi: lost <redacted>" in it })
    }

    @Test
    fun loggableUrlStripsQueryFragmentAndUserinfoButKeepsPath() {
        assertEquals(
            "https://example.com/article/page",
            GearslipLog.loggableUrl("https://example.com/article/page?token=secret&x=1#section"),
        )
        assertEquals(
            "https://example.com:8080/a/b",
            GearslipLog.loggableUrl("https://user:pass@example.com:8080/a/b?x#y"),
        )
        assertEquals(
            "https://example.com",
            GearslipLog.loggableUrl("https://example.com?search=hello"),
        )
        assertEquals(
            "https://example.com/path",
            GearslipLog.loggableUrl("https://example.com/path#frag"),
        )
        assertEquals("not a url", GearslipLog.loggableUrl("not a url?x=1"))
        assertEquals("", GearslipLog.loggableUrl(""))
        assertEquals(
            "https://example.com/a",
            GearslipLog.loggableUrl("https://example.com/a?redirect=https://other.com/b?token=z"),
        )
    }
}
