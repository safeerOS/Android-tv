package si.safeer.tv.os

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Naloga 80: seznami IPTV (vir vrste SEZNAM, npr. iptv-org »countries/si.m3u«, »languages/eng.m3u«) kot kanali TV v zivo.
 * Seznam se prenese in razcleni tokovno ([IptvPravila.Bralnik]) enkrat na [IptvPravila.OSVEZI_MS] v datoteko kartic na
 * disku (vrstica na kanal); mreza bere strani iz datoteke ([naslednja]) - cel seznam nikoli ni v pomnilniku. Dnevnik:
 * SafeerOsMedia.
 */
object IptvSeznami {
    /** Id kanala iz seznama: »iptv:« + naslov toka. */
    const val PREDPONA = "iptv:"
    private const val MAPA = "iptv_seznami"
    private const val NAJVEC_BAJTOV = 20L * 1024 * 1024

    fun jeKanal(s: Jamendo.Skladba) = s.id.startsWith(PREDPONA)

    /** Kar vemo o prenesenem seznamu (datoteka .info): kdaj, koliko kanalov, katere zvrsti in jeziki (za izbire). */
    class Podatki(val prenesenOb: Long, val kanalov: Int, val zvrsti: Set<String>, val jeziki: Set<String>)

    private val podatki = ConcurrentHashMap<String, Podatki>()
    /** Pregled Grok 39, I5: napaka zadnjega prenosa po seznamu (odtis naslova -> [IptvPravila.NapakaPrenosa]). */
    private const val PREFS_NAPAKE = "safeer_iptv_seznami_napake"
    /** Grok 68, Ą5: ena prijava na vir naenkrat (odlocitev in rezervacija atomarno, [IptvPravila.EnaPrijava]). */
    private val prijave = IptvPravila.EnaPrijava()

    // Pripona -j2: od 23. 10. 2026 ima kanal brez tvg-language jezik drzave - stare razclenitve (brez jezika) se ne berejo vec,
    // seznam se prenese znova, [obdrzi] stare datoteke pospravi. -j3 (26. 10. 2026): brez kanalov [Not 24/7], ».uk« = GB.
    private fun ime(naslov: String) = KanaliPravila.odtisDodatka(naslov) + "-j3"
    private fun mapa(c: Context) = File(c.applicationContext.filesDir, MAPA)
    /** Naloga 87: mapa seznamov na USB, ce je predpomnilnik na USB v uporabi (iz ozadja; z glavne niti null). */
    private fun mapaUsb(c: Context): File? = UsbPredpomnilnik.mapa(c, PredpomnilnikPravila.IPTV)
    /** Mapa, kjer seznam ta hip je: notranja, sicer USB; ce ga ni nikjer, notranja. */
    private fun mapaSeznama(c: Context, naslov: String): File =
        listOfNotNull(mapa(c), mapaUsb(c)).firstOrNull { File(it, ime(naslov) + ".tsv").exists() } ?: mapa(c)
    private fun datoteka(c: Context, naslov: String) = File(mapaSeznama(c, naslov), ime(naslov) + ".tsv")
    private fun opis(c: Context, naslov: String) = File(mapaSeznama(c, naslov), ime(naslov) + ".info")

    /**
     * Naloga 99 (pregled 231, N5): podatki seznama samo iz pomnilnika (brez diska) - za glavno nit. Napolni jih [podatki] ali
     * priprava seznama v ozadju ([pripravi]); prej null.
     */
    fun podatkiIzPomnilnika(naslov: String): Podatki? = podatki[naslov]

    /** Podatki seznama z diska (brez omrezja); null = se ni prenesen. */
    fun podatki(c: Context, naslov: String): Podatki? {
        podatki[naslov]?.let { p ->
            // Naloga 87: seznam na USB, ki je bil odklopljen, ni vec na voljo - prenese se znova (na notranji). Na glavni niti
            // (kartica v Mojih virih) diska ne preverjamo.
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper() || datoteka(c, naslov).exists()) return p
            podatki.remove(naslov)
        }
        val f = opis(c, naslov)
        if (!f.exists() || !datoteka(c, naslov).exists()) return null
        return try {
            val d = f.readText().lines()
            Podatki(d[0].toLong(), d[1].toInt(), d.getOrNull(2).orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                d.getOrNull(3).orEmpty().split(',').filter { it.isNotBlank() }.toSet()).also { podatki[naslov] = it }
        } catch (_: Exception) { null }
    }

    /**
     * Seznam je na disku in ni zastarel: nic. Zastarel: prenese ga v ozadju (stari velja do konca). Ga ni: prenese ga zdaj
     * (klic iz ozadja). Vrne podatke, ki zdaj veljajo.
     */
    fun pripravi(c: Context, naslov: String, rocno: Boolean = false, uporabnik: Boolean = false): Podatki? {
        // Grok 52, Ñ2 + Grok 60, Ů1/Ů2: ENA odlocitev ([IptvPravila.odlociPripravo]). Seznam z racunom se samodejno prenasa samo na napravi,
        // kjer je dodan z dokazom (odprtje TV v zivo, 24-urna osvezitev, predpomnilnik 24 ur); drugje samo na izrecen klik [rocno]
        // (»Poskusi znova«), najvec 1-krat na minuto na racun. Grok 39, I5: po zavrnjenem ali neuspelem prenosu premor (10 min, 1 h,
        // 6 h, 24 h) - vsak poskus je prijava v racun ponudnika. Grok 68, Ą2: vir iz ozjega kroga (brez dokaza »dodan tu«) dobi povezavo
        // samo, ce jo sprozi uporabnik ([uporabnik]: odprtje seznama, izrecna izbira zavihka TV v zivo) ali [rocno]; odprtje seznama in
        // mreza TV v zivo si delita ta predpomnilnik, premor I5 in mejo 1-krat na minuto (en vir, ena prijava).
        // Grok 68, Ą5: odlocitev in rezervacija sta ena atomarna poteza - socasen »Poskusi znova«, prva uporaba in osvezitev v ozadju
        // naredijo najvec eno prijavo; kdor pride med prenosom, dobi, kar je na disku (brez zahteve).
        // Pregled 105, S3 (26. 10. 2026): rezervacija, meja 1-krat na minuto in premor I5 veljajo za RACUN ([racun]), ne za naslov -
        // prej sta dva seznama istega racuna (m3u in m3u_plus) hkrati odprla dve prijavi. Strozje od vira in racuna velja.
        val racun = racun(c, naslov)
        val odlocitev = prijave.rezerviraj(racun.prijava, naslov) {
            IptvPravila.odlociPripravo(naslov, MedijskiViri.uporabaDovoljena(c, naslov), rocno, podatki(c, naslov)?.prenesenOb,
                IptvPravila.strozjaNapaka(napaka(c, naslov), racun.poverilnica?.let { napakaRacuna(c, it) }),
                maxOf(zadnjiPoskus(c, naslov), zadnjiPoskusRacuna(c, racun.prijava)), System.currentTimeMillis(),
                dodanTuDokaz = MedijskiViri.dodanTuZDokazom(c, naslov), uporabnik = uporabnik)
        }
        val p = podatki(c, naslov)
        return when (odlocitev) {
            null, IptvPravila.Priprava.BREZ -> p
            IptvPravila.Priprava.V_OZADJU -> {
                try { Thread({ prenesi(c, naslov, racun) }, "safeer-iptv").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start() }
                catch (e: Throwable) { prijave.sprosti(racun.prijava); throw e }
                p
            }
            IptvPravila.Priprava.ZDAJ -> prenesi(c, naslov, racun)
        }
    }

    /**
     * Pregled 105, S3: kljuca racuna vira - [prijava] za rezervacijo in mejo 1-krat na minuto ([IptvPravila.kljucRacuna]), [poverilnica]
     * za premor I5 ([IptvPravila.kljucPoverilnice], null = premor samo po viru). Javni seznam in vir, cigar skrivnosti ta naprava
     * ne zna prebrati (prenosa tako ni), ostaneta po naslovu kot doslej. Pravi naslov samo za odtis: v kljucih ni uporabnika ne gesla.
     */
    private class Racun(val prijava: String, val poverilnica: String?)
    private fun racun(c: Context, naslov: String): Racun {
        if (!IptvPravila.virZRacunom(MedijskiViri.SEZNAM, naslov)) return Racun(naslov, null)
        val pravi = try { SkrivniDodatki.dejanski(c, naslov) } catch (_: Exception) { return Racun(naslov, null) }
        if (SkrivnostiPravila.jeNadomestek(pravi)) return Racun(naslov, null)
        return Racun(IptvPravila.kljucRacuna(pravi), IptvPravila.kljucPoverilnice(pravi))
    }

    /** Grok 60, Ů1: zadnja prijava v racun seznama (ms, uspela ali ne). */
    private const val PREFS_POSKUSI = "safeer_iptv_seznami_poskusi"
    private fun poskusi(c: Context) = c.applicationContext.getSharedPreferences(PREFS_POSKUSI, Context.MODE_PRIVATE)
    private fun kljucPoskusa(naslov: String) = "p:" + KanaliPravila.odtisDodatka(naslov)
    /** Pregled 105, S3: zadnji poskus RACUNA (kljuc [Racun.prijava]); predpona r: se ne sreca s p: vira. */
    private fun kljucPoskusaRacuna(racun: String) = "r:" + KanaliPravila.odtisDodatka(racun)
    private fun zadnjiPoskusRacuna(c: Context, racun: String): Long =
        try { poskusi(c).getLong(kljucPoskusaRacuna(racun), 0L) } catch (_: Exception) { 0L }

    /** Grok 60, Ů1: kdaj smo se nazadnje poskusili prijaviti v racun seznama (0 = nikoli; javni seznami se ne zapisujejo). */
    fun zadnjiPoskus(c: Context, naslov: String): Long = try { poskusi(c).getLong(kljucPoskusa(naslov), 0L) } catch (_: Exception) { 0L }

    /** Grok 60, Ů1: tik pred zahtevo, ki se prijavi v racun [naslov] (samo vir z racunom): od tu steje meja 1-krat na minuto. */
    fun zapisiPoskus(c: Context, naslov: String, racun: String? = null) {
        if (!IptvPravila.virZRacunom(MedijskiViri.SEZNAM, naslov)) return
        try {
            val zdaj = System.currentTimeMillis()
            val e = poskusi(c).edit().putLong(kljucPoskusa(naslov), zdaj)
            // Pregled 105, S3: tudi za racun - drug vir istega racuna v isti minuti ne odpre nove prijave.
            if (racun != null) e.putLong(kljucPoskusaRacuna(racun), zdaj)
            e.apply()
        } catch (_: Exception) { }
    }

    /** Grok 39, I5: napaka zadnjega prenosa seznama (za kartico vira in premor); null = je ni. */
    fun napaka(c: Context, naslov: String): IptvPravila.NapakaPrenosa? = try {
        IptvPravila.NapakaPrenosa.izNiza(c.applicationContext.getSharedPreferences(PREFS_NAPAKE, Context.MODE_PRIVATE)
            .getString(KanaliPravila.odtisDodatka(naslov), null))
    } catch (_: Exception) { null }

    private fun zapisiNapako(c: Context, naslov: String, n: IptvPravila.NapakaPrenosa?) {
        try {
            val e = c.applicationContext.getSharedPreferences(PREFS_NAPAKE, Context.MODE_PRIVATE).edit()
            if (n == null) e.remove(KanaliPravila.odtisDodatka(naslov)) else e.putString(KanaliPravila.odtisDodatka(naslov), n.vNiz())
            e.apply()
        } catch (_: Exception) { }
    }

    /**
     * Pregled 105, S3: napaka (premor I5) poverilnice [kljuc] ([Racun.poverilnica]) - zavrnjeno geslo velja za vse vire racuna.
     * Kartica vira kaze napako vira ([napaka]). Kljuc r:<odtis> se ne sreca z odtisom naslova vira.
     */
    private fun kljucNapakeRacuna(kljuc: String) = "r:" + KanaliPravila.odtisDodatka(kljuc)
    private fun napakaRacuna(c: Context, kljuc: String): IptvPravila.NapakaPrenosa? = try {
        IptvPravila.NapakaPrenosa.izNiza(c.applicationContext.getSharedPreferences(PREFS_NAPAKE, Context.MODE_PRIVATE)
            .getString(kljucNapakeRacuna(kljuc), null))
    } catch (_: Exception) { null }
    private fun zapisiNapakoRacuna(c: Context, kljuc: String?, n: IptvPravila.NapakaPrenosa?) {
        if (kljuc == null) return
        try {
            val e = c.applicationContext.getSharedPreferences(PREFS_NAPAKE, Context.MODE_PRIVATE).edit()
            if (n == null) e.remove(kljucNapakeRacuna(kljuc)) else e.putString(kljucNapakeRacuna(kljuc), n.vNiz())
            e.apply()
        } catch (_: Exception) { }
    }

    /** Neuspel prenos ([koda] 0 = napaka povezave): premor po [IptvPravila.poNapaki], ce je to napaka s premorom. */
    private fun poNeuspehu(c: Context, naslov: String, koda: Int, kanalov: Int, poverilnica: String? = null) {
        if (!IptvPravila.napakaPrenosa(koda, kanalov, naslov)) return
        val zdaj = System.currentTimeMillis()
        val zavrnjen = IptvPravila.zavrnjen(koda, kanalov, naslov)
        val n = IptvPravila.poNapaki(napaka(c, naslov), zdaj, zavrnjen)
        zapisiNapako(c, naslov, n)
        // Pregled 105, S3: premor tudi za racun (svoje stetje - vec virov istega racuna ne podaljsa premora vsakega vira posebej).
        if (poverilnica != null) zapisiNapakoRacuna(c, poverilnica, IptvPravila.poNapaki(napakaRacuna(c, poverilnica), zdaj, zavrnjen))
        android.util.Log.i("SafeerOsMedia", "seznam IPTV: prenos ni uspel (${if (koda > 0) "HTTP $koda" else "povezava"}, kanalov=$kanalov, " +  // dnevnik-ok: samo koda HTTP ali beseda, nikoli naslov
            "zavrnjen=${n.zavrnjen}), ${n.zaporednih}. zapored, naslednji poskus cez ${(n.naslednjiOb - n.zadnjiOb) / 60_000} min")
    }

    /** Prenese in razcleni seznam tokovno v zacasno datoteko, nato jo zamenja (atomarno). Klic iz ozadja. */
    private fun prenesi(c: Context, naslov: String, racun: Racun): Podatki? {
        // Lastnik, 26. 10. 2026: seznam z racunom je v aplikaciji nadomestek - pravi naslov samo za zahtevo in relativne naslove.
        // Skrivnosti ta naprava ne zna prebrati (Keystore): nobene zahteve (na nadomestek ali s starim geslom).
        // Rezervacijo je naredil [pripravi] ([prijave], kljuc [Racun.prijava]); vsak izhod jo sprosti z ISTIM kljucem.
        val pravi = try { SkrivniDodatki.dejanski(c, naslov) } catch (e: Throwable) { prijave.sprosti(racun.prijava); throw e }
        if (SkrivnostiPravila.jeNadomestek(pravi)) { prijave.sprosti(racun.prijava); return podatki(c, naslov) }
        zapisiPoskus(c, naslov, racun.prijava)
        val od = android.os.SystemClock.uptimeMillis()
        try {
            val (drzava, jezik) = IptvPravila.izNaslovaSeznama(pravi)
            val p = (URL(pravi).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 15_000; setRequestProperty("User-Agent", "SafeerOS")
            }
            try {
                val koda = p.responseCode
                if (koda !in 200..299) { poNeuspehu(c, naslov, koda, 0, racun.poverilnica); return podatki(c, naslov) }
                // Naloga 87: z USB v uporabi gre seznam (do 20 MB) na USB - notranji pomnilnik TV ostane prost. Seznam z racunom
                // (tokovi nosijo uporabnika in geslo) nikoli na USB - ta je berljiv vsakomur, ki ga vtakne drugam (26. 10. 2026).
                val m = ((if (IptvPravila.virZRacunom(MedijskiViri.SEZNAM, naslov)) null else mapaUsb(c)) ?: mapa(c)).apply { mkdirs() }
                val zacasna = File(m, ime(naslov) + ".tmp")
                val bralnik = IptvPravila.Bralnik(pravi, drzava, jezik)
                // Grok 68: pogled seznama z racunom dobi skladbe iz ISTEGA prenosa (ena prijava): prvih 2 mio znakov besedila.
                val zaPogled = IptvPravila.virZRacunom(MedijskiViri.SEZNAM, naslov)
                val besedilo = StringBuilder()
                val zvrsti = HashSet<String>(); val jeziki = HashSet<String>()
                zacasna.bufferedWriter().use { w ->
                    p.inputStream.bufferedReader().use { r ->
                        // Naloga 86 (M7): vrstica najvec [IptvPravila.NAJVEC_VRSTICA] znakov (daljsa se preskoci, ne bere v pomnilnik),
                        // skupaj najvec [NAJVEC_BAJTOV] - prej je readLine() prebral celo vrstico brez preloma (OOM).
                        IptvPravila.vrstice(r, IptvPravila.NAJVEC_VRSTICA, NAJVEC_BAJTOV) { l ->
                            if (zaPogled && besedilo.length < IptvPravila.NAJVEC_ZNAKOV_SEZNAMA) besedilo.append(l).append('\n')
                            val v = bralnik.vrstica(l) ?: return@vrstice
                            if (v.zvrst.isNotEmpty()) zvrsti += v.zvrst
                            if (v.jezik.isNotEmpty()) jeziki += v.jezik
                            w.write(IptvPravila.vVrstico(v)); w.newLine()
                        }
                    }
                }
                // Grok 39, I5: seznam z racunom brez kanalov (potekel racun) ni nov seznam - zadnji znani ostane, premor tece.
                if (IptvPravila.napakaPrenosa(koda, bralnik.kanalov, naslov)) { zacasna.delete(); poNeuspehu(c, naslov, koda, bralnik.kanalov, racun.poverilnica); return podatki(c, naslov) }
                zapisiNapako(c, naslov, null)
                zapisiNapakoRacuna(c, racun.poverilnica, null)
                val podatkiNovi = Podatki(System.currentTimeMillis(), bralnik.kanalov, zvrsti, jeziki)
                val cilj = File(m, ime(naslov) + ".tsv")
                if (!zacasna.renameTo(cilj)) { cilj.delete(); if (!zacasna.renameTo(cilj)) { zacasna.delete(); return podatki(c, naslov) } }
                File(m, ime(naslov) + ".info").writeText("${podatkiNovi.prenesenOb}\n${podatkiNovi.kanalov}\n${zvrsti.joinToString(",")}\n${jeziki.joinToString(",")}")
                // Stara kopija na drugem mestu (notranji <-> USB) ni vec potrebna.
                for (drug in listOfNotNull(mapa(c), mapaUsb(c)).filter { it.path != m.path })
                    for (pr in listOf(".tsv", ".info")) try { File(drug, ime(naslov) + pr).delete() } catch (_: Throwable) { }
                podatki[naslov] = podatkiNovi
                if (zaPogled) MedijskiViri.shraniSkladbeSeznama(c, naslov, pravi, besedilo.toString())
                android.util.Log.i("SafeerOsMedia", "seznam IPTV: kanalov=${bralnik.kanalov}, zvrsti=${zvrsti.size}, jeziki=${jeziki.size}, " +
                    "drzava seznama=${drzava.ifEmpty { "-" }}, jezik seznama=${jezik.ifEmpty { "-" }}, po ${android.os.SystemClock.uptimeMillis() - od} ms")
                return podatkiNovi
            } finally { try { p.disconnect() } catch (_: Throwable) { } }
        } catch (_: Exception) {
            poNeuspehu(c, naslov, 0, 0, racun.poverilnica)
            // Naloga 87: napaka pisanja na USB (odklop, poln) - do naslednje preverbe notranji.
            if (mapaUsb(c) != null) UsbPredpomnilnik.napaka()
            for (m in listOfNotNull(mapa(c), mapaUsb(c))) try { File(m, ime(naslov) + ".tmp").delete() } catch (_: Throwable) { }
            return podatki(c, naslov)
        } catch (_: OutOfMemoryError) {
            // Naloga 86 (M7): pomnilnika je zmanjkalo (sibak TV) - seznam ostane, kot je bil; proces ne pade.
            for (m in listOfNotNull(mapa(c), mapaUsb(c))) try { File(m, ime(naslov) + ".tmp").delete() } catch (_: Throwable) { }
            return podatki(c, naslov)
        } finally { prijave.sprosti(racun.prijava) }
    }

    /**
     * Stran kanalov seznama za mrezo: [v] pove izbiro (zvrst, jezik), ali so to domaci ([Vir.domaci]) ali ostali, in koliko
     * jih je ze dal. Datoteko bere vrstico za vrstico; vrne najvec [najvec] kanalov. Klic iz ozadja.
     */
    fun naslednja(c: Context, v: Vir, drzavaNaprave: String, jezikNaprave: String, najvec: Int = 100): List<Jamendo.Skladba> {
        if (v.konec) return emptyList()
        val f = datoteka(c, v.naslov)
        if (!f.exists()) { v.konec = true; return emptyList() }
        val izhod = ArrayList<Jamendo.Skladba>(najvec)
        var preskoci = v.dal
        var konec = true
        try {
            f.bufferedReader().useLines { vrstice ->
                for (l in vrstice) {
                    val x = IptvPravila.izVrstice(l) ?: continue
                    if (!IptvPravila.ustreza(x, v.zvrst, v.jezik) || IptvPravila.jeDomac(x, drzavaNaprave, jezikNaprave) != v.domaci) continue
                    if (preskoci > 0) { preskoci--; continue }
                    if (izhod.size >= najvec) { konec = false; break }
                    izhod += skladba(x, v.naslov)
                }
            }
        } catch (_: Exception) {
            // Naloga 87: branje z USB ni uspelo (odklop) - do naslednje preverbe notranji; pripravi() seznam prenese znova.
            if (!f.path.startsWith(mapa(c).path)) UsbPredpomnilnik.napaka()
        }
        v.dal += izhod.size
        v.konec = konec
        return izhod
    }

    /** Vsi kanali seznama po vrsti (za pregled mrtvih, [PregledKanalov]); [obVnosu] vrne false = ustavi. Klic iz ozadja. */
    fun zaVsakega(c: Context, naslov: String, obVnosu: (Jamendo.Skladba) -> Boolean) {
        val f = datoteka(c, naslov)
        if (!f.exists()) return
        try { f.bufferedReader().useLines { vrstice -> for (l in vrstice) { val x = IptvPravila.izVrstice(l) ?: continue; if (!obVnosu(skladba(x, naslov))) break } } }
        catch (_: Exception) { }
    }

    /** Kartica kanala: logo, ime brez tehnicnih pripon, tok (HLS), zvrst in jezik; [Jamendo.Skladba.streznik] = seznam, iz katerega je. */
    fun skladba(v: IptvPravila.Vnos, seznam: String) = Jamendo.Skladba(PREDPONA + v.url, KanaliPravila.imeKanala(v.ime), "", v.logo, v.url, v.url,
        video = true, mime = if (v.url.substringBefore('?').lowercase().endsWith(".m3u8")) MedijskiViri.MIME_HLS else "",
        streznik = seznam, genres = listOfNotNull(v.zvrst.takeIf { it.isNotEmpty() }), language = v.jezik)

    /**
     * Prehod vira z racunom na nadomestek (lastnik, 26. 10. 2026): datoteki kanalov in napaka prenosa iz imena po pravem
     * naslovu [staro] v ime po nadomestku [novo] - brez novega prenosa (vsak je prijava v racun). Samo notranji pomnilnik;
     * kopijo na USB (iz glavne niti je ne vidimo) pospravi [obdrzi], seznam se takrat prenese na notranji.
     */
    fun preseli(c: Context, staro: String, novo: String) {
        val m = mapa(c)
        for (pr in listOf(".tsv", ".info")) {
            val f = File(m, ime(staro) + pr)
            if (f.exists() && !f.renameTo(File(m, ime(novo) + pr))) f.delete()
        }
        podatki.remove(staro)?.let { podatki[novo] = it }
        napaka(c, staro)?.let { zapisiNapako(c, novo, it) }
        zapisiNapako(c, staro, null)
    }

    /** Seznam je odstranjen: datoteki izgineta. */
    fun pozabi(c: Context, naslov: String) {
        podatki.remove(naslov)
        zapisiNapako(c, naslov, null)
        try { poskusi(c).edit().remove(kljucPoskusa(naslov)).apply() } catch (_: Exception) { }
        for (m in listOfNotNull(mapa(c), mapaUsb(c))) for (pr in listOf(".tsv", ".info")) try { File(m, ime(naslov) + pr).delete() } catch (_: Exception) { }
    }

    /** Naloga 87: »Pocisti predpomnilnik« - datoteke izbrise klicatelj, tu pozabimo podatke v pomnilniku. */
    fun pozabiVse() = podatki.clear()

    /**
     * Naloga 86 (M5): obdrzi samo datoteke seznamov [naslovi] (vsi viri SEZNAM uporabnika); ostale (.tsv, .info, .tmp) izbrise.
     * Seznam, ki se prenasa, ostane. Klic iz ozadja.
     */
    fun obdrzi(c: Context, naslovi: Collection<String>) {
        val imena = (naslovi + prijave.vsi()).map { ime(it) }.toSet()
        for (f in listOfNotNull(mapa(c), mapaUsb(c)).flatMap { it.listFiles().orEmpty().toList() }) {
            if (f.name.substringBefore('.') in imena) continue
            podatki.keys.filter { ime(it) == f.name.substringBefore('.') }.forEach { podatki.remove(it) }
            try { f.delete() } catch (_: Exception) { }
        }
    }

    /** Vir seznama v mrezi TV v zivo: izbira mreze, domaci ali ostali, koliko kanalov je ze dal. */
    class Vir(val naslov: String, val domaci: Boolean, val zvrst: String, val jezik: String) {
        @Volatile var dal = 0
        @Volatile var konec = false
    }
}
