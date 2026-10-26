package app.autospace.car

import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.navigation.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListMapTemplate
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
        assertTrue(shouldShowWidgetColumn(true, true, false, true, SidePanel.NONE, true))
        assertTrue(shouldShowWidgetColumn(true, true, false, true, SidePanel.CONTROLS, true))
    }

    @Test
    fun columnHidesWhenAnyGateFails() {
        // Master switch off (the default): classic Home, no column.
        assertFalse(shouldShowWidgetColumn(false, true, false, true, SidePanel.NONE, true))
        // Classic split base: no full-bleed background to overlay.
        assertFalse(shouldShowWidgetColumn(true, false, false, true, SidePanel.NONE, true))
        // Dismissed (persisted): stays hidden across drives until re-enabled.
        assertFalse(shouldShowWidgetColumn(true, true, true, true, SidePanel.NONE, true))
        // Not on Home: the column lives in CarHome only.
        assertFalse(shouldShowWidgetColumn(true, true, false, false, SidePanel.NONE, true))
        // Lyrics owns the side: classic split instead, no background.
        assertFalse(shouldShowWidgetColumn(true, true, false, true, SidePanel.LYRICS, true))
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

    // --- r39 widget/nav-panel gate ---------------------------------------

    private fun testRow() = Row.Builder().setTitle("Gas").build()

    private fun testItemList() = ItemList.Builder().addItem(testRow()).build()

    private fun testPane() = Pane.Builder().addRow(testRow()).build()

    private fun testStep() = Step.Builder()
        .setCue("Turn left")
        .setManeuver(Maneuver.Builder(Maneuver.TYPE_DEPART).build())
        .build()

    private fun testRoutingWithStep() = RoutingInfo.Builder()
        .setCurrentStep(testStep(), Distance.create(500, Distance.UNIT_METERS))
        .build()

    @Test
    fun navTemplateAllowsBareMapAndBareNavigation() {
        // No template at all: the map is bare, the column's corner is free.
        assertTrue(navTemplateAllowsWidgets(null, false))
        assertTrue(navTemplateAllowsWidgets(null, true))
        // A navigation template with no navigation info draws no turn card
        // (only top-end controls), so the column stays.
        assertTrue(navTemplateAllowsWidgets(NavigationTemplate.Builder().build(), false))
    }

    @Test
    fun navTemplateHidesForTurnCardUnlessPanning() {
        val withStep = NavigationTemplate.Builder()
            .setNavigationInfo(testRoutingWithStep()).build()
        assertFalse(navTemplateAllowsWidgets(withStep, false))
        // Pan mode hides the turn card (and the panel container), so the
        // column may show again.
        assertTrue(navTemplateAllowsWidgets(withStep, true))

        val loading = NavigationTemplate.Builder()
            .setNavigationInfo(RoutingInfo.Builder().setLoading(true).build()).build()
        assertFalse(navTemplateAllowsWidgets(loading, false))
        assertTrue(navTemplateAllowsWidgets(loading, true))
        // A non-loading RoutingInfo without a step cannot be built via its
        // Builder (build requires a step and distance unless loading), so
        // that arm of the predicate is defensive only and has no
        // constructible test case — a real app cannot send it either.
    }

    @Test
    fun navTemplateHidesForEveryPanelAndContentTemplate() {
        // MapTemplate: the panel container always renders, pane or list.
        assertFalse(
            navTemplateAllowsWidgets(
                MapTemplate.Builder().setPane(testPane()).build(), false,
            ),
        )
        assertFalse(
            navTemplateAllowsWidgets(
                MapTemplate.Builder().setItemList(testItemList()).build(), false,
            ),
        )
        assertFalse(
            navTemplateAllowsWidgets(
                MapWithContentTemplate.Builder()
                    .setContentTemplate(PaneTemplate.Builder(testPane()).build())
                    .build(),
                false,
            ),
        )
        // Place lists: browsable rows are exempt from the DistanceSpan
        // requirement the builders otherwise enforce on place rows, so a
        // plain-title row builds here.
        val placeRow = Row.Builder().setTitle("Gas").setBrowsable(true).build()
        val placeList = ItemList.Builder().addItem(placeRow).build()
        assertFalse(
            navTemplateAllowsWidgets(
                PlaceListMapTemplate.Builder()
                    .setTitle("Places").setItemList(placeList).build(),
                false,
            ),
        )
        assertFalse(
            navTemplateAllowsWidgets(
                PlaceListNavigationTemplate.Builder().setItemList(placeList).build(),
                false,
            ),
        )
        // Route preview: its rows must carry a DistanceSpan or
        // DurationSpan, which cannot be attached on the JVM unit-test
        // classpath — so the loading state proves the class branch (the
        // predicate never inspects content, only the template class).
        assertFalse(
            navTemplateAllowsWidgets(
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
        // Full-screen content templates: the column would sit over them.
        assertFalse(
            navTemplateAllowsWidgets(
                SearchTemplate.Builder(
                    object : SearchTemplate.SearchCallback {
                        override fun onSearchTextChanged(searchText: String) {}
                        override fun onSearchSubmitted(searchText: String) {}
                    },
                ).build(),
                false,
            ),
        )
        assertFalse(
            navTemplateAllowsWidgets(
                ListTemplate.Builder().setSingleList(testItemList()).build(), false,
            ),
        )
        assertFalse(
            navTemplateAllowsWidgets(
                PaneTemplate.Builder(testPane()).build(), false,
            ),
        )
    }

    @Test
    fun templateHidingIsNotDismissalAndColumnReturns() {
        // Every other gate passes; only the template hides the column.
        assertFalse(
            shouldShowWidgetColumn(true, true, false, true, SidePanel.NONE, false),
        )
        // The template gate touches no persisted flag, so when the template
        // clears the column returns on its own — no settings visit, and
        // the dismissed argument is unchanged throughout.
        assertTrue(
            shouldShowWidgetColumn(true, true, false, true, SidePanel.NONE, true),
        )
    }
}
