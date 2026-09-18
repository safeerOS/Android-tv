package si.safeer.tv.os

import android.content.Context
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Spletne aplikacije Safeer OS: spletna stran, ki se obnasa kot aplikacija na televizorju.
 *
 * Zamisel je stara in dobra (Firefox OS, danes Capyloon): splet je platforma, aplikacija pa je
 * samo naslov z imenom in ikono. Nasa razlicica doda, kar je takrat manjkalo - ista zascita kot v
 * brskalniku (blokiranje oglasov in sledilcev, nevarne strani, Safeer Scit za ves televizor),
 * navigacijo z daljincem in Safeer Link do drugih naprav.
 *
 * Ime in ikono vzamemo iz manifesta spletne aplikacije (`<link rel="manifest">`, W3C Web App
 * Manifest) - natanko tistega, s katerim se strani predstavljajo kot aplikacije; ce ga ni,
 * poskusimo apple-touch-icon in favicon, sicer narisemo crko. Ikone shranimo na televizor, da
 * domaci zaslon dela tudi brez omrezja.
 */
object SpletneAplikacije {
    private const val TAG = "SafeerOsSpletne"
    private const val PREFS = "safeer_os"
    private const val KLJUC = "spletne_aplikacije"
    private const val KLJUC_PREVZETO = "spletne_prevzete"
    private const val KLJUC_IKONE = "spletne_ikone_ostro4"
    private const val NAJVEC = 24

    data class Aplikacija(
        val url: String,
        val ime: String,
        val ikona: String = "",
        val barva: String = "",
        /** Naslov ikone na spletu (za kartico na domacem zaslonu televizorja). */
        val ikonaUrl: String = "",
        /** Kje je uporabnik nazadnje bil v tej aplikaciji (naslov znotraj nje). */
        val zadnji: String = "",
        /** Kdaj je bil tam; po [OKNO_MS] se aplikacija spet odpre na svoji zacetni strani. */
        val zadnjiCas: Long = 0L,
    )

    /** Koliko casa velja »nadaljuj, kjer si koncal«: pol ure je ravno prav za prekinjen ogled. */
    private const val OKNO_MS = 30 * 60 * 1000L

    private val ozadje = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-os-spletne").also { it.isDaemon = true } }
    private val glavna = Handler(Looper.getMainLooper())

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun seznam(c: Context): List<Aplikacija> {
        val surovo = prefs(c).getString(KLJUC, null) ?: return emptyList()
        return try {
            val polje = JSONArray(surovo)
            (0 until polje.length()).mapNotNull { i ->
                val o = polje.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url"); if (url.isBlank()) null
                else Aplikacija(url, o.optString("ime"), o.optString("ikona"), o.optString("barva"),
                    o.optString("ikona_url"), o.optString("zadnji"), o.optLong("zadnji_cas"))
            }
        } catch (e: Throwable) { Log.w(TAG, "Seznama ni bilo mogoce prebrati: ${e.message}"); emptyList() }
    }

    private fun shrani(c: Context, seznam: List<Aplikacija>) {
        val polje = JSONArray()
        for (a in seznam.take(NAJVEC)) {
            polje.put(JSONObject().put("url", a.url).put("ime", a.ime).put("ikona", a.ikona).put("barva", a.barva)
                .put("ikona_url", a.ikonaUrl).put("zadnji", a.zadnji).put("zadnji_cas", a.zadnjiCas))
        }
        prefs(c).edit().putString(KLJUC, polje.toString()).apply()
    }

    /**
     * Seznam vodi tisti, ki ima domaci zaslon: ce je Safeer OS namescen kot svoja aplikacija, gre
     * vsako vprasanje in vsaka sprememba k njemu (ponudnik z dovoljenjem istega podpisa). Sicer
     * jih vodi ta aplikacija sama.
     */
    fun jeDodana(c: Context, url: String): Boolean {
        SpletnePonudnik.klic(c, SpletnePonudnik.JE_DODANA, url)?.let { return it.getBoolean("je") }
        return seznam(c).any { istaStran(it.url, url) }
    }

    /**
     * Kje naj se aplikacija odpre. Ce je uporabnik v zadnji pol ure gledal kaj znotraj nje, se vrne
     * tja (tako delajo aplikacije: nadaljujes, kjer si koncal); pozneje se odpre na svoji zacetni
     * strani, da ne obticis na vceraj odprti podstrani. Nadaljuje samo znotraj istega gostitelja.
     */
    fun nadaljevanje(c: Context, url: String): String {
        SpletnePonudnik.klic(c, SpletnePonudnik.NADALJEVANJE, url)?.getString("url")?.let { return it }
        val a = seznam(c).firstOrNull { istaStran(it.url, url) } ?: return url
        if (a.zadnji.isBlank() || System.currentTimeMillis() - a.zadnjiCas > OKNO_MS) return url
        return if (gostitelj(a.zadnji) == gostitelj(url)) a.zadnji else url
    }

    /** Zapomni si, kje je uporabnik koncal (ob izhodu iz aplikacije ali ob odhodu z zaslona). */
    fun zapomniMesto(c: Context, url: String, zadnji: String) {
        if (url.isBlank()) return
        if (SpletnePonudnik.klic(c, SpletnePonudnik.ZAPOMNI, url, Bundle().apply { putString("zadnji", zadnji) }) != null) return
        val app = c.applicationContext
        val seznam = seznam(app)
        if (seznam.none { istaStran(it.url, url) }) return
        val cist = if (zadnji.isBlank() || gostitelj(zadnji) != gostitelj(url)) "" else zadnji
        shrani(app, seznam.map {
            if (istaStran(it.url, url)) it.copy(zadnji = cist, zadnjiCas = if (cist.isBlank()) 0L else System.currentTimeMillis()) else it
        })
    }

    /**
     * Doda spletno aplikacijo takoj (z zacasnim imenom) in v ozadju poisce njeno pravo ime in
     * ikono; [obKoncu] poklicemo na glavni niti, ko so podatki tu, da se domaci zaslon osvezi.
     */
    fun dodaj(c: Context, url: String, ime: String, obKoncu: (() -> Unit)? = null) {
        if (SpletnePonudnik.klic(c, SpletnePonudnik.DODAJ, url, Bundle().apply { putString("ime", ime) }) != null) {
            obKoncu?.invoke(); return
        }
        val app = c.applicationContext
        if (jeDodana(app, url)) { obKoncu?.invoke(); return }
        shrani(app, seznam(app) + Aplikacija(url, ime))
        obKoncu?.invoke()
        ozadje.execute {
            val podatki = try { preberiManifest(app, url) } catch (e: Throwable) { Log.w(TAG, "Manifest: ${e.message}"); null }
            if (podatki != null) {
                val posodobljene = seznam(app).map { if (istaStran(it.url, url)) it.copy(ime = podatki.ime.ifBlank { it.ime }, ikona = podatki.ikona, barva = podatki.barva, ikonaUrl = podatki.ikonaUrl) else it }
                shrani(app, posodobljene)
                glavna.post { obKoncu?.invoke() }
            }
        }
    }

    /** Seznam v JSON (samo naslov in ime) - za prenos med aplikacijama. */
    fun jsonSeznam(c: Context): String {
        val polje = JSONArray()
        for (a in seznam(c)) polje.put(JSONObject().put("url", a.url).put("ime", a.ime))
        return polje.toString()
    }

    /**
     * Prva pot iz brskalnika v Safeer OS: dokler sta bila eno, so spletne aplikacije zivele v
     * brskalniku. Ko se Safeer OS prvic zazene, jih prevzame - uporabniku ni treba znova dodajati
     * svojega domacega zaslona. Naredi se enkrat; ikone se poiscejo znova (poti v tujo aplikacijo
     * ne moremo brati).
     */
    fun prevzemiOdBrskalnika(c: Context, obKoncu: (() -> Unit)? = null) {
        val app = c.applicationContext
        if (prefs(app).getBoolean(KLJUC_PREVZETO, false)) return
        val paket = Sosed.brskalnik(app)
        if (paket == null) { prefs(app).edit().putBoolean(KLJUC_PREVZETO, true).apply(); return }
        val b = try {
            app.contentResolver.call(SpletnePonudnik.naslov(paket), SpletnePonudnik.SEZNAM, "", null)
        } catch (e: Throwable) { Log.w(TAG, "Brskalnik ni dal seznama: ${e.message}"); null }
        prefs(app).edit().putBoolean(KLJUC_PREVZETO, true).apply()
        val surovo = b?.getString("seznam") ?: return
        val polje = try { JSONArray(surovo) } catch (_: Throwable) { return }
        var dodanih = 0
        for (i in 0 until polje.length()) {
            val o = polje.optJSONObject(i) ?: continue
            val url = o.optString("url"); if (url.isBlank() || jeDodana(app, url)) continue
            dodaj(app, url, o.optString("ime")) { obKoncu?.invoke() }
            dodanih++
        }
        if (dodanih > 0) Log.i(TAG, "Iz brskalnika prevzeto $dodanih spletnih aplikacij.")
    }

    /**
     * Uporabnik razvrsca sam: aplikacijo premakne za [zamik] mest (-1 levo, +1 desno). Vrne true,
     * ce se je vrstni red res spremenil (na robu vrste se ne).
     */
    fun premakni(c: Context, url: String, zamik: Int): Boolean {
        SpletnePonudnik.klic(c, SpletnePonudnik.PREMAKNI, url, Bundle().apply { putInt("zamik", zamik) })
            ?.let { return it.getBoolean("je") }
        val app = c.applicationContext
        val s = seznam(app).toMutableList()
        val i = s.indexOfFirst { istaStran(it.url, url) }
        if (i < 0) return false
        val j = i + zamik
        if (j < 0 || j >= s.size) return false
        s.add(j, s.removeAt(i))
        shrani(app, s)
        return true
    }

    /**
     * Ikone, shranjene s prejsnjo razlicico, so bile omejene na 256 px in so na televizorju videti
     * zamegljene. Enkrat jih poiscemo znova - samo tiste, ki so premajhne ali jih ni.
     */
    fun osveziIkone(c: Context, obKoncu: (() -> Unit)? = null) {
        val app = c.applicationContext
        if (prefs(app).getBoolean(KLJUC_IKONE, false)) return
        prefs(app).edit().putBoolean(KLJUC_IKONE, true).apply()
        ozadje.execute {
            var spremenjenih = 0
            for (a in seznam(app)) {
                // Merimo logotip, ne datoteke: ikona je lahko velika slika z drobnim logotipom
                // sredi belega roba (YouTube). Sele porezan rob pove, koliko logotipa res imamo.
                val vsebina = try {
                    if (a.ikona.isEmpty()) 0 else {
                        val slika = BitmapFactory.decodeFile(a.ikona)
                        if (slika == null) 0 else {
                            val porezana = poreziRob(brezBelega(poreziRob(slika)))
                            minOf(porezana.width, porezana.height)
                        }
                    }
                } catch (_: Throwable) { 0 }
                if (vsebina >= DOVOLJ_OSTRA) continue
                val podatki = try { preberiManifest(app, a.url) } catch (e: Throwable) { Log.w(TAG, "Ikona: ${e.message}"); null } ?: continue
                if (podatki.ikona.isEmpty()) continue
                shrani(app, seznam(app).map {
                    if (istaStran(it.url, a.url)) it.copy(ikona = podatki.ikona, ikonaUrl = podatki.ikonaUrl) else it
                })
                spremenjenih++
            }
            if (spremenjenih > 0) {
                Log.i(TAG, "Osvezenih ikon: $spremenjenih")
                glavna.post { obKoncu?.invoke() }
            }
        }
    }

    fun odstrani(c: Context, url: String) {
        if (SpletnePonudnik.klic(c, SpletnePonudnik.ODSTRANI, url) != null) return
        val app = c.applicationContext
        seznam(app).firstOrNull { istaStran(it.url, url) }?.let { a ->
            if (a.ikona.isNotEmpty()) try { File(a.ikona).delete() } catch (_: Throwable) { }
        }
        shrani(app, seznam(app).filterNot { istaStran(it.url, url) })
    }

    /**
     * Ikona aplikacije: shranjena slika ali narisana crka v barvi strani.
     *
     * Ikone s spleta so razlicne - ena je poln rdec kvadrat (YouTube), druga logotip na prozornem
     * ozadju, tretja siroka slika. Zato jih vse oblikujemo enako, kot to dela Android z ikonami
     * aplikacij: poln kvadrat vrezemo v zaobljen kvadrat, logotip pa polozimo na zaobljeno plosco
     * in mu pustimo zrak naokoli. Vrsta na domacem zaslonu je tako urejena, ne glede na to, kaj
     * nam je stran dala.
     */
    fun ikona(c: Context, a: Aplikacija): Drawable {
        if (a.ikona.isNotEmpty()) {
            // Oblikovanje ni poceni (porezan rob, odstranjena belina, pomanjsanje), domaci zaslon
            // pa se izrise ob vsaki spremembi naprav. Zato si gotovo ikono zapomnimo; kljuc nosi
            // cas datoteke, da se osvezena ikona takoj pozna.
            val kljuc = a.ikona + ":" + (try { File(a.ikona).lastModified() } catch (_: Throwable) { 0L })
            oblikovane[kljuc]?.let { return BitmapDrawable(c.resources, it) }
            try {
                val b = BitmapFactory.decodeFile(a.ikona)
                if (b != null) {
                    val gotova = oblikuj(c, b)
                    if (oblikovane.size > 32) oblikovane.clear()
                    oblikovane[kljuc] = gotova
                    return BitmapDrawable(c.resources, gotova)
                }
            } catch (e: Throwable) { Log.w(TAG, "Ikone ni bilo mogoce oblikovati: ${e.message}") }
        }
        return crkaDrawable(c, a.ime.ifBlank { gostitelj(a.url) }, a.barva)
    }

    /** Ze oblikovane ikone; kljuc je pot datoteke in njen cas spremembe. */
    private val oblikovane = HashMap<String, Bitmap>()

    /**
     * Ista obdelava za ikono, ki ne pride s spleta (na primer ikona programa racunalnika iz
     * `apps.list`): vrne zaobljeno ploscico enake velikosti kot pri spletnih aplikacijah.
     */
    fun ikonaIzSlike(c: Context, slika: Bitmap): Drawable = BitmapDrawable(c.resources, oblikuj(c, slika))

    /** Kolikokrat najvec smemo povecati sliko, preden postane mehka. */
    private const val NAJVEC_POVECAVA = 2.2f

    /** Stranica oblikovane ikone v pikah (kartica ji da 92dp visine). */
    private fun stranica(c: Context): Int = (c.resources.displayMetrics.density * 92).toInt().coerceAtLeast(92)

    /**
     * Poreze prazen rob okrog logotipa: veliko strani da ikono z debelim belim (ali prozornim)
     * robom v sami sliki. Ce takega roba ne odrezemo, logotip na nasi ploscici obvisi majhen
     * sredi prazne ploskve - prav to se je videlo pri YouTubu. Rezemo samo enakomeren rob;
     * kadar ni kaj rezati, vrnemo izvirnik.
     */
    private fun poreziRob(vir: Bitmap): Bitmap {
        val s = vir.width
        val v = vir.height
        if (s < 8 || v < 8) return vir
        val piksli = try { IntArray(s * v).also { vir.getPixels(it, 0, s, 0, 0, s, v) } } catch (_: Throwable) { return vir }
        // Barva roba: povprecje stirih vogalov. Ce se vogali med seboj razlikujejo, roba ni.
        val vogali = intArrayOf(piksli[0], piksli[s - 1], piksli[(v - 1) * s], piksli[v * s - 1])
        val prozorni = vogali.all { Color.alpha(it) < 24 }
        if (!prozorni) {
            for (i in 1 until 4) if (razlika(vogali[0], vogali[i]) > 24) return vir
        }
        val rob = vogali[0]
        fun jeRob(p: Int): Boolean =
            if (prozorni) Color.alpha(p) < 24 else (Color.alpha(p) > 24 && razlika(p, rob) <= 24)
        var levo = 0; var desno = s - 1; var zgoraj = 0; var spodaj = v - 1
        while (levo < desno && (0 until v).all { jeRob(piksli[it * s + levo]) }) levo++
        while (desno > levo && (0 until v).all { jeRob(piksli[it * s + desno]) }) desno--
        while (zgoraj < spodaj && (0 until s).all { jeRob(piksli[zgoraj * s + it]) }) zgoraj++
        while (spodaj > zgoraj && (0 until s).all { jeRob(piksli[spodaj * s + it]) }) spodaj--
        val sirina = desno - levo + 1
        val visina = spodaj - zgoraj + 1
        if (sirina < 8 || visina < 8) return vir
        // Manj kot desetino nima smisla rezati; vec kot to pa logotipu res vrne velikost.
        if (sirina > s * 0.92f && visina > v * 0.92f) return vir
        return try { Bitmap.createBitmap(vir, levo, zgoraj, sirina, visina) } catch (_: Throwable) { vir }
    }

    /** Groba razdalja med barvama (0-255), dovolj za prepoznavanje enakomernega roba. */
    private fun razlika(a: Int, b: Int): Int = maxOf(
        Math.abs(Color.red(a) - Color.red(b)),
        Math.abs(Color.green(a) - Color.green(b)),
        Math.abs(Color.blue(a) - Color.blue(b)))

    /** Iz poljubne slike naredi ikono aplikacije: zaobljen kvadrat, vedno enako velik. */
    private fun oblikuj(c: Context, izvirnik: Bitmap): Bitmap {
        val vir = poreziRob(brezBelega(poreziRob(izvirnik)))
        val s = stranica(c)
        val r = s * 0.22f
        val maska = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; isFilterBitmap = true; isDither = true }
        val risba = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true; isDither = true }
        // Sirok logotip brez plosce (YouTube) naj zasede vso sirino ploscice: kadar ga vrnemo v
        // kvadratu, ga kartica pomanjsa na visino in je videti drobcen. Zato takrat vrnemo sliko v
        // razmerju logotipa - le ce je dovolj velika, da je povecava ne zmehca.
        val siroko = !polnaSlika(vir) && maxOf(vir.width, vir.height) >= s * 0.6f &&
            svetlostLogotipa(vir) >= 0.10f
        if (siroko) {
            val merilo = s / maxOf(vir.width, vir.height).toFloat()
            val sir = maxOf(1, (vir.width * merilo).toInt())
            val vis = maxOf(1, (vir.height * merilo).toInt())
            return try { Bitmap.createScaledBitmap(vir, sir, vis, true) } catch (_: Throwable) { vir }
        }
        val izhod = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val platno = Canvas(izhod)
        val cel = RectF(0f, 0f, s.toFloat(), s.toFloat())
        if (polnaSlika(vir)) {
            // Ikona sama je ze plosca (poln kvadrat): samo zaoblimo robove, nic ne dodajamo.
            // Drobne ikone (favicon) ne raztegujemo cez ves kvadrat - raztegnjen favicon je prav
            // tisto zamegljeno, kar je uporabnik videl; raje jo pustimo manjso in ostro.
            val najvec = minOf(vir.width, vir.height) * NAJVEC_POVECAVA
            if (najvec >= s) {
                platno.drawRoundRect(cel, r, r, maska)
                maska.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                platno.drawBitmap(vir, null, cel, maska)
            } else {
                val merilo = najvec / maxOf(vir.width, vir.height)
                platno.drawBitmap(vir, null, sredina(s, vir, merilo), risba)
            }
        } else {
            // Logotip na prozornem: brez plosce - na domacem zaslonu naj lebdi kot ikona na
            // telefonu. Ploscico dobi samo logotip, ki je pretemen za temno ozadje; mocna barva
            // (rdeca YouTubova) je na crnini dovolj vidna in plosce ne potrebuje.
            val svetlost = svetlostLogotipa(vir)
            val rob: Float
            if (svetlost < 0.10f) {
                val ozadje = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = plosca(vir) }
                platno.drawRoundRect(cel, r, r, ozadje)
                rob = s * 0.16f
            } else {
                rob = s * 0.05f
            }
            val prostor = s - 2 * rob
            // Logotipa ne povecujemo cez mero: majhen, a oster je boljsi od velikega in mehkega.
            // Cisto drobcen pa tudi ne sme ostati - takrat ga raje malo povecamo, da ga je sploh
            // videti; prava resitev (vecja ikona s strani) je zgoraj, pri iskanju kandidatov.
            val najvecjaStran = maxOf(vir.width, vir.height).toFloat()
            val merilo = minOf(prostor / vir.width, prostor / vir.height,
                maxOf(NAJVEC_POVECAVA, s * 0.62f / najvecjaStran))
            platno.drawBitmap(vir, null, sredina(s, vir, merilo), risba)
        }
        return izhod
    }

    /** Pravokotnik na sredini kvadrata stranice [s] za sliko [vir] v merilu [merilo]. */
    private fun sredina(s: Int, vir: Bitmap, merilo: Float): RectF {
        val sir = vir.width * merilo
        val vis = vir.height * merilo
        val levo = (s - sir) / 2f
        val zgoraj = (s - vis) / 2f
        return RectF(levo, zgoraj, levo + sir, zgoraj + vis)
    }

    /**
     * Belo ozadje ikone postane prozorno. Mnogo strani in aplikacij da logotip na belo ploscico;
     * na temnem domacem zaslonu je taka ploscica svetel kvadrat, ki krici bolj kot logotip sam.
     * Pobrisemo samo belino, ki se drzi roba slike - beline znotraj logotipa se ne dotaknemo.
     */
    private fun brezBelega(vir: Bitmap): Bitmap {
        val s = vir.width
        val v = vir.height
        if (s < 8 || v < 8 || s.toLong() * v > 1_200_000L) return vir
        val piksli = try { IntArray(s * v).also { vir.getPixels(it, 0, s, 0, 0, s, v) } } catch (_: Throwable) { return vir }
        fun jeBelo(p: Int): Boolean {
            if (Color.alpha(p) < 24) return true
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            return r > 232 && g > 232 && b > 232 && maxOf(r, g, b) - minOf(r, g, b) < 14
        }
        // Zacnemo pri robu; ce rob ni bel, ni kaj brisati.
        val sklad = ArrayDeque<Int>()
        val obiskano = BooleanArray(s * v)
        fun dodaj(i: Int) { if (!obiskano[i] && jeBelo(piksli[i])) { obiskano[i] = true; sklad.addLast(i) } }
        for (x in 0 until s) { dodaj(x); dodaj((v - 1) * s + x) }
        for (y in 0 until v) { dodaj(y * s); dodaj(y * s + s - 1) }
        if (sklad.isEmpty()) return vir
        var pobrisanih = 0
        while (sklad.isNotEmpty()) {
            val i = sklad.removeLast()
            piksli[i] = 0
            pobrisanih++
            val x = i % s
            val y = i / s
            if (x > 0) dodaj(i - 1)
            if (x < s - 1) dodaj(i + 1)
            if (y > 0) dodaj(i - s)
            if (y < v - 1) dodaj(i + s)
        }
        // Ce je od slike ostalo premalo, je bila najbrz vsa bela: pustimo izvirnik pri miru.
        if (pobrisanih > s * v * 0.97f) return vir
        return try {
            Bitmap.createBitmap(s, v, Bitmap.Config.ARGB_8888).also { it.setPixels(piksli, 0, s, 0, 0, s, v) }
        } catch (_: Throwable) { vir }
    }

    /** Povprecna svetlost neprozornih pik (0-1); pove, ali bi se logotip zlil s temnim ozadjem. */
    private fun svetlostLogotipa(b: Bitmap): Float {
        val korak = maxOf(1, minOf(b.width, b.height) / 48)
        var vsota = 0.0
        var stevec = 0
        var x = 0
        while (x < b.width) {
            var y = 0
            while (y < b.height) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) > 128) {
                    vsota += (0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)) / 255.0
                    stevec++
                }
                y += korak
            }
            x += korak
        }
        if (stevec == 0) return 1f
        return (vsota / stevec).toFloat()
    }

    /**
     * Barva plosce pod logotipom. Barve strani (theme_color) namenoma ne uporabimo: pogosto je
     * ista kot logotip in logotip izgine. Namesto tega pogledamo, kako svetel je logotip - temen
     * dobi svetlo plosco, svetel temno. Tako je ikona berljiva, kar nam je stran poslala.
     */
    private fun plosca(vir: Bitmap): Int =
        if (svetlost(vir) < 0.55f) Color.parseColor("#F3F5F7") else Color.parseColor("#152129")

    /** Povprecna svetlost neprozornih pik (0 crno, 1 belo); vzorcimo, ne beremo vsake pike. */
    private fun svetlost(b: Bitmap): Float {
        var vsota = 0.0
        var stevec = 0
        val korak = maxOf(1, minOf(b.width, b.height) / 16)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) > 128) {
                    vsota += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)) / 255.0
                    stevec++
                }
                x += korak
            }
            y += korak
        }
        return if (stevec == 0) 1f else (vsota / stevec).toFloat()
    }

    /**
     * Ali slika ze pokriva ves kvadrat (kot ikona YouTuba): priblizno kvadratna in neprozorna po
     * robovih. Takrat je ne polagamo na plosco, ampak ji samo zaoblimo vogale.
     */
    private fun polnaSlika(b: Bitmap): Boolean {
        val razmerje = b.width.toFloat() / b.height.toFloat()
        if (razmerje < 0.9f || razmerje > 1.1f) return false
        if (!b.hasAlpha()) return true
        val x = b.width - 1
        val y = b.height - 1
        val tocke = listOf(
            2 to 2, x - 2 to 2, 2 to y - 2, x - 2 to y - 2,
            b.width / 2 to 2, b.width / 2 to y - 2, 2 to b.height / 2, x - 2 to b.height / 2,
        )
        var neprozornih = 0
        for ((tx, ty) in tocke) {
            if (tx < 0 || ty < 0) continue
            if (Color.alpha(b.getPixel(tx, ty)) > 200) neprozornih++
        }
        return neprozornih >= 7
    }

    private fun crkaDrawable(c: Context, ime: String, barva: String): Drawable {
        val velikost = (c.resources.displayMetrics.density * 56).toInt().coerceAtLeast(56)
        val b = Bitmap.createBitmap(velikost, velikost, Bitmap.Config.ARGB_8888)
        val platno = Canvas(b)
        val ozadjeBarva = try { if (barva.isNotBlank()) Color.parseColor(barva) else Color.parseColor("#152129") } catch (_: Throwable) { Color.parseColor("#152129") }
        val c1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ozadjeBarva }
        platno.drawRoundRect(RectF(0f, 0f, velikost.toFloat(), velikost.toFloat()), velikost * 0.22f, velikost * 0.22f, c1)
        val crka = ime.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val c2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#57D6AD")
            textSize = velikost * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val y = velikost / 2f - (c2.descent() + c2.ascent()) / 2f
        platno.drawText(crka, velikost / 2f, y, c2)
        return BitmapDrawable(c.resources, b)
    }

    fun gostitelj(url: String): String = try { URL(url).host.removePrefix("www.") } catch (_: Throwable) { url }

    private fun istaStran(a: String, b: String): Boolean = a.trimEnd('/') == b.trimEnd('/')

    // ------------------------------------------------------------------ manifest spletne aplikacije

    private class Podatki(val ime: String, val ikona: String, val barva: String, val ikonaUrl: String)

    private fun odjemalec(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    /**
     * Prebere `<link rel="manifest">` strani in iz manifesta vzame ime, barvo in najvecjo ikono.
     * Brez manifesta poskusi apple-touch-icon in /favicon.ico. Vse gre prek navadnega HTTPS z
     * naslova same strani; nikamor drugam.
     */
    private fun preberiManifest(c: Context, url: String): Podatki? {
        val k = odjemalec()
        val html = try {
            k.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { o ->
                if (!o.isSuccessful) return null
                besedilo(o, NAJVEC_HTML)
            }
        } catch (e: Throwable) { Log.w(TAG, "Strani ni bilo mogoce prebrati: ${e.message}"); return null }

        var ime = Regex("<title[^>]*>([^<]{1,80})", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim().orEmpty()
        var barva = Regex("""<meta[^>]+name=["']theme-color["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()

        // Ikono iscemo med vsemi, ki jih stran ponuja, in vzamemo najvecjo: na televizorju je
        // kartica velika in 32-pikslovni favicon je na njej takoj videti zamegljen (prav to je
        // bilo videti pri YouTubu). Kandidate uredimo po napovedani velikosti in jih prenesemo,
        // dokler ne dobimo dovolj ostre.
        val kandidati = ArrayList<Pair<String, Int>>()

        val manifestPot = Regex("""<link[^>]+rel=["'][^"']*manifest[^"']*["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<link[^>]+href=["']([^"']+)["'][^>]+rel=["'][^"']*manifest[^"']*["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
        // Kadar stran manifesta ne navede (YouTube ga na /tv ne), poskusimo obicajni poti:
        // veliko strani ima manifest tam, v njem pa ikone 192 in 512 px.
        val manifestPoti = listOfNotNull(manifestPot, "/manifest.json", "/site.webmanifest")
        for (mp in manifestPoti) {
            if (kandidati.isNotEmpty()) break
            val manifestUrl = razresi(url, mp)
            try {
                k.newCall(Request.Builder().url(manifestUrl).header("User-Agent", UA).build()).execute().use { o ->
                    if (o.isSuccessful) {
                        val m = JSONObject(besedilo(o, NAJVEC_MANIFEST))
                        val mIme = m.optString("short_name").ifBlank { m.optString("name") }
                        if (mIme.isNotBlank()) ime = mIme
                        val mBarva = m.optString("theme_color").ifBlank { m.optString("background_color") }
                        if (mBarva.isNotBlank()) barva = mBarva
                        val ikone = m.optJSONArray("icons")
                        if (ikone != null) for (i in 0 until ikone.length()) {
                            val o2 = ikone.optJSONObject(i) ?: continue
                            val src = o2.optString("src"); if (src.isBlank()) continue
                            // SVG televizor ne odkodira; preskocimo ga, da ne zapravimo poskusa.
                            if (src.substringBefore('?').endsWith(".svg", true)) continue
                            val velikost = o2.optString("sizes").split(" ", "x").mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0
                            kandidati.add(razresi(manifestUrl, src) to velikost)
                        }
                    }
                }
            } catch (e: Throwable) { Log.w(TAG, "Manifesta ni bilo mogoce prebrati: ${e.message}") }
        }

        // Vse <link rel="... icon ...">: apple-touch-icon je navadno 180 px, "icon" pa ima velikost
        // zapisano v sizes. Kar nima velikosti, damo na konec vrste.
        kandidati.addAll(ikoneIzHtml(html, url))

        // Podstrani (youtube.com/tv) ikon pogosto ne navedejo, korenska stran pa jih. Kadar doslej
        // nismo nasli nic dovolj velikega, pogledamo se koren iste strani - nikamor drugam.
        if (kandidati.none { it.second >= DOVOLJ_OSTRA }) {
            val koren = razresi(url, "/")
            if (koren != url) {
                try {
                    k.newCall(Request.Builder().url(koren).header("User-Agent", UA).build()).execute().use { o ->
                        if (o.isSuccessful) kandidati.addAll(ikoneIzHtml(besedilo(o, NAJVEC_HTML), koren))
                    }
                } catch (e: Throwable) { Log.w(TAG, "Korenske strani ni bilo mogoce prebrati: ${e.message}") }
            }
        }
        kandidati.add(razresi(url, "/apple-touch-icon.png") to 180)
        kandidati.add(razresi(url, "/favicon.ico") to 0)

        val (pot, ikonaUrl) = prenesiNajboljso(c, k, kandidati, url)
        ime = ime.replace(Regex("\\s+"), " ").take(40)
        return Podatki(ime, pot, barva, ikonaUrl)
    }

    /** Ikone, ki jih stran navede v <link rel="... icon ...">, z napovedano velikostjo. */
    private fun ikoneIzHtml(html: String, osnova: String): List<Pair<String, Int>> {
        val najdene = ArrayList<Pair<String, Int>>()
        for (u in Regex("""<link[^>]+>""", RegexOption.IGNORE_CASE).findAll(html).map { it.value }) {
            val rel = Regex("""rel=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1)?.lowercase() ?: continue
            if (!rel.contains("icon")) continue
            val href = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1) ?: continue
            // SVG televizor ne odkodira; preskocimo ga, da ne zapravimo poskusa.
            if (href.substringBefore('?').endsWith(".svg", true)) continue
            val velikost = Regex("""sizes=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1)
                ?.split(" ", "x")?.mapNotNull { it.toIntOrNull() }?.maxOrNull()
                ?: if (rel.contains("apple-touch")) 180 else 0
            najdene.add(razresi(osnova, href) to velikost)
        }
        return najdene
    }

    private fun razresi(osnova: String, pot: String): String = try { URL(URL(osnova), pot).toString() } catch (_: Throwable) { pot }

    /**
     * Prenese kandidate po vrsti (najvecji napovedani najprej) in obdrzi najostrejso sliko, ki jo
     * dobi. Ustavi se takoj, ko je ena dovolj velika za kartico na televizorju; tako v najboljsem
     * primeru prenesemo eno samo datoteko.
     */
    private fun prenesiNajboljso(c: Context, k: OkHttpClient, kandidati: List<Pair<String, Int>>, stranUrl: String): Pair<String, String> {
        val urejeni = kandidati.distinctBy { it.first }.sortedByDescending { it.second }
        var najboljsa: Bitmap? = null
        var najboljsiUrl = ""
        var najboljsaStran = 0
        var poskusov = 0
        for ((u, _) in urejeni) {
            if (poskusov >= NAJVEC_POSKUSOV) break
            poskusov++
            val slika = prenesiSliko(k, u) ?: continue
            // Steje logotip, ne platno: velika bela slika z drobnim znakom je slabsa od manjse,
            // ki je vsa logotip.
            val stran = try {
                val vsebina = poreziRob(brezBelega(poreziRob(slika)))
                minOf(vsebina.width, vsebina.height)
            } catch (_: Throwable) { minOf(slika.width, slika.height) }
            if (stran > najboljsaStran) {
                najboljsa?.recycle(); najboljsa = slika; najboljsiUrl = u; najboljsaStran = stran
            } else slika.recycle()
            if (stran >= DOVOLJ_OSTRA) break
        }
        val zmagovalka = najboljsa ?: return "" to ""
        val pot = shraniIkono(c, zmagovalka, stranUrl)
        return pot to najboljsiUrl
    }

    private fun prenesiSliko(k: OkHttpClient, ikonaUrl: String): Bitmap? {
        if (ikonaUrl.isBlank()) return null
        return try {
            k.newCall(Request.Builder().url(ikonaUrl).header("User-Agent", UA).build()).execute().use { o ->
                if (!o.isSuccessful) return null
                // Beremo z omejitvijo: televizor ima malo pomnilnika in ikona s spleta je lahko karkoli.
                val bajti = bajti(o, NAJVEC_IKONA) ?: return null
                // Najprej samo mere, sele nato dekodiranje v velikosti, ki jo res potrebujemo.
                val mere = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bajti, 0, bajti.size, mere)
                if (mere.outWidth <= 0 || mere.outHeight <= 0) return null
                if (mere.outWidth > 4096 || mere.outHeight > 4096) return null
                val moznosti = BitmapFactory.Options().apply {
                    inSampleSize = vzorec(mere.outWidth, mere.outHeight, NAJVECJA_IKONA_PX)
                }
                BitmapFactory.decodeByteArray(bajti, 0, bajti.size, moznosti)
            }
        } catch (e: Throwable) { Log.w(TAG, "Ikone ni bilo mogoce prenesti: ${e.message}"); null }
    }

    private fun shraniIkono(c: Context, slika: Bitmap, stranUrl: String): String = try {
        // Na televizorju je kartica velika, zato ikono hranimo v vecji locljivosti; vecje od
        // zaslona pa je ne potrebujemo.
        val stran = if (slika.width > NAJVECJA_IKONA_PX)
            Bitmap.createScaledBitmap(slika, NAJVECJA_IKONA_PX, NAJVECJA_IKONA_PX * slika.height / slika.width, true)
        else slika
        val mapa = File(c.applicationContext.filesDir, "os/ikone").apply { mkdirs() }
        val datoteka = File(mapa, Integer.toHexString(stranUrl.trimEnd('/').hashCode()) + ".png")
        datoteka.outputStream().use { stran.compress(Bitmap.CompressFormat.PNG, 100, it) }
        datoteka.absolutePath
    } catch (e: Throwable) { Log.w(TAG, "Ikone ni bilo mogoce shraniti: ${e.message}"); "" }

    /** Toliko pik je dovolj, da je ikona na kartici ostra; manjso se poskusimo izboljsati. */
    private const val DOVOLJ_OSTRA = 192
    private const val NAJVEC_POSKUSOV = 4

    /** Koliko najvec preberemo: dovolj za pravo stran, premalo, da bi nam kdo napolnil pomnilnik. */
    private const val NAJVEC_HTML = 200_000
    private const val NAJVEC_MANIFEST = 256_000
    private const val NAJVEC_IKONA = 2_000_000
    /** Koliko pik hranimo za ikono: kartica na televizorju je velika in 256 px je bilo premalo. */
    private const val NAJVECJA_IKONA_PX = 512

    /** Besedilo odgovora, odrezano na [najvec] bajtov (brez nalaganja celega telesa v pomnilnik). */
    private fun besedilo(o: okhttp3.Response, najvec: Int): String {
        val vir = o.body?.source() ?: return ""
        vir.request(najvec.toLong() + 1)
        return vir.buffer.snapshot().utf8().take(najvec)
    }

    /** Bajti odgovora do [najvec]; ce je odgovor vecji, ga ne vzamemo. */
    private fun bajti(o: okhttp3.Response, najvec: Int): ByteArray? {
        val dolzina = o.body?.contentLength() ?: -1L
        if (dolzina > najvec) return null
        val vir = o.body?.source() ?: return null
        vir.request(najvec.toLong() + 1)
        val posnetek = vir.buffer.snapshot()
        if (posnetek.size > najvec) return null
        return posnetek.toByteArray()
    }

    /** Potenca dvojke, pri kateri je slika se vedno vsaj [cilj] pik siroka. */
    private fun vzorec(sirina: Int, visina: Int, cilj: Int): Int {
        var v = 1
        while (sirina / (v * 2) >= cilj && visina / (v * 2) >= 1) v *= 2
        return v
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 11; Safeer TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
}
