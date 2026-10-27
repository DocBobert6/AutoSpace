package app.autospace.car

import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Distance
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.compose.ui.unit.dp
import app.autospace.notify.CarNotification
import app.autospace.notify.ChatLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r37 widget layer: column gates, width budget, degrade order, digest rules. */
class WidgetLayerTest {

    private fun notification(
        title: String = "",
        appLabel: String = "Messages",
        lines: List<ChatLine> = emptyList(),
    ) = CarNotification(
        key = "k", id = 1L, packageName = "com.example", appLabel = appLabel,
        title = title, text = "body never shown", postedAt = 0L, icon = null,
        reply = null, lines = lines,
    )

    @Test
    fun columnShowsOnlyWhenEveryGatePasses() {
        assertTrue(shouldShowWidgetColumn(true, true, false, true, SidePanel.NONE, WidgetColumnPlacement.DRIVER_SIDE))
        assertTrue(shouldShowWidgetColumn(true, true, false, true, SidePanel.CONTROLS, WidgetColumnPlacement.PASSENGER_SIDE))
    }

    @Test
    fun columnHidesWhenAnyGateFails() {
        // Master switch off (the default): classic Home, no column.
        assertFalse(shouldShowWidgetColumn(false, true, false, true, SidePanel.NONE, WidgetColumnPlacement.DRIVER_SIDE))
        // Classic split base: no full-bleed background to overlay.
        assertFalse(shouldShowWidgetColumn(true, false, false, true, SidePanel.NONE, WidgetColumnPlacement.DRIVER_SIDE))
        // Dismissed (persisted): stays hidden across drives until re-enabled.
        assertFalse(shouldShowWidgetColumn(true, true, true, true, SidePanel.NONE, WidgetColumnPlacement.DRIVER_SIDE))
        // Not on Home: the column lives in CarHome only.
        assertFalse(shouldShowWidgetColumn(true, true, false, false, SidePanel.NONE, WidgetColumnPlacement.DRIVER_SIDE))
        // Lyrics owns the side: classic split instead, no background.
        assertFalse(shouldShowWidgetColumn(true, true, false, true, SidePanel.LYRICS, WidgetColumnPlacement.DRIVER_SIDE))
    }

    @Test
    fun columnWidthIsThirtyPercentCappedAndFloored() {
        assertEquals(240.dp, widgetColumnWidth(800.dp))
        // 30% of 1200dp is 360dp, capped at 280dp.
        assertEquals(280.dp, widgetColumnWidth(1200.dp))
        // 30% of 400dp is 120dp, floored at 200dp.
        assertEquals(200.dp, widgetColumnWidth(400.dp))
        // Never wider than the frame minus its 12dp side margins.
        assertEquals(176.dp, widgetColumnWidth(200.dp))
    }

    @Test
    fun visibleWidgetsDegradeInOrder() {
        assertEquals(
            listOf(WidgetKind.WEATHER, WidgetKind.DIGEST, WidgetKind.EVENT),
            visibleWidgets(800f, true, true, true, true),
        )
        // Below the event threshold: next-event hides first.
        assertEquals(
            listOf(WidgetKind.WEATHER, WidgetKind.DIGEST),
            visibleWidgets(500f, true, true, true, true),
        )
        // Below the digest threshold: weather last.
        assertEquals(
            listOf(WidgetKind.WEATHER),
            visibleWidgets(350f, true, true, true, true),
        )
        // Narrow frame while moving: nothing at all.
        assertEquals(
            emptyList<WidgetKind>(),
            visibleWidgets(300f, true, true, true, true),
        )
        // Narrow frame parked: the column shows its full available set.
        assertEquals(
            listOf(WidgetKind.WEATHER, WidgetKind.DIGEST, WidgetKind.EVENT),
            visibleWidgets(300f, false, true, true, true),
        )
    }

    @Test
    fun visibleWidgetsRespectSourceAvailability() {
        assertEquals(
            listOf(WidgetKind.DIGEST, WidgetKind.EVENT),
            visibleWidgets(800f, true, false, true, true),
        )
        assertEquals(
            listOf(WidgetKind.WEATHER, WidgetKind.EVENT),
            visibleWidgets(800f, true, true, false, true),
        )
        assertEquals(
            listOf(WidgetKind.WEATHER, WidgetKind.DIGEST),
            visibleWidgets(800f, true, true, true, false),
        )
        assertEquals(
            emptyList<WidgetKind>(),
            visibleWidgets(800f, true, false, false, false),
        )
    }

    @Test
    fun digestAvailableNeedsListeningUnreadAndHistory() {
        assertTrue(digestAvailable(true, 3, false))
        assertFalse(digestAvailable(false, 3, false))
        assertFalse(digestAvailable(true, 0, false))
        assertFalse(digestAvailable(true, 3, true))
    }

    @Test
    fun digestSenderPrefersChatSenderThenTitleThenApp() {
        assertEquals(
            "Alice",
            digestSender(
                notification(
                    title = "Group chat",
                    lines = listOf(
                        ChatLine(null, "mine", 1L),
                        ChatLine("Alice", "hello", 2L),
                    ),
                ),
            ),
        )
        // Owner-only lines carry no sender: fall back to the title.
        assertEquals(
            "Bob",
            digestSender(
                notification(
                    title = "Bob",
                    lines = listOf(ChatLine(null, "mine", 1L)),
                ),
            ),
        )
        // Blank title: the app label is the sender.
        assertEquals("Messages", digestSender(notification()))
    }

    // --- r59 restack: the column moves for templates instead of hiding ---
    // The r39 gate tests are deliberately REPLACED here, not silently
    // deleted: navTemplateAllowsWidgets is gone, and its template matrix
    // now pins widgetColumnPlacement instead. The r39 occupancy reasoning
    // (turn card / map pane on the driver side) survives — only its
    // response changed, from hide to move.

    private fun testRow() = Row.Builder().setTitle("Gas").build()

    private fun testItemList() = ItemList.Builder().addItem(testRow()).build()

    private fun testPane() = Pane.Builder().addRow(testRow()).build()

    private fun testStep() = Step.Builder()
        .setCue("Turn left")
        .setManeuver(Maneuver.Builder(Maneuver.TYPE_DEPART).build())
        .build()

    private fun testRoutingWithStep() = RoutingInfo.Builder()
        .setCurrentStep(testStep(), Distance.create(500.0, Distance.UNIT_METERS))
        .build()

    // NavigationTemplate.Builder.build() throws unless an ActionStrip is
    // set — the strip is incidental here (the rule never reads it).
    private fun testActionStrip() = ActionStrip.Builder()
        .addAction(Action.Builder().setTitle("Stop").setOnClickListener {}.build())
        .build()

    @Test
    fun columnPlacementBareMapAndBareNavigation() {
        // No template at all: the map is bare, the column stays driver side.
        assertEquals(WidgetColumnPlacement.DRIVER_SIDE, widgetColumnPlacement(null, false))
        assertEquals(WidgetColumnPlacement.DRIVER_SIDE, widgetColumnPlacement(null, true))
        // A navigation template with no navigation info draws no turn card
        // (only top-end controls), so the column stays driver side.
        assertEquals(
            WidgetColumnPlacement.DRIVER_SIDE,
            widgetColumnPlacement(
                NavigationTemplate.Builder().setActionStrip(testActionStrip()).build(),
                false,
            ),
        )
    }

    @Test
    fun columnPlacementMovesForTurnCardUnlessPanning() {
        val withStep = NavigationTemplate.Builder()
            .setActionStrip(testActionStrip())
            .setNavigationInfo(testRoutingWithStep()).build()
        // Guidance showing: the turn card owns the driver side, so the
        // column moves to the passenger side instead of hiding.
        assertEquals(WidgetColumnPlacement.PASSENGER_SIDE, widgetColumnPlacement(withStep, false))
        // Pan mode hides the turn card (and the panel container), so the
        // column moves back to the driver side.
        assertEquals(WidgetColumnPlacement.DRIVER_SIDE, widgetColumnPlacement(withStep, true))

        val loading = NavigationTemplate.Builder()
            .setActionStrip(testActionStrip())
            .setNavigationInfo(RoutingInfo.Builder().setLoading(true).build()).build()
        assertEquals(WidgetColumnPlacement.PASSENGER_SIDE, widgetColumnPlacement(loading, false))
        assertEquals(WidgetColumnPlacement.DRIVER_SIDE, widgetColumnPlacement(loading, true))
        // A non-loading RoutingInfo without a step cannot be built via its
        // Builder (build requires a step and distance unless loading), so
        // that arm of the placement rule is defensive only and has no
        // constructible test case — a real app cannot send it either.
    }

    @Test
    fun columnPlacementMovesForMapPanes() {
        // MapTemplate: the panel container always renders, pane or list —
        // driver side occupied, column moves over.
        assertEquals(
            WidgetColumnPlacement.PASSENGER_SIDE,
            widgetColumnPlacement(MapTemplate.Builder().setPane(testPane()).build(), false),
        )
        assertEquals(
            WidgetColumnPlacement.PASSENGER_SIDE,
            widgetColumnPlacement(MapTemplate.Builder().setItemList(testItemList()).build(), false),
        )
        // Pan hides the pane, so the driver side frees up again.
        assertEquals(
            WidgetColumnPlacement.DRIVER_SIDE,
            widgetColumnPlacement(MapTemplate.Builder().setPane(testPane()).build(), true),
        )
        assertEquals(
            WidgetColumnPlacement.PASSENGER_SIDE,
            widgetColumnPlacement(
                MapWithContentTemplate.Builder()
                    .setContentTemplate(PaneTemplate.Builder(testPane()).build())
                    .build(),
                false,
            ),
        )
        // Place lists: built in the loading state, not with an item list.
        // setItemList's row validation (ROW_LIST_CONSTRAINTS_SIMPLE /
        // ModelUtils distance checks) throws an NPE on the JVM unit-test
        // classpath, where the android.text stub machinery underneath
        // CarText span inspection returns nulls — the same rows build fine
        // as plain ItemList/Pane content above. The rule never inspects
        // content for these classes, only the template class, so a
        // loading-state instance proves exactly the branch under test; a
        // browsable row must still carry an OnClickListener, or its own
        // build() throws, which is why the plain list above keeps its rows
        // non-browsable. (PlaceListMap additionally requires a title or
        // header action.)
        assertEquals(
            WidgetColumnPlacement.PASSENGER_SIDE,
            widgetColumnPlacement(
                PlaceListMapTemplate.Builder()
                    .setTitle("Places").setLoading(true).build(),
                false,
            ),
        )
        assertEquals(
            WidgetColumnPlacement.PASSENGER_SIDE,
            widgetColumnPlacement(
                PlaceListNavigationTemplate.Builder().setLoading(true).build(),
                false,
            ),
        )
        // Route preview: its rows must carry a DistanceSpan or
        // DurationSpan, which cannot be attached on the JVM unit-test
        // classpath — so the loading state proves the class branch (the
        // rule never inspects content for these classes, only the class).
        assertEquals(
            WidgetColumnPlacement.PASSENGER_SIDE,
            widgetColumnPlacement(
                RoutePreviewNavigationTemplate.Builder()
                    .setLoading(true)
                    .setNavigateAction(
                        Action.Builder().setTitle("Drive")
                            .setOnClickListener {}.build(),
                    )
                    .build(),
                false,
            ),
        )
    }

    @Test
    fun columnPlacementHidesForFullScreenTemplates() {
        // Full-screen content templates ARE the screen — no corner
        // coexists with them, so the column hides. Not a dismissal: no
        // persisted flag is touched, and it returns when the template
        // clears.
        assertEquals(
            WidgetColumnPlacement.HIDDEN,
            widgetColumnPlacement(
                SearchTemplate.Builder(
                    object : SearchTemplate.SearchCallback {
                        override fun onSearchTextChanged(searchText: String) {}
                        override fun onSearchSubmitted(searchText: String) {}
                    },
                ).build(),
                false,
            ),
        )
        assertEquals(
            WidgetColumnPlacement.HIDDEN,
            widgetColumnPlacement(
                ListTemplate.Builder().setSingleList(testItemList()).build(), false,
            ),
        )
        assertEquals(
            WidgetColumnPlacement.HIDDEN,
            widgetColumnPlacement(PaneTemplate.Builder(testPane()).build(), false),
        )
    }

    @Test
    fun columnShowsOnEitherSideButNotWhenHidden() {
        // Both sides show when every other gate passes...
        assertTrue(shouldShowWidgetColumn(true, true, false, true, SidePanel.NONE, WidgetColumnPlacement.DRIVER_SIDE))
        assertTrue(shouldShowWidgetColumn(true, true, false, true, SidePanel.NONE, WidgetColumnPlacement.PASSENGER_SIDE))
        // ...and HIDDEN hides without dismissing: the dismissed argument
        // is unchanged throughout, so the column returns on its own when
        // the template clears — no settings visit.
        assertFalse(shouldShowWidgetColumn(true, true, false, true, SidePanel.NONE, WidgetColumnPlacement.HIDDEN))
    }

    @Test
    fun hiddenWidgetsNeverRender() {
        assertEquals(
            listOf(WidgetKind.WEATHER),
            visibleWidgets(800f, true, true, true, true, setOf(WidgetKind.DIGEST, WidgetKind.EVENT)),
        )
        // Hidden beats available: a hidden digest stays hidden even when its source is full.
        assertEquals(
            listOf(WidgetKind.WEATHER, WidgetKind.EVENT),
            visibleWidgets(800f, true, true, true, true, setOf(WidgetKind.DIGEST)),
        )
        assertEquals(
            emptyList<WidgetKind>(),
            visibleWidgets(800f, true, true, true, true, setOf(WidgetKind.WEATHER, WidgetKind.DIGEST, WidgetKind.EVENT)),
        )
    }

    @Test
    fun drivingHideNeedsMotionWithoutGlance() {
        assertTrue(shouldHideWhileDriving(true, false))
        assertFalse(shouldHideWhileDriving(true, true))
        assertFalse(shouldHideWhileDriving(false, false))
        assertFalse(shouldHideWhileDriving(false, true))
    }

    @Test
    fun popupPressMatrix() {
        assertEquals(WidgetPopupPress.DISMISS, widgetPopupPress(false, false))
        assertEquals(WidgetPopupPress.UNDISMISS, widgetPopupPress(true, false))
        assertEquals(WidgetPopupPress.GLANCE, widgetPopupPress(false, true))
        assertEquals(WidgetPopupPress.UNDISMISS_AND_GLANCE, widgetPopupPress(true, true))
    }

    @Test
    fun emptyReasonsNameEachCause() {
        // All sources empty, none hidden.
        assertEquals(
            listOf("Weather not ready yet", "Notifications off", "No upcoming events"),
            widgetEmptyReasons(false, false, 0, true, false, emptySet(), 800f),
        )
        // Hidden reads as hidden, never as empty.
        assertEquals(
            listOf("Weather hidden", "Digest hidden", "Next event hidden"),
            widgetEmptyReasons(false, false, 0, true, false, setOf(WidgetKind.WEATHER, WidgetKind.DIGEST, WidgetKind.EVENT), 800f),
        )
        // Available but width-degraded.
        assertEquals(
            listOf("Digest needs a wider screen", "Next event needs a wider screen"),
            widgetEmptyReasons(true, true, 3, false, true, emptySet(), 360f),
        )
        // Digest source states, in order.
        assertEquals(
            listOf("No unread notifications"),
            widgetEmptyReasons(true, true, 0, false, true, emptySet(), 800f),
        )
    }
}
