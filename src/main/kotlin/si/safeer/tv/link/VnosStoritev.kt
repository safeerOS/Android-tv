package si.safeer.tv.link

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Safeer Vnos: miska in tipkovnica z racunalnika (Safeer Control, Safeer OS na racunalniku) na tej
 * napravi - da je aplikacijo, ki jo naprava deli na racunalnik, tam mogoce upravljati kot namizni
 * program.
 *
 * Android drugi aplikaciji ne dovoli dotikati se zaslona; edina pot brez korenskega dostopa je
 * storitev dostopnosti, ki jo uporabnik enkrat sam vklopi v nastavitvah (Dostopnost -> Safeer Vnos).
 * Storitev ne posilja vsebine zaslona nikamor: samo izvede dotik, poteg, pomik ali tipko, ki jih
 * poslje seznanjena naprava v Safeer Linku (ukazi `input.*`, Daljinec).
 *
 * Prilagoditev je tu, na napravi, ker samo ona ve, kaksna je in kaj ima fokus: puscice v besedilnem
 * polju premikajo kazalec, drugje fokus (na televizorju kot krizec daljinca); Enter v polju potrdi
 * vnos, drugje klikne; kolesce na zaslonu na dotik pomakne vsebino brez zaleta, na televizorju
 * premakne fokus - aplikacije za televizor so narejene za krizec, ne za poteg.
 *
 * Koordinate pridejo kot delez zaslona (0..1), ker gledalec na racunalniku vidi pomanjsano sliko.
 */
class VnosStoritev : AccessibilityService() {

    companion object {
        private const val TAG = "SafeerVnos"
        /** Kolikšen del visine zaslona premakne en korak kolesca na zaslonu na dotik. */
        private const val KORAK_KOLESCA = 0.09
        private const val NAJVEC_KORAKOV = 8
        /** Meji iskanja fokusabilnih elementov pod Androidom 13 (globina drevesa, stevilo kandidatov). */
        private const val NAJVEC_GLOBINE = 40
        private const val NAJVEC_KANDIDATOV = 400

        @Volatile private var instanca: VnosStoritev? = null

        /** Ali je uporabnik Safeer Vnos vklopil (in ga sistem tece). */
        fun aktivna(): Boolean = instanca != null

        /**
         * Odpre dejavnost iz konteksta Safeer Vnosa (nacrt »dex-upravljanje« A2). Sistem storitev dostopnosti veze z
         * BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS (AOSP AccessibilityServiceConnection), zato Android zagona iz ozadja ne
         * zavrne - okno za soglasje se odpre takoj, brez tapa na obvestilo. False: Vnos ni vklopljen ali zagon ni uspel.
         */
        fun zazeni(namera: android.content.Intent): Boolean {
            val s = instanca ?: return false
            return try { s.startActivity(namera); true } catch (e: Throwable) {
                Log.w(TAG, "Zagona iz Safeer Vnosa ni bilo mogoce izvesti: ${e.javaClass.simpleName}"); false
            }
        }

        /**
         * Paket aktivnega okna - samo za preverbo, ali se je aplikacija po zagonu res odprla (nic ne gre z naprave).
         * Null: Vnos ni vklopljen ali okna ni mogoce prebrati.
         */
        fun paketNaVrhu(): String? =
            try { instanca?.rootInActiveWindow?.packageName?.toString() } catch (_: Throwable) { null }

        /** Dotik na delezu zaslona (0..1). */
        fun dotik(x: Double, y: Double, trajanjeMs: Long = 60): Boolean =
            instanca?.poteza(x, y, x, y, trajanjeMs) ?: false

        /** Poteg od (x1, y1) do (x2, y2), deleza zaslona. */
        fun poteg(x1: Double, y1: Double, x2: Double, y2: Double, trajanjeMs: Long = 300): Boolean =
            instanca?.poteza(x1, y1, x2, y2, trajanjeMs) ?: false

        /**
         * Tipka: sistemske (back, home, recents, notifications) in tipke tipkovnice (up, down, left,
         * right, enter, backspace, delete), prilagojene temu, kar ima na napravi fokus.
         */
        fun tipka(ime: String): Boolean {
            val s = instanca ?: return false
            val k = ime.lowercase()
            val globalna = when (k) {
                "back", "nazaj" -> GLOBAL_ACTION_BACK
                "home", "domov" -> GLOBAL_ACTION_HOME
                "recents", "nedavne" -> GLOBAL_ACTION_RECENTS
                "notifications", "obvestila" -> GLOBAL_ACTION_NOTIFICATIONS
                else -> null
            }
            if (globalna != null) return s.performGlobalAction(globalna)
            return try {
                when (k) {
                    "up", "down", "left", "right" -> s.smer(k)
                    "enter", "ok", "center" -> s.potrdi()
                    "backspace" -> s.brisi(naprej = false)
                    "delete" -> s.brisi(naprej = true)
                    else -> false
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Tipke $k ni bilo mogoce izvesti: ${e.message}"); false
            }
        }

        // ---- Opcijski touch-gamepad. Koordinate so lokalni, omejeni profil (ne pridejo iz omrezja).
        // Privzeti MOBA profil je namenoma generičen in ga bo mogoce kasneje umeriti v UI.
        private var lx = 0.0
        private var ly = 0.0
        private val gumbi = mapOf(
            "a" to (0.86 to 0.78), "b" to (0.78 to 0.84), "x" to (0.78 to 0.70), "y" to (0.87 to 0.66),
            "r1" to (0.93 to 0.55), "r2" to (0.91 to 0.88), "l1" to (0.67 to 0.55), "l2" to (0.66 to 0.88),
            "zacni" to (0.54 to 0.08), "izbira" to (0.46 to 0.08)
        )

        /** Imena z racunalnika (Linux evdev, angleska) -> imena telefonskega ploscka (slovenska). */
        private val sopomenke = mapOf(
            "start" to "zacni", "select" to "izbira",
            "left_x" to "leva_x", "left_y" to "leva_y", "right_x" to "desna_x", "right_y" to "desna_y",
            "dpad_x" to "krizec_x", "dpad_y" to "krizec_y"
        )
        private fun kanonsko(s: String) = s.lowercase().let { sopomenke[it] ?: it }

        fun igralniGumb(ime: String, dol: Boolean): Boolean {
            if (!dol) return true // dotik je kratek; release je idempotenten
            val p = gumbi[kanonsko(ime)] ?: return false
            return dotik(p.first, p.second, 55)
        }

        fun igralnaOs(os: String, vrednost: Double): Boolean {
            val v = vrednost.coerceIn(-1.0, 1.0)
            when (kanonsko(os)) {
                // krizec premika kot leva palica (profil nima locenih smernih tipk)
                "leva_x", "krizec_x" -> lx = v
                "leva_y", "krizec_y" -> ly = v
                // desna palica je kamera: kratek relativni poteg po desni polovici zaslona
                "desna_x" -> return if (kotlin.math.abs(v) < .08) true else poteg(.70, .48, (.70 + v*.12).coerceIn(.56,.84), .48, 70)
                "desna_y" -> return if (kotlin.math.abs(v) < .08) true else poteg(.70, .48, .70, (.48 + v*.12).coerceIn(.30,.66), 70)
                else -> return false
            }
            if (kotlin.math.abs(lx) < .08 && kotlin.math.abs(ly) < .08) return true
            // Gibalna palica: kratek ponovljiv poteg iz lokalnega centra. Naslednja izdaja lahko
            // uporabi nadaljevane multi-touch StrokeDescription za igre, ki zahtevajo stalen dotik.
            val cx=.18; val cy=.78
            return poteg(cx, cy, (cx + lx*.10).coerceIn(.06,.30), (cy + ly*.10).coerceIn(.64,.92), 90)
        }

        fun igralniSprosti(): Boolean { lx=0.0; ly=0.0; return true }

        /** Kolesce miske na delezu zaslona: [korakov] > 0 navzdol (vsebina gre gor), < 0 navzgor. */
        fun kolesce(x: Double, y: Double, korakov: Int): Boolean {
            val s = instanca ?: return false
            val n = korakov.coerceIn(-NAJVEC_KORAKOV, NAJVEC_KORAKOV)
            if (n == 0) return true
            return try { s.pomakni(x, y, n) } catch (e: Throwable) {
                Log.w(TAG, "Pomika ni bilo mogoce izvesti: ${e.message}"); false
            }
        }

        /** Besedilo v polje, ki ima fokus, na mesto kazalca (izbrano besedilo zamenja). */
        fun besedilo(niz: String): Boolean {
            val s = instanca ?: return false
            val polje = s.vnosnoPolje()?.takeIf { it.isEditable } ?: return false
            val (t, z, k) = s.stanjePolja(polje)
            return s.nastavi(polje, t.substring(0, z) + niz + t.substring(k), z + niz.length)
        }
    }

    private var fokusPogled: View? = null
    private var okenskiUpravitelj: android.view.WindowManager? = null
    private val glavnaNit = android.os.Handler(android.os.Looper.getMainLooper())
    private var fokusPrikazan = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanca = this
        Log.i(TAG, "Safeer Vnos je vklopljen.")
        glavnaNit.post { pripraviFokusOverlay() }
        if (jeTelevizor()) {
            miska = SistemskaMiska(this)
            tablica = TablicniPrikaz(this).also { it.obnovi() }
        }
        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        } catch (e: Throwable) {
            Log.w(TAG, "Nastavitev serviceInfo ni uspela: ${e.message}")
        }
    }

    /**
     * Neprilagojena aplikacija v ospredju (navaden zaganjalnik, brez TV zaganjalnika, ni sistemska); null drugje.
     * Lastnik 30. 10. 2026 (»oboje s preklopom«): privzeto fokus med gumbi (Gemini), drzan OK preklopi v misko in nazaj.
     */
    private var neprilagojena: String? = null
    /** Lezeca tablicna postavitev za neprilagojene aplikacije (lastnik 30. 10. 2026). */
    private var tablica: TablicniPrikaz? = null
    private val miskaVklopljena = HashSet<String>()
    private var okDolMs = 0L
    private var okPreklopil = false

    /** Daljinec kot miska v aplikacijah, ki niso narejene za TV (lastnik 30. 10. 2026). */
    private var miska: SistemskaMiska? = null
    private val miskaPaketi = HashMap<String, Boolean>()

    private val preveriMiskoOpravilo = Runnable { preveriMisko() }

    private fun preklopiMisko(paket: String) {
        val m = miska ?: return
        val vklop = paket !in miskaVklopljena
        if (vklop) { miskaVklopljena.add(paket); m.vklopi(paket); skrijFokusOverlay() }
        else { miskaVklopljena.remove(paket); m.izklopi() }
        try {
            android.widget.Toast.makeText(
                this,
                getString(if (vklop) si.safeer.tv.R.string.vnos_miska_vklop else si.safeer.tv.R.string.vnos_fokus_vklop),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (_: Throwable) { }
    }

    private fun preveriMisko() {
        val m = miska ?: return
        val koren = try { rootInActiveWindow } catch (_: Throwable) { null }
        val paket = koren?.packageName?.toString().orEmpty()
        // Tipkovnica in sistemska okna (obvestila, glasnost) ne spremenijo aplikacije v ospredju.
        if (paket.isEmpty() || paket == "com.android.systemui" || jeTipkovnicaOdprta()) return
        val vklop = miskaPaketi.getOrPut(paket) {
            val pm = packageManager
            val navaden = try { pm.getLaunchIntentForPackage(paket) != null } catch (_: Throwable) { false }
            val tv = try { pm.getLeanbackLaunchIntentForPackage(paket) != null } catch (_: Throwable) { false }
            val sistemska = try {
                (pm.getApplicationInfo(paket, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (_: Throwable) { true }
            si.safeer.tv.os.OsPravila.miskaZaPaket(true, paket, navaden, tv, sistemska)
        }
        neprilagojena = if (vklop) paket else null
        if (vklop) tablica?.vklopi() else tablica?.izklopi()
        if (vklop && paket in miskaVklopljena) m.vklopi(paket) else m.izklopi()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instanca = null
        miska?.izklopi()
        tablica?.izklopi()
        odstraniFokusOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instanca = null
        miska?.izklopi()
        tablica?.izklopi()
        odstraniFokusOverlay()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!jeTelevizor()) return
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                glavnaNit.removeCallbacks(posodobiFokusOpravilo)
                glavnaNit.postDelayed(posodobiFokusOpravilo, 30)
                if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
                    event.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED
                ) {
                    glavnaNit.removeCallbacks(preveriMiskoOpravilo)
                    glavnaNit.postDelayed(preveriMiskoOpravilo, 150)
                }
            }
        }
    }

    override fun onInterrupt() {}

    private val posodobiFokusOpravilo = Runnable {
        posodobiFokusOverlay()
    }

    private fun pripraviFokusOverlay() {
        if (fokusPogled != null || !jeTelevizor()) return
        try {
            okenskiUpravitelj = getSystemService(WINDOW_SERVICE) as? android.view.WindowManager
            val wm = okenskiUpravitelj ?: return
            val d = resources.displayMetrics.density

            val v = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(android.graphics.Color.parseColor("#222DD4BF"))
                    setStroke((3 * d).toInt(), android.graphics.Color.parseColor("#2DD4BF"))
                }
                visibility = View.GONE
            }

            val tipOkna = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val lp = android.view.WindowManager.LayoutParams(
                0, 0,
                tipOkna,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 0
                y = 0
            }

            wm.addView(v, lp)
            fokusPogled = v
        } catch (e: Throwable) {
            Log.w(TAG, "Fokus overlay-a ni bilo mogoce dodati: ${e.message}")
        }
    }

    private fun posodobiFokusOverlay() {
        val v = fokusPogled ?: return
        val wm = okenskiUpravitelj ?: return
        try {
            // Poišči fokusirano vozlišče v aktivnem oknu ali kateremkoli drugem interaktivnem oknu
            var vozlisce: AccessibilityNodeInfo? = null
            var korenPaket = ""

            val aktivniKoren = rootInActiveWindow
            if (aktivniKoren != null) {
                korenPaket = aktivniKoren.packageName?.toString().orEmpty()
                if (korenPaket != packageName && korenPaket != "com.aurora.store") {
                    vozlisce = fokusirano(aktivniKoren)
                    if (korenPaket == neprilagojena && miska?.paket == null && jePasivenVsebnik(vozlisce)) {
                        najdiInteraktivnoVozlisce(aktivniKoren)?.let { n ->
                            vozlisce = n
                            try { n.performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Throwable) { }
                        }
                    }
                }
            }

            if (vozlisce == null) {
                for (w in windows) {
                    if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                    val r = w.root ?: continue
                    val p = r.packageName?.toString().orEmpty()
                    if (p == packageName || p == "com.aurora.store") continue
                    val f = fokusirano(r)
                    if (f != null && f.isVisibleToUser) {
                        vozlisce = f
                        korenPaket = p
                        break
                    }
                }
            }

            // Aplikacije za TV (Leanback) imajo svoj fokus: nas okvir bi se podvojil in med drsenjem zamikal
            // (lastnik 30. 10. 2026, RTV 365: okvir med dvema plakatoma).
            if (vozlisce == null || !vozlisce.isVisibleToUser || korenPaket == packageName ||
                korenPaket == "com.aurora.store" || jeTvAplikacija(korenPaket) || miska?.paket == korenPaket) {
                skrijFokusOverlay()
                return
            }

            // Ne prekrivamo tipk na virtualni tipkovnici (IME ima lasten TV fokus, naš overlay ne sme motiti tipkanja)
            val vozliscePaket = vozlisce.packageName?.toString().orEmpty()
            if (vozliscePaket.contains("inputmethod") || vozliscePaket.contains("latin") || jeFokusVTipkovnici()) {
                skrijFokusOverlay()
                return
            }

            val r = Rect()
            vozlisce.getBoundsInScreen(r)
            var levo = r.left
            var desno = r.right
            val zgoraj = r.top
            val spodaj = r.bottom

            // Preveri smiselne dimenzije (da ne obrobimo celotnega zaslona ali praznih koordinat)
            val (zaslonSirina, zaslonVisina) = velikost()
            val celotnaSirina = desno - levo
            val celotnaVisina = spodaj - zgoraj
            if (celotnaSirina <= 10 || celotnaVisina <= 10 || (celotnaSirina >= zaslonSirina - 20 && celotnaVisina >= zaslonVisina - 20)) {
                skrijFokusOverlay()
                return
            }

            // Ce je vrstica/naslov cez celoten zaslon (npr. naslov kategorije v Aurora Store kot "Komunikacija >"):
            // ne obrobi celotne pasice cez vseh 1920 slikovnih pik, temvec le zacetni del z besedilom in ikono
            val d = resources.displayMetrics.density
            val radij = if (celotnaVisina <= (32 * d).toInt()) 8 * d else 14 * d
            (v.background as? android.graphics.drawable.GradientDrawable)?.cornerRadius = radij

            if (celotnaSirina >= zaslonSirina - 100) {
                // Poisci otroka z besedilom, ce obstaja, da dobimo lepo obrobo okoli napisa
                var otrokZBesedilom: AccessibilityNodeInfo? = null
                for (i in 0 until vozlisce.childCount) {
                    val ot = try { vozlisce.getChild(i) } catch (_: Throwable) { null } ?: continue
                    if (!ot.text.isNullOrEmpty()) {
                        otrokZBesedilom = ot
                        break
                    }
                }
                if (otrokZBesedilom != null) {
                    val rOt = Rect()
                    otrokZBesedilom.getBoundsInScreen(rOt)
                    levo = (rOt.left - (12 * d).toInt()).coerceAtLeast(0)
                    desno = (rOt.right + (32 * d).toInt()).coerceAtMost(zaslonSirina)
                } else {
                    desno = (levo + (320 * d).toInt()).coerceAtMost(zaslonSirina)
                }
            }

            val sirina = desno - levo
            val visina = spodaj - zgoraj

            val lp = v.layoutParams as? android.view.WindowManager.LayoutParams ?: return
            val odmik = (2 * d).toInt()

            lp.x = (levo - odmik).coerceAtLeast(0)
            lp.y = (zgoraj - odmik).coerceAtLeast(0)
            lp.width = sirina + (odmik * 2)
            lp.height = visina + (odmik * 2)

            wm.updateViewLayout(v, lp)
            if (v.visibility != View.VISIBLE) {
                v.visibility = View.VISIBLE
            }
            fokusPrikazan = true
        } catch (_: Throwable) {
            skrijFokusOverlay()
        }
    }

    private val tvAplikacije = HashMap<String, Boolean>()

    /** Ima paket zaganjalnik za TV (Leanback)? Taka aplikacija sama rise fokus. */
    private fun jeTvAplikacija(paket: String): Boolean = tvAplikacije.getOrPut(paket) {
        try { packageManager.getLeanbackLaunchIntentForPackage(paket) != null } catch (_: Throwable) { false }
    }

    private fun jePasivenVsebnik(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return true
        if (n.isClickable) return false
        val c = n.className?.toString().orEmpty()
        return n.childCount > 0 || c.contains("RecyclerView") || c.contains("ScrollView") ||
            c.contains("ViewGroup") || c.contains("Layout") || c.contains("ViewPager")
    }

    /** Vidni, omogoceni interaktivni elementi aktivnega okna (brez vsebnikov in celozaslonskih pogledov). */
    private fun zberiInteraktivne(n: AccessibilityNodeInfo, izid: MutableList<AccessibilityNodeInfo>, globina: Int) {
        if (globina > NAJVEC_GLOBINE || izid.size >= NAJVEC_KANDIDATOV) return
        val r = Rect()
        n.getBoundsInScreen(r)
        val (zaslonW, zaslonH) = velikost()
        val celozaslonski = zaslonW > 0 && zaslonH > 0 && r.width() >= zaslonW - 30 && r.height() >= zaslonH - 30
        if ((n.isFocusable || n.isClickable) && n.isVisibleToUser && n.isEnabled && !celozaslonski &&
            r.width() > 15 && r.height() > 15 && !jePasivenVsebnik(n)
        ) izid.add(n)
        for (i in 0 until n.childCount) {
            val otrok = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue
            zberiInteraktivne(otrok, izid, globina + 1)
        }
    }

    /**
     * Primarni gumb za zacetni fokus (Naprej, Nadaljuj, V redu ...). Pregled 30. 10. 2026: nikoli samodejno na
     * soglasja in namestitve (Allow, Accept, Install, Dovoli, Sprejmi, Namesti) - en OK ne sme dati dovoljenja.
     */
    private fun poisciPrimarniGumb(kandidati: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val kljucne = listOf(
            "next", "continue", "skip", "done", "finish", "start", "get started", "ok", "got it",
            "naprej", "nadaljuj", "preskoči", "končaj", "začni", "v redu", "razumem"
        )
        for (k in kandidati) {
            val txt = k.text?.toString()?.trim()?.lowercase().orEmpty()
            val opis = k.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            if (k.isClickable && kljucne.any { b -> txt == b || txt.startsWith("$b ") || opis == b }) return k
        }
        return null
    }

    private fun najdiInteraktivnoVozlisce(koren: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val kandidati = ArrayList<AccessibilityNodeInfo>()
        zberiInteraktivne(koren, kandidati, 0)
        if (kandidati.isEmpty()) return null
        return poisciPrimarniGumb(kandidati)
            ?: kandidati.firstOrNull { it.isClickable && it.isVisibleToUser && it.isEnabled }
            ?: kandidati.firstOrNull { it.isVisibleToUser && it.isEnabled }
    }

    /** Smerna tipka v neprilagojeni aplikaciji, kjer je fokus obtical na vsebniku: na najblizji interaktivni element. */
    private fun premakniMedInteraktivnimi(smer: VnosVklopPravila.Smer, od: AccessibilityNodeInfo?): Boolean {
        val koren = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return false
        val kandidati = ArrayList<AccessibilityNodeInfo>()
        zberiInteraktivne(koren, kandidati, 0)
        if (kandidati.isEmpty()) return false
        val okvirji = kandidati.map { okvir(it) }
        val i = if (od != null) VnosVklopPravila.najblizji(okvir(od), okvirji, smer) else VnosVklopPravila.prvi(okvirji)
        if (i < 0) return false
        val izbran = kandidati[i]
        val uspelo = izbran.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        try { izbran.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) } catch (_: Throwable) { }
        return uspelo || izbran.isAccessibilityFocused
    }

    /** OK v neprilagojeni aplikaciji (fokus nacin): ENO dejanje - klik elementa ali najblizjega klikljivega starsa, sicer dotik. */
    private fun aktivirajFokus() {
        val koren = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return
        var vozlisce = fokusirano(koren)
        if (jePasivenVsebnik(vozlisce)) vozlisce = najdiInteraktivnoVozlisce(koren) ?: vozlisce
        val n = vozlisce ?: return
        var cilj: AccessibilityNodeInfo? = n
        while (cilj != null && !cilj.isClickable) cilj = try { cilj.parent } catch (_: Throwable) { null }
        if (cilj != null && cilj.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect()
        n.getBoundsInScreen(r)
        val (zaslonW, zaslonH) = velikost()
        if (!r.isEmpty && zaslonW > 0 && zaslonH > 0) {
            val cx = r.centerX().toDouble() / zaslonW
            val cy = r.centerY().toDouble() / zaslonH
            if (cx in 0.0..1.0 && cy in 0.0..1.0) poteza(cx, cy, cx, cy, 60)
        }
    }

    private fun skrijFokusOverlay() {
        val v = fokusPogled ?: return
        if (v.visibility != View.GONE) {
            v.visibility = View.GONE
        }
        fokusPrikazan = false
    }

    private fun odstraniFokusOverlay() {
        try {
            fokusPogled?.let { okenskiUpravitelj?.removeView(it) }
        } catch (_: Throwable) {}
        fokusPogled = null
        okenskiUpravitelj = null
        fokusPrikazan = false
    }

    /** Tipka, za katero je dolg pritisk ze skocil na vrstico (do spusta ne naredi nicesar vec). */
    private var skocilaTipka = 0

    /**
     * Lastnik 27. 10. 2026: dolg pritisk dol/gor na daljincu skoci na spodnjo/zgornjo orodno vrstico aplikacije (npr. Aurora
     * Store, kamor kratki pritiski ne pridejo).
     * Nadgradnja 29. 10. 2026: ob iskanju (ENTER/SEARCH ali DOL v vnosnem polju) se virtualna tipkovnica samodejno skrije,
     * fokus pa se prestavi na rezultate pod iskalnim poljem (povsod, tudi v Aurora Store).
     */
    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        val k = event.keyCode
        if (!jeTelevizor()) return false
        // Neprilagojena aplikacija (ni za TV): OK in smerne tipke. Ob odprti tipkovnici tipke ostanejo tipkovnici.
        val nep = neprilagojena
        if (nep != null && !jeTipkovnicaOdprta()) {
            if (k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) { okDolMs = event.eventTime; okPreklopil = false }
                    else if (!okPreklopil && event.eventTime - okDolMs >= 600) {
                        okPreklopil = true
                        preklopiMisko(nep)
                    }
                } else if (!okPreklopil) {
                    val m = miska
                    if (m?.paket != null) m.klik() else glavnaNit.post { aktivirajFokus() }
                }
                return true
            }
            if (miska?.paket != null && miska?.tipka(event) == true) return true
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val smer = when (k) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> VnosVklopPravila.Smer.GOR
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> VnosVklopPravila.Smer.DOL
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> VnosVklopPravila.Smer.LEVO
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> VnosVklopPravila.Smer.DESNO
                    else -> null
                }
                if (smer != null) {
                    val koren = try { rootInActiveWindow } catch (_: Throwable) { null }
                    val f = fokusirano(koren)
                    if (jePasivenVsebnik(f) && premakniMedInteraktivnimi(smer, f?.takeIf { it.isClickable })) {
                        glavnaNit.removeCallbacks(posodobiFokusOpravilo)
                        glavnaNit.postDelayed(posodobiFokusOpravilo, 40)
                        return true
                    }
                }
            }
        }
        // Posodobi univerzalni Safeer OS fokus overlay ob premikanju s križcem
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (k) {
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_TAB -> {
                    glavnaNit.removeCallbacks(posodobiFokusOpravilo)
                    glavnaNit.postDelayed(posodobiFokusOpravilo, 20)
                    glavnaNit.postDelayed(posodobiFokusOpravilo, 70)
                    glavnaNit.postDelayed(posodobiFokusOpravilo, 150)
                    glavnaNit.postDelayed(posodobiFokusOpravilo, 250)
                    glavnaNit.postDelayed(posodobiFokusOpravilo, 380)
                }
            }
        }

        // 1. Ko uporabnik sproži iskanje (ENTER ali SEARCH): zapri tipkovnico
        if (k == android.view.KeyEvent.KEYCODE_SEARCH || k == android.view.KeyEvent.KEYCODE_ENTER) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                zapriTipkovnico()
            }
        }

        // 3. Dolg pritisk dol/gor za skok na orodno vrstico:
        if (k != android.view.KeyEvent.KEYCODE_DPAD_DOWN && k != android.view.KeyEvent.KEYCODE_DPAD_UP) return false
        if (event.action == android.view.KeyEvent.ACTION_UP) {
            val bila = skocilaTipka == k
            skocilaTipka = 0
            return bila
        }
        if (event.repeatCount == 0) return false
        if (skocilaTipka == k) return true
        if (!skociNaVrstico(k == android.view.KeyEvent.KEYCODE_DPAD_DOWN)) return false   // ni vrstice: navadno pomikanje
        skocilaTipka = k
        return true
    }

    private fun jeTipkovnicaOdprta(): Boolean {
        return try {
            windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Throwable) { false }
    }

    private fun jeFokusVTipkovnici(): Boolean {
        return try {
            val aktivno = windows.firstOrNull { it.isFocused }
            if (aktivno != null && aktivno.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                return true
            }
            val imeOkno = windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val imeKoren = imeOkno?.root
            imeKoren != null && (imeKoren.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null || imeKoren.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) != null)
        } catch (_: Throwable) { false }
    }

    private fun zapriTipkovnico() {
        try {
            // Brez sistemskega Nazaj: ce se tipkovnica ob ENTER zapre sama, bi Nazaj zaprl zaslon aplikacije.
            if (!jeTipkovnicaOdprta()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                softKeyboardController.showMode = SHOW_MODE_HIDDEN
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try { softKeyboardController.showMode = SHOW_MODE_AUTO } catch (_: Throwable) {}
                }, 400)
            }
        } catch (_: Throwable) {}
    }

    private fun skociNaVrstico(dol: Boolean): Boolean {
        val koren = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return false
        val kandidati = ArrayList<AccessibilityNodeInfo>()
        zberiFokusabilne(koren, kandidati, 0)
        val i = VnosVklopPravila.vrstica(kandidati.map { okvir(it) }, dol, velikost().second)
        return i >= 0 && try { kandidati[i].performAction(AccessibilityNodeInfo.ACTION_FOCUS) } catch (_: Throwable) { false }
    }

    // ------------------------------------------------------------------ fokus in besedilo

    private fun jeTelevizor(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private fun vnosnoPolje(): AccessibilityNodeInfo? =
        try { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null }

    /** Besedilo polja (brez namiga v praznem polju) in izbira; brez izbire je kazalec na koncu. */
    private fun stanjePolja(polje: AccessibilityNodeInfo): Triple<String, Int, Int> {
        val namig = Build.VERSION.SDK_INT >= 26 && polje.isShowingHintText
        val t = if (namig) "" else polje.text?.toString().orEmpty()
        var z = polje.textSelectionStart
        var k = polje.textSelectionEnd
        if (z < 0 || k < 0 || z > t.length || k > t.length) { z = t.length; k = t.length }
        return Triple(t, minOf(z, k), maxOf(z, k))
    }

    private fun nastavi(polje: AccessibilityNodeInfo, besedilo: String, kazalec: Int): Boolean {
        val ok = polje.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, besedilo)
        })
        if (ok) polje.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, kazalec)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, kazalec)
        })
        return ok
    }

    /** Vracalka (znak pred kazalcem) ali Delete (znak za njim); izbrano besedilo izbrise v celoti. */
    private fun brisi(naprej: Boolean): Boolean {
        val polje = vnosnoPolje()?.takeIf { it.isEditable } ?: return false
        val (t, z0, k0) = stanjePolja(polje)
        var z = z0
        var k = k0
        if (z == k) {
            if (naprej) { if (k >= t.length) return true; k += Character.charCount(t.codePointAt(k)) }
            else { if (z == 0) return true; z -= Character.charCount(t.codePointBefore(z)) }
        }
        return nastavi(polje, t.substring(0, z) + t.substring(k), z)
    }

    /**
     * Puscica: v besedilnem polju premakne kazalec (levo/desno za znak, gor/dol za vrstico); drugje
     * premakne fokus kot krizec daljinca. Android 13+ to zna sam, starejsi (Android TV 11) ne - tam
     * poiscemo naslednji element v smeri od tistega, ki ima fokus.
     */
    private fun smer(k: String): Boolean {
        val polje = vnosnoPolje()
        if (polje != null && polje.isEditable) {
            val (akcija, enota) = when (k) {
                "left" -> AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY to AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
                "right" -> AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY to AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
                "up" -> AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY to AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
                else -> AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY to AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
            }
            val ok = polje.performAction(akcija, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, enota)
                putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false)
            })
            // Enovrsticno polje gor/dol ne premakne: takrat gre fokus naprej, kot na televizorju.
            if (ok) return true
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return performGlobalAction(when (k) {
                "up" -> GLOBAL_ACTION_DPAD_UP; "down" -> GLOBAL_ACTION_DPAD_DOWN
                "left" -> GLOBAL_ACTION_DPAD_LEFT; else -> GLOBAL_ACTION_DPAD_RIGHT
            })
        }
        return premakniFokus(k, polje)
    }

    /**
     * Krizec pod Androidom 13 (lastnikov televizor: Android TV 11; GLOBAL_ACTION_DPAD_* je sele od API 33): fokus
     * premaknemo sami. Najprej focusSearch od elementa s fokusom (isto pravilo kot daljinec v aplikaciji), sicer najblizji
     * fokusabilni element v smeri (VnosVklopPravila.najblizji, po vzoru FocusFinder); brez fokusa prvi zgoraj levo.
     * Nazaj in Domov gresta prek performGlobalAction (vse razlicice). V tujih aplikacijah (Compose, WebView, igre)
     * NEPREVERJENO.
     */
    private fun premakniFokus(k: String, polje: AccessibilityNodeInfo?): Boolean {
        val smer = VnosVklopPravila.smer(k) ?: return false
        val koren = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return false
        val od = polje ?: fokusirano(koren)
        if (od != null) {
            val cilj = try {
                od.focusSearch(when (smer) {
                    VnosVklopPravila.Smer.GOR -> View.FOCUS_UP; VnosVklopPravila.Smer.DOL -> View.FOCUS_DOWN
                    VnosVklopPravila.Smer.LEVO -> View.FOCUS_LEFT; VnosVklopPravila.Smer.DESNO -> View.FOCUS_RIGHT
                })
            } catch (_: Throwable) { null }
            if (cilj != null && cilj != od && cilj.isVisibleToUser && cilj.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return true
        }
        val kandidati = ArrayList<AccessibilityNodeInfo>()
        zberiFokusabilne(koren, kandidati, 0)
        val okvirji = kandidati.map { okvir(it) }
        val i = if (od != null) VnosVklopPravila.najblizji(okvir(od), okvirji, smer) else VnosVklopPravila.prvi(okvirji)
        return i >= 0 && kandidati[i].performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    /** Element s fokusom: vnosni fokus (daljinec), sicer fokus dostopnosti. */
    private fun fokusirano(koren: AccessibilityNodeInfo?): AccessibilityNodeInfo? = try {
        koren?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: koren?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    } catch (_: Throwable) { null }

    private fun okvir(n: AccessibilityNodeInfo): VnosVklopPravila.Okvir {
        val r = Rect()
        n.getBoundsInScreen(r)
        return VnosVklopPravila.Okvir(r.left, r.top, r.right, r.bottom)
    }

    /** Vidni, omogoceni fokusabilni elementi aktivnega okna (meja globine in stevila: veliki seznami ne zavrejo vnosa). */
    private fun zberiFokusabilne(n: AccessibilityNodeInfo, izid: MutableList<AccessibilityNodeInfo>, globina: Int) {
        if (globina > NAJVEC_GLOBINE || izid.size >= NAJVEC_KANDIDATOV) return
        if (n.isFocusable && n.isVisibleToUser && n.isEnabled) izid.add(n)
        for (i in 0 until n.childCount) {
            val otrok = try { n.getChild(i) } catch (_: Throwable) { null } ?: continue
            zberiFokusabilne(otrok, izid, globina + 1)
        }
    }

    /** Enter: v besedilnem polju potrdi vnos (iskanje, poslji); drugje klikne, kar ima fokus. */
    private fun potrdi(): Boolean {
        val polje = vnosnoPolje()
        if (polje != null && polje.isEditable && Build.VERSION.SDK_INT >= 30 &&
            polje.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        if (Build.VERSION.SDK_INT >= 33) return performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
        // Pod Androidom 13 klikne element s fokusom (tudi, ce ni vnosno polje - kartica, gumb na televizorju); prednika
        // samo z istim okvirjem (VnosVklopPravila.ciljEnter) - ne vecjega vsebnika, ki ga uporabnik ni izbral.
        val veriga = ArrayList<AccessibilityNodeInfo>()
        var n = polje ?: fokusirano(try { rootInActiveWindow } catch (_: Throwable) { null })
        while (n != null && veriga.size < NAJVEC_GLOBINE) { veriga.add(n); n = try { n.parent } catch (_: Throwable) { null } }
        val i = VnosVklopPravila.ciljEnter(veriga.map { okvir(it) to it.isClickable })
        return i >= 0 && veriga[i].performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ------------------------------------------------------------------ kolesce

    /**
     * Na televizorju kolesce premika fokus gor in dol (aplikacije za televizor so narejene za krizec),
     * drugod pomakne vsebino pod miško za korak - s potegom, ki se na koncu ustavi, da se seznam ne
     * zalete dalec naprej, kot bi se ob hitrem potegu s prstom.
     */
    private fun pomakni(x: Double, y: Double, korakov: Int): Boolean {
        if (jeTelevizor()) {
            var ok = false
            repeat(kotlin.math.abs(korakov)) { ok = smer(if (korakov > 0) "down" else "up") || ok }
            return ok
        }
        val pot = (KORAK_KOLESCA * korakov).coerceIn(-0.45, 0.45)
        val y1 = y.coerceIn(0.05, 0.95)
        val y2 = (y1 - pot).coerceIn(0.02, 0.98)
        return poteza(x, y1, x, y2, 220L + 40L * kotlin.math.abs(korakov), zadrzi = true)
    }

    // ------------------------------------------------------------------ poteze

    private fun velikost(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            if (Build.VERSION.SDK_INT >= 30) {
                val b: Rect = wm.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val r = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(r)
                r.widthPixels to r.heightPixels
            }
        } catch (_: Throwable) { dm.widthPixels to dm.heightPixels }
    }

    /**
     * Poteza od (x1, y1) do (x2, y2). [zadrzi]: prst na koncu se malo obdrzi na mestu, da je hitrost ob
     * dvigu nic - seznam se premakne natanko za pot, brez zaleta.
     */
    private fun poteza(x1: Double, y1: Double, x2: Double, y2: Double, trajanjeMs: Long, zadrzi: Boolean = false): Boolean {
        val (sirina, visina) = velikost()
        fun px(d: Double, n: Int) = (d.coerceIn(0.0, 1.0) * (n - 1)).toFloat()
        val pot = Path().apply {
            moveTo(px(x1, sirina), px(y1, visina))
            lineTo(px(x2, sirina), px(y2, visina))
        }
        val cas = trajanjeMs.coerceIn(1, 3000)
        return try {
            if (!zadrzi) {
                dispatchGesture(GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(pot, 0, cas)).build(), null, null)
            } else {
                val prvi = GestureDescription.StrokeDescription(pot, 0, cas, true)
                val mirno = Path().apply { moveTo(px(x2, sirina), px(y2, visina)); lineTo(px(x2, sirina), px(y2, visina) + 0.5f) }
                val drugi = prvi.continueStroke(mirno, 0, 90, false)
                dispatchGesture(GestureDescription.Builder().addStroke(prvi).build(), object : GestureResultCallback() {
                    override fun onCompleted(opis: GestureDescription?) {
                        try { dispatchGesture(GestureDescription.Builder().addStroke(drugi).build(), null, null) } catch (_: Throwable) { }
                    }
                }, null)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Poteze ni bilo mogoce izvesti: ${e.message}")
            false
        }
    }
}
