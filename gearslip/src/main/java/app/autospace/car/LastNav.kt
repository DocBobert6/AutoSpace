package app.autospace.car

/**
 * Resolves a stored last-navigation-app value against the flattened components of the
 * navigation apps actually available, for the map autostart path in CarServices.
 *
 * The stored value is a flattened component name (setLastNav only ever writes templated app
 * components), so a value that matches nothing — first run (null), or a stale value for an
 * app that no longer exists, e.g. the retired mirrored-Maps tile, which was a built-in and
 * never a templated component — falls back to the first available candidate. It never
 * returns a value outside candidates, so the map can never land on a dead tile; with no
 * candidates at all it returns null and the caller shows "no mapping apps".
 */
internal fun resolveLastNav(last: String?, candidates: List<String>): String? =
    last?.takeIf { it in candidates } ?: candidates.firstOrNull()
