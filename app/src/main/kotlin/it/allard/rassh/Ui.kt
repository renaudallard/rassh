package it.allard.rassh

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

const val EXTRA_SESSION = "it.allard.rassh.SESSION"
const val EXTRA_OPENED = "it.allard.rassh.OPENED"
const val EXTRA_HOST = "it.allard.rassh.HOST"
const val SHELL = "/system/bin/sh"

/** Pad a root view for the system bars, the display cutout and the keyboard. */
fun View.padForInsets() {
    setOnApplyWindowInsetsListener { v, insets ->
        val i = insets.getInsets(WindowInsets.Type.systemBars() or
            WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        v.setPadding(i.left, i.top, i.right, i.bottom)
        WindowInsets.CONSUMED
    }
}

/** A color of the current theme, light or dark like the phone. */
fun Context.themeColor(attr: Int): Int {
    val a = obtainStyledAttributes(intArrayOf(attr))
    try {
        return a.getColor(0, 0)
    } finally {
        a.recycle()
    }
}

/** A single line field for a file name or path, without suggestions. */
fun Context.pathField(text: String): EditText {
    val field = EditText(this)
    field.isSingleLine = true
    field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    field.setText(text)
    return field
}

/** Views stacked and padded like the text of a dialog. */
fun Context.dialogLayout(vararg views: View): LinearLayout {
    val layout = LinearLayout(this)
    layout.orientation = LinearLayout.VERTICAL
    val pad = (resources.displayMetrics.density * DIALOG_PADDING).toInt()
    layout.setPadding(pad, pad / 2, pad, 0)
    for (v in views) layout.addView(v)
    return layout
}

private const val DIALOG_PADDING = 20

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

/** A binding to the session service, connected is called once it is up. */
class ServiceBinding(
    private val activity: Activity,
    private val connected: (SessionService) -> Unit,
) : ServiceConnection {
    var service: SessionService? = null
        private set

    fun bind() {
        activity.bindService(Intent(activity, SessionService::class.java), this, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        activity.unbindService(this)
        service = null
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val s = (binder as SessionService.LocalBinder).service
        service = s
        connected(s)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }
}

/** A list of title and subtitle pairs. */
class TwoLineAdapter(private val context: Context) : BaseAdapter() {
    var items: List<Pair<String, String>> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Pair<String, String> = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView
            ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
        v.findViewById<TextView>(android.R.id.text1).text = items[position].first
        v.findViewById<TextView>(android.R.id.text2).text = items[position].second
        return v
    }
}

/*
 * Whether e is a failure of a file or a content provider, which may also
 * refuse with SecurityException or IllegalArgumentException, a revoked
 * permission for instance. Uncaught in a thread, those would end the
 * app and its sessions.
 */
fun isFileError(e: Exception): Boolean =
    e is IOException || e is SecurityException || e is IllegalArgumentException

/*
 * An intent for the screen of session. Ids start again in a new process,
 * a screen restored there must not take another session of the same id:
 * the opening time tells them apart.
 */
fun Intent.forSession(session: Session): Intent =
    putExtra(EXTRA_SESSION, session.id).putExtra(EXTRA_OPENED, session.opened)
