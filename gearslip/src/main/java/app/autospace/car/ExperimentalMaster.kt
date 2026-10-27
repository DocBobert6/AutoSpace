package app.autospace.car

/**
 * r53: the "All experimental features" master switch — pure logic, no Android
 * framework, so the contract is unit-testable on the JVM. The UI row lives in
 * AboutAndExperimentalPage (car/CarSettingsScreen.kt); this object decides
 * what the switch reads and what it writes, nothing more.
 *
 * DERIVED STATE, NEVER STORED: there is no stored master key. The master
 * reads ON if and only if floating media AND home widgets are both on
 * ([isOn]). Turning any individual feature off therefore makes the master
 * read OFF automatically, with no stored override to go stale, and
 * individual toggles keep working after either master action.
 *
 * SUITE MEMBERSHIP (Robert, 2026-10-26): the master covers anything that
 * brings the floating Android Auto 17 look. Current members: floating
 * media, home widgets, and the r46 frosted map chrome. The r43
 * map-behind-bar is deliberately NOT a member since r83: it has its own
 * Floating nav bar switch, and neither master action touches it.
 *
 * MASTER ON writes: the experimental-rows visibility flag on (so the rows
 * show), floating media on, home widgets on, the persisted widget-column
 * dismissal cleared (a master ON brings the column back), and frosted map
 * chrome on.
 *
 * MASTER OFF writes: floating media off and home widgets off ONLY. Two
 * deliberate asymmetries, both by design: (1) the visibility flag is left
 * exactly as-is — turning the suite off must not hide rows the driver
 * chose to show; (2) frosted map chrome is left exactly as-is — it is a
 * suite member for ON, but OFF only clears the two flags the derived
 * state reads. A driver who wants frosted chrome off turns its own row
 * off; the master never forces it off.
 *
 * VEHICLE DATA IS EXCLUDED, ALWAYS: it has no field in [Writes] by
 * design, so no master action in either direction can ever touch it. It
 * stays its own switch at the very bottom of the experimental list (see
 * ExperimentalFeaturesList's order rule).
 *
 * VISIBILITY: the master row itself is a control, not a feature row, so
 * it stays visible like the Hide/Show row itself — the Hide/Show row
 * hides only the feature rows, never the master switch.
 */
object ExperimentalMaster {

    /**
     * One master action's flag writes. A null field means "leave
     * untouched". There is deliberately no vehicle-data field: see the
     * class KDoc — no master action can touch vehicle data.
     */
    data class Writes(
        val experimentalFeatures: Boolean?,
        val floatingMedia: Boolean?,
        val homeWidgets: Boolean?,
        val widgetsDismissed: Boolean?,
        val frostedMapChrome: Boolean?,
    )

    /** Derived master state: ON iff floating media and home widgets are both on. */
    fun isOn(floatingMedia: Boolean, homeWidgets: Boolean): Boolean =
        floatingMedia && homeWidgets

    /** Master ON: visibility on, floating media on, home widgets on, widget dismissal cleared, frosted chrome on. */
    fun onWrites(): Writes = Writes(
        experimentalFeatures = true,
        floatingMedia = true,
        homeWidgets = true,
        widgetsDismissed = false,
        frostedMapChrome = true,
    )

    /** Master OFF: floating media off and home widgets off; everything else untouched. */
    fun offWrites(): Writes = Writes(
        experimentalFeatures = null,
        floatingMedia = false,
        homeWidgets = false,
        widgetsDismissed = null,
        frostedMapChrome = null,
    )
}
