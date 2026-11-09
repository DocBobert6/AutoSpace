package app.autospace.car

import android.webkit.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** r206 (compat finding 7): the site-permission decision, Mariner's rule. */
class BrowserPermissionsTest {

    @Test
    fun resourceNamesMapCaptureKinds() {
        assertEquals(
            listOf("camera", "microphone"),
            permissionResourceNames(
                arrayOf(
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                ),
            ),
        )
        // An unknown resource is dropped, never granted.
        assertEquals(
            listOf("microphone"),
            permissionResourceNames(arrayOf("unknown-resource", PermissionRequest.RESOURCE_AUDIO_CAPTURE)),
        )
        assertEquals(emptyList<String>(), permissionResourceNames(arrayOf()))
    }

    @Test
    fun grantableDropsCameraWhileUndeclared() {
        // android.permission.CAMERA is not declared in the manifest,
        // so camera can never be granted in this build.
        assertEquals(
            listOf("microphone"),
            grantablePermissionResources(listOf("camera", "microphone"), cameraDeclared = false),
        )
        assertEquals(
            listOf("camera", "microphone"),
            grantablePermissionResources(listOf("camera", "microphone"), cameraDeclared = true),
        )
    }

    @Test
    fun decisionSplitKeepsMarinerBranches() {
        val split = splitPermissionDecisions(
            listOf("camera", "microphone", "geolocation"),
            listOf("allow", "ask", "deny"),
        )
        assertEquals(listOf("camera"), split.allowed)
        assertEquals(listOf("microphone"), split.asked)
        assertEquals(listOf("geolocation"), split.denied)
        val allAsked = splitPermissionDecisions(listOf("camera", "microphone"), listOf("ask", "ask"))
        assertEquals(emptyList<String>(), allAsked.allowed)
        assertEquals(listOf("camera", "microphone"), allAsked.asked)
        assertEquals(emptyList<String>(), allAsked.denied)
    }

    @Test
    fun promptLabelsNameTheResource() {
        assertEquals("your camera", permissionPromptLabel("camera"))
        assertEquals("your microphone", permissionPromptLabel("microphone"))
        assertEquals("your location", permissionPromptLabel("geolocation"))
    }

    @Test
    fun sitePermissionCodecRoundTrips() {
        val perms = mapOf("example.com" to mapOf("camera" to "allow", "geolocation" to "deny"))
        assertEquals(perms, BrowserStore.decodeSitePermissions(BrowserStore.encodeSitePermissions(perms)))
        assertEquals("allow", BrowserStore.sitePermissionForHost(perms, "EXAMPLE.com", "camera"))
        assertEquals("ask", BrowserStore.sitePermissionForHost(perms, "example.com", "microphone"))
        assertEquals("ask", BrowserStore.sitePermissionForHost(perms, "other.org", "camera"))
    }

    @Test
    fun sitePermissionCodecSkipsBadLines() {
        val decoded = BrowserStore.decodeSitePermissions(
            "example.com|camera|allow\nbroken\nfoo.org|microphone|maybe\nbar.org|geolocation|deny\n",
        )
        assertEquals(
            mapOf(
                "example.com" to mapOf("camera" to "allow"),
                "bar.org" to mapOf("geolocation" to "deny"),
            ),
            decoded,
        )
    }

    @Test
    fun sitePermissionUpdateStoresAndClears() {
        val empty = emptyMap<String, Map<String, String>>()
        val allowed = BrowserStore.sitePermissionMapWith(empty, "Example.COM", "camera", "allow")
        assertEquals("allow", BrowserStore.sitePermissionForHost(allowed, "example.com", "camera"))
        val cleared = BrowserStore.sitePermissionMapWith(allowed, "example.com", "camera", "ask")
        assertEquals("ask", BrowserStore.sitePermissionForHost(cleared, "example.com", "camera"))
        assertEquals(empty, cleared)
    }

    @Test
    fun fileChooserResultPicksSingleOrClip() {
        assertEquals(
            listOf("content://x/1"),
            fileChooserResultUris(android.app.Activity.RESULT_OK, "content://x/1", emptyList()),
        )
        assertEquals(
            listOf("content://x/1", "content://x/2"),
            fileChooserResultUris(
                android.app.Activity.RESULT_OK,
                null,
                listOf("content://x/1", "content://x/2"),
            ),
        )
        assertNull(fileChooserResultUris(android.app.Activity.RESULT_OK, null, emptyList()))
        assertNull(
            fileChooserResultUris(android.app.Activity.RESULT_CANCELED, "content://x/1", emptyList()),
        )
    }
}
