package it.allard.rassh

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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

/* Open the document picker, which a phone may have disabled. */
fun Activity.pick(intent: Intent, request: Int) {
    try {
        startActivityForResult(intent, request)
    } catch (_: ActivityNotFoundException) {
        toast(getString(R.string.no_picker))
    }
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

    /* The row shown as held, or -1. */
    var held = -1
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView
            ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false).apply {
                background = context.obtainStyledAttributes(intArrayOf(android.R.attr.activatedBackgroundIndicator))
                    .use { it.getDrawable(0) }
            }
        v.isActivated = position == held
        v.findViewById<TextView>(android.R.id.text1).text = items[position].first
        v.findViewById<TextView>(android.R.id.text2).text = items[position].second
        return v
    }
}

/*
 * Whether e is a failure of a file or a content provider. A provider in
 * another app may also refuse with exceptions Android passes back from
 * it, for a revoked permission or a mode it does not support. Uncaught
 * in a thread, those would end the app and its sessions. Android passes
 * back NullPointerException too, left out as it would hide bugs of the
 * app.
 */
fun isFileError(e: Exception): Boolean =
    e is IOException || e is SecurityException || e is IllegalArgumentException ||
        e is IllegalStateException || e is UnsupportedOperationException

/*
 * A document the picker made to save into is empty, an existing one
 * picked to be replaced may not be: it is only removed once written to.
 */
fun Context.isEmptyDocument(uri: Uri): Boolean =
    try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            it.moveToFirst() && !it.isNull(0) && it.getLong(0) == 0L
        } == true
    } catch (_: Exception) {
        false
    }

/*
 * The size and the time of the last change in ns of the file afd is,
 * when a whole regular file, null for a pipe or a part of a file.
 */
fun wholeFile(afd: AssetFileDescriptor): Pair<Long, Long>? {
    if (afd.startOffset != 0L || afd.declaredLength != AssetFileDescriptor.UNKNOWN_LENGTH) return null
    return try {
        val st = Os.fstat(afd.fileDescriptor)
        if (!OsConstants.S_ISREG(st.st_mode)) null
        else Pair(st.st_size, st.st_mtim.tv_sec * 1_000_000_000 + st.st_mtim.tv_nsec)
    } catch (_: ErrnoException) {
        null
    }
}

/* The same for the document at uri, null as well when it cannot be opened. */
fun Context.wholeFile(uri: Uri): Pair<Long, Long>? = try {
    contentResolver.openAssetFileDescriptor(uri, "r")?.use { wholeFile(it) }
} catch (e: Exception) {
    if (!isFileError(e)) throw e
    null
}

/* Not every provider deletes, the file is then left as it is. */
fun Context.deleteDocument(uri: Uri) {
    try {
        DocumentsContract.deleteDocument(contentResolver, uri)
    } catch (_: Exception) {
    }
}

/*
 * An intent for the screen of session. Ids start again in a new process,
 * a screen restored there must not take another session of the same id:
 * the opening time tells them apart.
 */
fun Intent.forSession(session: Session): Intent =
    putExtra(EXTRA_SESSION, session.id).putExtra(EXTRA_OPENED, session.opened)

/*
 * Call dismissed once dialog is gone, also when the screen goes, recreated
 * or finished: Android then only takes its window away, and the cleanup
 * after what it asked would never run. dismissed runs after the screen is
 * destroyed then, isDestroyed tells.
 */
fun Activity.dismissWithScreen(dialog: Dialog, dismissed: () -> Unit) {
    val watch = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreDestroyed(activity: Activity) {
            if (dialog.isShowing) dialog.dismiss()
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
    registerActivityLifecycleCallbacks(watch)
    dialog.setOnDismissListener {
        unregisterActivityLifecycleCallbacks(watch)
        dismissed()
    }
}
