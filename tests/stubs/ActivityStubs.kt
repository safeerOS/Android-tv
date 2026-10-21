package android.app

import android.content.Context

open class Activity : Context() {
    open fun runOnUiThread(action: Runnable) { action.run() }
    open fun getString(resId: Int): String = "test"
    open fun getString(resId: Int, vararg formatArgs: Any): String = "test"
}

open class AlertDialog {
    open class Builder(context: Context, themeResId: Int = 0) {
        open fun setTitle(title: CharSequence): Builder = this
        open fun setMessage(message: CharSequence): Builder = this
        open fun setPositiveButton(text: CharSequence, listener: Any?): Builder = this
        open fun show(): AlertDialog = AlertDialog()
    }
}
