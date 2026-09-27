package si.safeer.tv

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.EditText

/**
 * Polje za iskanje in naslove. Tipka Nazaj, dokler je tipkovnica odprta, zapre tipkovnico
 * in predloge v enem koraku; naslednji Nazaj ze gre po zgodovini strani.
 */
class OmniboxPolje @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : EditText(context, attrs) {
    var obZaprtju: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && hasFocus()) {
            if (event.action == KeyEvent.ACTION_UP) obZaprtju?.invoke()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
