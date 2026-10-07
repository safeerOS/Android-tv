package si.safeer.tv.cast

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Krog zaupanja: naprave, ki si zaupajo med seboj, ne samo hubu.
 *
 * Danes je zaupanje hubovsko: hub izda zeton, naprava si zapomni odtis huba in zeton. Drug hub
 * pomeni novo seznanitev za vsako napravo. Krog to obrne: vsaka naprava ima svoj kljuc (EC P-256,
 * na Androidu v AndroidKeyStore, na Linuxu v nastavitvah), krog pa je seznam vseh javnih kljucev,
 * ki ga hrani VSAKA naprava. Kdorkoli iz kroga lahko postane hub, ker ostale naprave preverijo
 * njegov kljuc, ne odtisa enega potrdila; in hub preveri podpis naprave, ne zetona.
 *
 * Zapis (JSON, brez seznamov - JsonLahki bere samo objekte in nize):
 *   {"v":1, "clani": {"<id>": {"kljuc": "<base64 SPKI>", "ime": "...", "platforma": "tv",
 *                               "dodano": 1758300000.0, "dodal": "<id>"}},
 *           "umiki": {"<id>": {"umaknjeno": 1758300000.0, "umaknil": "<id>"}}}
 *
 * Zdruzevanje dveh krogov je deterministicno, zato pridejo vse naprave do istega kroga ne glede
 * na vrstni red sporocil: unija clanov po id (novejsi zapis zmaga), umik ima prednost pred
 * vnosom, ki je starejsi od njega; vnos, novejsi od umika, napravo vrne (ponovna seznanitev).
 *
 * Ta razred ne pozna Androida in tece tudi v preizkusih na JVM.
 */
class KrogZaupanja(private val shramba: HubUsmerjevalnik.Shramba? = null) {

    data class Clan(
        val id: String,
        /** Javni kljuc, base64 zapisa X.509 SubjectPublicKeyInfo (DER). */
        val kljuc: String,
        val ime: String,
        val platforma: String,
        val dodano: Double,
        val dodal: String,
        /** Kdaj je uporabnik napravo nazadnje poimenoval (0 = nikoli); novejse ime zmaga, `dodano` ostane. */
        val imenovano: Double = 0.0,
        /** Podpis naprave [dodal] cez ta vnos (podatkiClana); prazno pri starih krogih. */
        val podpis: String = "",
    )

    data class Umik(val id: String, val umaknjeno: Double, val umaknil: String, val podpis: String = "")

    /** Podpisnik te naprave (KrogNaprave ga nastavi): vnose, ki jih dodamo mi, podpisemo. */
    @Volatile
    var podpisnik: ((ByteArray) -> String?)? = null

    private val kljucnica = Any()
    private val clani = LinkedHashMap<String, Clan>()
    private val umiki = LinkedHashMap<String, Umik>()
    /** Zadnji stik s clanom (id -> cas v s): prijava, sosed ali seznam soseda. Za pospravljanje kroga. */
    private val stiki = HashMap<String, Double>()
    private var stikiZapisani = 0.0

    /** Klice se ob vsaki spremembi kroga (hub ga takrat razposlje). */
    @Volatile
    var naSpremembo: (() -> Unit)? = null

    init {
        shramba?.beri(KLJUC_SHRAMBE)?.let { zdruzi(it, obvesti = false) }
        shramba?.beri(KLJUC_STIKOV)?.let { json ->
            try {
                val o = org.json.JSONObject(json)
                for (k in o.keys()) stiki[k] = o.optDouble(k, 0.0)
            } catch (_: Throwable) { }
        }
    }

    // ------------------------------------------------------------------ stiki in pospravljanje

    /** Clan(i) se je/so oglasil(i): zapomnimo si cas (v shrambo najvec vsakih 10 min). Neznanih ne belezimo. */
    fun zabeleziStik(idji: Collection<String>, zdaj: Double = zdaj()) {
        val zapisi: String? = synchronized(kljucnica) {
            for (i in idji) if (i.isNotBlank() && clani.containsKey(i)) stiki[i] = zdaj
            if (zdaj - stikiZapisani >= STIKI_ZAPIS_S) {
                stikiZapisani = zdaj
                org.json.JSONObject(stiki.toMap()).toString()
            } else null
        }
        if (zapisi != null) try { shramba?.pisi(KLJUC_STIKOV, zapisi) } catch (_: Throwable) { }
    }

    /** Zadnji stik z napravo: vsi id-ji z istim kljucem stejejo; najvec od stika, vpisa in imenovanja. */
    fun zadnjiStik(id: String): Double = synchronized(kljucnica) {
        val c = clani[id] ?: return 0.0
        val sorodniki = clani.values.filter { it.kljuc == c.kljuc }
        (sorodniki.map { stiki[it.id] ?: 0.0 } + sorodniki.map { it.dodano } + sorodniki.map { it.imenovano }).maxOrNull() ?: 0.0
    }

    /**
     * Umakne clane, ki jih ni bilo vec kot [mejaS] (privzeto 90 dni): stare identitete naprav po ponovni namestitvi
     * (nov kljuc) bi sicer ostale v krogu za vedno. Naprava z istim kljucem kot kateri koli zivi clan ostane,
     * nase naprave ne umikamo. Ce MI nismo videli nikogar 7 dni (ugasnjena naprava), smo bili odsotni mi - nic.
     * Umik podpisemo, da ga sosedje sprejmejo. Vrne umaknjene id-je.
     */
    fun pospravi(kdo: String, zdaj: Double = zdaj(), mejaS: Double = DNI_BREZ_STIKA * 86400.0): List<String> {
        val kandidati = synchronized(kljucnica) {
            val zadnji = stiki.values.maxOrNull() ?: return emptyList()
            if (zdaj - zadnji > 7 * 86400.0) return emptyList()
            clani.values.filter { jeVeljaven(it) && !smoMi(it.id) }.map { it.id }
        }
        val umaknjeni = ArrayList<String>()
        for (id in kandidati) {
            if (zdaj - zadnjiStik(id) <= mejaS) continue
            if (umakni(id, kdo, zdaj)) umaknjeni.add(id)
        }
        return umaknjeni
    }

    fun clani(): List<Clan> = synchronized(kljucnica) { clani.values.filter { jeVeljaven(it) } }

    fun clan(id: String): Clan? = synchronized(kljucnica) { clani[id]?.takeIf { jeVeljaven(it) } }

    fun jeClan(id: String): Boolean = clan(id) != null

    /**
     * Clan za [id], tudi kadar je id izpeljan iz kljuca (`n-…`, [idIzKljuca]) in je ta kljuc v krogu pod
     * drugim (starejsim) id-jem: id iz kljuca dokazuje isti kljuc, zato naprava ostane ista. Pripona za
     * sorodnika (`n-…-os`, `n-…-control`) ne moti - kljuc je isti.
     */
    fun clanZaId(id: String): Clan? {
        clan(id)?.let { return it }
        if (!jeIdIzKljuca(id)) return null
        val jedro = id.take(DOLZINA_ID_IZ_KLJUCA)
        return clani().firstOrNull { idIzKljuca(it.kljuc) == jedro }
    }

    fun stevilo(): Int = clani().size

    /**
     * Ali je naprava izrecno umaknjena iz kroga (nadgrobnik novejsi od vpisa), tudi pod id-jem iz kljuca.
     * Naprava, ki je krog ne pozna, NI umaknjena (o njej odloca seznanitev). Pregled 29. 9. 2026, tocka 12:
     * zeton umaknjene naprave ne sme veljati naprej.
     */
    fun jeUmaknjen(id: String): Boolean {
        if (id.isBlank()) return false
        synchronized(kljucnica) {
            val u = umiki[id]
            val c = clani[id]
            if (u != null && (c == null || u.umaknjeno > c.dodano)) return true
            if (c != null || !jeIdIzKljuca(id)) return false
            val jedro = id.take(DOLZINA_ID_IZ_KLJUCA)
            val ujemanja = clani.values.filter { idIzKljuca(it.kljuc) == jedro }
            return ujemanja.isNotEmpty() && ujemanja.none { jeVeljaven(it) }
        }
    }

    /** Doda ali osvezi clana. Vrne true, ce se je krog spremenil. Vnos, ki ga dodamo mi, podpisemo. */
    fun dodaj(vnos: Clan): Boolean {
        val clan = if (vnos.podpis.isNotBlank()) vnos else podpisiVnos(vnos)
        if (clan.id.isBlank() || clan.kljuc.isBlank() || dekodirajKljuc(clan.kljuc) == null) return false
        val spremenjeno = synchronized(kljucnica) {
            val obstojeci = clani[clan.id]
            if (obstojeci != null && obstojeci.kljuc == clan.kljuc && obstojeci.dodano >= clan.dodano
                && umiki[clan.id]?.let { it.umaknjeno > obstojeci.dodano } != true) {
                // Ista naprava, isti kljuc: osvezimo le ime, ce se je spremenilo.
                if (obstojeci.ime == clan.ime) return@synchronized false
                clani[clan.id] = obstojeci.copy(ime = clan.ime)
                return@synchronized true
            }
            clani[clan.id] = clan
            // Nov vnos, novejsi od umika, napravo vrne.
            umiki[clan.id]?.let { if (clan.dodano > it.umaknjeno) umiki.remove(clan.id) }
            true
        }
        if (spremenjeno) { shrani(); naSpremembo?.invoke() }
        return spremenjeno
    }

    /** Umakne napravo iz kroga (nadgrobnik ostane, da umik preide na vse naprave). Naprava, ki je
     *  ni v krogu, ne spremeni nicesar - sicer bi vsak tuj id pustil nadgrobnik in razposlal krog. */
    fun umakni(id: String, kdo: String, ob: Double = zdaj()): Boolean {
        val podpis = if (smoMi(kdo)) podpisnik?.invoke(podatkiUmika(id, ob, kdo)).orEmpty() else ""
        val spremenjeno = synchronized(kljucnica) {
            val c = clani[id] ?: return@synchronized false
            if (umiki[id]?.let { it.umaknjeno > c.dodano } == true) return@synchronized false
            umiki[id] = Umik(id, ob, kdo, podpis)
            true
        }
        if (spremenjeno) { shrani(); naSpremembo?.invoke() }
        return spremenjeno
    }

    /**
     * Zdruzi tuj krog s svojim. Vrne true, ce se je nas krog spremenil. Neveljavne vnose (brez
     * kljuca, nerazumljiv kljuc) preskoci - pokvarjen zapis ne sme pokvariti kroga.
     */
    /** Ali je ta id nasa naprava (isti kljuc)? Samo zase lahko podpisujemo. */
    private fun smoMi(id: String): Boolean {
        if (id.isBlank() || podpisnik == null) return false
        val nas = lastniKljuc ?: return false
        return clani[id]?.kljuc == nas || idIzKljuca(nas) == id.take(DOLZINA_ID_IZ_KLJUCA)
    }

    /** Javni kljuc te naprave; KrogNaprave ga nastavi skupaj s podpisnikom. */
    @Volatile
    var lastniKljuc: String? = null

    private fun podpisiVnos(clan: Clan): Clan {
        if (!smoMi(clan.dodal)) return clan
        val podpis = podpisnik?.invoke(podatkiClana(clan.id, clan.kljuc, clan.platforma, clan.dodano, clan.dodal)).orEmpty()
        return if (podpis.isBlank()) clan else clan.copy(podpis = podpis)
    }

    /**
     * Podpise vnose, ki jih je dodala ta naprava, a so bili shranjeni brez podpisa (hub pred 2.1.143 ni imel
     * podpisnika). Brez podpisa jih sosedje v mesh zavrnejo in nov clan ostane znan samo temu hubu.
     * Vrne true, ce se je krog spremenil (takrat ga je treba razposlati).
     */
    fun podpisiLastne(): Boolean {
        if (podpisnik == null || lastniKljuc == null) return false
        val spremenjeno = synchronized(kljucnica) {
            var sp = false
            for ((id, c) in clani.toMap()) {
                if (c.podpis.isNotBlank() || !smoMi(c.dodal)) continue
                val podpisan = podpisiVnos(c)
                if (podpisan.podpis.isNotBlank()) { clani[id] = podpisan; sp = true }
            }
            sp
        }
        if (spremenjeno) { shrani(); naSpremembo?.invoke() }
        return spremenjeno
    }

    /** Javni kljuc clana (tudi prek id-ja iz kljuca) za preverjanje podpisa vnosa; prazno, ce ga ne poznamo. */
    private fun kljucClana(id: String): String = clanZaId(id)?.kljuc.orEmpty()

    fun zdruzi(json: String, obvesti: Boolean = true, preveriPodpise: Boolean = false): Boolean {
        val pogled = JsonLahki.objekt(json) ?: return false
        var spremenjeno = false
        synchronized(kljucnica) {
            pogled.objekt("umiki")?.let { u ->
                for (id in u.kljuci()) {
                    val z = u.objekt(id) ?: continue
                    val ob = z.stevilo("umaknjeno") ?: continue
                    val umaknil = z.nizAli("umaknil")
                    val podpis = z.nizAli("podpis")
                    if (preveriPodpise && !preveriPodpisSKljucem(kljucClana(umaknil), podatkiUmika(id, ob, umaknil), podpis)) continue
                    val obstojeci = umiki[id]
                    if (obstojeci == null || obstojeci.umaknjeno < ob) {
                        umiki[id] = Umik(id, ob, umaknil, podpis)
                        spremenjeno = true
                    }
                }
            }
            pogled.objekt("clani")?.let { c ->
                for (id in c.kljuci()) {
                    val z = c.objekt(id) ?: continue
                    val kljuc = z.niz("kljuc") ?: continue
                    if (dekodirajKljuc(kljuc) == null) continue
                    val nov = Clan(id, kljuc, z.nizAli("ime", id), z.nizAli("platforma"), z.stevilo("dodano") ?: 0.0, z.nizAli("dodal"),
                        z.stevilo("imenovano") ?: 0.0, z.nizAli("podpis"))
                    val obstojeci = clani[id]
                    if (preveriPodpise && (obstojeci == null || obstojeci.kljuc != nov.kljuc) &&
                        !preveriPodpisSKljucem(kljucClana(nov.dodal), podatkiClana(nov.id, nov.kljuc, nov.platforma, nov.dodano, nov.dodal), nov.podpis)) continue
                    if (obstojeci == null || obstojeci.dodano < nov.dodano
                        || (obstojeci.dodano == nov.dodano && obstojeci.kljuc != nov.kljuc && obstojeci.kljuc < nov.kljuc)) {
                        // Novejsi vnos iste naprave ne izgubi imena, ki ga je dal uporabnik.
                        clani[id] = if (obstojeci != null && obstojeci.kljuc == nov.kljuc && obstojeci.imenovano > nov.imenovano)
                            nov.copy(ime = obstojeci.ime, imenovano = obstojeci.imenovano) else nov
                        spremenjeno = true
                    } else if (obstojeci.kljuc == nov.kljuc && nov.imenovano > obstojeci.imenovano && nov.ime.isNotBlank()) {
                        clani[id] = obstojeci.copy(ime = nov.ime, imenovano = nov.imenovano)
                        spremenjeno = true
                    }
                }
            }
            // Vnos, novejsi od umika, napravo vrne; umik brez clana ostane kot spomin.
            for ((id, u) in umiki.toMap()) {
                val c = clani[id] ?: continue
                if (c.dodano > u.umaknjeno) { umiki.remove(id); spremenjeno = true }
            }
        }
        if (spremenjeno) { shrani(); if (obvesti) naSpremembo?.invoke() }
        return spremenjeno
    }

    /**
     * Uporabnik je napravo [id] poimenoval [ime] ob [ob]. Samo ime in cas imena - `dodano` ostane, zato
     * preimenovanje ne more obuditi umaknjene naprave. Vrne true, ce se je krog spremenil.
     */
    fun preimenuj(id: String, ime: String, ob: Double = zdaj()): Boolean {
        val cisto = ime.trim().take(64)
        if (cisto.isEmpty()) return false
        val spremenjeno = synchronized(kljucnica) {
            val c = clani[id]?.takeIf { jeVeljaven(it) } ?: return@synchronized false
            // Enako ime brez casa imena (star vnos) potrdimo, da ga hub sprejme kot uporabnikovo.
            if ((c.ime == cisto && c.imenovano > 0) || ob <= c.imenovano) return@synchronized false
            clani[id] = c.copy(ime = cisto, imenovano = ob)
            true
        }
        if (spremenjeno) { shrani(); naSpremembo?.invoke() }
        return spremenjeno
    }

    /**
     * Imena, ki jih ponudi naprava (trust.names): sprejmemo samo ime clanov, ki jih ze poznamo z ISTIM kljucem
     * in niso umaknjeni, in samo novejse (imenovano). Nov clan, drug kljuc ali umik po tej poti ne pride - to
     * sme le hub z zdruzi(). Ura iz prihodnosti (vec kot dan) ne sme za vedno zakleniti imena.
     */
    fun zdruziImena(json: String): Boolean {
        val c = JsonLahki.objekt(json)?.objekt("clani") ?: return false
        val meja = zdaj() + 86_400.0
        var spremenjeno = false
        synchronized(kljucnica) {
            for (id in c.kljuci()) {
                val z = c.objekt(id) ?: continue
                val obstojeci = clani[id]?.takeIf { jeVeljaven(it) } ?: continue
                val ob = z.stevilo("imenovano") ?: continue
                val ime = z.niz("ime")?.trim()?.take(64).orEmpty()
                if (z.niz("kljuc") != obstojeci.kljuc || ime.isEmpty() || ob <= obstojeci.imenovano || ob > meja) continue
                clani[id] = obstojeci.copy(ime = ime, imenovano = ob)
                spremenjeno = true
            }
        }
        if (spremenjeno) { shrani(); naSpremembo?.invoke() }
        return spremenjeno
    }

    /** Zapis kroga; clani in umiki po id, da je isti krog na vsaki napravi tudi isti niz. */
    fun json(): String = synchronized(kljucnica) {
        val c = JsonLahki.Zapis()
        for (clan in clani.values.sortedBy { it.id }) {
            val z = JsonLahki.Zapis()
                .niz("kljuc", clan.kljuc).niz("ime", clan.ime).niz("platforma", clan.platforma)
                .stevilo("dodano", clan.dodano).niz("dodal", clan.dodal)
            if (clan.imenovano > 0) z.stevilo("imenovano", clan.imenovano)
            if (clan.podpis.isNotBlank()) z.niz("podpis", clan.podpis)
            c.surovo(clan.id, z.toString())
        }
        val u = JsonLahki.Zapis()
        for (umik in umiki.values.sortedBy { it.id }) {
            val zu = JsonLahki.Zapis().stevilo("umaknjeno", umik.umaknjeno).niz("umaknil", umik.umaknil)
            if (umik.podpis.isNotBlank()) zu.niz("podpis", umik.podpis)
            u.surovo(umik.id, zu.toString())
        }
        JsonLahki.Zapis().stevilo("v", 1.0).surovo("clani", c.toString()).surovo("umiki", u.toString()).toString()
    }

    /**
     * Ali je [podpisB64] podpis [podatkov] s kljucem naprave [id] (SHA256withECDSA, DER podpis).
     * Naprava, ki ni v krogu ali je umaknjena, nima veljavnega podpisa.
     */
    fun preveriPodpis(id: String, podatki: ByteArray, podpisB64: String): Boolean {
        val clan = clan(id) ?: return false
        // Izrecno funkcija spremljevalca: ta metoda ima isti podpis in bi sicer poklicala samo sebe
        // (kljuc kot id -> ni clana -> false). Zato je JVM preizkus nekoc padel s 401.
        return preveriPodpisSKljucem(clan.kljuc, podatki, podpisB64)
    }

    private fun jeVeljaven(c: Clan): Boolean = umiki[c.id]?.let { it.umaknjeno > c.dodano } != true

    private fun shrani() {
        shramba?.pisi(KLJUC_SHRAMBE, json())
    }

    companion object {
        const val KLJUC_SHRAMBE = "cast_krog"
        const val KLJUC_STIKOV = "cast_krog_stiki"
        /** Clan brez stika toliko dni gre iz kroga (pospravi); stiki v shrambo najvec vsakih 10 min. */
        const val DNI_BREZ_STIKA = 90
        const val STIKI_ZAPIS_S = 600.0

        fun zdaj(): Double = System.currentTimeMillis() / 1000.0

        fun dekodirajKljuc(b64: String): java.security.PublicKey? = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))
        } catch (_: Throwable) { null }

        /** Ali je [podpisB64] podpis [podatkov] z javnim kljucem [kljucB64] (SHA256withECDSA, DER podpis). */
        /**
         * Kar podpise naprava, ki v krog doda drugo napravo. Podpis je vezan na id, kljuc, platformo,
         * cas in podpisnika: vnosa ni mogoce spremeniti ne prestaviti k drugemu clanu. Isti zapis kot
         * link_krog.podatki_clana na racunalniku.
         */
        fun podatkiClana(id: String, kljuc: String, platforma: String, dodano: Double, dodal: String): ByteArray =
            "safeer-krog-clan-v1\n$id\n$kljuc\n$platforma\n${String.format(java.util.Locale.ROOT, "%.3f", dodano)}\n$dodal".toByteArray(Charsets.UTF_8)

        /** Kar podpise naprava, ki clana umakne (link_krog.podatki_umika). */
        fun podatkiUmika(id: String, umaknjeno: Double, umaknil: String): ByteArray =
            "safeer-krog-umik-v1\n$id\n${String.format(java.util.Locale.ROOT, "%.3f", umaknjeno)}\n$umaknil".toByteArray(Charsets.UTF_8)

        fun preveriPodpisSKljucem(kljucB64: String, podatki: ByteArray, podpisB64: String): Boolean {
            val kljuc = dekodirajKljuc(kljucB64) ?: return false
            return try {
                val s = Signature.getInstance("SHA256withECDSA")
                s.initVerify(kljuc)
                s.update(podatki)
                s.verify(Base64.getDecoder().decode(podpisB64))
            } catch (_: Throwable) { false }
        }

        /** Id naprave iz javnega kljuca: prvih 16 sestnajstiskih znakov SHA-256 zapisa SPKI. */
        fun idIzKljuca(kljucB64: String): String {
            val izvlecek = java.security.MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(kljucB64))
            return "n-" + izvlecek.joinToString("") { "%02x".format(it) }.take(16)
        }

        /** Dolzina id-ja iz kljuca brez pripone sorodnika: "n-" + 16 znakov. */
        const val DOLZINA_ID_IZ_KLJUCA = 18

        /**
         * Ali je [id] izpeljan iz kljuca: `n-<16 hex>`, po zelji s pripono programa (`-os`, `-control` ...) iz crk, stevk,
         * pike, podcrtaja in vezaja, skupaj najvec 128 znakov. Id z drugimi znaki (presledek, prelom vrstice ...) NI id iz
         * kljuca in kljuca po jedru ne dobi: z njim je sredisce sejo zascite ene naprave prevezalo na drugo (drugi
         * neodvisni pregled, 7. 10. 2026). Isto pravilo kot DostopPravila.jeIdIzKljuca in core/link_krog.py.
         */
        fun jeIdIzKljuca(id: String): Boolean {
            if (id.length < DOLZINA_ID_IZ_KLJUCA || id.length > 128 || !id.startsWith("n-")) return false
            if (!id.substring(2, DOLZINA_ID_IZ_KLJUCA).all { it in '0'..'9' || it in 'a'..'f' }) return false
            if (id.length == DOLZINA_ID_IZ_KLJUCA) return true
            return id[DOLZINA_ID_IZ_KLJUCA] == '-' && id.substring(DOLZINA_ID_IZ_KLJUCA + 1).all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-'
            }
        }
    }
}
