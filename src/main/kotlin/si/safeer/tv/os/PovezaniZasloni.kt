package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import si.safeer.tv.R
import si.safeer.tv.link.Daljinec
import si.safeer.tv.os.SeznamZaslonov.Dostop

/**
 * Povezani zasloni za naprave Android (telefon, tablica, televizor): ta naprava zaprosi drugo, naj ji deli cel zaslon.
 * Druga naprava pokaze sistemsko potrditev (Android jo zahteva za vsako deljenje - je ni mogoce obiti); po potrditvi
 * slika pride sem kot share.screen in se odpre v gledalcu (CastReceiverService). Lastnik, 11. 10. 2026.
 *
 * Seznam vseh naprav z zaslonom na telefonu in tablici ([pokaziSeznam], lastnik, 16. 10. 2026): kaj se pokaze, dolocajo
 * pravila v [SeznamZaslonov]; tu so tihe preverbe dovoljenja, shramba naprav brez povezave in okno.
 */
object PovezaniZasloni {
    private const val NASTAVITVE = "safeer_os"
    private const val KLJUC_ZNANE = "povezani_zasloni_znane"
    /** Isto napravo preverimo znova sele po tem casu (okno odprto veckrat zapored ne sprasuje vsakic). */
    private const val PREVERBA_VELJA_MS = 20_000L
    private const val PREVERBA_POTEK_MS = 8_000L

    /** Odgovori tihih preverb: id -> (stanje, kdaj). Samo v pomnilniku; zapis znanih je v nastavitvah. */
    private val stanja = HashMap<String, Pair<Dostop, Long>>()

    fun zaprosi(c: Context, link: LinkUpravitelj, r: LinkOdjemalec.Naprava) {
        val ime = r.ime.ifBlank { r.id }
        link.ukaz(r.id, "apps.launch", JSONObject().put("app", Daljinec.CEL_ZASLON).put("stream", true), 15_000) { izid, napaka ->
            val ok = napaka.isEmpty() && izid?.optBoolean("ok") == true
            val besedilo = if (ok) c.getString(R.string.os_zasloni_potrdi_na, ime)
                else c.getString(R.string.os_zasloni_ni_uspelo, ime)
            Toast.makeText(c, besedilo, Toast.LENGTH_LONG).show()
        }
    }

    private fun vPravila(n: LinkOdjemalec.Naprava) =
        SeznamZaslonov.Naprava(n.id, n.ime, n.platforma, n.zmoznosti, n.naprava)

    private fun znane(c: Context): List<SeznamZaslonov.Znana> =
        SeznamZaslonov.izNiza(c.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(KLJUC_ZNANE, null))

    private fun trenutnaStanja(): Map<String, Dostop> = stanja.mapValues { it.value.first }

    /**
     * Tiha preverba dovoljenja: racunalnik dobi "screen.status", naprava Android "status" (oboje zahteva zaslon in nic ne
     * odpre). Z [ProsnjaDostopa.V_OZADJU] racunalnik ob zavrnitvi ne pokaze obvestila - pove le, ali prosnja ze caka.
     */
    private fun preveri(link: LinkUpravitelj, n: LinkOdjemalec.Naprava, znova: Boolean = false, naprej: () -> Unit) {
        val zdaj = SystemClock.elapsedRealtime()
        val prej = stanja[n.id]
        if (!znova && prej != null && (prej.first == Dostop.PREVERJAM || zdaj - prej.second < PREVERBA_VELJA_MS)) return
        stanja[n.id] = Dostop.PREVERJAM to zdaj
        val racunalnik = SeznamZaslonov.jeRacunalnik(n.zmoznosti)
        val dejanje = if (racunalnik) "screen.status" else "status"
        link.ukaz(n.id, dejanje, JSONObject().put(ProsnjaDostopa.V_OZADJU, true), PREVERBA_POTEK_MS) { izid, _ ->
            val podatki = izid?.optJSONObject("data")
            val prosnja = izid?.optString("access_request", "").orEmpty().ifBlank { podatki?.optString("access_request", "").orEmpty() }
            val dostop = SeznamZaslonov.izOdgovora(izid?.optBoolean("ok"), izid?.optString("code", ""),
                podatki?.optString("reason", ""), prosnja, racunalnik)
            // Prvi ukaz po povezavi caka na zascito seje in lahko potece (OnePlus 9, 16. 10. 2026: prva preverba brez
            // odgovora, druga takoj »dostopno«): enkrat poskusimo znova, preden pokazemo »ni odgovora«.
            if (dostop == Dostop.NI_ODGOVORA && !znova) { preveri(link, n, znova = true, naprej = naprej); return@ukaz }
            stanja[n.id] = dostop to SystemClock.elapsedRealtime()
            naprej()
        }
    }

    /**
     * Okno »Povezani zasloni«: vse naprave z zaslonom, ki jih ta naprava sme gledati (in prosnje zanje), naprave brez
     * povezave oznacene. Med odprtim oknom se seznam osvezi, ko pride odgovor ali se Link spremeni.
     */
    fun pokaziSeznam(a: Activity, link: LinkUpravitelj) {
        if (a.isFinishing) return
        val glavna = Handler(Looper.getMainLooper())
        val gradnik = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val kontekst = gradnik.context
        val gostota = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * gostota).toInt()
        val seznam = LinearLayout(kontekst).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val drsnik = ScrollView(kontekst).apply { addView(seznam) }
        var okno: AlertDialog? = null

        // Ziva naprava gre v jeTaNaprava cela (naslov 127.0.0.1 ipd.); zapomnjena samo z id-jem in kljucem.
        fun jeTa(n: SeznamZaslonov.Naprava): Boolean = link.jeTaNaprava(link.naprave.firstOrNull { it.id == n.id }
            ?: LinkOdjemalec.Naprava(n.id, n.ime, "", n.zmoznosti, "", n.platforma, n.fizicna))

        fun dotik(v: SeznamZaslonov.Vnos) {
            val naprava = link.naprave.firstOrNull { it.id == v.id }
            if (!v.povezana || naprava == null) {
                Toast.makeText(a, a.getString(R.string.os_zasloni_brez_povezave_opis, v.ime), Toast.LENGTH_LONG).show(); return
            }
            when {
                v.dostop == Dostop.ZAVRNJENO -> Toast.makeText(a, a.getString(R.string.os_prosnja_zavrnjeno, v.ime), Toast.LENGTH_LONG).show()
                // Racunalnik: zaslon racunalnika. Ta pri prosnji pokaze »Na racunalniku potrdi dostop« in pocaka na potrditev.
                v.racunalnik -> {
                    okno?.dismiss()
                    try {
                        a.startActivity(Intent(a, ZaslonActivity::class.java).putExtra(DatotekeActivity.EXTRA_RACUNALNIK, v.id))
                    } catch (e: Throwable) { Toast.makeText(a, "${v.ime}: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
                else -> { okno?.dismiss(); zaprosi(a, link, naprava) }
            }
        }

        fun narisi() {
            if (a.isFinishing) return
            val zive = link.naprave.map { vPravila(it) }
            val prejZnane = znane(a)
            val trenutna = trenutnaStanja()
            val nove = SeznamZaslonov.zapomni(prejZnane, zive, ::jeTa, trenutna)
            if (nove != prejZnane) a.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit()
                .putString(KLJUC_ZNANE, SeznamZaslonov.vNiz(nove)).apply()
            val vnosi = SeznamZaslonov.seznam(zive, ::jeTa, nove, trenutna)
            seznam.removeAllViews()
            val prazno = SeznamZaslonov.prazno(vnosi, zive, ::jeTa, link.povezan)
            if (prazno != null) {
                seznam.addView(TextView(kontekst).apply {
                    text = a.getString(when (prazno) {
                        SeznamZaslonov.Prazno.NI_LINKA -> R.string.os_zasloni_prazno_ni_linka
                        SeznamZaslonov.Prazno.NI_NAPRAV -> R.string.os_zasloni_prazno_ni_naprav
                        SeznamZaslonov.Prazno.NI_DOVOLJENJ -> R.string.os_zasloni_prazno_ni_dovoljenj
                    })
                    textSize = 15f
                    setPadding(0, dp(8), 0, dp(8))
                })
                return
            }
            for (v in vnosi) seznam.addView(vrstica(kontekst, v, ::dp) { dotik(v) })
        }

        val poslusalec = object : LinkOdjemalec.Poslusalec {
            override fun naStanje(povezan: Boolean, sporocilo: String) { narisi() }
            override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
                naprave.filter { SeznamZaslonov.zmoreZaslon(it.zmoznosti) && !link.jeTaNaprava(it) }
                    .forEach { preveri(link, it) { glavna.post { if (okno?.isShowing == true) narisi() } } }
                narisi()
            }
            override fun naNaslov(url: String, naslov: String, od: String) { }
            override fun naBesedilo(besedilo: String, od: String) { }
            override fun naZavrnitev() { }
        }

        val dialog = gradnik
            .setTitle(a.getString(R.string.os_zasloni_naslov))
            .setView(drsnik)
            .setNeutralButton(a.getString(R.string.os_meni_naprave)) { _, _ ->
                try { a.startActivity(Intent(a, NapraveActivity::class.java)) } catch (_: Throwable) { }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        okno = dialog
        dialog.setOnDismissListener { link.odstrani(poslusalec) }
        narisi()
        dialog.show()
        link.dodaj(poslusalec)      // takoj poklice naStanje in naNaprave: preverbe se zacnejo
    }

    private fun vrstica(c: Context, v: SeznamZaslonov.Vnos, dp: (Int) -> Int, ob: () -> Unit): View {
        val vrsta = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            minimumHeight = dp(56)
            isClickable = true
            isFocusable = true
            val ozadje = android.util.TypedValue()
            if (c.theme.resolveAttribute(android.R.attr.selectableItemBackground, ozadje, true)) setBackgroundResource(ozadje.resourceId)
            setOnClickListener { ob() }
            alpha = if (v.povezana) 1f else 0.5f
        }
        val ime = TextView(c).apply {
            text = v.ime
            textSize = 16f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val ikona = ImageView(c).apply {
            setImageResource(when {
                v.racunalnik || v.platforma == "linux" || v.platforma == "windows" -> R.drawable.os_ikona_racunalnik
                v.platforma == "phone" -> R.drawable.os_ikona_telefon
                v.platforma == "tablet" -> R.drawable.os_ikona_tablica
                v.platforma == "tv" -> R.drawable.os_ikona_naprava
                else -> R.drawable.os_ikona_zaslon
            })
            imageTintList = ColorStateList.valueOf(ime.currentTextColor)
            contentDescription = null
        }
        vrsta.addView(ikona, LinearLayout.LayoutParams(dp(28), dp(28)))
        val besedili = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        besedili.addView(ime)
        besedili.addView(TextView(c).apply {
            text = c.getString(when {
                !v.povezana -> R.string.os_zasloni_brez_povezave
                v.dostop == Dostop.DOSTOPNO -> R.string.os_zasloni_dostopno
                v.dostop == Dostop.CAKA -> R.string.os_zasloni_caka
                v.dostop == Dostop.ZAVRNJENO -> R.string.os_zasloni_zavrnjeno
                v.dostop == Dostop.PROSNJA -> R.string.os_zasloni_prosnja
                v.dostop == Dostop.NI_ODGOVORA -> R.string.os_zasloni_ni_odgovora
                else -> R.string.os_zasloni_preverjam
            })
            textSize = 13f
            alpha = 0.75f
        })
        vrsta.addView(besedili, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        return vrsta
    }
}
