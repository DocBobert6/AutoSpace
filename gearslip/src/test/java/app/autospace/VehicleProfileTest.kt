package app.autospace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleProfileTest {

    private val challenger = ServiceDiscovery.HeadUnitInfo(
        headUnitName = "Uconnect", carModel = "Dodge Challenger", carYear = "18",
        headUnitMake = "Delphi", headUnitModel = "VP2_R Uconnect 7",
    )

    private fun profile(name: String, vararg match: Pair<String, String>) =
        VehicleProfile(name, match.toMap(), resolution = null, insets = Insets.NONE)

    @Test
    fun matchesWhenEveryKeyEqualsIgnoringCase() {
        val p = profile("challenger", "carModel" to "dodge challenger", "headUnitName" to "UCONNECT")
        assertEquals(p, VehicleProfiles.find(listOf(p), challenger))
    }

    @Test
    fun aDifferentCarDoesNotMatch() {
        val p = profile("challenger", "carModel" to "Dodge Challenger")
        val honda = ServiceDiscovery.HeadUnitInfo("Honda", "Accord", "25", "", "")
        assertNull(VehicleProfiles.find(listOf(p), honda))
    }

    @Test
    fun aProfileWithNoMatchKeysNeverMatchesEverything() {
        assertNull(VehicleProfiles.find(listOf(profile("wildcard")), challenger))
    }

    @Test
    fun firstMatchingProfileWins() {
        val specific = profile("specific", "carYear" to "18")
        val loose = profile("loose", "headUnitName" to "Uconnect")
        assertEquals(specific, VehicleProfiles.find(listOf(specific, loose), challenger))
    }
}
