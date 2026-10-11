package si.safeer.tv.cast

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tokovi Safeer Huba: deljenje zaslona in prenos datotek.
 *
 * Nadzor (kdo komu kaj deli) gre po WebSocketu prek usmerjevalnika; VSEBINA gre tu, po
 * loceni zahtevi HTTP. Tako deljenje zaslona tece gladko, medtem ko se prenasa datoteka -
 * nista v isti vrsti - in nobena od njiju ne more zamasiti sporocil.
 *
 * Pravila, ki jih ta razred uveljavlja (glej SAFEER-ZNANJE.md):
 *  - vsak gledalec zaslona ima omejeno vrsto; ce ne dohaja, izgubi okvirje, ne pomnilnika;
 *  - datoteke se pisejo na disk v koscih, nikoli ne sestavljajo v pomnilniku;
 *  - hkrati tece omejeno stevilo prenosov in en sam deljen zaslon na posiljatelja;
 *  - ime cilja je enolicno; prostor na disku se preveri pred sprejemom.
 *
 * Razred ne pozna Androida; mapo dobi od klicatelja, da ga je mogoce preizkusiti v JVM.
 */
class HubTokovi(
    /** Kam se shranjujejo prejete datoteke, kadar je cilj ta naprava (mapa za prenose). */
    private val mapaPrenosov: () -> File,
    /** Zacasna mapa za datoteke, ki cakajo, da jih prevzame druga naprava. */
    private val mapaZacasna: () -> File,
    /** Ali je zeton v glavi veljaven (seznanjena naprava). */
    private val jeVeljavenZeton: (String?) -> Boolean,
    /** Id naprave, na kateri Hub tece (da vemo, kdaj je cilj gostitelj sam). */
    private val lastniId: () -> String,
    /** Klice se, ko je datoteka za gostitelja shranjena: ime in pot. */
    private val naPrejetoDatoteko: (String, File) -> Unit = { _, _ -> },
    private val ura: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Usmerjevalnik se pripne sem, da o vsebini obvesti ciljno napravo po WebSocketu:
     * ko je datoteka cela (share.file) in ko se deljenje zaslona konca (share.screen stop).
     * Posiljatelju tako ni treba vzdrzevati lastne povezave: vsebino odda po HTTP, Hub pove naprej.
     */
    @Volatile var naDatoteko: ((Datoteka) -> Unit)? = null
    @Volatile var naKonecZaslona: ((String, String) -> Unit)? = null
    /** Ali je ciljna naprava povezana; brez tega datoteke za tujo napravo ne sprejmemo. */
    @Volatile var jeCiljPovezan: ((String) -> Boolean)? = null
    /** Naprava, ki ji zeton pripada; posiljatelj se doloci iz zetona, ne iz poizvedbe. */
    @Volatile var napravaZeZetona: ((String?) -> String?)? = null
    /** Z eno napravo deli naenkrat ena naprava: vrne id naprave, ki cilj ze ima, ali null. */
    @Volatile var zasediCilj: ((String, String) -> String?)? = null
    @Volatile var sprostiCilj: ((String, String) -> Unit)? = null
    /**
     * Dotik ali poteg s strani gledalca (posiljatelj deljenja, akcija "input.tap"/"input.swipe"/
     * "input.key", parametri kot JSON): usmerjevalnik ga posreduje gostitelju po WebSocketu, isto
     * pot, kot bi ukaz poslala seznanjena naprava (Daljinec). Gledalec sam ni nujno seznanjen -
     * dovoljenje mu da ze kljuc, s katerim gleda ta zaslon.
     */
    @Volatile var naVnosGledalca: ((String, String, String) -> Unit)? = null

    // ------------------------------------------------------------------ zaslon

    private class Gledalec {
        val vrsta = ArrayBlockingQueue<ByteArray>(VRSTA_GLEDALCA)
        @Volatile var konec = false
        fun ponudi(okvir: ByteArray) {
            // Kdor ne dohaja, izgubi najstarejsi okvir. Nikoli ne cakamo in nikoli ne kopicimo.
            while (!vrsta.offer(okvir)) vrsta.poll()
        }
    }

    private class Zaslon(val id: String, val kljuc: String, val posiljatelj: String) {
        /** Znak deljenja za dotike gledalca (naprava, ki deli, ga izracuna iz istega id-ja in kljuca). */
        val znak: String = DostopPravila.znakGledalca(id, kljuc)
        @Volatile var zadnji: ByteArray? = null
        @Volatile var tece = true
        val gledalci = CopyOnWriteArrayList<Gledalec>()
        val zacetek: Long = System.currentTimeMillis()
    }

    private val zasloni = ConcurrentHashMap<String, Zaslon>()

    /** Deljenje se zacne z navadno zahtevo (usmerjevalnik jo poklice); vrne id in kljuc gledalca. */
    fun zacniZaslon(posiljatelj: String): Pair<String, String>? {
        // En deljen zaslon na posiljatelja: prejsnjega koncamo.
        zasloni.values.filter { it.posiljatelj == posiljatelj }.forEach { koncajZaslon(it.id) }
        if (zasloni.size >= NAJVEC_ZASLONOV) return null
        val id = nakljucno(8)
        val kljuc = nakljucno(16)
        zasloni[id] = Zaslon(id, kljuc, posiljatelj)
        return id to kljuc
    }

    fun koncajZaslon(id: String) {
        val z = zasloni.remove(id) ?: return
        z.tece = false
        for (g in z.gledalci) g.konec = true
        try { naKonecZaslona?.invoke(id, z.posiljatelj) } catch (e: Exception) { SafeerLog.napaka("Tokovi", "naKonecZaslona", e) }
    }

    fun zaslonTece(id: String): Boolean = zasloni[id]?.tece == true

    // ------------------------------------------------------------------ datoteke

    class Datoteka(val id: String, val ime: String, val velikost: Long, val pot: File, val kljuc: String,
                   val cilj: String, val posiljatelj: String, val zaGostitelja: Boolean, val nastala: Long,
                   val sha256: String = "",
                   /** Relativna mapa pri posiljanju cele mape ("" = brez); cilj jo ustvari (share.file "dir"). */
                   val mapa: String = "") {
        /** Pot, po kateri ciljna naprava datoteko prevzame (samo, ce ni za gostitelja). */
        fun potPrevzema(): String = "/cast/file/$id?k=$kljuc"
    }

    private val datoteke = ConcurrentHashMap<String, Datoteka>()
    private val prenosovZdaj = AtomicInteger(0)

    /**
     * Prekinjena oddaja (izpad Wi-Fi; lastnik, 11. 10. 2026): kar je prislo, ostane na disku in odtis tece naprej;
     * posiljatelj s HEAD /cast/file izve, koliko ze imamo, in poslje le ostanek. Kljuc: posiljatelj|cilj|sha|velikost.
     */
    private class Delna(val pot: File, val ime: String, val mapa: String, val zaGostitelja: Boolean, val ciljnaMapa: File) {
        var prejeto = 0L
        val prstni: java.security.MessageDigest = java.security.MessageDigest.getInstance("SHA-256")
        @Volatile var cas = 0L
        var vTeku = false
    }
    private val delne = ConcurrentHashMap<String, Delna>()
    private fun kljucDelne(posiljatelj: String, cilj: String, sha: String, skupaj: Long) = "$posiljatelj|$cilj|$sha|$skupaj"

    fun datoteka(id: String): Datoteka? = datoteke[id]

    /** Datoteke, ki jih nihce ni prevzel, pospravimo; klice se obcasno. */
    fun pocistiDatoteke() {
        val zdaj = ura()
        for ((k, d) in delne) {
            if (!d.vTeku && zdaj - d.cas > DATOTEKA_VELJA_MS && delne.remove(k, d)) try { d.pot.delete() } catch (_: Exception) { }
        }
        for ((id, d) in datoteke) {
            if (zdaj - d.nastala > DATOTEKA_VELJA_MS) {
                datoteke.remove(id)
                try { if (d.pot.parentFile == mapaZacasna()) d.pot.delete() } catch (_: Exception) { }
            }
        }
    }

    // ------------------------------------------------------------------ HTTP tokovi

    /** Poti streznika datotek te naprave pod /cast (isti seznam kot DatotekeStreznik.jePotPrekHuba). */
    private fun jePotStreznikaDatotek(pot: String): Boolean =
        pot.startsWith("/cast/d/") || pot.startsWith("/cast/thumb/") || pot.startsWith("/cast/live/") || pot.startsWith("/cast/magnet/")

    /**
     * Prevzame tokovne zahteve. Vrne true, ce je odgovoril sam (tudi z napako).
     *   PUT  /cast/file?name=&target=&from=      telo = datoteka; zeton v glavi
     *   GET  /cast/file/{id}?k=                  prevzem datoteke (kljuc iz share.file)
     *   POST /cast/screen/{id}?k=                tok okvirjev: 4 bajti dolzine + JPEG, ponavljaj
     *   GET  /cast/screen/{id}/stream?k=         MJPEG za gledalca
     *   GET  /cast/screen/{id}/view?k=           HTML stran gledalca (za televizor/brskalnik)
     *   POST /cast/screen/{id}/input?k=          dotik/poteg gledalca nazaj h gostitelju
     */
    fun obdelaj(zahteva: HubStreznik.Zahteva, vhod: InputStream, izhod: OutputStream, vticnica: Socket): Boolean {
        val pot = zahteva.pot
        val krajevni = HubUsmerjevalnik.jeKrajevni(zahteva.odjemalec)
        if (!krajevni) {
            odgovori(izhod, 403, "{\"napaka\":\"samo v krajevnem omrežju\"}")
            return true
        }
        return when {
            pot == "/cast/file" && zahteva.metoda == "PUT" -> { sprejmiDatoteko(zahteva, vhod, izhod); true }
            pot == "/cast/file" && zahteva.metoda == "HEAD" -> { odmikOddaje(zahteva, izhod); true }
            pot.startsWith("/cast/file/") && zahteva.metoda == "GET" -> { posljiDatoteko(zahteva, izhod); true }
            // Poti streznika datotek te naprave prek Huba (Global Link pripelje samo do vrat Huba): datoteka, slicica,
            // urejanje (POST), sprotni tok in tok torrenta - z istim zetonom in istimi pravili kot doma.
            jePotStreznikaDatotek(pot) &&
                (zahteva.metoda == "GET" || zahteva.metoda == "HEAD" || zahteva.metoda == "POST") ->
                si.safeer.tv.link.DatotekeStreznik.prekHuba(zahteva.metoda, pot, zahteva.glave, vhod, izhod)
            pot.startsWith("/cast/screen/") && pot.endsWith("/stream") && zahteva.metoda == "GET" -> { gledajZaslon(zahteva, izhod, vticnica); true }
            pot.startsWith("/cast/screen/") && pot.endsWith("/view") && zahteva.metoda == "GET" -> { stranGledalca(zahteva, izhod); true }
            pot.startsWith("/cast/screen/") && pot.endsWith("/input") && zahteva.metoda == "POST" -> { vnosVZaslon(zahteva, vhod, izhod); true }
            pot.startsWith("/cast/screen/") && zahteva.metoda == "POST" -> { sprejmiZaslon(zahteva, vhod, izhod, vticnica); true }
            else -> false
        }
    }

    // ---- zaslon: posiljatelj ----

    private fun sprejmiZaslon(zahteva: HubStreznik.Zahteva, vhod: InputStream, izhod: OutputStream, vticnica: Socket) {
        val id = zahteva.pot.removePrefix("/cast/screen/").substringBefore('/')
        val z = zasloni[id]
        if (z == null || z.kljuc != zahteva.poizvedba["k"]) {
            odgovori(izhod, 404, "{\"napaka\":\"tega deljenja ni\"}")
            return
        }
        val zeton = zahteva.glave["x-safeer-token"]
        if (!jeVeljavenZeton(zeton)) {
            odgovori(izhod, 401, "{\"napaka\":\"naprava ni seznanjena\"}")
            return
        }
        val lastnik = napravaZeZetona?.invoke(zeton)
        if (lastnik != null && lastnik != z.posiljatelj) {
            // Okvirje sme potiskati samo naprava, ki je deljenje zacela.
            odgovori(izhod, 403, "{\"napaka\":\"to deljenje pripada drugi napravi\"}")
            return
        }
        // Okvirji prihajajo, dokler posiljatelj deli; brez okvirja 20 s pomeni, da je odsel.
        vticnica.soTimeout = 20_000
        val glava = ByteArray(4)
        try {
            while (z.tece) {
                if (!preberiTocno(vhod, glava, 4)) break
                val dolzina = ((glava[0].toInt() and 0xFF) shl 24) or ((glava[1].toInt() and 0xFF) shl 16) or
                    ((glava[2].toInt() and 0xFF) shl 8) or (glava[3].toInt() and 0xFF)
                if (dolzina <= 0 || dolzina > NAJVECJI_OKVIR) break
                val okvir = ByteArray(dolzina)
                if (!preberiTocno(vhod, okvir, dolzina)) break
                z.zadnji = okvir
                for (g in z.gledalci) g.ponudi(okvir)
            }
        } catch (_: IOException) {
        } finally {
            koncajZaslon(id)
        }
        try { odgovori(izhod, 200, "{\"koncano\":true}") } catch (_: Exception) { }
    }

    // ---- zaslon: gledalec ----

    private fun gledajZaslon(zahteva: HubStreznik.Zahteva, izhod: OutputStream, vticnica: Socket) {
        val id = zahteva.pot.removePrefix("/cast/screen/").substringBefore('/')
        val z = zasloni[id]
        if (z == null || z.kljuc != zahteva.poizvedba["k"] || !z.tece) {
            odgovori(izhod, 404, "{\"napaka\":\"tega deljenja ni\"}")
            return
        }
        if (z.gledalci.size >= NAJVEC_GLEDALCEV) {
            odgovori(izhod, 503, "{\"napaka\":\"preveč gledalcev\"}")
            return
        }
        val g = Gledalec()
        z.gledalci.add(g)
        z.zadnji?.let { g.ponudi(it) }
        vticnica.soTimeout = 0
        try {
            izhod.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$MEJA\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            izhod.flush()
            while (z.tece && !g.konec) {
                val okvir = g.vrsta.poll(1, TimeUnit.SECONDS) ?: continue
                izhod.write(("--$MEJA\r\nContent-Type: image/jpeg\r\nContent-Length: ${okvir.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
                izhod.write(okvir)
                izhod.write("\r\n".toByteArray(Charsets.US_ASCII))
                izhod.flush()
            }
            izhod.write("--$MEJA--\r\n".toByteArray(Charsets.US_ASCII))
            izhod.flush()
        } catch (_: Exception) {
        } finally {
            z.gledalci.remove(g)
        }
    }

    private fun stranGledalca(zahteva: HubStreznik.Zahteva, izhod: OutputStream) {
        val id = zahteva.pot.removePrefix("/cast/screen/").substringBefore('/')
        val z = zasloni[id]
        val kljuc = zahteva.poizvedba["k"] ?: ""
        if (z == null || z.kljuc != kljuc) {
            odgovori(izhod, 404, STRAN_KONEC, "text/html; charset=utf-8")
            return
        }
        val html = STRAN_GLEDALCA
            .replace("%%TOK%%", "/cast/screen/$id/stream?k=$kljuc")
            .replace("%%VNOS%%", "/cast/screen/$id/input?k=$kljuc")
        odgovori(izhod, 200, html, "text/html; charset=utf-8")
    }

    /**
     * Dotik ali poteg gledalca: telo je majhen JSON ({"vrsta":"tap",x,y} ali {"vrsta":"swipe",...}
     * ali {"vrsta":"key",key}), kljuc gledalca je isti kot za sliko - posebne seznanitve gledalec ne
     * potrebuje. Posredujemo naprej h gostitelju (isti ukaz, kot bi ga poslala seznanjena naprava);
     * odgovorimo takoj, brez cakanja na izvedbo, da dotik na sliki ostane odziven.
     */
    private fun vnosVZaslon(zahteva: HubStreznik.Zahteva, vhod: InputStream, izhod: OutputStream) {
        val id = zahteva.pot.removePrefix("/cast/screen/").substringBefore('/')
        val z = zasloni[id]
        if (z == null || z.kljuc != zahteva.poizvedba["k"] || !z.tece) {
            odgovori(izhod, 404, "{\"napaka\":\"tega deljenja ni\"}")
            return
        }
        val dolzina = (zahteva.glave["content-length"] ?: "").toIntOrNull() ?: 0
        if (dolzina <= 0 || dolzina > NAJVECJI_VNOS) {
            odgovori(izhod, 400, "{\"napaka\":\"neveljavno telo\"}")
            return
        }
        val telo = ByteArray(dolzina)
        if (!preberiTocno(vhod, telo, dolzina)) {
            odgovori(izhod, 400, "{\"napaka\":\"telo je prekinjeno\"}")
            return
        }
        try {
            val o = org.json.JSONObject(String(telo, Charsets.UTF_8))
            val (akcija, parametri) = when (o.optString("vrsta", "")) {
                "tap" -> "input.tap" to org.json.JSONObject().put("x", o.optDouble("x")).put("y", o.optDouble("y"))
                "swipe" -> "input.swipe" to org.json.JSONObject()
                    .put("x1", o.optDouble("x1")).put("y1", o.optDouble("y1"))
                    .put("x2", o.optDouble("x2")).put("y2", o.optDouble("y2"))
                    .put("ms", o.optLong("ms", 220L).coerceIn(60L, 1500L))
                "key" -> "input.key" to org.json.JSONObject().put("key", o.optString("key", "").take(24))
                else -> null to null
            }
            // Znak deljenja doloci sredisce (ne telo zahteve): gostitelj po njem ve, da dotik prihaja od tod.
            if (akcija != null && parametri != null) naVnosGledalca?.invoke(z.posiljatelj, akcija,
                parametri.put(DostopPravila.PARAM_ZNAK_GLEDALCA, z.znak).toString())
        } catch (_: Exception) {
            // Slabo oblikovan dotik ne sme podreti gledanja - preprosto ga izpustimo.
        }
        odgovori(izhod, 200, "{\"ok\":true}")
    }

    // ---- datoteke ----

    /** HEAD /cast/file?target=&sha256=&size=: koliko te datoteke ze imamo od prekinjene oddaje (x-safeer-offset). */
    private fun odmikOddaje(zahteva: HubStreznik.Zahteva, izhod: OutputStream) {
        val zeton = zahteva.glave["x-safeer-token"]
        val veljaven = jeVeljavenZeton(zeton)
        pocistiDatoteke()
        var odmik = 0L
        if (veljaven) {
            val posiljatelj = (napravaZeZetona?.invoke(zeton) ?: zahteva.poizvedba["from"] ?: "").take(64)
            val cilj = (zahteva.poizvedba["target"] ?: "").take(64)
            val sha = (zahteva.poizvedba["sha256"] ?: "").trim().lowercase()
            val velikost = zahteva.poizvedba["size"]?.toLongOrNull() ?: -1L
            val d = delne[kljucDelne(posiljatelj, cilj, sha, velikost)]
            if (d != null && !d.vTeku) odmik = d.prejeto
        }
        izhod.write(("HTTP/1.1 ${if (veljaven) "200 OK" else "401 Unauthorized"}\r\nx-safeer-offset: $odmik\r\n" +
            "x-safeer-resume: 1\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        izhod.flush()
    }

    private fun sprejmiDatoteko(zahteva: HubStreznik.Zahteva, vhod: InputStream, izhod: OutputStream) {
        val zeton = zahteva.glave["x-safeer-token"]
        if (!jeVeljavenZeton(zeton)) {
            odgovori(izhod, 401, "{\"napaka\":\"naprava ni seznanjena\"}")
            return
        }
        val ime = varnoIme(zahteva.poizvedba["name"] ?: "")
        val cilj = (zahteva.poizvedba["target"] ?: "").take(64)
        val podmapa = varnaMapa(zahteva.poizvedba["dir"] ?: "")
        // Posiljatelj je lastnik zetona; "from" v poizvedbi velja le, ce Hub zetonov ne veze.
        val posiljatelj = (napravaZeZetona?.invoke(zeton) ?: zahteva.poizvedba["from"] ?: "").take(64)
        val dolzina = zahteva.glave["content-length"]?.toLongOrNull() ?: -1L
        // Nadaljevanje: x-safeer-size (cela datoteka) in x-safeer-offset (od kod). Brez njiju vse kot prej.
        val skupajGlava = zahteva.glave["x-safeer-size"]?.toLongOrNull()
        val nadaljevanje = skupajGlava != null
        val skupaj = skupajGlava ?: dolzina
        val odmik = if (nadaljevanje) (zahteva.glave["x-safeer-offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L) else 0L
        val napovedan = zahteva.glave["x-safeer-sha256"]?.trim()?.lowercase().orEmpty()
        if (ime.isEmpty() || cilj.isEmpty() || dolzina < 0) {
            odgovori(izhod, 400, "{\"napaka\":\"manjka ime, cilj ali dolžina\"}")
            return
        }
        if (odmik + dolzina != skupaj || (odmik > 0 && napovedan.isEmpty())) {
            odgovori(izhod, 400, "{\"napaka\":\"dolžina se ne ujema\",\"koda\":\"napacna_dolzina\"}")
            return
        }
        if (skupaj > NAJVECJA_DATOTEKA) {
            odgovori(izhod, 413, "{\"napaka\":\"datoteka je prevelika\"}")
            return
        }
        if (prenosovZdaj.get() >= NAJVEC_PRENOSOV) {
            odgovori(izhod, 503, "{\"napaka\":\"preveč hkratnih prenosov\"}")
            return
        }
        val zaGostitelja = cilj == lastniId()
        if (!zaGostitelja && jeCiljPovezan?.invoke(cilj) == false) {
            odgovori(izhod, 404, "{\"napaka\":\"ciljna naprava ni povezana\",\"koda\":\"naprava_ni_povezana\"}")
            return
        }
        val mapa = if (zaGostitelja) mapaPrenosov() else mapaZacasna()
        try { mapa.mkdirs() } catch (_: Exception) { }
        // usableSpace je tu prava mera: datoteko pisemo v svojo mapo in je ne smemo zapisati na racun
        // predpomnilnika drugih aplikacij (preizkus tece tudi v JVM, brez StorageManagerja).
        @Suppress("UsableSpace")
        val prosto = mapa.usableSpace
        if (prosto in 1 until dolzina + REZERVA_PROSTORA) {
            odgovori(izhod, 507, "{\"napaka\":\"ni dovolj prostora\"}")
            return
        }
        val kljucD = if (nadaljevanje && napovedan.isNotEmpty()) kljucDelne(posiljatelj, cilj, napovedan, skupaj) else null
        var delna: Delna? = null
        synchronized(delne) {
            val obstojeca = kljucD?.let { delne[it] }
            if (odmik > 0 && (obstojeca == null || obstojeca.vTeku || obstojeca.prejeto != odmik)) {
                val imamo = if (obstojeca == null || obstojeca.vTeku) 0L else obstojeca.prejeto
                odgovori(izhod, 409, "{\"napaka\":\"nadaljevanje se ne ujema\",\"koda\":\"napacen_odmik\",\"odmik\":$imamo}")
                return
            }
            if (obstojeca != null && obstojeca.vTeku) {
                odgovori(izhod, 409, "{\"napaka\":\"ta datoteka se že sprejema\",\"koda\":\"ze_v_teku\",\"odmik\":0}")
                return
            }
            if (obstojeca != null && odmik == 0L) {
                delne.remove(kljucD); try { obstojeca.pot.delete() } catch (_: Exception) { }
            } else if (obstojeca != null) {
                obstojeca.vTeku = true; delna = obstojeca
            }
            Unit
        }
        // Med prenosom je cilj zaseden za to napravo: nihce drug mu medtem ne poslje nicesar.
        zasediCilj?.invoke(cilj, posiljatelj)?.let { kdo ->
            delna?.vTeku = false
            odgovori(izhod, 409, "{\"napaka\":\"z napravo trenutno deli druga naprava\",\"koda\":\"naprava_zasedena\",\"busy_by\":\"${ubezi(kdo)}\"}")
            return
        }
        val d0 = delna ?: run {
            // Za gostitelja v mapo prenosov (z mapo posiljatelja), sicer zacasno; do konca z ».safeer-delno«.
            val ciljnaMapa = if (zaGostitelja) podmapaVarno(mapa, podmapa) else mapa
            val pot = enolicnaPot(ciljnaMapa, if (zaGostitelja) "$ime.safeer-delno" else nakljucno(6) + "-" + ime)
            Delna(pot, ime, podmapa, zaGostitelja, ciljnaMapa).also {
                it.vTeku = true; it.cas = ura()
                if (kljucD != null) delne[kljucD] = it
            }
        }
        prenosovZdaj.incrementAndGet()
        var prekinjeno = false
        try {
            java.io.RandomAccessFile(d0.pot, "rw").use { out ->
                out.seek(d0.prejeto); out.setLength(d0.prejeto)
                val kos = ByteArray(KOS)
                val konec = d0.prejeto + dolzina
                while (d0.prejeto < konec) {
                    val n = vhod.read(kos, 0, minOf(kos.size.toLong(), konec - d0.prejeto).toInt())
                    if (n < 0) break
                    out.write(kos, 0, n)
                    d0.prstni.update(kos, 0, n)
                    d0.prejeto += n
                    d0.cas = ura()
                }
            }
        } catch (e: IOException) {
            prekinjeno = true
        } finally {
            prenosovZdaj.decrementAndGet()
            sprostiCilj?.invoke(cilj, posiljatelj)
        }
        if (prekinjeno || d0.prejeto != skupaj) {
            if (kljucD != null && d0.prejeto < skupaj) {
                d0.vTeku = false            // pocaka na nadaljevanje (pocistiDatoteke po eni uri)
                odgovori(izhod, if (prekinjeno) 500 else 400,
                    "{\"napaka\":\"${if (prekinjeno) "prenos prekinjen" else "datoteka ni prišla cela"}\",\"odmik\":${d0.prejeto}}")
                return
            }
            if (kljucD != null) delne.remove(kljucD, d0)
            try { d0.pot.delete() } catch (_: Exception) { }
            odgovori(izhod, if (prekinjeno) 500 else 400, "{\"napaka\":\"${if (prekinjeno) "prenos prekinjen" else "datoteka ni prišla cela"}\"}")
            return
        }
        if (kljucD != null) delne.remove(kljucD, d0)
        val sha256 = d0.prstni.digest().joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
        // Ce je posiljatelj prstni odtis napovedal, se mora ujemati; sicer datoteke ne obdrzimo.
        if (napovedan.isNotEmpty() && napovedan != sha256) {
            try { d0.pot.delete() } catch (_: Exception) { }
            odgovori(izhod, 400, "{\"napaka\":\"prstni odtis se ne ujema\",\"koda\":\"napacen_odtis\"}")
            return
        }
        // Za gostitelja dobi datoteka koncno ime sele zdaj (nepopolne uporabnik v Prenosih ne vidi).
        val koncna = if (d0.zaGostitelja) enolicnaPot(d0.ciljnaMapa, d0.ime).also { if (!d0.pot.renameTo(it)) d0.pot.copyTo(it); if (d0.pot.exists()) d0.pot.delete() } else d0.pot
        val id = nakljucno(8)
        val kljuc = nakljucno(16)
        // Zacasna datoteka ima nakljucno predpono (vec hkratnih prenosov z istim imenom); prejemnik pa
        // dobi izvirno ime, kot ga je poslal posiljatelj (podvojitve razresi sam, v svoji mapi).
        val d = Datoteka(id, if (d0.zaGostitelja) koncna.name else d0.ime, skupaj, koncna, kljuc, cilj, posiljatelj,
            d0.zaGostitelja, ura(), sha256, d0.mapa)
        datoteke[id] = d
        if (d0.zaGostitelja) {
            try { naPrejetoDatoteko(d.ime, koncna) } catch (e: Exception) { SafeerLog.napaka("Tokovi", "prejeta datoteka ni oddana", e) }
        }
        // Ciljni napravi pove Hub (share.file), posiljatelj je s tem opravil.
        try { naDatoteko?.invoke(d) } catch (e: Exception) { SafeerLog.napaka("Tokovi", "naDatoteko", e) }
        odgovori(izhod, 200, "{\"id\":\"$id\",\"name\":\"${ubezi(d.ime)}\",\"size\":$skupaj,\"key\":\"$kljuc\",\"for_host\":${d0.zaGostitelja},\"sha256\":\"$sha256\"}")
    }

    private fun posljiDatoteko(zahteva: HubStreznik.Zahteva, izhod: OutputStream) {
        val id = zahteva.pot.removePrefix("/cast/file/").substringBefore('/')
        val d = datoteke[id]
        if (d == null || d.kljuc != zahteva.poizvedba["k"] || !d.pot.isFile) {
            odgovori(izhod, 404, "{\"napaka\":\"te datoteke ni\"}")
            return
        }
        if (prenosovZdaj.get() >= NAJVEC_PRENOSOV) {
            odgovori(izhod, 503, "{\"napaka\":\"preveč hkratnih prenosov\"}")
            return
        }
        val velikost = d.pot.length()
        // Cilj nadaljuje prekinjen prevzem (Range: bytes=N-): samo se manjkajoci del.
        val zacetek = zacetekObsega(zahteva.glave["range"].orEmpty(), velikost)
        prenosovZdaj.incrementAndGet()
        try {
            val glava = if (zacetek > 0) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $zacetek-${velikost - 1}/$velikost\r\n"
                        else "HTTP/1.1 200 OK\r\n"
            izhod.write((glava + "Accept-Ranges: bytes\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Length: ${velikost - zacetek}\r\n" +
                "x-safeer-sha256: ${d.sha256}\r\n" +
                "Content-Disposition: attachment; filename=\"${ubezi(d.ime)}\"\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
            var poslano = 0L
            FileInputStream(d.pot).use { vhodDat ->
                if (zacetek > 0) vhodDat.channel.position(zacetek)
                val kos = ByteArray(KOS)
                while (true) {
                    val n = vhodDat.read(kos)
                    if (n < 0) break
                    izhod.write(kos, 0, n)
                    poslano += n
                }
            }
            izhod.flush()
            // Prevzeta zacasna datoteka je opravila svoje.
            if (zacetek + poslano == velikost && d.pot.parentFile == mapaZacasna()) {
                datoteke.remove(id)
                try { d.pot.delete() } catch (_: Exception) { }
            }
        } catch (_: IOException) {
        } finally {
            prenosovZdaj.decrementAndGet()
        }
    }

    // ------------------------------------------------------------------ pomozno

    private fun preberiTocno(vhod: InputStream, kam: ByteArray, koliko: Int): Boolean {
        var prebrano = 0
        while (prebrano < koliko) {
            val n = vhod.read(kam, prebrano, koliko - prebrano)
            if (n < 0) return false
            prebrano += n
        }
        return true
    }

    private fun odgovori(izhod: OutputStream, koda: Int, telo: String, vrsta: String = "application/json; charset=utf-8") {
        val bajti = telo.toByteArray(Charsets.UTF_8)
        val opis = when (koda) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 413 -> "Payload Too Large"; 500 -> "Internal Server Error"
            409 -> "Conflict"; 503 -> "Service Unavailable"; 507 -> "Insufficient Storage"; else -> "OK"
        }
        izhod.write(("HTTP/1.1 $koda $opis\r\nContent-Type: $vrsta\r\nContent-Length: ${bajti.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        izhod.write(bajti)
        izhod.flush()
    }

    private fun ubezi(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        const val NAJVEC_ZASLONOV = 4
        const val NAJVEC_GLEDALCEV = 4
        const val VRSTA_GLEDALCA = 2
        const val NAJVECJI_OKVIR = 2 * 1024 * 1024
        /** Telo POST .../input je majhen JSON - nekaj deset bajtov; ta meja je ze zelo velikodusna. */
        const val NAJVECJI_VNOS = 2 * 1024
        const val NAJVEC_PRENOSOV = 3
        /** Prej 4 GB; datoteka gre na disk v koscih, meja je prostor na disku (lastnik, 11. 10. 2026). */
        const val NAJVECJA_DATOTEKA = 1024L * 1024 * 1024 * 1024
        const val REZERVA_PROSTORA = 200L * 1024 * 1024
        const val DATOTEKA_VELJA_MS = 60 * 60 * 1000L
        const val KOS = 64 * 1024
        private const val MEJA = "safeerokvir"

        private val nakljucniStevec = SecureRandom()

        fun nakljucno(bajtov: Int): String {
            val b = ByteArray(bajtov)
            nakljucniStevec.nextBytes(b)
            return b.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
        }

        /** Ime brez poti in brez znakov, ki bi jih sistem razumel kot ukaz. */
        fun varnoIme(ime: String): String {
            val golo = ime.substringAfterLast('/').substringAfterLast('\\').trim()
            val ocisceno = golo.replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_").take(120)
            return if (ocisceno.isEmpty() || ocisceno == "." || ocisceno == "..") "datoteka" else ocisceno
        }

        /** Kot na namizju: ime (1).ext, ce datoteka ze obstaja - dve hkratni ne pisieta ena cez drugo. */
        @Synchronized
        /** Relativna mapa (posiljanje cele mape): varna imena, brez »..« in skritih map; najvec 16 ravni. */
        fun varnaMapa(mapa: String): String = mapa.replace('\\', '/').split('/')
            .map { it.trim().trimStart('.') }.filter { it.isNotEmpty() }.map { varnoIme(it) }
            .take(16).joinToString("/").take(400)

        /** Podmapa v `koren` (ustvari jo); ce bi pot vodila ven, ostane koren. */
        fun podmapaVarno(koren: File, podmapa: String): File {
            if (podmapa.isEmpty()) return koren
            val cilj = File(koren, podmapa)
            return try {
                if (!cilj.canonicalPath.startsWith(koren.canonicalPath + File.separator)) koren
                else { cilj.mkdirs(); cilj }
            } catch (_: Exception) { koren }
        }

        /** Range: bytes=N- -> N; brez glave, druga oblika ali zunaj datoteke -> 0 (cela). */
        fun zacetekObsega(glava: String, velikost: Long): Long {
            val g = glava.trim().lowercase()
            if (!g.startsWith("bytes=") || g.contains(',')) return 0L
            val deli = g.removePrefix("bytes=").split('-')
            if (deli.size != 2 || deli[1].isNotBlank()) return 0L
            val n = deli[0].trim().toLongOrNull() ?: return 0L
            return if (n in 1 until velikost) n else 0L
        }

        fun enolicnaPot(mapa: File, ime: String): File {
            var kandidat = File(mapa, ime)
            if (!kandidat.exists()) return kandidat
            val pika = ime.lastIndexOf('.')
            val osnova = if (pika > 0) ime.substring(0, pika) else ime
            val koncnica = if (pika > 0) ime.substring(pika) else ""
            var i = 1
            while (kandidat.exists()) {
                kandidat = File(mapa, "$osnova ($i)$koncnica")
                i++
            }
            return kandidat
        }

        /**
         * Gledalec: crno ozadje, slika umerjena po krajsi stranici, razmerje vedno ohranjeno. Dotik
         * na sliki gre nazaj h gostitelju (kratek dotik = tap, premik = poteg) prek %%VNOS%% - stran
         * ni le mirujoca slika, aplikacijo na drugi napravi je mogoce dejansko upravljati od tu.
         */
        private const val STRAN_GLEDALCA = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no"><title>Safeer Link – zaslon</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden;touch-action:none}
img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;background:#000;-webkit-user-select:none;user-select:none}
#konec{display:none;position:absolute;inset:0;color:#cbd5e1;font:20px sans-serif;align-items:center;justify-content:center;text-align:center;padding:24px}
</style></head><body>
<img id="zaslon" src="%%TOK%%" alt="" draggable="false">
<div id="konec">Deljenje zaslona je končano.</div>
<script>
var s=document.getElementById('zaslon');
s.onerror=function(){s.style.display='none';document.getElementById('konec').style.display='flex';};
var vnosPot='%%VNOS%%';
function tocka(e){
  var r=s.getBoundingClientRect();
  var t=e.changedTouches?e.changedTouches[0]:e;
  var x=(t.clientX-r.left)/r.width, y=(t.clientY-r.top)/r.height;
  return [Math.min(1,Math.max(0,x)), Math.min(1,Math.max(0,y))];
}
var zacetna=null, zacetniCas=0;
function dol(e){ e.preventDefault(); zacetna=tocka(e); zacetniCas=Date.now(); }
function gor(e){
  e.preventDefault();
  if(!zacetna) return;
  var koncna=tocka(e), trajanje=Date.now()-zacetniCas;
  var dx=koncna[0]-zacetna[0], dy=koncna[1]-zacetna[1], telo;
  if(Math.abs(dx)<0.02 && Math.abs(dy)<0.02) telo={vrsta:'tap',x:zacetna[0],y:zacetna[1]};
  else telo={vrsta:'swipe',x1:zacetna[0],y1:zacetna[1],x2:koncna[0],y2:koncna[1],ms:Math.max(80,Math.min(1200,trajanje))};
  zacetna=null;
  fetch(vnosPot,{method:'POST',body:JSON.stringify(telo)}).catch(function(){});
}
s.addEventListener('touchstart',dol,{passive:false});
s.addEventListener('touchend',gor,{passive:false});
s.addEventListener('mousedown',dol);
s.addEventListener('mouseup',gor);
</script></body></html>"""

        private const val STRAN_KONEC = """<!doctype html><html><head><meta charset="utf-8"><title>Safeer Link</title>
<style>html,body{margin:0;height:100%;background:#000;color:#cbd5e1;font:20px sans-serif;display:flex;align-items:center;justify-content:center}</style>
</head><body>Deljenje zaslona je končano.</body></html>"""
    }
}
