package tv.qissat.app.ui

import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import tv.qissat.app.R

/** The only "settings" surface: a short, remote-friendly list opened with Menu / long-press Back. */
class MenuDialog(private val context: Context) {

    data class Item(val label: String, val action: () -> Unit)

    private var dialog: Dialog? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show(items: List<Item>) {
        dismiss()
        val themed = ContextThemeWrapper(context, R.style.Theme_Qissat_Dialog)
        @Suppress("InflateParams") // dialog content: no parent exists yet
        val content = LayoutInflater.from(themed).inflate(R.layout.dialog_menu, null)
        val list = content.findViewById<LinearLayout>(R.id.menu_items)
        val d = Dialog(themed)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        items.forEach { item ->
            val row = TextView(themed, null, 0, R.style.Widget_Qissat_MenuItem)
            row.text = item.label
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            row.setOnClickListener {
                d.dismiss()
                item.action()
            }
            list.addView(row)
        }
        d.setContentView(content)
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
        list.getChildAt(0)?.requestFocus()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
