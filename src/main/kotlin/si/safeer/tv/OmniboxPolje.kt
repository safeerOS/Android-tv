package si.safeer.tv

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.EditText

/**
 * Polje za iskanje in naslove. Nazaj ob odprti tipkovnici zapre samo tipkovnico (OmniboxTipke): TV 3. 11. 2026 je
 * prej zaprl tudi predloge in uporabnik z daljincem ni mogel izbrati nobenega.
 */
class OmniboxPolje @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : EditText(context, attrs) {
    var obZaprtju: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && hasFocus() && OmniboxTipke.nazajPrestreziPredTipkovnico()) {
            if (event.action == KeyEvent.ACTION_UP) obZaprtju?.invoke()
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }
}
