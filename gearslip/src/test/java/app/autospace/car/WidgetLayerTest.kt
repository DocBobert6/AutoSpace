package app.autospace.car

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
        assertTrue(shouldShowWidgetColumn(true, true, false, true, SidePanel.NONE))
        assertTrue(shouldShowWidgetColumn(true, true, false, true, SidePanel.CONTROLS))
    }

    @Test
    fun columnHidesWhenAnyGateFails() {
        // Master switch off (the default): classic Home, no column.
        assertFalse(shouldShowWidgetColumn(false, true, false, true, SidePanel.NONE))
        // Classic split base: no full-bleed background to overlay.
        assertFalse(shouldShowWidgetColumn(true, false, false, true, SidePanel.NONE))
        // Dismissed (persisted): stays hidden across drives until re-enabled.
        assertFalse(shouldShowWidgetColumn(true, true, true, true, SidePanel.NONE))
        // Not on Home: the column lives in CarHome only.
        assertFalse(shouldShowWidgetColumn(true, true, false, false, SidePanel.NONE))
        // Lyrics owns the side: classic split instead, no background.
        assertFalse(shouldShowWidgetColumn(true, true, false, true, SidePanel.LYRICS))
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
}
