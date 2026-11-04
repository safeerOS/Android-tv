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

    /**
     * Zasciteni vhod TV Browserja (okusi/brskalnik: activity-alias z dovoljenjem LINK): samo po njem sme Safeer OS odpreti
     * spletno aplikacijo cez cel zaslon (tedenski pregled 4. 11. 2026, S1). Starejsi TV Browser ga nima - takrat MainActivity.
     */
    const val VARNI_VHOD = "si.safeer.tv.SafeerVhod"

    private fun tvBrowser(c: Context): ComponentName {
        val vhod = ComponentName(Sosed.BRSKALNIK, VARNI_VHOD)
        val ima = try { c.packageManager.resolveActivity(Intent().setComponent(vhod), 0) != null } catch (_: Throwable) { false }
        return if (ima) vhod else ComponentName(Sosed.BRSKALNIK, TV_BROWSER_AKTIVNOST)
    }

    /** Kam gre splet te aplikacije (glej [OsPravila.spletCilj]). */
    fun cilj(c: Context): OsPravila.SpletCilj =
        OsPravila.spletCilj(Sosed.smoOs(c), Sosed.namescen(c, Sosed.BRSKALNIK))

    /**
     * Namera za splet. Safeer OS na televizorju spletnih vsebin ne odpira v svojem WebView-ju, ampak jih z
     * eksplicitnim intentom preda TV Browserju; ce ga ni, odpre sporocilo z gumbom za namestitev
     * ([BrskalnikManjkaActivity]). Brskalnik, telefon in tablica odpirajo svojega (en proces, en WebView).
     */
    fun namera(c: Context): Intent = when (cilj(c)) {
        OsPravila.SpletCilj.TV_BROWSER ->
            // Brskalnik naj ve, od kod je prisel: ob izhodu se vrne v Safeer OS, ne na Android.
            Intent().setComponent(tvBrowser(c))
        OsPravila.SpletCilj.NAMESTI_TV_BROWSER -> Intent(c, BrskalnikManjkaActivity::class.java)
        OsPravila.SpletCilj.LOKALNO -> Intent(c, si.safeer.tv.MainActivity::class.java)
    }.putExtra("iz_safeer_os", c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Dodatek namere: Splet iz Safeer OS - brskalnik pokaze domaco stran, ne zadnjega brskanja (lastnik 4. 11. 2026). */
    const val EXTRA_ODPRI_DOMACO = "odpri_domaco"

    /** Shramba in kljuc izbire brskalnika za Splet (prazno = Safeer TV Browser). */
    const val SHRAMBA = "safeer_os"
    const val KLJUC_SPLET_BRSKALNIK = "splet_brskalnik"

    /** Tuj brskalnik, ki ga je izbral uporabnik in je se namescen, ali null = Safeer ([OsPravila.spletBrskalnik]). */
    fun izbrani(c: Context): String? {
        val shranjen = try {
            c.getSharedPreferences(SHRAMBA, Context.MODE_PRIVATE).getString(KLJUC_SPLET_BRSKALNIK, null)
        } catch (_: Throwable) { null }
        return OsPravila.spletBrskalnik(shranjen, Sosed.smoOs(c), !shranjen.isNullOrBlank() && Sosed.namescen(c, shranjen.trim()))
    }

    fun shraniIzbiro(c: Context, paket: String?) {
        c.getSharedPreferences(SHRAMBA, Context.MODE_PRIVATE).edit().apply {
            if (paket.isNullOrBlank() || paket in OsPravila.SAFEER_BRSKALNIKI) remove(KLJUC_SPLET_BRSKALNIK)
            else putString(KLJUC_SPLET_BRSKALNIK, paket)
        }.apply()
    }

    /** Nameščeni tuji brskalniki (paket -> ime): aplikacije, ki odprejo https iz povezave in imajo zagon na TV ali v predalu. */
    fun namesceni(c: Context): List<Pair<String, String>> = try {
        val pm = c.packageManager
        val splet = Intent(Intent.ACTION_VIEW, Uri.parse("https://safeer.si")).addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        val kandidati = pm.queryIntentActivities(splet, 0).mapNotNull { r ->
            val paket = r.activityInfo?.packageName ?: return@mapNotNull null
            // Samo aplikacija, ki jo lahko zazenemo (domaca stran brskalnika), ne npr. prestreznik povezav.
            if (zagon(c, paket) == null) return@mapNotNull null
            paket to (r.loadLabel(pm)?.toString().orEmpty().ifBlank { paket })
        }
        OsPravila.brskalnikiZaIzbiro(kandidati)
    } catch (_: Throwable) { emptyList() }

    /** Zagonska namera paketa (najprej TV, nato predal). */
    private fun zagon(c: Context, paket: String): Intent? = try {
        c.packageManager.getLeanbackLaunchIntentForPackage(paket) ?: c.packageManager.getLaunchIntentForPackage(paket)
    } catch (_: Throwable) { null }

    /**
     * Razdelek Splet: domaca stran izbranega brskalnika. Tuj brskalnik dobi svojo zagonsko namero (njegova domaca
     * stran); Safeer TV Browser dodatek [EXTRA_ODPRI_DOMACO]. Ce izbranega ni vec, Safeer brez napake.
     */
    fun splet(c: Context): Intent =
        izbrani(c)?.let { zagon(c, it) }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: namera(c).putExtra(EXTRA_ODPRI_DOMACO, true)

    /**
     * Iskanje v spletu ali predlog iz Lupe: naslov v izbranem brskalniku (ACTION_VIEW s paketom), sicer v Safeerju
     * kot doslej. Odpre samo http(s); ce tuj brskalnik naslova ne sprejme, gre v Safeer.
     */
    fun iskanje(c: Context, url: String): Intent {
        val uri = Uri.parse(url)
        val shema = uri.scheme?.lowercase()
        val tuj = izbrani(c)
        if (tuj != null && (shema == "https" || shema == "http")) {
            val i = Intent(Intent.ACTION_VIEW, uri).setPackage(tuj).addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val sprejme = try { c.packageManager.resolveActivity(i, 0) != null } catch (_: Throwable) { false }
            if (sprejme) return i
        }
        return namera(c).setAction(Intent.ACTION_VIEW).setData(uri)
    }

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
