package it.allard.rassh

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import it.allard.rassh.terminal.KeyEncoder
import it.allard.rassh.terminal.KeyEncoder.Key

/** Rows of keys missing from soft keyboards. Ctrl and Alt apply to the next key. */
class ExtraKeysView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    sealed class Action {
        class Special(val key: Key) : Action()

        class Text(val text: String) : Action()

        class Modifier(val mask: Int) : Action()
    }

    fun interface Listener {
        fun onExtraKey(action: Action)
    }

    var listener: Listener? = null

    private var modifiers = 0
    private val modifierViews = mutableMapOf<Int, TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var repeater: Runnable? = null

    init {
        orientation = VERTICAL
        for (row in ROWS) {
            val line = LinearLayout(context)
            line.orientation = HORIZONTAL
            for ((label, action) in row) line.addView(key(label, action), LayoutParams(0, dp(44), 1f))
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    /** Return the pending modifiers and release them. */
    fun consumeModifiers(): Int {
        val m = modifiers
        if (m != 0) {
            modifiers = 0
            refresh()
        }
        return m
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        super.onDetachedFromWindow()
    }

    private fun key(label: String, action: Action): TextView {
        val v = TextView(context)
        v.text = label
        v.gravity = Gravity.CENTER
        v.typeface = Typeface.MONOSPACE
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        v.setTextColor(context.getColor(R.color.key_text))
        v.setBackgroundResource(R.drawable.key_background)
        v.isClickable = true
        v.contentDescription = label
        if (action is Action.Modifier) modifierViews[action.mask] = v
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    press(action)
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    stopRepeat()
                    view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    stopRepeat()
                }
            }
            true
        }
        return v
    }

    private fun press(action: Action) {
        if (action is Action.Modifier) {
            modifiers = modifiers xor action.mask
            refresh()
            return
        }
        listener?.onExtraKey(action)
        if (action is Action.Special) {
            val r = object : Runnable {
                override fun run() {
                    listener?.onExtraKey(action)
                    handler.postDelayed(this, REPEAT_INTERVAL)
                }
            }
            repeater = r
            handler.postDelayed(r, REPEAT_DELAY)
        }
    }

    private fun stopRepeat() {
        repeater?.let { handler.removeCallbacks(it) }
        repeater = null
    }

    private fun refresh() {
        for ((mask, v) in modifierViews) {
            if (modifiers and mask != 0) v.setBackgroundColor(context.getColor(R.color.key_active))
            else v.setBackgroundResource(R.drawable.key_background)
        }
    }

    private fun dp(n: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val REPEAT_DELAY = 400L
        private const val REPEAT_INTERVAL = 50L

        private val ROWS = listOf(
            listOf(
                "ESC" to Action.Special(Key.ESCAPE),
                "/" to Action.Text("/"),
                "|" to Action.Text("|"),
                "-" to Action.Text("-"),
                "HOME" to Action.Special(Key.HOME),
                "↑" to Action.Special(Key.UP),
                "END" to Action.Special(Key.END),
                "PGUP" to Action.Special(Key.PAGE_UP),
            ),
            listOf(
                "TAB" to Action.Special(Key.TAB),
                "CTRL" to Action.Modifier(KeyEncoder.CTRL),
                "ALT" to Action.Modifier(KeyEncoder.ALT),
                "~" to Action.Text("~"),
                "←" to Action.Special(Key.LEFT),
                "↓" to Action.Special(Key.DOWN),
                "→" to Action.Special(Key.RIGHT),
                "PGDN" to Action.Special(Key.PAGE_DOWN),
            ),
        )
    }
}
