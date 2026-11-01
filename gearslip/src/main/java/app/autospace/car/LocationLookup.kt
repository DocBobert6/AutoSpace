package app.autospace.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager

/**
 * Last-known device location, shared (r174 audit §2 CLEAN-N03): the
 * prefix [NightAuto] and [Weather] used to spell out separately — a
 * permission check, a [LocationManager], and the newest fix across all
 * providers — now in one place.
 *
 * Returns the newest last-known fix, or null without permission, without
 * a location manager, or without any fix. It never asks for a fresh fix:
 * [Weather]'s fresh-fix fallback and [NightAuto]'s null-means-inactive
 * behavior both live at their call sites, on top of this result.
 */
internal object LocationLookup {

    fun lastKnown(context: Context): Location? {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return manager.allProviders
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }
}
