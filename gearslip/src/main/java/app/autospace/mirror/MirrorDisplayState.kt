package app.autospace.mirror

/**
 * The mirror's LOCAL display state: what the car shows, not what the
 * phone does. Fit/Fill, zoom (1x-3x, the video player's range), pan,
 * and whether the chrome row is open. Session-only: reset on every
 * mirror start/end, never persisted — a new drive starts at Fit, 1x,
 * unpanned, chrome closed.
 *
 * Why this lives outside Compose: while mirroring, the projector is
 * stopped and the mirror owns the encoder surface, so no Compose layer
 * (CarUi included) is drawn over the video at all. The GL renderer
 * draws the pill and chrome from this state, and GearslipActivity's
 * touch path reads it for hit-testing and tap mapping. The video
 * player's Compose chrome could not be reused here even though it
 * looks the same — the player is a Compose screen, the mirror is not.
 */
object MirrorDisplayState {
    @Volatile var fill: Boolean = false
    @Volatile var zoom: Float = 1f
    @Volatile var panX: Float = 0f
    @Volatile var panY: Float = 0f
    @Volatile var chromeVisible: Boolean = false

    fun reset() {
        zoom = 1f
        panX = 0f
        panY = 0f
        chromeVisible = false
    }

    fun setZoomClamped(value: Float) {
        zoom = value.coerceIn(1f, 3f)
    }
}
