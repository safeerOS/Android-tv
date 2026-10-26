package si.safeer.tv.os

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TreeMap

// Lokalni stevec virov predvajanja, enak core/stevec_virov.py (naloga 66).
// Zakaj: odlocitev o narocninah mora temeljiti na meritvi. Podatki ostanejo
// na napravi: samo vir, dan, stevila, vsota trajanja in omejen vzorec casov.
// Brez Android API in brez knjiznice JSON (naloga 69): JVM preizkusi v
// tests/run_os_tests.sh je nimajo na poti razredov, zato JSON beremo in pisemo sami.
class StevecVirov(datoteka: File) {

    // Eno vedro = en kljuc vira na en dan (kot slovar v Pythonu).
    // Neznane kljuce vedra Python ohrani in zapise nazaj, zato jih hranimo tudi tu.
    private class Vedro {
        var predvajal = 0L
        var kratki = 0L
        var neuspehi = 0L
        var sekunde = 0L
        val casi = ArrayList<Long>()
        val dodatno = LinkedHashMap<String, Any?>()
    }

    // Vse, kar pride z diska: dnevi, prvotna verzija (1, 1.0 ali true) in
    // neznani kljuci na vrhu, ki jih Python ob zapisu ohrani.
    private class Podatki(
        val dnevi: TreeMap<String, TreeMap<String, Vedro>>,
        val verzija: Any,
        val dodatno: Map<String, Any?>,
    )

    private val pot: File = datoteka.absoluteFile
    private val zaklep = Any()

    private val podatki: Podatki = nalozi()

    // TreeMap: urejeni dnevi in kljuci dajo enak vrstni red kot sort_keys v Pythonu.
    private val dnevi: TreeMap<String, TreeMap<String, Vedro>> = podatki.dnevi

    companion object {
        val VIRI: List<String> = listOf(
            "debrid_takoj",
            "usenet_torbox",
            "usenet_lasten",
            "usenet_cakanje",
            "webtor",
            "debrid_cakanje",
            "p2p",
            "iptv",
            "drugo",
        )
        val NAROCNINE: List<String> = listOf("webtor", "torbox")

        // Viri, pri katerih je storitev lahko torbox; zastavico hranimo v kljucu.
        private val VIRI_S_STORITVIJO = listOf("debrid_takoj", "debrid_cakanje")
        private const val PRIPONA_TORBOX = "+torbox"

        const val VRSTA = "safeer-stevec-virov"
        const val VERZIJA = 1
        const val MIN_TRAJANJE_S = 60L
        const val MAX_DNI = 120L
        const val MAX_BAJTOV = 64 * 1024
        const val MAX_VZOREC = 50
        const val MIN_PREDVAJANJ = 10L

        // Enako kot f.read(MAX_BAJTOV * 4 + 1) v Pythonu: rocno urejena (zamaknjena)
        // datoteka je lahko vecja od 64 KiB in je se vedno veljavna; vec od tega ne beremo.
        const val MAX_BRANJE = MAX_BAJTOV * 4 + 1

        private val POLJA_VEDRA = setOf("casi", "kratki", "neuspehi", "predvajal", "sekunde")
        private val POLJA_VRHA = setOf("dnevi", "verzija", "vrsta")

        private val KLJUCI_VEDER: Set<String> =
            VIRI.toSet() + VIRI_S_STORITVIJO.map { it + PRIPONA_TORBOX }

        private val VZOREC_DNEVA = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

        // Neznan ali napacen vir gre v drugo, da klic nikoli ne pade.
        fun normalizirajVir(vir: String?): String =
            if (vir != null && vir in VIRI) vir else "drugo"

        // Primerjamo brez velikosti crk in presledkov; drugega ne hranimo.
        fun jeTorbox(storitev: String?): Boolean =
            storitev != null && storitev.trim().lowercase() == "torbox"

        // Najblizji rang s celimi stevili, da je rezultat enak kot v Pythonu
        // in vedno izmerjena vrednost; pri sodem n spodnja srednja.
        fun percentil(vrednosti: List<Long>, p: Int): Long? {
            if (vrednosti.isEmpty()) return null
            val urejene = vrednosti.sorted()
            val n = urejene.size
            var rang = (p * n + 99) / 100
            rang = maxOf(1, minOf(n, rang))
            return urejene[rang - 1]
        }

        // Pol navzgor s celimi stevili; Math.round ali double bi lahko dal drug rezultat.
        fun zaokrozi(stevec: Long, imenovalec: Long, mest: Int): Double {
            if (imenovalec <= 0) return 0.0
            var faktor = 1L
            repeat(mest) { faktor *= 10 }
            val celo = Math.floorDiv(2 * stevec * faktor + imenovalec, 2 * imenovalec)
            return celo.toDouble() / faktor.toDouble()
        }

        // Ms od epohe v lokalni datum naprave, enako kot astimezone() v Pythonu.
        fun lokalniDan(zdajMs: Long): LocalDate =
            Instant.ofEpochMilli(zdajMs).atZone(ZoneId.systemDefault()).toLocalDate()

        // Povzetek (ali drugo vrednost) kot besedilo, enako json.dumps(sort_keys=True,
        // separators=(",", ":")) v Pythonu, da ga lahko posljemo naprej brez knjiznice.
        fun vJson(vrednost: Any?): String = Json.zapisi(vrednost)
    }

    // ---- javni vmesnik ----

    // Zabelezi en ogled. Vrne false ob napaki, izjeme ne spusti ven.
    // Predvajal steje le uspeh z vsaj 60 s, ker kratek preizkus ni raba.
    fun zabelezi(
        vir: String?,
        uspeh: Boolean,
        casDoSlikeMs: Long?,
        trajanjeS: Long,
        storitev: String? = null,
        zdaj: LocalDate = LocalDate.now(),
    ): Boolean = zabeleziDan(vir, uspeh, casDoSlikeMs, trajanjeS, storitev, zdaj)

    // Lokalni cas brez pasu: velja njegov datum (kot naivni datetime v Pythonu).
    fun zabelezi(
        vir: String?,
        uspeh: Boolean,
        casDoSlikeMs: Long?,
        trajanjeS: Long,
        storitev: String? = null,
        zdaj: LocalDateTime,
    ): Boolean = zabeleziDan(vir, uspeh, casDoSlikeMs, trajanjeS, storitev, zdaj.toLocalDate())

    // Cas v ms od epohe (System.currentTimeMillis) se pretvori v lokalni datum.
    fun zabelezi(
        vir: String?,
        uspeh: Boolean,
        casDoSlikeMs: Long?,
        trajanjeS: Long,
        storitev: String? = null,
        zdajMs: Long,
    ): Boolean {
        val danes = try {
            lokalniDan(zdajMs)
        } catch (e: Exception) {
            return false
        }
        return zabeleziDan(vir, uspeh, casDoSlikeMs, trajanjeS, storitev, danes)
    }

    // Povzetek zadnjih dni (vkljucno z danes) in priporocila narocnin.
    // Oblika kot slovar v Pythonu: Map (vrstni red kljucev kot v Pythonu), cela
    // stevila so Long, decimalke Double, manjkajoca vrednost je null.
    fun povzetek(dni: Int = 7, zdaj: LocalDate = LocalDate.now()): Map<String, Any?> {
        return try {
            povzetekDan(dni, zdaj)
        } catch (e: Exception) {
            // Nikoli izjema navzven: raje prazen povzetek kot padec zaslona.
            try {
                izracunajPovzetek(maxOf(1, dni), zdaj, emptyList())
            } catch (e2: Exception) {
                // nemogoce okno (npr. ogromen dni): en dan je varna izbira
                izracunajPovzetek(1, zdaj, emptyList())
            }
        }
    }

    fun povzetek(dni: Int = 7, zdaj: LocalDateTime): Map<String, Any?> = povzetek(dni, zdaj.toLocalDate())

    fun povzetek(dni: Int = 7, zdajMs: Long): Map<String, Any?> {
        val danes = try {
            lokalniDan(zdajMs)
        } catch (e: Exception) {
            LocalDate.now()
        }
        return povzetek(dni, danes)
    }

    // ---- belezenje ----

    private fun zabeleziDan(
        vir: String?,
        uspeh: Boolean,
        casDoSlikeMs: Long?,
        trajanjeS: Long,
        storitev: String?,
        danes: LocalDate,
    ): Boolean {
        return try {
            val normaliziran = normalizirajVir(vir)
            val kljuc = if (normaliziran in VIRI_S_STORITVIJO && jeTorbox(storitev)) {
                normaliziran + PRIPONA_TORBOX
            } else {
                normaliziran
            }
            val trajanje = if (trajanjeS > 0) trajanjeS else 0L
            val cas = if (casDoSlikeMs != null && casDoSlikeMs >= 0) casDoSlikeMs else null
            synchronized(zaklep) {
                val dan = dnevi.getOrPut(danes.toString()) { TreeMap() }
                val vedro = dan.getOrPut(kljuc) { Vedro() }
                if (!uspeh) {
                    vedro.neuspehi += 1
                } else {
                    if (trajanje >= MIN_TRAJANJE_S) {
                        vedro.predvajal += 1
                        vedro.sekunde += trajanje
                    } else {
                        vedro.kratki += 1
                    }
                    // Prvih 50 vrednosti je dovolj za oceno in ohrani velikost.
                    if (cas != null && vedro.casi.size < MAX_VZOREC) {
                        vedro.casi.add(cas)
                    }
                }
                val vsebina = obrezi(danes)
                zapisiAtomarno(vsebina)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // ---- povzetek ----

    private class Zbir {
        var predvajal = 0L
        var kratki = 0L
        var neuspehi = 0L
        var sekunde = 0L
        val casi = ArrayList<Long>()
    }

    private class Kos(val kljuc: String, val predvajal: Long, val kratki: Long, val neuspehi: Long, val sekunde: Long, val casi: List<Long>)

    private fun povzetekDan(dni: Int, danes: LocalDate): Map<String, Any?> {
        val dniInt = maxOf(1, dni)
        val od = danes.minusDays((dniInt - 1).toLong())
        val odIso = od.toString()
        val doIso = danes.toString()
        // Pod zaklepom samo kopiramo, racunamo zunaj, da pisanje ne caka.
        val kosi = ArrayList<Kos>()
        synchronized(zaklep) {
            // subMap namesto continue: okno [od, do] vkljucno na obeh koncih.
            for (vedra in dnevi.subMap(odIso, true, doIso, true).values) {
                for ((kljuc, v) in vedra) {
                    kosi.add(Kos(kljuc, v.predvajal, v.kratki, v.neuspehi, v.sekunde, ArrayList(v.casi)))
                }
            }
        }
        return izracunajPovzetek(dniInt, danes, kosi)
    }

    private fun izracunajPovzetek(dniInt: Int, danes: LocalDate, kosi: List<Kos>): Map<String, Any?> {
        val od = danes.minusDays((dniInt - 1).toLong())
        val zbir = LinkedHashMap<String, Zbir>()
        for (v in VIRI) zbir[v] = Zbir()
        var torboxPredvajal = 0L
        for (k in kosi) {
            val vir = k.kljuc.substringBefore('+')
            // Kljuci so preverjeni ob branju, zato cilj vedno obstaja.
            val cilj = zbir.getValue(vir)
            cilj.predvajal += k.predvajal
            cilj.kratki += k.kratki
            cilj.neuspehi += k.neuspehi
            cilj.sekunde += k.sekunde
            cilj.casi.addAll(k.casi)
            if (k.kljuc.endsWith(PRIPONA_TORBOX) || vir == "usenet_torbox") {
                torboxPredvajal += k.predvajal
            }
        }

        var skupaj = 0L
        var skupajNeuspehi = 0L
        for (z in zbir.values) {
            skupaj += z.predvajal
            skupajNeuspehi += z.neuspehi
        }

        val viri = LinkedHashMap<String, Any?>()
        for ((vir, z) in zbir) {
            val o = LinkedHashMap<String, Any?>()
            o["predvajal"] = z.predvajal
            o["kratki"] = z.kratki
            o["neuspehi"] = z.neuspehi
            o["ure_gledanja"] = zaokrozi(z.sekunde, 3600, 2)
            o["mediana_ms"] = percentil(z.casi, 50)
            o["p90_ms"] = percentil(z.casi, 90)
            o["delez"] = zaokrozi(z.predvajal, skupaj, 4)
            viri[vir] = o
        }

        val narocnine = LinkedHashMap<String, Any?>()
        val pari = listOf("webtor" to (zbir["webtor"]?.predvajal ?: 0L), "torbox" to torboxPredvajal)
        for ((ime, predvajal) in pari) {
            val priporocilo = when {
                skupaj < MIN_PREDVAJANJ -> "premalo_podatkov"
                predvajal == 0L -> "ni_rabe"
                else -> "obdrzi"
            }
            val o = LinkedHashMap<String, Any?>()
            o["predvajal"] = predvajal
            o["delez"] = zaokrozi(predvajal, skupaj, 4)
            o["priporocilo"] = priporocilo
            narocnine[ime] = o
        }

        val izid = LinkedHashMap<String, Any?>()
        izid["dni"] = dniInt.toLong()
        izid["od"] = od.toString()
        izid["do"] = danes.toString()
        izid["skupaj_predvajal"] = skupaj
        izid["skupaj_neuspehi"] = skupajNeuspehi
        izid["viri"] = viri
        izid["narocnine"] = narocnine
        return izid
    }

    // ---- zapis na disk ----

    // Najprej starost, nato velikost: najstarejsi dnevi gredo prvi.
    private fun obrezi(danes: LocalDate): ByteArray {
        val meja = danes.minusDays(MAX_DNI - 1).toString()
        dnevi.headMap(meja).clear()
        while (true) {
            val vsebina = serializiraj()
            if (vsebina.size <= MAX_BAJTOV || dnevi.size <= 1) return vsebina
            dnevi.remove(dnevi.firstKey())
        }
    }

    // Vse gre skozi isti pisalnik kot json.dumps(sort_keys=True, separators=(",", ":")),
    // da so bajti enaki Pythonu tudi z neznanimi kljuci in verzijo 1.0 ali true.
    private fun serializiraj(): ByteArray {
        val vrh = LinkedHashMap<String, Any?>(podatki.dodatno)
        val dneviZapis = LinkedHashMap<String, Any?>()
        for ((dan, vedra) in dnevi) {
            val vedraZapis = LinkedHashMap<String, Any?>()
            for ((kljuc, v) in vedra) {
                val vedroZapis = LinkedHashMap<String, Any?>(v.dodatno)
                vedroZapis["casi"] = v.casi
                vedroZapis["kratki"] = v.kratki
                vedroZapis["neuspehi"] = v.neuspehi
                vedroZapis["predvajal"] = v.predvajal
                vedroZapis["sekunde"] = v.sekunde
                vedraZapis[kljuc] = vedroZapis
            }
            dneviZapis[dan] = vedraZapis
        }
        vrh["dnevi"] = dneviZapis
        vrh["verzija"] = podatki.verzija
        vrh["vrsta"] = VRSTA
        // Pisalnik ubeza vse, kar ni ASCII (ensure_ascii), zato je UTF-8 kar ASCII.
        return Json.zapisi(vrh).toByteArray(Charsets.UTF_8)
    }

    // Zacasna datoteka v isti mapi + preimenovanje: bralec vidi staro ali novo, nikoli pol.
    private fun zapisiAtomarno(vsebina: ByteArray) {
        val mapa = pot.parentFile ?: File(".").absoluteFile
        ustvariMapo(mapa)
        val zacasna = ustvariZacasno(mapa)
        try {
            samoLastnik(zacasna)
            FileOutputStream(zacasna).use { izhod ->
                izhod.write(vsebina)
                izhod.flush()
                izhod.fd.sync()
            }
            premakni(zacasna, pot)
        } catch (e: Exception) {
            zacasna.delete()
            throw e
        }
    }

    private fun ustvariMapo(mapa: File) {
        if (mapa.isDirectory) return
        try {
            try {
                val pravice = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
                Files.createDirectories(mapa.toPath(), pravice)
            } catch (e: UnsupportedOperationException) {
                Files.createDirectories(mapa.toPath())
            }
        } catch (e: LinkageError) {
            // Starejsi Android brez java.nio.file: navaden mkdirs.
            mapa.mkdirs()
            samoLastnik(mapa)
        }
    }

    private fun ustvariZacasno(mapa: File): File {
        return try {
            // Na POSIX nastane takoj z 0600, brez okna z odprtimi pravicami.
            Files.createTempFile(mapa.toPath(), ".stevec-", ".tmp").toFile()
        } catch (e: UnsupportedOperationException) {
            File.createTempFile(".stevec-", ".tmp", mapa)
        } catch (e: LinkageError) {
            File.createTempFile(".stevec-", ".tmp", mapa)
        }
    }

    // Samo lastnik sme brati in pisati; ce POSIX ni na voljo, vsaj java.io zastavice.
    private fun samoLastnik(datoteka: File) {
        try {
            val niz = if (datoteka.isDirectory) "rwx------" else "rw-------"
            Files.setPosixFilePermissions(datoteka.toPath(), PosixFilePermissions.fromString(niz))
            return
        } catch (e: Exception) {
            // padec na java.io spodaj
        } catch (e: LinkageError) {
            // padec na java.io spodaj
        }
        try {
            datoteka.setReadable(false, false)
            datoteka.setReadable(true, true)
            datoteka.setWritable(false, false)
            datoteka.setWritable(true, true)
            if (!datoteka.isDirectory) datoteka.setExecutable(false, false)
        } catch (e: Exception) {
            // pravice so samo dodatna zascita, zapis naj vseeno uspe
        }
    }

    // ATOMIC_MOVE kot os.replace; ce ga datotecni sistem ne zna, navaden premik z zamenjavo.
    private fun premakni(iz: File, v: File) {
        try {
            try {
                Files.move(iz.toPath(), v.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(iz.toPath(), v.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: LinkageError) {
            if (!iz.renameTo(v)) throw IOException("premik ni uspel")
        }
    }

    // ---- branje ----

    private fun prazniPodatki(): Podatki = Podatki(TreeMap(), VERZIJA.toLong(), emptyMap())

    // Nikoli izjema: brez datoteke ali ob napaki zacnemo znova.
    private fun nalozi(): Podatki {
        try {
            if (!pot.exists()) return prazniPodatki()
            val surovo = try {
                FileInputStream(pot).use { beriNajvec(it, MAX_BRANJE) }
            } catch (e: IOException) {
                odmakniPokvarjeno()
                return prazniPodatki()
            }
            val prebrano = razcleni(surovo)
            if (prebrano == null) {
                odmakniPokvarjeno()
                return prazniPodatki()
            }
            return prebrano
        } catch (e: Exception) {
            return prazniPodatki()
        }
    }

    // Enaka meja kot v Pythonu: prevelika datoteka se odreze in je zato pokvarjena.
    private fun beriNajvec(vhod: InputStream, najvec: Int): ByteArray {
        val medpomnilnik = ByteArray(najvec)
        var skupaj = 0
        while (skupaj < najvec) {
            val n = vhod.read(medpomnilnik, skupaj, najvec - skupaj)
            if (n < 0) return medpomnilnik.copyOf(skupaj)
            skupaj += n
        }
        return medpomnilnik.copyOf(skupaj)
    }

    // Tujo ali pokvarjeno datoteko prepoznamo, preden jo prepisemo; null = neveljavna.
    // Pravila so ista kot _preveri v Pythonu.
    private fun razcleni(surovo: ByteArray): Podatki? {
        try {
            // Strog UTF-8 kot surovo.decode("utf-8"): napacni bajti so pokvarjena datoteka.
            val dekoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val besedilo = dekoder.decode(ByteBuffer.wrap(surovo)).toString()
            val koren = Json.beri(besedilo)
            if (koren !is Map<*, *>) return null
            if (koren["vrsta"] != VRSTA) return null
            val verzija = koren["verzija"]
            if (verzija == null || !jeEna(verzija)) return null
            val dneviJson = koren["dnevi"] as? Map<*, *> ?: return null
            val izid = TreeMap<String, TreeMap<String, Vedro>>()
            for ((dan, vedraJson) in dneviJson) {
                if (dan !is String || !jeDatum(dan)) return null
                if (vedraJson !is Map<*, *>) return null
                val vedra = TreeMap<String, Vedro>()
                for ((kljuc, vedroJson) in vedraJson) {
                    if (kljuc !is String || kljuc !in KLJUCI_VEDER) return null
                    vedra[kljuc] = preberiVedro(vedroJson) ?: return null
                }
                izid[dan] = vedra
            }
            val dodatno = LinkedHashMap<String, Any?>()
            for ((kljuc, vrednost) in koren) {
                if (kljuc is String && kljuc !in POLJA_VRHA) dodatno[kljuc] = vrednost
            }
            return Podatki(izid, verzija, dodatno)
        } catch (e: Exception) {
            return null
        } catch (e: StackOverflowError) {
            // Bralnik ima mejo globine, zato se to ne bi smelo zgoditi; ce se, je datoteka pokvarjena.
            return null
        }
    }

    // Python primerja verzija != 1, zato velja tudi 1.0 ali true; vrednost ohranimo,
    // ker jo Python ob naslednjem zapisu izpise nespremenjeno.
    private fun jeEna(vrednost: Any): Boolean = when (vrednost) {
        is Boolean -> vrednost
        is Long -> vrednost == 1L
        is Double -> vrednost == 1.0
        is BigInteger -> vrednost == BigInteger.ONE
        else -> false
    }

    // Samo YYYY-MM-DD: tako kljuc nastane ob zapisu. Python 3.11+ bi sprejel tudi
    // druge oblike ISO, a to je odvisno od razlicice, zato smo namerno strozji.
    private fun jeDatum(dan: String): Boolean {
        if (!VZOREC_DNEVA.matches(dan)) return false
        return try {
            val leto = dan.substring(0, 4).toInt()
            // Leto 0000 Java sprejme, Python date pa ne (najmanj leto 1).
            LocalDate.of(leto, dan.substring(5, 7).toInt(), dan.substring(8, 10).toInt())
            leto >= 1
        } catch (e: Exception) {
            false
        }
    }

    // Samo cela nenegativna stevila; decimalke in bool niso meritev.
    // Stevila zunaj Long (BigInteger) zavrnemo, ker jih stevec ne more sesteti.
    private fun celo(vrednost: Any?): Long? {
        if (vrednost !is Long) return null
        return if (vrednost >= 0) vrednost else null
    }

    private fun preberiVedro(vrednost: Any?): Vedro? {
        val o = vrednost as? Map<*, *> ?: return null
        val v = Vedro()
        v.predvajal = celo(o["predvajal"]) ?: return null
        v.kratki = celo(o["kratki"]) ?: return null
        v.neuspehi = celo(o["neuspehi"]) ?: return null
        v.sekunde = celo(o["sekunde"]) ?: return null
        val casi = o["casi"] as? List<*> ?: return null
        if (casi.size > MAX_VZOREC) return null
        for (c in casi) {
            v.casi.add(celo(c) ?: return null)
        }
        for ((kljuc, x) in o) {
            if (kljuc is String && kljuc !in POLJA_VEDRA) v.dodatno[kljuc] = x
        }
        return v
    }

    // Staro datoteko ohranimo za pregled, da se nic ne izgubi tiho.
    private fun odmakniPokvarjeno() {
        try {
            var cilj = File(pot.path + ".pokvarjeno")
            var i = 1
            while (cilj.exists()) {
                cilj = File(pot.path + ".pokvarjeno." + i)
                i += 1
            }
            premakni(pot, cilj)
        } catch (e: Exception) {
            // ce premik ne uspe, se vedno zacnemo s praznimi podatki
        }
    }

    // ---- JSON brez knjiznice ----

    // Majhen strog bralnik in pisalnik JSON, ki se obnasa kot json.loads in
    // json.dumps(sort_keys=True, separators=(",", ":")) v Pythonu.
    // Zakaj lasten in ne JsonLahki: JsonLahki bere samo ovojnico (prvo raven),
    // stevila vraca kot Double (1 in 1.0 bi bila enaka), sprejme vodilne nicle in
    // ne-ASCII stevke ter ima mejo globine 12. Tu potrebujemo celo drevo in
    // natancno pravilo Pythona za cela stevila, decimalke in nize.
    // Vrednosti: Map (LinkedHashMap), List, String, Long, BigInteger (cela zunaj
    // Long), Double, Boolean, null. internal, da ga preizkus uporabi za skupne primere.
    internal object Json {
        // Globlje od tega ne gremo: branje ne sme biti nacin, da nekdo porabi sklad.
        // Nasa datoteka je gnezdena 5 ravni; Python bi sel do priblizno 1000.
        const val NAJVECJA_GLOBINA = 64

        // Enako kot sys.get_int_max_str_digits() v Pythonu 3.11+: daljse celo stevilo
        // json.loads zavrne z ValueError, torej je datoteka pokvarjena.
        const val NAJVEC_STEVK = 4300

        // Oznaka za neveljaven vhod; null je veljavna vrednost JSON.
        val NEVELJAVNO: Any = Any()

        // Brez sledi sklada: napaka je pricakovan izid pri pokvarjeni datoteki, ne hrosc.
        private class Napaka : RuntimeException() {
            override fun fillInStackTrace(): Throwable = this
        }

        // Prebere en sam dokument JSON. Ob katerikoli napaki vrne NEVELJAVNO in nikoli
        // ne vrze izjeme, ker vhod pride z diska in mu ne zaupamo.
        fun beri(
            besedilo: String,
            najvecGlobina: Int = NAJVECJA_GLOBINA,
            najvecZnakov: Int = MAX_BRANJE,
        ): Any? {
            if (besedilo.length > najvecZnakov) return NEVELJAVNO
            return try {
                val b = Bralnik(besedilo, najvecGlobina)
                b.presledki()
                val v = b.vrednost(0)
                b.presledki()
                // Kot "Extra data" v Pythonu: za vrednostjo sme biti samo praznina.
                if (b.konec()) v else NEVELJAVNO
            } catch (e: Exception) {
                NEVELJAVNO
            } catch (e: StackOverflowError) {
                NEVELJAVNO
            }
        }

        private class Bralnik(private val s: String, private val najvecGlobina: Int) {
            private var i = 0

            private fun napaka(): Nothing = throw Napaka()

            fun konec(): Boolean = i >= s.length

            // Samo stirje znaki kot v Pythonu; BOM in drugi presledki Unicode niso praznina.
            fun presledki() {
                while (i < s.length) {
                    val c = s[i]
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else return
                }
            }

            fun vrednost(globina: Int): Any? {
                if (i >= s.length) napaka()
                return when (s[i]) {
                    '{' -> objekt(globina + 1)
                    '[' -> seznam(globina + 1)
                    '"' -> niz()
                    'n' -> beseda("null", null)
                    't' -> beseda("true", true)
                    'f' -> beseda("false", false)
                    // Python json.loads privzeto sprejme NaN in Infinity; da se ne razlikujemo.
                    'N' -> beseda("NaN", Double.NaN)
                    'I' -> beseda("Infinity", Double.POSITIVE_INFINITY)
                    '-' -> if (s.startsWith("-Infinity", i)) beseda("-Infinity", Double.NEGATIVE_INFINITY) else stevilo()
                    else -> stevilo()
                }
            }

            private fun beseda(beseda: String, vrednost: Any?): Any? {
                if (!s.startsWith(beseda, i)) napaka()
                i += beseda.length
                return vrednost
            }

            private fun objekt(globina: Int): Map<String, Any?> {
                if (globina > najvecGlobina) napaka()
                i++
                // Podvojen kljuc: zadnja vrednost, prvi polozaj, enako kot dict v Pythonu.
                val izid = LinkedHashMap<String, Any?>()
                presledki()
                if (i < s.length && s[i] == '}') {
                    i++
                    return izid
                }
                while (true) {
                    // Kljuc mora biti niz v narekovajih; gole besede so napaka kot v Pythonu.
                    if (i >= s.length || s[i] != '"') napaka()
                    val kljuc = niz()
                    presledki()
                    if (i >= s.length || s[i] != ':') napaka()
                    i++
                    presledki()
                    izid[kljuc] = vrednost(globina)
                    presledki()
                    if (i >= s.length) napaka()
                    val c = s[i]
                    i++
                    if (c == '}') return izid
                    if (c != ',') napaka()
                    presledki()
                }
            }

            private fun seznam(globina: Int): List<Any?> {
                if (globina > najvecGlobina) napaka()
                i++
                val izid = ArrayList<Any?>()
                presledki()
                if (i < s.length && s[i] == ']') {
                    i++
                    return izid
                }
                while (true) {
                    izid.add(vrednost(globina))
                    presledki()
                    if (i >= s.length) napaka()
                    val c = s[i]
                    i++
                    if (c == ']') return izid
                    if (c != ',') napaka()
                    presledki()
                }
            }

            private fun hex(c: Char): Int = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                // Character.digit bi sprejel tudi ne-ASCII stevke, Python ne.
                else -> napaka()
            }

            private fun niz(): String {
                i++
                var izpis: StringBuilder? = null
                var cisto = i
                while (true) {
                    if (i >= s.length) napaka()
                    val c = s[i]
                    if (c == '"') {
                        val izid = if (izpis == null) s.substring(cisto, i) else izpis.append(s, cisto, i).toString()
                        i++
                        return izid
                    }
                    // Neubezani nadzorni znaki niso dovoljeni (strict=True v Pythonu).
                    if (c.code < 0x20) napaka()
                    if (c != '\\') {
                        i++
                    } else {
                        val sb = izpis ?: StringBuilder()
                        izpis = sb
                        sb.append(s, cisto, i)
                        i++
                        if (i >= s.length) napaka()
                        when (s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) napaka()
                                val koda = (hex(s[i + 1]) shl 12) or (hex(s[i + 2]) shl 8) or
                                    (hex(s[i + 3]) shl 4) or hex(s[i + 4])
                                // Osamljen nadomestni znak ostane tak, kot ga pusti Python.
                                sb.append(koda.toChar())
                                i += 4
                            }
                            else -> napaka()
                        }
                        i++
                        cisto = i
                    }
                }
            }

            private fun stevka(k: Int): Boolean = k < s.length && s[k] in '0'..'9'

            // Slovnica kot regularni izraz v Pythonu: -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][-+]?[0-9]+)?
            // Samo ASCII stevke; kar ostane (npr. "01", "1."), se zatakne pri locilu.
            private fun stevilo(): Any {
                val zacetek = i
                if (i < s.length && s[i] == '-') i++
                if (!stevka(i)) napaka()
                if (s[i] == '0') {
                    i++
                } else {
                    while (stevka(i)) i++
                }
                val konecCelega = i
                var celo = true
                if (i < s.length && s[i] == '.' && stevka(i + 1)) {
                    celo = false
                    i += 2
                    while (stevka(i)) i++
                }
                if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                    var j = i + 1
                    if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
                    if (stevka(j)) {
                        celo = false
                        i = j
                        while (stevka(i)) i++
                    }
                }
                val besedilo = s.substring(zacetek, i)
                if (!celo) return java.lang.Double.parseDouble(besedilo)
                val stevk = konecCelega - zacetek - (if (s[zacetek] == '-') 1 else 0)
                if (stevk > NAJVEC_STEVK) napaka()
                if (stevk <= 18) return besedilo.toLong()
                val veliko = BigInteger(besedilo)
                // V Long, ce gre, da so vsa obicajna cela stevila iste vrste.
                return if (veliko.bitLength() < 64) veliko.toLong() else veliko
            }
        }

        // ---- pisanje ----

        // Python sort_keys ureja po kodnih tockah; String.compareTo po enotah UTF-16,
        // kar se razlikuje nad U+FFFF. Zato primerjamo sami.
        private val PO_KODNIH_TOCKAH = Comparator<String> { a, b -> primerjajKodneTocke(a, b) }

        private fun primerjajKodneTocke(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a.codePointAt(i)
                val cb = b.codePointAt(j)
                if (ca != cb) return ca.compareTo(cb)
                i += Character.charCount(ca)
                j += Character.charCount(cb)
            }
            return (i < a.length).compareTo(j < b.length)
        }

        fun zapisi(vrednost: Any?): String {
            val sb = StringBuilder()
            zapisi(sb, vrednost)
            return sb.toString()
        }

        private fun zapisi(sb: StringBuilder, v: Any?) {
            when (v) {
                null -> sb.append("null")
                is Boolean -> sb.append(if (v) "true" else "false")
                is String -> niz(sb, v)
                is Long, is Int, is Short, is Byte, is BigInteger -> sb.append(v.toString())
                is Double -> sb.append(decimalka(v))
                is Float -> sb.append(decimalka(v.toDouble()))
                is Map<*, *> -> {
                    val kljuci = ArrayList<String>(v.size)
                    for (k in v.keys) kljuci.add(k as String)
                    kljuci.sortWith(PO_KODNIH_TOCKAH)
                    sb.append('{')
                    var prvi = true
                    for (k in kljuci) {
                        if (!prvi) sb.append(',')
                        prvi = false
                        niz(sb, k)
                        sb.append(':')
                        zapisi(sb, v[k])
                    }
                    sb.append('}')
                }
                is Iterable<*> -> {
                    sb.append('[')
                    var prvi = true
                    for (x in v) {
                        if (!prvi) sb.append(',')
                        prvi = false
                        zapisi(sb, x)
                    }
                    sb.append(']')
                }
                else -> throw IllegalArgumentException("vrsta ni JSON: " + v.javaClass.name)
            }
        }

        private val HEX = "0123456789abcdef"

        // ensure_ascii=True: vse zunaj presledek..~ gre v \\u z malimi crkami,
        // znaki nad U+FFFF kot par nadomestnih znakov (tako so ze v String).
        private fun niz(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c == '\b' -> sb.append("\\b")
                    c == '\u000C' -> sb.append("\\f")
                    c in ' '..'~' -> sb.append(c)
                    else -> {
                        val k = c.code
                        sb.append("\\u").append(HEX[(k shr 12) and 15]).append(HEX[(k shr 8) and 15])
                            .append(HEX[(k shr 4) and 15]).append(HEX[k and 15])
                    }
                }
            }
            sb.append('"')
        }

        // Kot repr(float) v Pythonu: najkrajse stevke, ki se preberejo nazaj v isto
        // stevilo, nespremenjeno do 1e16, sicer znanstveni zapis (1e-05, 1e+16).
        // Double.toString ne zadosca: na starejsih JVM in Androidu ni vedno najkrajsi,
        // novejsi pa pri eni stevki vcasih izbere dve (4.9E-324 proti 5e-324).
        internal fun decimalka(d: Double): String {
            if (d.isNaN()) return "NaN"
            if (d == Double.POSITIVE_INFINITY) return "Infinity"
            if (d == Double.NEGATIVE_INFINITY) return "-Infinity"
            if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
            val x = Math.abs(d)
            val tocno = BigDecimal(x)
            var najden: BigDecimal? = null
            var p = 1
            while (najden == null && p <= 17) {
                najden = najkrajsi(tocno, x, p)
                p++
            }
            val dec = (najden ?: BigDecimal(java.lang.Double.toString(x))).stripTrailingZeros()
            val cifre = dec.unscaledValue().toString()
            val n = cifre.length
            // Polozaj decimalne vejice: vrednost = 0.cifre * 10^decpt.
            val decpt = n - dec.scale()
            val sb = StringBuilder()
            if (d < 0) sb.append('-')
            if (decpt <= -4 || decpt > 16) {
                sb.append(cifre[0])
                if (n > 1) sb.append('.').append(cifre, 1, n)
                val eksponent = decpt - 1
                sb.append('e').append(if (eksponent < 0) '-' else '+')
                val e = Math.abs(eksponent)
                if (e < 10) sb.append('0')
                sb.append(e)
            } else if (decpt <= 0) {
                sb.append("0.")
                repeat(-decpt) { sb.append('0') }
                sb.append(cifre)
            } else if (decpt >= n) {
                sb.append(cifre)
                repeat(decpt - n) { sb.append('0') }
                sb.append(".0")
            } else {
                sb.append(cifre, 0, decpt).append('.').append(cifre, decpt, n)
            }
            return sb.toString()
        }

        // Med p-mestnima sosedoma (navzdol, navzgor) vzame tistega, ki se prebere nazaj
        // v x; ce oba, blizjega (ob enakosti sodo zadnjo stevko). null, ce p ne zadosca.
        private fun najkrajsi(tocno: BigDecimal, x: Double, p: Int): BigDecimal? {
            val dol = tocno.round(MathContext(p, RoundingMode.FLOOR))
            val gor = tocno.round(MathContext(p, RoundingMode.CEILING))
            val dolOk = java.lang.Double.parseDouble(dol.toString()) == x
            val gorOk = java.lang.Double.parseDouble(gor.toString()) == x
            if (dolOk && gorOk) {
                val r = tocno.subtract(dol).compareTo(gor.subtract(tocno))
                if (r < 0) return dol
                if (r > 0) return gor
                return if (dol.unscaledValue().testBit(0)) gor else dol
            }
            if (dolOk) return dol
            if (gorOk) return gor
            return null
        }
    }
}
