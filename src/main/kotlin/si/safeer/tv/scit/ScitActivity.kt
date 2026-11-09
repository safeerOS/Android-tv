package si.safeer.tv.scit

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import si.safeer.tv.R
import si.safeer.tv.os.OsActivity

/**
 * Zaslon Safeer Scita (lastnikov pregled 3. 11. 2026: prej samo toast in konec). Vklop in izklop, koliko je bilo danes
 * ustavljenega, zadnjih 20 ustavljenih imen z uro, izjeme (uporabnikove in Webtorjeve) s preklicem, opozorilo, ce zasebni
 * DNS Scit obide, in ce omrezje ne javi DNS. OK na ustavljenem imenu, ki ni s seznama grozenj, ga spusti za 30 dni.
 *
 * Prvic Android zahteva potrditev v sistemskem oknu (VPN); brez dodatka [EXTRA_POKAZI] zaslon to okno odpre sam (stari
 * klicatelji: meni brskalnika, Safeer OS). Dodatek [EXTRA_IZKLOPI] = izklop brez zaslona. Seznam zadnjih imen pise proces
 * Scita v zasebno datoteko (scit/zadnja.txt); zaslon jo bere vsaki 2 s, dokler je viden - na delovni niti (pregled 5. 11.
 * 2026: prej mmap grozne.bin, branje datotek in poizvedbe omrezij na glavni niti), zasebni DNS samo ob onResume.
 */
class ScitActivity : OsActivity() {

    private lateinit var vsebina: LinearLayout
    private val glavna = Handler(Looper.getMainLooper())
    private var zadnjiOdtis = ""
    /** Napaka zadnjega dejanja (na zaslonu, ne toast). */
    private var napaka: String? = null
    private val osvezevanje = object : Runnable {
        override fun run() { narisi(false); glavna.postDelayed(this, 2_000) }
    }
    /** Najvec eno branje hkrati (drugi krog pregleda 5. 11. 2026: osvezitve vsaki 2 s so se kopicile). */
    private val branje = ScitStanjePravila.EnoBranje()
    /** Fokus nujne zahteve, ki je prisla med branjem (izvede se po njem). Samo glavna nit. */
    private var fokusPoBranju: String? = null
    /** Branje stanja (datoteke, nabor grozenj, omrezja) - ena delovna nit, da se branja ne prehitevajo. */
    private val delavec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-scit-zaslon").apply { isDaemon = true } }
    /** Nabor grozenj, odprt enkrat; znova samo, ko se grozne.bin spremeni (lastModified). Samo na [delavec]. */
    private var grozneNabor: DomenskiNabor.Poizvedba? = null
    /** Zasebni DNS obide Scit - prebrano ob onResume (nastavitev se spremeni samo v sistemskih nastavitvah). */
    @Volatile private var zasebniDns = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_IZKLOPI, false) == true) {
            Scit.izklopi(this)
            Toast.makeText(this, getString(R.string.scit_izklopljen), Toast.LENGTH_SHORT).show()
            finish(); return
        }
        vsebina = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(32), dp(56), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(barva(R.attr.osOzadje))
            isFillViewport = true
            addView(vsebina)
        })
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_POKAZI, false) != true && !Scit.jeVklopljen(this)) vklopi()
    }

    override fun onResume() {
        super.onResume()
        if (!::vsebina.isInitialized) return
        zadnjiOdtis = ""
        glavna.removeCallbacks(osvezevanje)
        // Zasebni DNS najprej (vrnitev iz sistemskih nastavitev omrezja), nato prvo branje; obe na isti delovni niti.
        vOzadju { zasebniDns = zasebniDnsObide() }
        glavna.post(osvezevanje)
    }

    override fun onDestroy() {
        glavna.removeCallbacks(osvezevanje)
        delavec.shutdownNow()
        super.onDestroy()
    }

    private fun vOzadju(delo: () -> Unit): Boolean {
        try { delavec.execute { try { delo() } catch (e: Throwable) { Log.w(TAG, "Zaslon: ${e.javaClass.simpleName}") } }; return true }
        catch (_: java.util.concurrent.RejectedExecutionException) { return false }
    }

    override fun onPause() {
        glavna.removeCallbacks(osvezevanje)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ZAHTEVA) return
        if (isFinishing || isDestroyed) return
        if (resultCode == RESULT_OK) { if (!Scit.vklopi(this)) napaka = getString(R.string.scit_napaka_dovoljenje) }
        else napaka = getString(R.string.scit_napaka_dovoljenje)
        zadnjiOdtis = ""
        if (::vsebina.isInitialized) narisi(true)
    }

    private fun vklopi() {
        val dovoljenje = Scit.dovoljenjeNamera(this)
        if (dovoljenje == null) { if (!Scit.vklopi(this)) napaka = getString(R.string.scit_napaka_dovoljenje); return }
        try { startActivityForResult(dovoljenje, ZAHTEVA) } catch (_: Throwable) { napaka = getString(R.string.scit_napaka_dovoljenje) }
    }

    private fun preklopi() {
        napaka = null
        // Preverba TV 4. 11. 2026: en OK na privzetem fokusu (Izklopi) je takoj izklopil zascito - izklop zdaj vprasa.
        if (ScitPregled.izklopZPotrditvijo(Scit.jeVklopljen(this))) { potrdiIzklop(); return }
        vklopi()
        zadnjiOdtis = ""
        // Storitev se zazene ali ustavi v drugem procesu trenutek kasneje; osvezevanje vsaki 2 s pokaze novo stanje.
        narisi(true, fokus = OZNAKA_PREKLOP)
    }

    /**
     * Prekinjen (drug VPN je prevzel tunel, zastavica je ostala): ponovni vklop z isto potjo kot prvi - ce ima tunel drug
     * VPN, Android znova pokaze sistemsko okno (startActivityForResult, onActivityResult), sicer storitev zazenemo takoj.
     */
    private fun vklopiZnova() {
        napaka = null
        vklopi()
        zadnjiOdtis = ""
        narisi(true, fokus = OZNAKA_PREKLOP)
    }

    /** Okno »Izklopim Safeer Scit?«: privzeto Preklici, izklop samo z izrecno izbiro. */
    private fun potrdiIzklop() {
        val okno = android.app.AlertDialog.Builder(this, R.style.Os_Okno)
            .setTitle(R.string.scit_ime)
            .setMessage(R.string.scit_z_izklopi_vprasanje)
            .setPositiveButton(R.string.scit_z_izklopi) { _, _ ->
                Scit.izklopi(this)
                zadnjiOdtis = ""
                narisi(true, fokus = OZNAKA_PREKLOP)
            }
            .setNegativeButton(R.string.os_preklici, null)
            .create()
        okno.setOnShowListener { okno.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.requestFocus() }
        okno.show()
        si.safeer.tv.os.Kontroler.pokazi(okno)
    }

    // ------------------------------------------------------------------ stanje

    private class Pogled(
        val vklopljen: Boolean, val stanje: String, val blokiranih: Long, val domen: Int, val brezDns: Boolean,
        val zasebniDns: Boolean, val zadnja: List<ScitPregled.Zadnje>, val grozneNalozene: Boolean, val groznje: Set<String>,
        val uporabniske: Map<String, Long>, val webtor: Map<String, Long>,
    ) {
        fun odtis(): String = listOf(vklopljen, stanje, blokiranih, domen, brezDns, zasebniDns, grozneNalozene,
            zadnja.joinToString { it.ime + it.ura }, uporabniske, webtor).joinToString("|")
    }

    /** Samo na [delavec]. */
    private fun preberi(): Pogled {
        val s = Scit.statistika(this)
        val vklopljen = Scit.jeVklopljen(this)
        val zadnja = if (vklopljen) Scit.zadnja(this) else emptyList()
        val g = try { DomenskiNabor.Poizvedba.odpriCeSpremenjeno(Scit.grozneDatoteka(this), grozneNabor) } catch (_: Throwable) { null }
        grozneNabor = g
        val groznje = zadnja.filter { g?.jeBlokirana(it.ime) == true }.map { it.ime }.toSet()
        return Pogled(vklopljen, s.stanje, if (s.dan == danes()) s.blokiranih else 0L, s.domen, s.brezKrajevnegaDns,
            zasebniDns, zadnja, g != null, groznje, Scit.izjemeUporabnika(this), Scit.izjemeWebtor(this))
    }

    private fun danes(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())

    /**
     * Zasebni DNS (DoT) z imenom streznika Scit obide: beremo samo (Settings.Global brez posebnih dovoljenj in
     * LinkProperties omrezij, ki niso VPN). Nastavitve ne spreminjamo.
     */
    private fun zasebniDnsObide(): Boolean {
        val nacin = try { Settings.Global.getString(contentResolver, "private_dns_mode") } catch (_: Throwable) { null }
        var ime: String? = null
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            @Suppress("DEPRECATION") val vsa = cm?.allNetworks.orEmpty()
            for (n in vsa) {
                if (cm == null) break
                val zm = cm.getNetworkCapabilities(n) ?: continue
                if (zm.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(n) ?: continue
                if (lp.isPrivateDnsActive && !lp.privateDnsServerName.isNullOrBlank()) { ime = lp.privateDnsServerName; break }
            }
        } catch (_: Throwable) { }
        return ScitPregled.zasebniDnsObide(nacin, ime)
    }

    // ------------------------------------------------------------------ risanje

    /** Prebere stanje na [delavec] in ga izrise na glavni niti. */
    private fun narisi(prisilno: Boolean, fokus: String? = null) {
        if (!branje.zacni(prisilno)) { if (prisilno && fokus != null) fokusPoBranju = fokus; return }
        val zacet = vOzadju {
            try {
                val p = preberi()
                runOnUiThread { if (!isFinishing && !isDestroyed) izrisi(p, prisilno, fokus) }
            } finally {
                if (branje.konec()) runOnUiThread {
                    if (!isFinishing && !isDestroyed) { val f = fokusPoBranju; fokusPoBranju = null; narisi(true, f) }
                }
            }
        }
        if (!zacet) branje.konec()
    }

    private fun izrisi(p: Pogled, prisilno: Boolean, fokus: String?) {
        val imaKljuc = AbuseChKljuc.ima(this)
        val poMeri = ScitPoMeri.seznam(this)
        val odtis = p.odtis() + "|" + napaka + "|" + imaKljuc + "|" + poMeri.joinToString { it.id + it.vklop }
        if (!prisilno && odtis == zadnjiOdtis) return
        zadnjiOdtis = odtis
        // Izbira (daljinec) ostane na isti vrstici tudi po osvezitvi.
        val izbrana = fokus ?: (currentFocus?.tag as? String)
        vsebina.removeAllViews()

        vsebina.addView(besedilo(getString(R.string.scit_ime), 26f, R.attr.osBesedilo, krepko = true))
        val stanje = ScitStanjePravila.stanje(p.vklopljen, p.stanje == "tece")
        val (stanjeBesedilo, stanjeBarva) = when {
            stanje == ScitStanjePravila.Stanje.IZKLOPLJEN -> getString(R.string.scit_z_stanje_izklopljen) to R.attr.osUmirjeno
            stanje == ScitStanjePravila.Stanje.PREKINJEN -> getString(R.string.scit_z_stanje_prekinjen) to R.attr.osOpozorilo
            p.domen <= 0 -> getString(R.string.scit_z_stanje_prazen) to R.attr.osOpozorilo
            else -> getString(R.string.scit_z_stanje_tece) to R.attr.osMint
        }
        vsebina.addView(besedilo(stanjeBesedilo, 17f, stanjeBarva, krepko = true).apply { setPadding(0, dp(6), 0, dp(4)) })
        napaka?.let { vsebina.addView(besedilo(it, 15f, R.attr.osOpozorilo).apply { setPadding(0, dp(4), 0, dp(4)) }) }
        if (p.vklopljen && p.stanje == "tece" && p.brezDns)
            vsebina.addView(besedilo(getString(R.string.scit_z_ni_dns), 15f, R.attr.osOpozorilo).apply { setPadding(0, dp(4), 0, dp(4)) })
        if (p.vklopljen && p.stanje == "tece" && p.domen > 0)
            vsebina.addView(besedilo(getString(R.string.scit_z_danes, p.blokiranih), 15f, R.attr.osUmirjeno).apply { setPadding(0, dp(2), 0, dp(8)) })

        val vrstaGumbov = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(12)) }
        // Pregled 5. 11. 2026: prekinjen (drug VPN) ponudi ponovni vklop (prvi, fokus), izklop ostane.
        for ((i, g) in ScitStanjePravila.gumbi(stanje).withIndex()) {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dp(12) }
            vrstaGumbov.addView(when (g) {
                ScitStanjePravila.Gumb.VKLOPI -> gumb(getString(R.string.scit_z_vklopi), OZNAKA_PREKLOP) { preklopi() }
                ScitStanjePravila.Gumb.IZKLOPI -> gumb(getString(R.string.scit_z_izklopi), if (i == 0) OZNAKA_PREKLOP else OZNAKA_IZKLOP) { preklopi() }
                ScitStanjePravila.Gumb.VKLOPI_ZNOVA -> gumb(getString(R.string.scit_z_vklopi), OZNAKA_PREKLOP) { vklopiZnova() }
            }, lp)
        }
        vsebina.addView(vrstaGumbov)

        if (p.zasebniDns) {
            vsebina.addView(besedilo(getString(R.string.scit_z_zasebni_dns), 15f, R.attr.osOpozorilo).apply { setPadding(0, dp(4), 0, dp(6)) })
            vsebina.addView(gumb(getString(R.string.scit_z_zasebni_dns_gumb), OZNAKA_NASTAVITVE) { odpriNastavitveOmrezja() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }

        if (p.vklopljen) {
            vsebina.addView(naslov(getString(R.string.scit_z_zadnja)))
            // Preverba TV 4. 11. 2026: ob dnevnem stevcu > 0 in praznem seznamu povemo, da so se imena ob izklopu izbrisala.
            if (p.zadnja.isEmpty()) vsebina.addView(besedilo(getString(
                if (ScitPregled.imenaIzbrisana(0, p.blokiranih)) R.string.scit_z_zadnja_po_izklopu else R.string.scit_z_zadnja_prazno),
                14f, R.attr.osUmirjeno))
            val ura = android.text.format.DateFormat.getTimeFormat(this)
            val zdaj = System.currentTimeMillis()
            for (z in p.zadnja) {
                val groznja = z.ime in p.groznje
                val izjema = ScitPregled.uporabniskaVelja(z.ime, p.uporabniske, zdaj)
                val lahko = ScitPregled.lahkoSpusti(p.grozneNalozene, groznja) && !izjema
                val opis = when {
                    izjema -> getString(R.string.scit_z_ze_spusceno)
                    groznja -> getString(R.string.scit_z_groznja)
                    lahko -> getString(R.string.scit_z_spusti)
                    else -> ""
                }
                // TV 4. 11. 2026 23:18: OK samo vprasa (ScitPregled.okNaImenu), spust je izrecni »Da«.
                val ob: (() -> Unit)? = if (ScitPregled.okNaImenu(lahko) == ScitPregled.OkNaImenu.VPRASAJ) ({ potrdiSpust(z.ime) }) else null
                vsebina.addView(vrstica(ura.format(java.util.Date(z.ura)) + "   " + z.ime, opis,
                    if (groznja) R.attr.osOpozorilo else R.attr.osUmirjeno, OZNAKA_IME + z.ime, ob))
            }
        }

        // Lastnik 9. 11. 2026: seznami s kljucem uporabnika (abuse.ch URLhaus od 21. 10. 2026 samo s kljucem).
        vsebina.addView(naslov(getString(R.string.scit_z_seznami)))
        vsebina.addView(vrstica(getString(R.string.scit_z_urlhaus), getString(if (imaKljuc) R.string.scit_z_kljuc_vpisan else R.string.scit_z_kljuc_ni),
            if (imaKljuc) R.attr.osMint else R.attr.osOpozorilo, OZNAKA_KLJUC) { kljucAbuseCh() })

        // Lastnik 9. 11. 2026: filtri po meri (kot AdGuard »Dodaj filter po meri«) - samo za Scit (DNS).
        vsebina.addView(naslov(getString(R.string.scit_z_po_meri)))
        for (f in poMeri) {
            vsebina.addView(vrstica(ScitPoMeriPravila.ime(f.url), getString(if (f.vklop) R.string.scit_z_po_meri_vklopljen else R.string.scit_z_po_meri_izklopljen),
                if (f.vklop) R.attr.osMint else R.attr.osUmirjeno, OZNAKA_PO_MERI + f.id) { filterPoMeri(f) })
        }
        if (poMeri.size < ScitPoMeriPravila.NAJVEC)
            vsebina.addView(vrstica(getString(R.string.scit_z_po_meri_dodaj), "", R.attr.osUmirjeno, OZNAKA_PO_MERI_DODAJ) { dodajFilter() })

        vsebina.addView(naslov(getString(R.string.scit_z_izjeme)))
        val datum = android.text.format.DateFormat.getMediumDateFormat(this)
        for ((ime, velja) in p.uporabniske) {
            vsebina.addView(vrstica(ime, getString(R.string.scit_z_izjema_do, datum.format(java.util.Date(velja))), R.attr.osUmirjeno,
                OZNAKA_IZJEMA + ime) { preklici(ime) })
        }
        for ((ime, velja) in p.webtor) {
            vsebina.addView(vrstica(ime, getString(R.string.scit_z_izjema_webtor, datum.format(java.util.Date(velja))), R.attr.osUmirjeno,
                OZNAKA_IZJEMA + ime) { preklici(ime) })
        }
        // Stalni Webtorjevi izjemi (v kodi): vidni, a ju ni mogoce preklicati.
        for (ime in ScitIzjeme.STALNE)
            vsebina.addView(vrstica(ime, getString(R.string.scit_z_izjema_stalna), R.attr.osUmirjeno, OZNAKA_IZJEMA + ime, null))

        vsebina.addView(besedilo(getString(R.string.scit_z_zasebnost), 13f, R.attr.osUmirjeno).apply { setPadding(0, dp(18), 0, 0) })

        val cilj = izbrana?.let { vsebina.findViewWithTag<View>(it) } ?: vsebina.findViewWithTag<View>(OZNAKA_PREKLOP)
        cilj?.post { cilj.requestFocus() }
    }

    /** Okno »Spustim <ime> za 30 dni?«: privzeto Ne (ScitPregled.PRIVZETO_SPUSTI), spust samo z izrecno izbiro. */
    private fun potrdiSpust(ime: String) {
        val okno = android.app.AlertDialog.Builder(this, R.style.Os_Okno)
            .setTitle(R.string.scit_ime)
            .setMessage(getString(R.string.scit_z_spusti_vprasanje, ime))
            .setPositiveButton(R.string.scit_z_spusti_da) { _, _ -> spusti(ime) }
            .setNegativeButton(R.string.scit_z_spusti_ne, null)
            .create()
        okno.setOnShowListener {
            okno.getButton(if (ScitPregled.PRIVZETO_SPUSTI) android.app.AlertDialog.BUTTON_POSITIVE else android.app.AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        }
        okno.show()
        si.safeer.tv.os.Kontroler.pokazi(okno)
    }

    /** Vpis kljuca abuse.ch: navodilo, kje ga dobi, polje; kljuc se pred shranjevanjem preveri pri abuse.ch. */
    private fun kljucAbuseCh() {
        val rob = dp(20)
        val polje = android.widget.EditText(this).apply {
            hint = getString(R.string.scit_z_kljuc_namig)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val okvir = android.widget.FrameLayout(this).apply { setPadding(rob, rob / 2, rob, 0); addView(polje) }
        val g = android.app.AlertDialog.Builder(this, R.style.Os_Okno)
            .setTitle(R.string.scit_z_urlhaus)
            .setMessage(R.string.scit_z_kljuc_navodilo)
            .setView(okvir)
            .setPositiveButton(R.string.scit_z_kljuc_shrani) { _, _ -> shraniKljuc(polje.text.toString()) }
            .setNegativeButton(R.string.scit_z_spusti_ne, null)
        if (AbuseChKljuc.ima(this)) g.setNeutralButton(R.string.scit_z_kljuc_odstrani) { _, _ ->
            AbuseChKljuc.pozabi(applicationContext)
            zadnjiOdtis = ""
            narisi(true, fokus = OZNAKA_KLJUC)
        }
        val okno = g.create()
        okno.show()
        si.safeer.tv.os.Kontroler.pokazi(okno)
    }

    private fun shraniKljuc(vnos: String) {
        val kljuc = AbuseChPravila.ocisti(vnos)
        val app = applicationContext
        Toast.makeText(this, R.string.scit_z_kljuc_preverjam, Toast.LENGTH_SHORT).show()
        Thread({
            val izid = try { AbuseChKljuc.preveri(kljuc) } catch (_: Throwable) { AbuseChPravila.Preverba.NI_ODGOVORA }
            val shranjen = izid == AbuseChPravila.Preverba.VELJA && AbuseChKljuc.shrani(app, kljuc)
            if (shranjen && Scit.jeVklopljen(app)) ScitStoritev.osveziSezname(app)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, when {
                    shranjen -> R.string.scit_z_kljuc_shranjen
                    izid == AbuseChPravila.Preverba.NI_VELJAVEN || izid == AbuseChPravila.Preverba.OBLIKA -> R.string.scit_z_kljuc_neveljaven
                    else -> R.string.scit_z_kljuc_ni_odgovora
                }, Toast.LENGTH_LONG).show()
                zadnjiOdtis = ""
                narisi(true, fokus = OZNAKA_KLJUC)
            }
        }, "safeer-scit-kljuc").start()
    }

    private fun dodajFilter() {
        val rob = dp(20)
        val polje = android.widget.EditText(this).apply {
            hint = "https://"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        val okvir = android.widget.FrameLayout(this).apply { setPadding(rob, rob / 2, rob, 0); addView(polje) }
        val okno = android.app.AlertDialog.Builder(this, R.style.Os_Okno)
            .setTitle(R.string.scit_z_po_meri_dodaj)
            .setMessage(R.string.scit_z_po_meri_navodilo)
            .setView(okvir)
            .setPositiveButton(R.string.scit_z_po_meri_naprej) { _, _ -> preizkusiFilter(polje.text.toString().trim()) }
            .setNegativeButton(R.string.scit_z_spusti_ne, null)
            .create()
        okno.show()
        si.safeer.tv.os.Kontroler.pokazi(okno)
    }

    /** Seznam se pred dodajanjem prenese in preveri (HTTPS, ne HTML, imena domen); sele nato je dodan. */
    private fun preizkusiFilter(url: String) {
        if (ScitPoMeriPravila.napaka(url) != null) { Toast.makeText(this, R.string.scit_z_po_meri_napacen, Toast.LENGTH_LONG).show(); return }
        val app = applicationContext
        Toast.makeText(this, R.string.scit_z_po_meri_preverjam, Toast.LENGTH_SHORT).show()
        Thread({
            val n = ScitPoMeri.preizkusi(url)
            val dodan = n > 0 && ScitPoMeri.dodaj(app, url)
            if (dodan && Scit.jeVklopljen(app)) ScitStoritev.osveziSezname(app)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (dodan) Toast.makeText(this, getString(R.string.scit_z_po_meri_dodan, n), Toast.LENGTH_LONG).show()
                else Toast.makeText(this, R.string.scit_z_po_meri_ni_seznam, Toast.LENGTH_LONG).show()
                zadnjiOdtis = ""
                narisi(true, fokus = OZNAKA_PO_MERI + ScitPoMeriPravila.id(url))
            }
        }, "safeer-scit-po-meri").start()
    }

    private fun filterPoMeri(f: ScitPoMeri.Filter) {
        val app = applicationContext
        val okno = android.app.AlertDialog.Builder(this, R.style.Os_Okno)
            .setTitle(ScitPoMeriPravila.ime(f.url))
            .setMessage(f.url)
            .setPositiveButton(if (f.vklop) R.string.scit_z_po_meri_izklopi else R.string.scit_z_po_meri_vklopi) { _, _ ->
                ScitPoMeri.nastavi(app, f.id, !f.vklop)
                if (Scit.jeVklopljen(app)) ScitStoritev.osveziSezname(app)
                zadnjiOdtis = ""; narisi(true, fokus = OZNAKA_PO_MERI + f.id)
            }
            .setNeutralButton(R.string.scit_z_po_meri_odstrani) { _, _ ->
                ScitPoMeri.odstrani(app, f.id)
                if (Scit.jeVklopljen(app)) ScitStoritev.osveziSezname(app)
                zadnjiOdtis = ""; narisi(true, fokus = OZNAKA_PO_MERI_DODAJ)
            }
            .setNegativeButton(R.string.scit_z_spusti_ne, null)
            .create()
        okno.show()
        si.safeer.tv.os.Kontroler.pokazi(okno)
    }

    private fun spusti(ime: String) {
        val app = applicationContext
        Thread({
            val ok = Scit.spusti(app, ime)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                napaka = if (ok) null else getString(R.string.scit_z_ni_spusceno)
                zadnjiOdtis = ""
                narisi(true, fokus = OZNAKA_IME + ime)
            }
        }, "safeer-scit-izjema").start()
    }

    private fun preklici(ime: String) {
        val app = applicationContext
        Thread({
            Scit.preklici(app, ime)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                zadnjiOdtis = ""
                narisi(true, fokus = OZNAKA_PREKLOP)
            }
        }, "safeer-scit-izjema").start()
    }

    /**
     * Sistemske nastavitve omrezja (zasebni DNS je tam). Izmerjeno 4. 11. 2026 na Philips Android TV 11:
     * WIRELESS_SETTINGS nima dejavnosti, WIFI_SETTINGS odpre org.droidtv.networkwizard, SETTINGS splosne nastavitve.
     */
    private fun odpriNastavitveOmrezja() {
        for (dejanje in listOf(Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS)) {
            try { startActivity(Intent(dejanje)); return } catch (_: Throwable) { }
        }
    }

    // ------------------------------------------------------------------ gradniki

    private fun barva(atribut: Int): Int {
        val v = TypedValue()
        theme.resolveAttribute(atribut, v, true)
        return v.data
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun besedilo(niz: String, velikost: Float, atribut: Int, krepko: Boolean = false) = TextView(this).apply {
        text = niz
        textSize = velikost
        setTextColor(barva(atribut))
        if (krepko) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun naslov(niz: String) = besedilo(niz, 18f, R.attr.osBesedilo, krepko = true).apply { setPadding(0, dp(18), 0, dp(8)) }

    private fun gumb(niz: String, oznaka: String, ob: () -> Unit) = besedilo(niz, 16f, R.attr.osBesedilo, krepko = true).apply {
        setBackgroundResource(R.drawable.os_tipka_velika)
        setPadding(dp(22), dp(10), dp(22), dp(10))
        isFocusable = true
        isClickable = true
        tag = oznaka
        contentDescription = niz
        setOnClickListener { ob() }
    }

    /** Vrstica seznama: vedno izbirna (daljinec mora priti do vsake), dejanje samo, kadar je [ob]. */
    private fun vrstica(glavno: String, opis: String, opisBarva: Int, oznaka: String, ob: (() -> Unit)?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.os_kartica)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        isFocusable = true
        isClickable = ob != null
        tag = oznaka
        contentDescription = if (opis.isEmpty()) glavno else "$glavno, $opis"
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        addView(besedilo(glavno, 15f, R.attr.osBesedilo).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (opis.isNotEmpty()) addView(besedilo(opis, 13f, opisBarva).apply { setPadding(dp(16), 0, 0, 0) })
        if (ob != null) setOnClickListener { ob() }
    }

    companion object {
        private const val TAG = "SafeerScit"
        const val EXTRA_IZKLOPI = "izklopi"
        /** Samo pokazi zaslon (brez samodejnega vklopa); Safeer OS › Scit › Podrobnosti, meni brskalnika. */
        const val EXTRA_POKAZI = "pokazi"
        private const val ZAHTEVA = 7312
        private const val OZNAKA_PREKLOP = "scit:preklop"
        private const val OZNAKA_IZKLOP = "scit:izklop"
        private const val OZNAKA_NASTAVITVE = "scit:nastavitve"
        private const val OZNAKA_IME = "scit:ime:"
        private const val OZNAKA_IZJEMA = "scit:izjema:"
        private const val OZNAKA_KLJUC = "scit:kljuc:abusech"
        private const val OZNAKA_PO_MERI = "scit:po-meri:"
        private const val OZNAKA_PO_MERI_DODAJ = "scit:po-meri:dodaj"
    }
}
