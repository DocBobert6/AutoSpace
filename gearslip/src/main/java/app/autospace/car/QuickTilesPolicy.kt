package app.autospace.car

/**
 * r70: the pure Do Not Disturb tile rules. The Android boundary lives in
 * QuickTiles.kt (NotificationManager filters); this file takes only
 * booleans, so it is fully JVM-testable with no android.* import.
 *
 * [policyAccessGranted] is NotificationManager.isNotificationPolicyAccessGranted;
 * [silenced] is currentInterruptionFilter != INTERRUPTION_FILTER_ALL. A filter
 * of PRIORITY, ALARMS, or NONE all read as ON: the phone is silencing
 * something, and the tile's job is to offer the way back to ALL.
 */
enum class DndTileState { ON, OFF, NEEDS_ACCESS }

fun dndTileState(policyAccessGranted: Boolean, silenced: Boolean): DndTileState =
    if (!policyAccessGranted) DndTileState.NEEDS_ACCESS
    else if (silenced) DndTileState.ON
    else DndTileState.OFF
