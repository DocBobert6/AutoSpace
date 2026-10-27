package app.autospace.car

/**
 * r78: map-pane collapse to logo.
 *
 * The five map-backed templates that render a side pane through
 * MapPaneLayout (Map, MapWithContent, PlaceListNavigation,
 * PlaceListMap, RoutePreview) gain a collapse affordance: a button
 * at the top of the pane folds the pane down to the connected app's
 * logo, and tapping the logo re-expands it. NavigationTemplate has
 * no side pane — its chrome is the turn card, the edge controls,
 * and the trip bar, none of which MapPaneLayout draws — so collapse
 * cannot apply to it; that is a property of the chrome split, not
 * an omission.
 *
 * Session-only: the collapse lives in a remember keyed on the
 * connected app's package inside MapPaneLayout. It survives pan
 * excursions (MapPaneLayout stays composed; pan only hides the pane
 * content) and content refreshes of the same pane, and resets when
 * the app changes, when the pane leaves composition (template
 * change, app stop), or on restart. Nothing is persisted: toggling
 * the feature off never rewrites the stored toggle, and the stale
 * collapse state simply stops rendering until the gate is on again.
 *
 * Still counts as panel-open: collapsing changes only what
 * MapPaneLayout draws. The template object is untouched, so every
 * behavior that keys off a pane being open keeps treating it as
 * open: widgetColumnPlacement still moves the column passenger-side
 * for all five pane templates (it reads the template class, never
 * any collapse state — pinned by PaneCollapseTest); visibleAreaFor
 * still reports the left 42% occupied (it takes only template and
 * size, and AppSurface re-reports only on a template or size
 * change, neither of which collapsing causes); the right-edge
 * controls are drawn outside the collapsed branch and stay
 * visible; the side-bar yields and the card-clearance rule read
 * chrome state collapse never touches. The app therefore keeps
 * laying out around the pane area while collapsed — the collapse
 * reveals map tiles underneath, it does not reflow the app's UI.
 *
 * Map surface boundary: the pane is host chrome drawn OVER the
 * nav app's lent SurfaceView (CarAppStage composes AppSurface and
 * TemplateChrome as siblings). Collapsing touches only the chrome
 * sibling; the SurfaceView is never recomposed, disposed, or
 * resized by it, so the map keeps running underneath.
 *
 * Driving: no new gate. Pane rows are tappable while moving today
 * (no CarMotion check exists in TemplateChrome or TemplateParts),
 * and collapse/expand are the same class of host-chrome tap as the
 * card X or the column dismiss. The collapsed logo is a single
 * icon — glanceable by construction, nothing to read.
 */

/**
 * r78: the pane-collapse gate. Pure, so the unit test pins it: the
 * pane collapses only when the driver has opted into experimental
 * features as a whole AND the collapse toggle is on. Either off
 * returns exactly today's pane, and the stored toggle is preserved,
 * never rewritten, while the gate is off. The toggle joins no
 * master switch.
 */
fun paneCollapseNow(experimentalOn: Boolean, toggleOn: Boolean): Boolean =
    experimentalOn && toggleOn
