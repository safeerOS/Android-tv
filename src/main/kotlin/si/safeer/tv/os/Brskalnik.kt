package si.safeer.tv.os

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Namera za splet: ce je Safeer Browser namescen kot svoja aplikacija, odpremo njega (en pogon, ena
 * zascita, en seznam zavihkov); sicer vgrajenega, ki je v Safeer OS za ta primer.
 */
object Brskalnik {

    /** Safeer Browser za telefone in tablice (repozitorij telefon). */
    const val MOBILNI = "com.safeer.mobile.browser"

    /**
     * Na napravi z dotikom (Safeer OS Tablet) splet odpre mobilni Safeer: brskalnik za televizor je
     * narejen za daljinec (fokus, namizne strani) in se na dotik ne obnasa prav. Na televizorju null.
     */
    fun mobilni(c: Context): String? = null

    /** Naslov v mobilnem Safeerju (ali null, ce ga na tej napravi ne uporabljamo). */
    fun mobilniNaslov(c: Context, url: String): Intent? = mobilni(c)?.let {
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun namera(c: Context): Intent =
        // Safeer OS ze vsebuje isti brskalniski pogon. Ne zaganjamo vec locenega Safeer Browser APK-ja:
        // TV in tablica ostaneta v enem procesu, prehod nazaj je takojšen in ni podvojenih WebViewev.
        Intent(c, si.safeer.tv.MainActivity::class.java)
            .putExtra("iz_safeer_os", c.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Spletna aplikacija v svojem oknu brskalnika (kot s kartice na domacem zaslonu). */
    fun spletnaAplikacija(c: Context, url: String, ime: String): Intent =
        mobilniNaslov(c, url) ?: namera(c).putExtra("spletna_aplikacija", url).putExtra("aplikacija_ime", ime)

    /** Uporabnikov vir glasbe ali videa: kot spletna aplikacija, zvok pa ob tipki Domov igra naprej. */
    fun medijskaStran(c: Context, url: String, ime: String): Intent =
        izMedijev(spletnaAplikacija(c, url, ime).putExtra("zvok_v_ozadju", true))

    /** Brskalnik, odprt iz Safeer Media: ob izhodu se vrne v Safeer Media, ne na zacetni zaslon. */
    fun izMedijev(i: Intent): Intent = i.putExtra("os_ohrani_mesto", true)
}
