package app.autospace.call

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r45 call-route fix tests. Everything here runs on the JVM: the probe helpers
 * are pure (Android constants are compile-time inlined), and the permission gate
 * takes the check result because the Context-bound check itself cannot run here.
 * The Handler-driven observation loop and re-assert chain are Android-only and
 * are covered by these pins on their timing/count plus code review, not by a
 * unit test that cannot see a main looper.
 */
class CallRouteProbeTest {

    @Test fun observationCadenceIsTwoSeconds() {
        assertEquals(2000L, CallRouteProbe.OBSERVE_INTERVAL_MS)
    }

    @Test fun reassertSequenceIsExactlyTwoAtOneAndThreeSeconds() {
        assertEquals(listOf(1000L, 3000L), CallRouteProbe.REASSERT_AT_MS)
        assertEquals(2, CallRouteProbe.REASSERT_AT_MS.size)
    }

    @Test fun reassertDelaysAreAbsoluteOffsetsNotRepeats() {
        // First delay is absolute (+1 s); the second is relative to the first
        // (+3 s absolute = +2 s after the first), never another full 3 s.
        assertEquals(1000L, CallRouteProbe.nextReassertDelayMs(0))
        assertEquals(2000L, CallRouteProbe.nextReassertDelayMs(1))
        assertNull(CallRouteProbe.nextReassertDelayMs(2))
        assertNull(CallRouteProbe.nextReassertDelayMs(-1))
    }

    @Test fun alreadyOffhookAppliesOnRegistrationOnly() {
        assertTrue(CallRouteProbe.shouldApplyOnRegistration(TelephonyManager.CALL_STATE_OFFHOOK))
        assertFalse(CallRouteProbe.shouldApplyOnRegistration(TelephonyManager.CALL_STATE_IDLE))
        assertFalse(CallRouteProbe.shouldApplyOnRegistration(TelephonyManager.CALL_STATE_RINGING))
    }

    @Test fun modeNamesCoverTheFourRealModes() {
        assertEquals("NORMAL", CallRouteProbe.modeName(AudioManager.MODE_NORMAL))
        assertEquals("RINGTONE", CallRouteProbe.modeName(AudioManager.MODE_RINGTONE))
        assertEquals("IN_CALL", CallRouteProbe.modeName(AudioManager.MODE_IN_CALL))
        assertEquals("IN_COMMUNICATION", CallRouteProbe.modeName(AudioManager.MODE_IN_COMMUNICATION))
        assertEquals("mode=99", CallRouteProbe.modeName(99))
    }

    @Test fun deviceTypeNamesCoverTheRouteRelevantTypes() {
        assertEquals("BUILTIN_SPEAKER", CallRouteProbe.deviceTypeName(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals("BUILTIN_EARPIECE", CallRouteProbe.deviceTypeName(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals("BLUETOOTH_SCO", CallRouteProbe.deviceTypeName(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals("type=999", CallRouteProbe.deviceTypeName(999))
    }

    @Test fun sampleLineShowsReadBackNotRequest() {
        // Bluetooth route with the flag on: the trace must show both facts, so a
        // later reader can tell the flag lost to Telecom instead of never being set.
        assertEquals(
            "calls: route sample mode=IN_CALL speaker=true sco=true outputs=[BLUETOOTH_SCO]",
            CallRouteProbe.sampleLine(
                AudioManager.MODE_IN_CALL, true, true,
                listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            )
        )
        assertEquals(
            "calls: route sample mode=NORMAL speaker=false sco=false outputs=[]",
            CallRouteProbe.sampleLine(AudioManager.MODE_NORMAL, false, false, emptyList())
        )
    }

    @Test fun permissionGateLogsAndRefusesWhenMissing() {
        assertTrue(phoneStateGate(true))
        assertFalse(phoneStateGate(false))
        assertEquals(
            "calls: call watching needs READ_PHONE_STATE, which was never granted",
            MISSING_PHONE_STATE_LOG
        )
    }
}
