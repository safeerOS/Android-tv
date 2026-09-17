package si.safeer.tv.scit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import si.safeer.tv.R

/**
 * Vklop Safeer Scita: Android zahteva potrditev uporabnika v sistemskem oknu (prvic), potem
 * storitev zazenemo. Dejavnost je prosojna in se takoj konca; klice jo meni brskalnika ali Safeer OS
 * (dovoljenje istega podpisa). Dodatek `izklopi` = izklop brez okna.
 */
class ScitActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_IZKLOPI, false) == true) {
            Scit.izklopi(this)
            Toast.makeText(this, getString(R.string.scit_izklopljen), Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val dovoljenje = Scit.dovoljenjeNamera(this)
        if (dovoljenje == null) { vklopi(); return }
        try { startActivityForResult(dovoljenje, ZAHTEVA) } catch (e: Throwable) {
            Toast.makeText(this, getString(R.string.scit_napaka_dovoljenje), Toast.LENGTH_LONG).show(); finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ZAHTEVA) return
        if (resultCode == RESULT_OK) vklopi() else {
            Toast.makeText(this, getString(R.string.scit_napaka_dovoljenje), Toast.LENGTH_LONG).show(); finish()
        }
    }

    private fun vklopi() {
        val ok = Scit.vklopi(this)
        Toast.makeText(this, getString(if (ok) R.string.scit_vklopljen else R.string.scit_napaka_dovoljenje), Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_IZKLOPI = "izklopi"
        private const val ZAHTEVA = 7312
    }
}
