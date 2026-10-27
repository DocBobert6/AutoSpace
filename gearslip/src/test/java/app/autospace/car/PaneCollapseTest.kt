package app.autospace.car

import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.model.Row
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import org.junit.Assert.assertEquals
import org.junit.Test

/** r78: pane-collapse gate + the still-counts-as-panel-open contract. */
class PaneCollapseTest {

    private fun testRow() = Row.Builder().setTitle("Gas").build()

    private fun testItemList() = ItemList.Builder().addItem(testRow()).build()

    private fun testPane() = Pane.Builder().addRow(testRow()).build()

    // NavigationTemplate.Builder.build() throws unless an ActionStrip is
    // set — the strip is incidental here (no rule under test reads it).
    private fun testActionStrip() = ActionStrip.Builder()
        .addAction(Action.Builder().setTitle("Stop").setOnClickListener {}.build())
        .build()

    // Place lists and route preview are built in the loading state:
    // setItemList's row validation throws on the JVM unit-test
    // classpath (see WidgetLayerTest), and the rules under test read
    // only the template class, never the content.
    private fun paneTemplates() = listOf(
        MapTemplate.Builder().setPane(testPane()).build(),
        MapTemplate.Builder().setItemList(testItemList()).build(),
        MapWithContentTemplate.Builder()
            .setContentTemplate(PaneTemplate.Builder(testPane()).build())
            .build(),
        PlaceListMapTemplate.Builder()
            .setTitle("Places").setLoading(true).build(),
        PlaceListNavigationTemplate.Builder().setLoading(true).build(),
        RoutePreviewNavigationTemplate.Builder()
            .setLoading(true)
            .setNavigateAction(
                Action.Builder().setTitle("Drive")
                    .setOnClickListener {}.build(),
            )
            .build(),
    )





    @Test
    fun collapsedPaneStillCountsAsPanelOpen() {
        // Collapse is draw-time only inside MapPaneLayout: the rule
        // reads the template class, never any collapse state, so a
        // collapsed pane still moves the column passenger-side
        // exactly like an expanded one. If a future change threaded
        // collapse into placement, this test is the contract it
        // would break.
        for (template in paneTemplates()) {
            assertEquals(
                WidgetColumnPlacement.PASSENGER_SIDE,
                widgetColumnPlacement(template, false),
            )
        }
    }

    @Test
    fun panStillFreesDriverSide() {
        // Pan hides the pane entirely (MapPaneLayout's if (!pan)),
        // so the driver side frees up whether or not the pane was
        // collapsed before panning.
        assertEquals(
            WidgetColumnPlacement.DRIVER_SIDE,
            widgetColumnPlacement(MapTemplate.Builder().setPane(testPane()).build(), true),
        )
    }

    @Test
    fun navigationTemplateHasNoPane() {
        // NavigationTemplate never goes through MapPaneLayout — its
        // chrome is the turn card, the edge controls, and the trip
        // bar — so there is no pane for collapse to act on, and the
        // column stays driver-side without routing info.
        assertEquals(
            WidgetColumnPlacement.DRIVER_SIDE,
            widgetColumnPlacement(
                NavigationTemplate.Builder().setActionStrip(testActionStrip()).build(),
                false,
            ),
        )
    }
}
