package si.safeer.tv.os

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Namera za splet: na televizorju s Safeer OS gre splet v TV Browser (en WebView, en seznam zavihkov);
 * drugje se odpre brskalnik te aplikacije.
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

    /** Komponenta TV Browserja (si.safeer.tv), edinega WebView-ja na televizorju s Safeer OS. */
    private const val TV_BROWSER_AKTIVNOST = "si.safeer.tv.MainActivity"

    /** Kam gre splet te aplikacije (glej [OsPravila.spletCilj]). */
    fun cilj(c: Context): OsPravila.SpletCilj =
        OsPravila.spletCilj(Sosed.smoOs(c), Sosed.jeTelevizor(c), Sosed.namescen(c, Sosed.BRSKALNIK))

    /**
     * Namera za splet. Safeer OS na televizorju spletnih vsebin ne odpira v svojem WebView-ju, ampak jih z
     * eksplicitnim intentom preda TV Browserju; ce ga ni, odpre sporocilo z gumbom za namestitev
     * ([BrskalnikManjkaActivity]). Brskalnik, telefon in tablica odpirajo svojega (en proces, en WebView).
     */
    fun namera(c: Context): Intent = when (cilj(c)) {
        OsPravila.SpletCilj.TV_BROWSER ->
            // Brskalnik naj ve, od kod je prisel: ob izhodu se vrne v Safeer OS, ne na Android.
            Intent().setComponent(ComponentName(Sosed.BRSKALNIK, TV_BROWSER_AKTIVNOST))
        OsPravila.SpletCilj.NAMESTI_TV_BROWSER -> Intent(c, BrskalnikManjkaActivity::class.java)
        OsPravila.SpletCilj.LOKALNO -> Intent(c, si.safeer.tv.MainActivity::class.java)
    }.putExtra("iz_safeer_os", c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Spletna aplikacija se odpre v istem razdelku Splet; lupina Safeer OS ostane vidna. */
    fun spletnaAplikacija(c: Context, url: String, ime: String): Intent =
        mobilniNaslov(c, url) ?: namera(c).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            putExtra("aplikacija_ime", ime)
        }

    /** Uporabnikov vir glasbe ali videa: kot spletna aplikacija, zvok pa ob tipki Domov igra naprej. */
    fun medijskaStran(c: Context, url: String, ime: String): Intent =
        izMedijev(mobilniNaslov(c, url) ?: namera(c)
            .putExtra("spletna_aplikacija", url)
            .putExtra("aplikacija_ime", ime)
            .putExtra("zvok_v_ozadju", true))

    /** Brskalnik, odprt iz Safeer Media: ob izhodu se vrne v Safeer Media, ne na zacetni zaslon. */
    fun izMedijev(i: Intent): Intent = i.putExtra("os_ohrani_mesto", true)
}
