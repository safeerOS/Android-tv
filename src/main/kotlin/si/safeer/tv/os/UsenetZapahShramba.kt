package si.safeer.tv.os

import android.content.Context

/**
 * Trajni zapah racuna Usenet (pregled Grok 44 D2, lastnik 26. 10. 2026: »Nevarnost zaklepa racuna je prioriteta«):
 * zapisi [UsenetNntp] (sol, odtis racuna in pocasen odtis gesla - nikoli geslo) so v [SporocilaSkrivnosti], sifrirani s
 * kljucem Android Keystore. Zapah zato prezivi ponovni zagon, sesutje in posodobitev aplikacije.
 */
class UsenetZapahShramba private constructor(private val c: Context) : UsenetNntp.ShrambaZapaha {
    override fun beri(): String? = SporocilaSkrivnosti.preberi(c, IME)
    override fun pisi(besedilo: String): Boolean = SporocilaSkrivnosti.shrani(c, IME, besedilo)

    companion object {
        private const val IME = "usenet_zapah_v1"

        /** Pred prvim [UsenetNntp] (korak 2: pogon Usenet); brez tega zapah velja samo do konca procesa. */
        fun namesti(c: Context) {
            if (UsenetNntp.shrambaZapaha == null) UsenetNntp.shrambaZapaha = UsenetZapahShramba(c.applicationContext)
        }
    }
}
