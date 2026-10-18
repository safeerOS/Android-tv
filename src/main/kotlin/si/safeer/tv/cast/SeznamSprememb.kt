package si.safeer.tv.cast

import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Zapis naprave v seznamu: polje -> izvirno besedilo JSON vrednosti (urejeno kot pri sestavljanju). */
typealias ZapisSeznama = Map<String, String>

/**
 * Seznam naprav v Safeer Linku: po prvem posnetku samo spremembe (lastnik, 18. 10. 2026). Isti protokol kot
 * core/link_spremembe.py na racunalniku - skupni primeri so v tests/fixtures/seznam_sprememb.json obeh repozitorijev.
 *
 * Do Safeer OS 0.5.89 je sredisce ob vsaki spremembi vsem poslalo CEL seznam: val prijav N naprav je bil O(N^3) bajtov
 * (meritev 18. 10. 2026 na racunalniku: 100 naprav 79 MB, 400 naprav 5,2 GB). Zdaj:
 *
 *  - **Dogovor z zmoznostjo.** Odjemalec, ki zna, navede [ZMOZNOST] v prijavi; sosednje sredisce v prijavi in v `caps`
 *    svojega `mesh.devices`. Kdor je ne navede (starejsi Safeer), dobi cel seznam kot doslej.
 *  - **Posnetek in spremembe.** Najprej cel seznam z `epoch` in `ver`, potem samo spremembe od `base` do `ver`: dodane
 *    naprave (cel zapis), spremenjene (samo spremenjena polja: `set`, odstranjena `unset`) in odstranjene (`del`).
 *  - **Vrzel ali dvom pomeni posnetek.** Prejemnik, ki dobi spremembo, ki se ne nadaljuje na njegovo verzijo ali se ne
 *    sklada z njegovim seznamom, zahteva posnetek ([ZAHTEVA] oz. [MESH_ZAHTEVA]).
 *  - **Zdruzevanje.** Spremembe v oknu [OKNO_MS] gredo kot eno sporocilo; prva po mirovanju gre takoj.
 *
 * Varnost je ista kot pri celem seznamu: sprememba je razlika istega seznama, ki bi ga prejemnik dobil cel (isti filtri),
 * po isti povezavi. Nic iz sporocila sprememb ne podeli dostopa.
 *
 * Zapis naprave je urejen slovar polje -> izvirno besedilo JSON vrednosti (kot ga da [JsonLahki]); razred ne pozna
 * Androida in se preizkusi v navadnem JVM.
 */
object SeznamSprememb {
    const val ZMOZNOST = "devdelta1"
    /** Okno zdruzevanja; izbrano z meritvijo na racunalniku (tools/link_val_prijav.py, docs/LINK-MESH.md v Safeer-linux). */
    const val OKNO_MS = 250L
    const val POSNETEK = "cast.devices"
    const val SPREMEMBE = "cast.devices.delta"
    const val ZAHTEVA = "cast.devices.sync"
    const val MESH_POSNETEK = "mesh.devices"
    const val MESH_SPREMEMBE = "mesh.devices.delta"
    const val MESH_ZAHTEVA = "mesh.devices.sync"
    /** Cas zadnjega stika ni sprememba: sicer bi vsaka objava nosila vse aktivne naprave. */
    val NESTALNA_POLJA = setOf("last_seen")
    /** Zrcalo pri prejemniku: najvec toliko znakov JSON (posnetek mora iti v en okvir; spremembe se ne smejo kopiciti). */
    const val NAJVEC_ZRCALA = 4L * 1024 * 1024
    const val ZAHTEVA_NAJPREJ_MS = 2000L

    private val nakljucje = SecureRandom()

    fun novaEpoha(): String {
        val b = ByteArray(8)
        nakljucje.nextBytes(b)
        return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Zapis iz izvirnega besedila objekta (polje -> izvirna vrednost) ali null. */
    fun zapis(surovo: String): LinkedHashMap<String, String>? {
        val p = JsonLahki.objekt(surovo) ?: return null
        val izid = LinkedHashMap<String, String>()
        for (k in p.kljuci()) izid[k] = p.surovo(k) ?: return null
        return izid
    }

    fun json(zapis: ZapisSeznama): String =
        zapis.entries.joinToString(",", "{", "}") { "\"" + JsonLahki.ubezi(it.key) + "\":" + it.value }

    private fun niz(s: String): String = "\"" + JsonLahki.ubezi(s) + "\""

    /** Slovar id -> zapis iz objekta JSON (`mesh.devices`: devices, relay); null, ce ni objekt. */
    fun slovar(surovo: String?): LinkedHashMap<String, ZapisSeznama>? {
        if (surovo == null) return null
        val p = JsonLahki.objekt(surovo) ?: return null
        val izid = LinkedHashMap<String, ZapisSeznama>()
        for (k in p.kljuci()) {
            if (p.vrsta(k) != JsonLahki.Vrsta.OBJEKT) continue
            zapis(p.surovo(k) ?: continue)?.let { izid[k] = it }
        }
        return izid
    }

    /** Seznam zapisov z "id" (cast.devices) kot slovar id -> zapis; null, ce ni seznam. */
    fun seznam(surovo: String?): LinkedHashMap<String, ZapisSeznama>? {
        if (surovo == null) return null
        val elementi = JsonLahki.elementi(surovo) ?: return null
        val izid = LinkedHashMap<String, ZapisSeznama>()
        for (e in elementi) {
            val z = zapis(e) ?: continue
            val id = z["id"]?.let { JsonLahki.niz(it) } ?: continue
            izid[id] = z
        }
        return izid
    }

    /**
     * Nov objavljeni seznam in sprememba enega dela kot JSON (null, ce je enak). Objavljeni vrstni red: kar ostane, na
     * svojem mestu, nove naprave na koncu - tako ga sestavi prejemnik, zato je posnetek vedno enak njegovemu seznamu.
     * [zId]: zapisi nosijo "id" (cast.devices); sicer je id samo kljuc (mesh.devices) in ga sprememba doda sama.
     */
    fun razlika(staro: LinkedHashMap<String, ZapisSeznama>, novo: Map<String, ZapisSeznama>, zId: Boolean): Pair<LinkedHashMap<String, ZapisSeznama>, String?> {
        val objavljeno = LinkedHashMap<String, ZapisSeznama>()
        val spremenjene = ArrayList<String>()
        val odstranjene = ArrayList<String>()
        for ((i, z) in staro) {
            val n = novo[i]
            if (n == null) { odstranjene.add(niz(i)); continue }
            val nastavi = n.entries.filter { it.key != "id" && it.key !in NESTALNA_POLJA && z[it.key] != it.value }
            val odstrani = z.keys.filter { it != "id" && it !in NESTALNA_POLJA && it !in n }
            if (nastavi.isNotEmpty() || odstrani.isNotEmpty()) {
                val vnos = StringBuilder("{\"id\":").append(niz(i)).append(",\"set\":")
                    .append(nastavi.joinToString(",", "{", "}") { niz(it.key) + ":" + it.value })
                if (odstrani.isNotEmpty()) vnos.append(",\"unset\":").append(odstrani.joinToString(",", "[", "]") { niz(it) })
                spremenjene.add(vnos.append('}').toString())
            }
            objavljeno[i] = n
        }
        val dodane = ArrayList<String>()
        for ((i, z) in novo) {
            if (i in staro) continue
            objavljeno[i] = z
            dodane.add(if (zId) json(z) else json(LinkedHashMap<String, String>().apply { put("id", niz(i)); putAll(z - "id") }))
        }
        if (dodane.isEmpty() && spremenjene.isEmpty() && odstranjene.isEmpty()) return objavljeno to null
        val deli = ArrayList<String>()
        if (dodane.isNotEmpty()) deli.add("\"add\":" + dodane.joinToString(",", "[", "]"))
        if (spremenjene.isNotEmpty()) deli.add("\"chg\":" + spremenjene.joinToString(",", "[", "]"))
        if (odstranjene.isNotEmpty()) deli.add("\"del\":" + odstranjene.joinToString(",", "[", "]"))
        return objavljeno to deli.joinToString(",", "{", "}")
    }

    /** Celo stevilo iz JSON (verzija); null, ce ni celo stevilo. */
    private fun celo(d: Double?): Long? =
        if (d == null || d.isNaN() || d.isInfinite() || d != Math.floor(d) || Math.abs(d) > 9.0e15) null else d.toLong()

    /** Kar ima prejemnik: zadnji pravi seznam (en ali vec delov) z verzijo. */
    class Zrcalo(vrste: Map<String, Boolean>) {
        private val zId = LinkedHashMap(vrste)
        val deli = LinkedHashMap<String, LinkedHashMap<String, ZapisSeznama>>().apply { for (k in vrste.keys) put(k, LinkedHashMap()) }
        private val velikosti = HashMap<String, Int>()
        var velikost = 0L; private set
        var epoha: String? = null; private set
        var ver: Long? = null; private set
        var velja = false; private set

        /** Cel seznam: del je izvirno besedilo seznama (zId) ali objekta. Verzija velja samo s pravilnimi tipi. */
        fun posnetek(vsebina: Map<String, String?>, epoha: String?, ver: Double?) {
            velikosti.clear(); velikost = 0
            for ((ime, z) in zId) {
                deli[ime] = LinkedHashMap()
                val del = (if (z) seznam(vsebina[ime]) else slovar(vsebina[ime])) ?: LinkedHashMap()
                for ((i, zapis) in del) postavi(ime, i, zapis)
            }
            val v = celo(ver)
            val dobra = epoha != null && epoha.isNotEmpty() && epoha.length <= 64 && v != null
            this.epoha = if (dobra) epoha else null
            this.ver = if (dobra) v else null
            velja = true
        }

        fun zavrzi() {
            for (k in deli.keys.toList()) deli[k] = LinkedHashMap()
            velikosti.clear(); velikost = 0; epoha = null; ver = null; velja = false
        }

        private fun postavi(ime: String, i: String, zapis: ZapisSeznama?) {
            val kljuc = ime + "\u0000" + i
            velikost -= (velikosti.remove(kljuc) ?: 0)
            if (zapis == null) { deli[ime]?.remove(i); return }
            deli[ime]!![i] = zapis
            val v = json(zapis).length
            velikosti[kljuc] = v
            velikost += v
        }

        private fun uporabiDel(ime: String, surovo: String?): Boolean {
            if (surovo == null) return true
            val d = JsonLahki.objekt(surovo) ?: return false
            val del = deli[ime]!!
            val z = zId[ime]!!
            for (polje in listOf("add", "chg", "del")) if (d.ima(polje) && d.vrsta(polje) != JsonLahki.Vrsta.SEZNAM) return false
            for (e in d.surovo("del")?.let { JsonLahki.elementi(it) ?: return false }.orEmpty()) {
                val i = JsonLahki.niz(e) ?: return false
                if (i !in del) return false
                postavi(ime, i, null)
            }
            for (e in d.surovo("chg")?.let { JsonLahki.elementi(it) ?: return false }.orEmpty()) {
                val c = JsonLahki.objekt(e) ?: return false
                val i = c.niz("id") ?: return false
                val star = del[i] ?: return false
                if (c.ima("set") && c.vrsta("set") != JsonLahki.Vrsta.OBJEKT) return false
                if (c.ima("unset") && c.vrsta("unset") != JsonLahki.Vrsta.SEZNAM) return false
                val nastavi = c.surovo("set")?.let { zapis(it) ?: return false }.orEmpty()
                val brisi = c.surovo("unset")?.let { s -> (JsonLahki.elementi(s) ?: return false).map { JsonLahki.niz(it) ?: return false } }.orEmpty()
                if ("id" in nastavi || "id" in brisi) return false
                val nov = LinkedHashMap(star)
                nov.putAll(nastavi)
                for (k in brisi) nov.remove(k)
                postavi(ime, i, nov)
            }
            for (e in d.surovo("add")?.let { JsonLahki.elementi(it) ?: return false }.orEmpty()) {
                val a = zapis(e) ?: return false
                val i = a["id"]?.let { JsonLahki.niz(it) } ?: return false
                if (i in del) return false
                postavi(ime, i, if (z) a else LinkedHashMap(a).apply { remove("id") })
            }
            return true
        }

        /** Sprememba (`epoch`, `base`, `ver` in deli): "ok", "vrzel" (ni nasa verzija) ali "dvom" (zrcalo zavrzeno). */
        fun uporabi(tovor: JsonLahki.Pogled): String {
            val ver = celo(tovor.stevilo("ver"))
            val base = celo(tovor.stevilo("base"))
            val nasa = this.ver
            if (!velja || epoha == null || tovor.niz("epoch") != epoha || nasa == null || base != nasa || ver == null || ver <= nasa)
                return "vrzel"
            for (ime in zId.keys) {
                if (tovor.ima(ime) && tovor.vrsta(ime) != JsonLahki.Vrsta.OBJEKT) { zavrzi(); return "dvom" }
                if (!uporabiDel(ime, tovor.surovo(ime)) || velikost > NAJVEC_ZRCALA) { zavrzi(); return "dvom" }
            }
            this.ver = ver
            return "ok"
        }

        fun seznamJson(ime: String = "devices"): String = deli[ime]!!.values.joinToString(",", "[", "]") { json(it) }

        fun slovarJson(ime: String): String =
            deli[ime]!!.entries.joinToString(",", "{", "}") { niz(it.key) + ":" + json(it.value) }
    }

    /** Odjemalceva stran za `cast.devices`: program dobi vedno cel seznam, kot od starega sredisca. */
    class Prejemnik(private val ura: () -> Long = { System.currentTimeMillis() }) {
        private val zrcalo = Zrcalo(mapOf("devices" to true))
        private var zahtevano: Long? = null

        /** (besedilo za program ali null, ali zahtevati posnetek). Sporocila, ki niso seznam naprav, gredo naprej nespremenjena. */
        fun sprejmi(besedilo: String): Pair<String?, Boolean> {
            if (!besedilo.contains("cast.devices")) return besedilo to false
            val p = JsonLahki.objekt(besedilo) ?: return besedilo to false
            return when (p.niz("type")) {
                POSNETEK -> {
                    zrcalo.posnetek(mapOf("devices" to p.surovo("devices")), p.niz("epoch"), p.stevilo("ver"))
                    zahtevano = null
                    besedilo to false
                }
                SPREMEMBE -> {
                    if (zrcalo.uporabi(p) != "ok") null to zahtevaj()
                    else ("{\"id\":\"seznam-${zrcalo.ver}\",\"type\":\"$POSNETEK\",\"devices\":" + zrcalo.seznamJson() + "}") to false
                }
                else -> besedilo to false
            }
        }

        private fun zahtevaj(): Boolean {
            val zdaj = ura()
            val prej = zahtevano
            if (prej != null && zdaj - prej < ZAHTEVA_NAJPREJ_MS) return false
            zahtevano = zdaj
            return true
        }
    }

    /** Zahteva za posnetek (odjemalec -> sredisce). */
    fun zahteva(id: String): String = "{\"id\":" + niz(id) + ",\"type\":\"" + ZAHTEVA + "\"}"

    /** Kar je sredisce objavilo eni skupini prejemnikov: posnetek v objavljenem vrstnem redu, verzija, zadnja sprememba. */
    class Pogled(vrste: Map<String, Boolean>) {
        private val zId = LinkedHashMap(vrste)
        val deli = LinkedHashMap<String, LinkedHashMap<String, ZapisSeznama>>().apply { for (k in vrste.keys) put(k, LinkedHashMap()) }
        val epoha: String = novaEpoha()
        var ver = 0L; private set
        /** Zadnja sprememba (od ver-1 do ver): ime dela -> JSON. */
        var zadnja: Map<String, String>? = null; private set

        fun posodobi(novo: Map<String, Map<String, ZapisSeznama>>): Boolean {
            val spremembe = LinkedHashMap<String, String>()
            for ((ime, z) in zId) {
                val (objavljeno, d) = razlika(deli[ime]!!, novo[ime].orEmpty(), z)
                deli[ime] = objavljeno
                if (d != null) spremembe[ime] = d
            }
            if (ver > 0 && spremembe.isEmpty()) return false
            ver += 1
            zadnja = if (ver > 1) spremembe else null
            return true
        }

        fun posnetekJson(ime: String = "devices"): String = deli[ime]!!.values.joinToString(",", "[", "]") { json(it) }

        fun slovarJson(ime: String): String =
            deli[ime]!!.entries.joinToString(",", "{", "}") { niz(it.key) + ":" + json(it.value) }
    }

    /** Kaj sredisce ve o eni povezavi: ali zna spremembe, kaj je dobila, in (sosed) kaj nam je poslala. */
    class Stanje {
        @Volatile var zna = false
        @Volatile var poslano: Pair<String, Long>? = null
        var zrcalo: Zrcalo? = null
        var zahtevano: Long? = null
    }

    /**
     * Vsaki povezavi skupine to, kar potrebuje: nic (ima to verzijo), spremembe (zna in ima prejsnjo) ali posnetek.
     * Stanje se zapise PRED posiljanjem: zahteva za posnetek, ki pride kot odgovor (tudi v isti niti), ga pobrise.
     * Vrne stevilo poslanih znakov.
     */
    fun <T> razposlji(pogled: Pogled, povezave: List<T>, stanje: (T) -> Stanje, poslji: (T, String) -> Boolean,
                      posnetek: () -> String, spremembe: () -> String): Long {
        var p: String? = null
        var s: String? = null
        var poslano = 0L
        val zdaj = pogled.epoha to pogled.ver
        val prej = pogled.epoha to (pogled.ver - 1)
        for (povezava in povezave) {
            val st = stanje(povezava)
            if (st.poslano == zdaj) continue
            val besedilo = if (st.zna && pogled.zadnja != null && st.poslano == prej) (s ?: spremembe().also { s = it })
                else (p ?: posnetek().also { p = it })
            st.poslano = zdaj
            val ok = try { poslji(povezava, besedilo) } catch (_: Throwable) { false }
            if (!ok) st.poslano = null else poslano += besedilo.length
        }
        return poslano
    }

    /**
     * Zdruzi spremembe v oknu: prva po mirovanju takoj, vse naslednje v oknu skupaj ob koncu. Objava vedno prebere
     * TRENUTNO stanje, zato se nobena sprememba ne izgubi. [oknoMs] 0: objava v klicateljevi niti (preizkusi), brez
     * gnezdenja - ce se med objavo sprozi nova, se tekoca ponovi.
     */
    class Dusilec(
        private val izvedi: () -> Unit,
        private val oknoMs: Long,
        private val ura: () -> Long = { System.nanoTime() / 1_000_000L },
        casovnik: ((Long, () -> Unit) -> Unit)? = null
    ) {
        private val zaklep = Any()
        private var zadnja: Long? = null
        private var caka = false
        private var ustavljen = false
        private var vTeku = false
        private var ponovi = false
        @Volatile private var izvajalec: ScheduledExecutorService? = null
        private val casovnik: (Long, () -> Unit) -> Unit = casovnik ?: { zamik, f ->
            val ex = synchronized(zaklep) {
                izvajalec ?: Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-link-seznam").apply { isDaemon = true } }
                    .also { izvajalec = it }
            }
            try { ex.schedule({ f() }, zamik, TimeUnit.MILLISECONDS) } catch (_: Throwable) { }
        }

        fun sprozi() {
            if (oknoMs <= 0) { takoj(); return }
            val zamik = synchronized(zaklep) {
                if (ustavljen || caka) return
                caka = true
                val z = zadnja
                if (z == null) 0L else maxOf(0L, z + oknoMs - ura())
            }
            casovnik(zamik) { obIzteku() }
        }

        private fun takoj() {
            synchronized(zaklep) {
                if (ustavljen) return
                if (vTeku) { ponovi = true; return }
                vTeku = true
            }
            try {
                while (true) {
                    synchronized(zaklep) { ponovi = false }
                    izvedi()
                    synchronized(zaklep) { if (!ponovi || ustavljen) { vTeku = false; return } }
                }
            } catch (e: Throwable) {
                synchronized(zaklep) { vTeku = false }
                throw e
            }
        }

        private fun obIzteku() {
            synchronized(zaklep) {
                caka = false
                if (ustavljen) return
                zadnja = ura()
            }
            try { izvedi() } catch (_: Throwable) { }   // naslednja sprememba sprozi novo objavo
        }

        fun ustavi() {
            val ex = synchronized(zaklep) { ustavljen = true; izvajalec.also { izvajalec = null } }
            try { ex?.shutdownNow() } catch (_: Throwable) { }
        }
    }
}
