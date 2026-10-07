package si.safeer.tv.cast

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Zascita ukazov od naprave do naprave za ENO povezavo s srediscem. Sprejemnik (CastReceiverService) in Safeer OS
 * (os/LinkOdjemalec) imata vsak svojo, ker sta v Linku pod razlicnima oznakama; kljuc naprave in zapis naprav, ki so
 * kljuc ze dokazale ([Dostop]), sta skupna. Razred veze [E2e] na Android: podpis s kljucem naprave (HubTls), kljuci drugih
 * naprav iz kroga zaupanja. Isto delo na racunalniku opravlja core/link_hub.Povezava.
 *
 * Pravila (ista na vseh napravah):
 * - ukaz, odgovor nanj, stran, nadzor predvajanja in »nadaljuj na napravi« gredo napravi, ki zascito zna, samo zasciteno;
 *   ce zascite ni mogoce vzpostaviti, sporocilo NE gre (nezascitenega nadomestka ni);
 * - sporocilo iz preverjene seje dobi `sender` in [POLJE] (jedro iz PREVERJENEGA kljuca) - po njem odloca [Dostop];
 * - z napravo, ki zascito prijavi, a z njo (se ali vec) nimamo seje, se dogovorimo takoj, ko jo zagledamo v seznamu
 *   naprav (ne sele ob prvem ukazu). Po dogovoru od nje nezascitenega ukaza ne sprejmemo vec;
 * - na ukaz, ki je sel zasciten, velja samo odgovor iz preverjene seje iste naprave ([veljaOdgovor]);
 * - po zasciteni poti gre samo dogovorjeni nabor tipov (E2e.ZASCITENI_TIPI): sporocil, ki jih sicer poslje samo sredisce
 *   (seznam naprav, krog zaupanja, koda za seznanitev), naprava tako ne more poslati.
 */
class ZascitaLinka(
    context: Context,
    private val mojId: () -> String,
    /** Poslje besedilo srediscu po povezavi tega programa; false, ce povezave ni. */
    private val posljiSurovo: (String) -> Boolean,
    /**
     * Sporocilo, ki ga obdela program kot vsako drugo: notranje sporocilo iz preverjene seje (z `sender` in [POLJE]) ali
     * potrditev sredisca za nase zasciteno sporocilo (`<prostor>.ack`, kot pri nezasciteni poti).
     */
    private val obSporocilu: (JSONObject) -> Unit,
) {
    private val ctx: Context = context.applicationContext

    /** Zmoznosti in imena naprav iz zadnjega seznama sredisca. Seznam pise sredisce - glej [znaZascito]. */
    @Volatile private var zmoznosti: Map<String, Set<String>> = emptyMap()
    @Volatile private var imena: Map<String, String> = emptyMap()
    /** Kdaj smo z napravo nazadnje zaceli dogovor samo za dokaz kljuca (monotoni cas). */
    private val dokazi = ConcurrentHashMap<String, Long>()

    private val e2e = E2e(
        mojId = mojId,
        poslji = { tip, cilj, oznaka, tovor ->
            // Tovor je ze JSON (E2e ga sestavi sam); ovojnico zlozimo brez ponovnega razclenjevanja.
            posljiSurovo("{\"id\":${JSONObject.quote(oznaka)},\"type\":${JSONObject.quote(tip)},\"target\":${JSONObject.quote(cilj)},\"payload\":$tovor}")
        },
        podpisi = { podatki -> HubTls.podpisi(podatki) },
        kljucZa = { id -> kljucZaZascito(ctx, id) },
        preveri = { kljuc, podatki, podpis -> KrogZaupanja.preveriPodpisSKljucem(kljuc, podatki, podpis) },
        idIzKljuca = { kljuc -> KrogZaupanja.idIzKljuca(kljuc) },
        obSporocilu = { notranje, od, jedro -> prejeto(notranje, od, jedro) },
        obSeji = { jedro -> Dostop.zabeleziZascito(ctx, jedro) },
        obZavrnitvi = { notranje, napaka, koda -> potrditev(notranje, "rejected", napaka, koda) },
        obSprejemu = { notranje -> potrditev(notranje, "accepted", "", "") },
        ura = { SystemClock.elapsedRealtime() },
        dnevnik = { vrstica -> Log.i(TAG, vrstica) },
    )

    /**
     * Ali naprava zascito zna: prijavi jo srediscu (zmoznost) ali pa je kljuc s to napravo ze dokazala. Drugo je
     * pomembno, ker seznam naprav pise sredisce: s crtanjem zmoznosti nas ne sme pripraviti do nezascitenega ukaza.
     */
    fun znaZascito(cilj: String): Boolean =
        E2e.ZMOZNOST in (zmoznosti[cilj] ?: emptySet()) || Dostop.zahtevaZascito(ctx, cilj)

    /**
     * Poslje sporocilo srediscu. Zasciten tip gre napravi, ki zascito zna, samo po preverjeni seji; false pomeni, da
     * sporocilo ni slo (povezave ni ali zascite ni mogoce vzpostaviti) - klicatelj ga NE sme poslati kako drugace.
     */
    fun poslji(sporocilo: JSONObject): Boolean {
        val cilj = sporocilo.optString("target", "")
        if (cilj.isNotEmpty() && cilj != "all" && cilj != mojId() && sporocilo.optString("type", "") in E2e.ZASCITENI_TIPI &&
            znaZascito(cilj)
        ) {
            val notranje = JSONObject()
            for (k in sporocilo.keys()) if (k != "target" && k != "allow" && k != POLJE) notranje.put(k, sporocilo.opt(k))
            // Zapomnimo si PRED posiljanjem: odgovor lahko pride, se preden se posiljanje vrne.
            val id = sporocilo.optString("id", "")
            val ukaz = id.isNotEmpty() && sporocilo.optString("type", "") == "control.command"
            if (ukaz) zapomniVprasanje(id, cilj)
            val poslano = try { e2e.poslji(cilj, notranje.toString()) } catch (e: Throwable) {
                Log.w(TAG, "Zascitenega sporocila ni bilo mogoce poslati: ${e.javaClass.simpleName}"); false
            }
            if (!poslano && ukaz) vprasanja.remove(id)
            return poslano
        }
        return posljiSurovo(sporocilo.toString())
    }

    private fun zapomniVprasanje(id: String, cilj: String) {
        val zdaj = SystemClock.elapsedRealtime()
        if (vprasanja.size >= NAJVEC_VPRASANJ / 2) vprasanja.entries.removeAll { zdaj - it.value.cas > VPRASANJE_VELJA_MS }
        while (vprasanja.size >= NAJVEC_VPRASANJ) {
            val najstarejse = vprasanja.entries.minByOrNull { it.value.cas } ?: break
            vprasanja.remove(najstarejse.key)
        }
        vprasanja[id] = Vprasanje(cilj, zdaj)
    }

    /**
     * Ali odgovor na ukaz (`control.result`) velja. Na ukaz, ki je sel zasciten, velja samo odgovor iz preverjene seje
     * naprave, ki smo jo vprasali: oznako posiljatelja takemu sporocilu vpise ta razred (oznaka seje, katere kljuc je
     * preverjen), ne sredisce. Odgovor, ki pride nezasciten ali iz seje druge naprave, ni njen - tudi ce mu je sredisce
     * vpisalo oznako naprave, ki zascite ne zna, ali oznake sploh ni. Za ukaze, ki niso sli zasciteni (starejsa naprava),
     * velja pravilo posiljatelja (Dostop.zahtevaZascito) kot za druga sporocila.
     */
    fun veljaOdgovor(json: JSONObject): Boolean {
        val zasciten = json.optString(POLJE, "").isNotEmpty()
        val vprasanje = vprasanja[json.optString("ref_id", "")]
        if (vprasanje == null || SystemClock.elapsedRealtime() - vprasanje.cas > VPRASANJE_VELJA_MS) {
            // Zapisa ni: ukaz ni sel zasciten (starejsa naprava) ali pa je zapis ze potekel. Zasciten odgovor je pristen
            // (posiljatelja mu je vpisala seja). Nezasciten mora imeti vsaj posiljatelja, ki ga vpise sredisce - odgovor
            // brez njega ni prisel od nobene naprave.
            return zasciten || json.optString("sender", "").isNotEmpty()
        }
        return zasciten && json.optString("sender", "") == vprasanje.cilj
    }

    /**
     * Samo za zavrnitev ukaza, ki je prisel brez zascite: odgovor gre tistemu, ki ga je poslal, po isti poti (starejsi
     * program iste naprave tako dobi sporocilo »posodobi Safeer« namesto izteka casa). Vsebine ne sme nositi.
     */
    fun posljiNezasciteno(sporocilo: JSONObject): Boolean = posljiSurovo(sporocilo.toString())

    /**
     * Prejeto sporocilo sredisca. True: bilo je del zascite (dogovor, sifriran kos, potrditev sredisca za nase sporocilo
     * prenosa) in je obdelano - morebitno notranje sporocilo je ze slo v obSporocilu. False: ni nase, obdela ga klicatelj.
     */
    fun prejmi(json: JSONObject): Boolean {
        val tip = json.optString("type", "")
        return try {
            when {
                tip == "data.ack" -> e2e.prejmi(tip, json.optString("sender", ""), PoljaJson(json))
                tip in E2e.TIPI_PRENOSA -> {
                    val tovor = json.optJSONObject("payload") ?: return false
                    e2e.prejmi(tip, json.optString("sender", ""), PoljaJson(tovor))
                }
                else -> false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Sporocila zascite ni bilo mogoce obdelati: ${e.javaClass.simpleName}")
            true
        }
    }

    /**
     * Nov seznam naprav sredisca: zapomnimo si zmoznosti in imena ter z napravami, ki zascito prijavijo, a z njimi (se
     * ali vec) nimamo seje, zacnemo dogovor. Program, ki se je znova zagnal, starih sej nima - z dogovorom ob prvem
     * seznamu naprav jih obnovi, se preden mu kdo poslje ukaz po seji, ki je ne pozna vec (ponovnega posiljanja po »seje
     * ni« ni, glej E2e). Seznamu ne verjamemo nicesar: dogovor uspe samo z napravo, ki se podpise s kljucem iz NASEGA
     * kroga. Najvec en poskus na napravo na minuto (naprava brez nasega kljuca v krogu zavrne takoj).
     */
    fun zapomniNaprave(naprave: JSONArray?) {
        val z = HashMap<String, Set<String>>()
        val i = HashMap<String, String>()
        if (naprave != null) for (k in 0 until naprave.length()) {
            val d = naprave.optJSONObject(k) ?: continue
            val id = d.optString("id", "")
            if (id.isEmpty()) continue
            val polje = d.optJSONArray("capabilities")
            val nabor = HashSet<String>()
            if (polje != null) for (j in 0 until polje.length()) (polje.opt(j) as? String)?.let { nabor.add(it) }
            z[id] = nabor
            d.optString("name", "").takeIf { it.isNotBlank() }?.let { i[id] = it }
        }
        zmoznosti = z
        imena = i
        dokaziKljuce()
    }

    private fun dokaziKljuce() {
        val moj = mojId()
        val znane = zmoznosti
        val zdaj = SystemClock.elapsedRealtime()
        // Zapis o zadnjem poskusu zavrzemo po casu, ne takrat, ko naprava izgine s seznama: seznam pise sredisce in z
        // izmenicnim skrivanjem naprave ne sme doseci, da se dogovor (podpis s kljucem naprave) zacenja znova in znova.
        dokazi.entries.removeAll { zdaj - it.value > 10 * DOKAZ_NAJVEC_NA_MS }
        for ((id, nabor) in znane) {
            if (id == moj || E2e.ZMOZNOST !in nabor) continue
            try {
                // Seja je: zapis, da naprava zascito zna, se ob tem po potrebi obnovi (ze vpisano se samo preveri).
                if (e2e.znovaJaviSejo(id)) continue
                val zadnjic = dokazi[id]
                if (zadnjic != null && zdaj - zadnjic < DOKAZ_NAJVEC_NA_MS) continue
                dokazi[id] = zdaj
                e2e.dogovoriSe(id)
            } catch (e: Throwable) {
                Log.w(TAG, "Dogovora za dokaz kljuca ni bilo mogoce zaceti: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Notranje sporocilo iz preverjene seje: posiljatelj je naprava, ki je dokazala kljuc z jedrom [jedro]. */
    private fun prejeto(notranjeJson: String, od: String, jedro: String) {
        val json = try { JSONObject(notranjeJson) } catch (_: Throwable) { return }
        if (json.optString("type", "") !in E2e.ZASCITENI_TIPI) {
            // Zasciteno gre samo to, kar je dogovorjeno; prenosa (data.*) ali cesa neznanega v ovojnici ne sprejmemo.
            Log.i(TAG, "Zasciteno sporocilo nedogovorjenega tipa zavrzeno.")
            return
        }
        json.remove("target")
        json.put("sender", od)
        // Ime za prikaz je iz seznama naprav (kot pri nezasciteni poti, kjer ga vpise sredisce), ne iz sporocila.
        json.remove("sender_name")
        imena[od]?.let { json.put("sender_name", it) }
        json.put(POLJE, jedro)
        obSporocilu(json)
    }

    /**
     * Sredisce je nase zasciteno sporocilo sprejelo v posredovanje ali ga zavrnilo (naprave ni v Linku ...): klicatelj
     * dobi enako potrditev kot pri nezascitenem sporocilu (`cast.ack`, `control.ack`), da ne caka na iztek casa.
     * Odgovorov in potrditev sredisce ne potrjuje niti po nezasciteni poti.
     */
    private fun potrditev(notranjeJson: String, stanje: String, napaka: String, koda: String) {
        val json = try { JSONObject(notranjeJson) } catch (_: Throwable) { return }
        val tip = json.optString("type", "")
        val id = json.optString("id", "")
        if (id.isEmpty() || !tip.contains('.') || tip.endsWith(".result") || tip.endsWith(".ack")) return
        val ack = JSONObject()
            .put("id", java.util.UUID.randomUUID().toString())
            .put("type", tip.substringBefore('.') + ".ack")
            .put("ref_id", id)
            .put("status", stanje)
        if (stanje != "accepted") ack.put("error", napaka).put("error_code", koda)
        obSporocilu(ack)
    }

    /** Polja prejetega tovora za [E2e]: niz samo, ce je niz; celo stevilo samo, ce je zapisano kot celo. */
    private class PoljaJson(private val o: JSONObject) : E2e.Polja {
        override fun niz(ime: String): String? = o.opt(ime) as? String
        override fun celo(ime: String): Long? {
            val v: Any? = o.opt(ime)
            return if (v is Int) v.toLong() else if (v is Long) v else null
        }
    }

    companion object {
        private const val TAG = "SafeerZascita"

        /**
         * Polje sporocila z jedrom iz preverjenega kljuca. Vpise ga samo ta razred; iz sporocila, ki pride po omrezju,
         * ga je treba PRED obdelavo zbrisati (CastReceiverService.handleIncomingMessage, LinkOdjemalec.obdelaj).
         */
        const val POLJE = "_zascita"

        /** Dogovor samo za dokaz kljuca: najvec en poskus na napravo na minuto. */
        const val DOKAZ_NAJVEC_NA_MS = 60_000L
        /** Kako dolgo za zasciten ukaz pricakujemo odgovor iz iste seje (ukazi sami potecejo prej). */
        const val VPRASANJE_VELJA_MS = 5 * 60_000L
        const val NAJVEC_VPRASANJ = 4096

        /**
         * Ukazi, ki so sli zasciteni: oznaka ukaza -> (oznaka naprave, ki smo jo vprasali, kdaj). Skupno vsem povezavam
         * programa (sprejemnik, Safeer OS, nova povezava po prekinitvi): zapis mora preziveti ponovno povezavo, sicer bi
         * nezasciten odgovor na se cakajoc ukaz spet veljal. Glej [veljaOdgovor].
         */
        private class Vprasanje(val cilj: String, val cas: Long)
        private val vprasanja = ConcurrentHashMap<String, Vprasanje>()

        /**
         * Javni kljuc naprave iz NASEGA kroga zaupanja, s katerim preverimo njen podpis v dogovoru zascite
         * (pravilo: [DostopPravila.kljucZaZascito]). Null: naprave ne priznamo.
         */
        fun kljucZaZascito(ctx: Context, id: String): String? = try {
            val krog = KrogNaprave.krog(ctx)
            DostopPravila.kljucZaZascito(id, { krog.clan(it)?.kljuc }, { krog.clani().map { c -> c.kljuc } }, KrogZaupanja::idIzKljuca)
        } catch (_: Throwable) { null }
    }
}
