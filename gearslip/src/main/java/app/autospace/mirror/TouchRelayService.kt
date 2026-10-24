package app.autospace.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.view.accessibility.AccessibilityEvent
import app.autospace.GearslipLog

/**
 * Replays head-unit touches onto the phone while [PhoneMirror] is running.
 *
 * dispatchGesture is the only public API that lets one app inject input into another, which
 * is why mirroring needs an accessibility service at all. Gearslip subscribes to no event
 * types and never inspects a window, so nothing about the phone's screen is read here - the
 * service is a one-way output.
 *
 * A head unit sends a stream of DOWN/MOVE/UP, but a gesture is dispatched as a whole path, so
 * the two are bridged with continued strokes: each dispatch carries the movement since the
 * last one, and the next is only queued once the previous completes. Moves that arrive while
 * a segment is in flight collapse into the most recent point, which keeps the replayed path
 * tracking the finger instead of falling behind it.
 */
class TouchRelayService : AccessibilityService() {

    private var stroke: GestureDescription.StrokeDescription? = null
    private var last: PointF? = null

    /** A segment is in flight; further movement waits so strokes stay properly ordered. */
    private var dispatching = false

    /** Most recent unsent position, and whether the finger has already lifted. */
    private var pending: PointF? = null
    private var lifted = false

    override fun onServiceConnected() {
        instance = this
        GearslipLog.i("touch relay connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        reset()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = reset()

    fun down(x: Float, y: Float) {
        reset()
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val start = GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true)
        stroke = start
        last = PointF(x, y)
        send(start)
    }

    fun move(x: Float, y: Float) {
        if (stroke == null) return
        pending = PointF(x, y)
        pump()
    }

    fun up(x: Float, y: Float) {
        if (stroke == null) return
        pending = PointF(x, y)
        lifted = true
        pump()
    }

    private fun pump() {
        if (dispatching) return
        val previous = stroke ?: return
        val from = last ?: return
        val to = pending
        // Nothing new to send yet: hold the stroke open until movement or a lift arrives.
        if (to == null && !lifted) return
        val target = to ?: from

        val path = Path().apply { moveTo(from.x, from.y); lineTo(target.x, target.y) }
        val next = try {
            previous.continueStroke(path, 0, SEGMENT_MS, !lifted)
        } catch (t: Throwable) {
            GearslipLog.e("continueStroke rejected", t)
            reset()
            return
        }
        stroke = next
        last = target
        pending = null
        send(next)
    }

    private fun send(description: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(description).build()
        dispatching = true
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                dispatching = false
                if (lifted && pending == null) reset() else pump()
            }

            override fun onCancelled(g: GestureDescription?) {
                dispatching = false
                reset()
            }
        }, null)
        if (!ok) {
            GearslipLog.w("dispatchGesture refused - is another gesture in flight?")
            dispatching = false
            reset()
        }
    }

    private fun reset() {
        stroke = null
        last = null
        pending = null
        lifted = false
        dispatching = false
    }

    companion object {
        /**
         * How long each replayed segment is told to take. Short enough that the pointer keeps
         * up with the finger, long enough that the framework does not treat a drag as a fling.
         */
        private const val SEGMENT_MS = 32L

        @Volatile
        var instance: TouchRelayService? = null
            private set

        /** False until the user enables Gearslip under Settings > Accessibility. */
        val enabled: Boolean get() = instance != null
    }
}
