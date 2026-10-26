package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Naloga 128: debrid storitve v Medijskem centru - TorBox, Real-Debrid, AllDebrid ([DebridPravila]).
 *
 * Kljuci: vsak se hrani sifriran z Android Keystore ([SporocilaSkrivnosti]); TorBox pod istim imenom kot v nalogi 119.
 * Izbira: najprej vse storitve s kljucem po vrstnem redu (prednostna prva) SAMO s predpomnjeno vsebino - zmaga prva, ki
 * jo ima pripravljeno (brez cakanja); ce je nima nobena in uporabnik ni vklopil »Samo takoj predvajljivo«, prednostna
 * storitev torrent prenese (cakanje z napredkom). Klici so blokirajoci - samo z delovne niti.
 */
object Debrid {
    private const val TAG = "SafeerDebrid"
    private const val PREFS = "safeer_debrid"
    private const val KLJUC_SAMO_TAKOJ = "samo_takoj"
    private const val KLJUC_PREDNOSTNA = "prednostna"
    private const val KLJUC_IZKLOPLJENO = "izklopljeno"
    private const val KLJUC_MERITEV = "meritev_"
    private const val KLJUC_ZAPOMNJENI = "zapomnjeni"

    // ------------------------------------------------------------------ kljuci in nastavitve

    private fun imeSkrivnosti(p: String) = "debrid_$p"

    fun kljuc(c: Context, p: String): String? = when (p) {
        DebridPravila.TORBOX -> TorBox.kljuc(c)
        else -> SporocilaSkrivnosti.preberi(c, imeSkrivnosti(p))?.let { DebridPravila.ocistiKljuc(it) }
    }

    fun imaKljuc(c: Context, p: String): Boolean = kljuc(c, p) != null

    /** Ali ima uporabnik vpisan kljuc vsaj ene storitve. */
    fun imaKateriKljuc(c: Context): Boolean = DebridPravila.VSI.any { imaKljuc(c, it) }

    /**
     * Storitve, ki jih Safeer sme klicati: kljuc je vpisan IN ne velja »ne velja« ali »zaklenjen« (varovalo racunov K1,
     * lastnik 26. 10. 2026 - prej je bilo stanje samo za prikaz: vsako odprtje vsebine in predvajanje je klicalo storitev z
     * zavrnjenim kljucem ali zaklenjenim racunom). Iz tega gredo vrstniRed, aktivna, ⚡ (predpomnjeni) in predvajanje.
     */
    fun zKljucem(c: Context): Set<String> = DebridPravila.VSI.filterTo(LinkedHashSet()) { uporaben(c, it) }

    /** Kljuc je vpisan in ga smemo uporabiti (en klic Keystore). */
    private fun uporaben(c: Context, p: String): Boolean {
        val k = kljuc(c, p) ?: return false
        return DebridPravila.smeKlicati(stanjeZa(c, p, DebridPravila.odtisStanja(k))?.first)
    }

    /**
     * Storitev, katere kljuc je vpisan, a ga Safeer ne uporablja (ne velja ali racun zaklenjen) - torrent takrat NE gre tiho
     * mimo debrida (kot [neberljiva]): uporabnik izve, kaj narediti. null = take ni ali je izbral »Brez debrida«.
     */
    fun ustavljena(c: Context): String? {
        if (prefs(c).getBoolean(KLJUC_IZKLOPLJENO, false)) return null
        val ustavljene = DebridPravila.VSI.filterTo(LinkedHashSet()) { imaKljuc(c, it) && !uporaben(c, it) }
        return DebridPravila.vrstniRed(ustavljene, prednostna(c)).firstOrNull()
    }

    /** Pregled A5: kljuc storitve je shranjen - ne glede na to, ali ga Keystore se zna odsifrirati. */
    fun kljucShranjen(c: Context, p: String): Boolean = when (p) {
        DebridPravila.TORBOX -> TorBox.kljucShranjen(c)
        else -> SporocilaSkrivnosti.ima(c, imeSkrivnosti(p))
    }

    /**
     * Pregled A5: storitev, katere kljuc je shranjen, a ga ni mogoce prebrati (napaka Keystore, obnovljena varnostna kopija).
     * Torrent takrat NE gre tiho mimo debrida (naslov IP bi bil viden) - uporabnik izve, da mora kljuc vpisati znova.
     * null = take storitve ni ali je uporabnik sam izbral »Brez debrida«.
     */
    fun neberljiva(c: Context): String? {
        if (prefs(c).getBoolean(KLJUC_IZKLOPLJENO, false)) return null
        val neberljive = DebridPravila.VSI.filterTo(LinkedHashSet()) { kljucShranjen(c, it) && !imaKljuc(c, it) }
        return DebridPravila.vrstniRed(neberljive, prednostna(c)).firstOrNull()
    }

    /** Shrani ze ocisceni in preverjeni kljuc (sifrirano); brez usklajevanja - odloca [vpisiKljuc] oz. [sprejmiKljuc]. */
    private fun shraniKljuc(c: Context, p: String, k: String): Boolean = when (p) {
        DebridPravila.TORBOX -> TorBox.nastaviKljuc(c, k)
        else -> SporocilaSkrivnosti.shrani(c, imeSkrivnosti(p), k)
    }

    // ------------------------------------------------------------------ preverba kljuca (lastnik 25. 10. 2026)

    private const val KLJUC_STANJE = "stanje_"
    /** Zadnja preverba kljuca po odtisu (cakajoci: »caka_« + storitev; samo v pomnilniku - po zagonu se preveri znova). */
    private val zadnjaPreverba = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** (stanje, paket) shranjenega kljuca storitve [p]; null = ni kljuca ali ga se nihce ni preveril (stanje drugega kljuca ne velja). */
    fun stanjeKljuca(c: Context, p: String): Pair<DebridPravila.StanjeKljuca, Int?>? {
        val k = kljuc(c, p) ?: return null
        return stanjeZa(c, p, DebridPravila.odtisStanja(k))?.let { it.first to it.second }
    }

    private const val KLJUC_ZAPORE = "zapore_"
    /** Pregled Grok 41, Z3 (namizje S3): zapore »storitev|odtis« v pomnilniku - postavljene PRED zapisom v nastavitve. */
    private val zaprti = java.util.concurrent.ConcurrentHashMap<String, DebridPravila.StanjeKljuca>()

    /**
     * Pregled Grok 41, Z3: (stanje, paket, cas) kljuca z [odtis] storitve [p] - tudi kljuca, ki trenutno ni shranjen (zapora
     * po kljucu, ne po storitvi: [DebridPravila.stanjeZZaporo]). null = ni stanja.
     */
    private fun stanjeZa(c: Context, p: String, odtis: String): Triple<DebridPravila.StanjeKljuca, Int?, Long>? {
        val pr = prefs(c)
        val naMestu = DebridPravila.stanjeIzZapisa(pr.getString(KLJUC_STANJE + p, null), odtis)
        val s = DebridPravila.stanjeZZaporo(zaprti["$p|$odtis"], naMestu?.first,
            DebridPravila.zaporeIzZapisa(pr.getString(KLJUC_ZAPORE + p, null))[odtis]) ?: return null
        return Triple(s, naMestu?.second, naMestu?.third ?: 0L)
    }

    /**
     * Pregled Grok 41, Z5 (namizje S6): vsi zapisi stanja in odlocitev po uporabi ([obIzidu]) pod istim zaklepom - pozen
     * uspeh ne prepise zapore, ki jo je druga nit zapisala po njegovem branju.
     */
    private val zaklepStanja = Any()

    /** Zapis stanja kljuca z [odtis]: zapora najprej v pomnilnik, nato na mesto storitve in med zapore po kljucu (Z3). */
    private fun zapisi(c: Context, p: String, odtis: String, s: DebridPravila.StanjeKljuca, paket: Int?, ob: Long, rocno: Boolean = false) {
        synchronized(zaklepStanja) {
            val k = "$p|$odtis"
            if (s == DebridPravila.StanjeKljuca.NE_VELJA || s == DebridPravila.StanjeKljuca.ZAKLENJEN) zaprti[k] = s
            val pr = prefs(c)
            pr.edit().putString(KLJUC_STANJE + p, DebridPravila.zapisStanja(s, odtis, paket, ob, rocno))
                .putString(KLJUC_ZAPORE + p, DebridPravila.zZaporo(pr.getString(KLJUC_ZAPORE + p, null), odtis, s)).apply()
            if (s == DebridPravila.StanjeKljuca.VELJA) zaprti.remove(k)
        }
    }

    /** Pregled Grok 41, Z3/Z5: zapora kljuca, ki ni vec shranjen (izid zahteve s prejsnjim kljucem) - samo po kljucu, mesto ostane. */
    private fun zapisiZaporo(c: Context, p: String, odtis: String, s: DebridPravila.StanjeKljuca) {
        if (DebridPravila.smeKlicati(s)) return
        synchronized(zaklepStanja) {
            zaprti["$p|$odtis"] = s
            val pr = prefs(c)
            pr.edit().putString(KLJUC_ZAPORE + p, DebridPravila.zZaporo(pr.getString(KLJUC_ZAPORE + p, null), odtis, s)).apply()
        }
    }

    /** Kljuc, ki ga je storitev potrdila - samo tak gre med naprave v ozjem krogu ([DebridPravila.zaUskladitev]). */
    fun kljucVelja(c: Context, p: String): Boolean = DebridPravila.zaUskladitev(stanjeKljuca(c, p)?.first)

    private fun zapisiStanje(c: Context, p: String, kljuc: String, s: DebridPravila.StanjeKljuca, paket: Int?, rocno: Boolean = false) {
        zapisi(c, p, DebridPravila.odtisStanja(kljuc), s, paket, System.currentTimeMillis(), rocno)
    }

    /**
     * Preverba kljuca pri storitvi (TorBox user/me, Real-Debrid /user, AllDebrid /user - nic ne doda v racun). Samo z delovne
     * niti. Odgovora ne zapisemo nikamor (nosi tudi e-naslov racuna) - samo izid in paket.
     */
    fun preveriKljuc(p: String, kljuc: String): Pair<DebridPravila.Preverba, Int?> = if (!smeOmrezje(TAG)) DebridPravila.Preverba.NI_ODGOVORA to null else when (p) {
        DebridPravila.TORBOX -> TorBox.preveri(kljuc)
        DebridPravila.REAL_DEBRID -> try {
            val o = klic(DebridPravila.RD_UPORABNIK, kljuc, povezavaMs = 8_000, branjeMs = 8_000)
            val j = o.json
            DebridPravila.preverbaRdOdgovor(o.koda, j?.opt("error_code"), j?.opt("id"), j?.opt("username")) to null
        } catch (e: Exception) { Log.i(TAG, "preverba RD: ${e.javaClass.simpleName}"); DebridPravila.Preverba.NI_ODGOVORA to null }
        else -> try {
            val o = klic(DebridPravila.AD_UPORABNIK, kljuc, povezavaMs = 8_000, branjeMs = 8_000)
            val j = o.json
            DebridPravila.preverbaAdOdgovor(o.koda, j?.opt("status"), j?.optJSONObject("error")?.opt("code")) to null
        } catch (e: Exception) { Log.i(TAG, "preverba AD: ${e.javaClass.simpleName}"); DebridPravila.Preverba.NI_ODGOVORA to null }
    }.also { Log.i(TAG, "preverba ${DebridPravila.ime(p)}: ${it.first}") }

    /**
     * Vpis kljuca v oknu »Debrid storitve« (delovna nit). Vzorec ali ne-kljuc: [DebridPravila.Preverba.OBLIKA] brez klica;
     * zavrnjen kljuc se NE shrani; brez povezave se shrani kot nepreverjen in ne gre na druge naprave, dokler ga storitev ne
     * potrdi ([preveriNepreverjene]). Veljaven gre sam v ozji krog.
     */
    /**
     * Izid vpisa: preverba, paket in [caka] = novi kljuc caka ob strani, dosedanji veljaven ostane (pregled Grok 33, F3).
     * Pregled Grok 33, F9: [zastarel] = medtem je bil vpisan drug kljuc te storitve (izid se zavrze, okno ga ne pokaze);
     * [napakaShrambe] = sifrirana shramba ni uspela (prej se je pokazalo kot »neveljaven kljuc«).
     */
    class IzidVpisa(val preverba: DebridPravila.Preverba, val paket: Int?, val caka: Boolean = false,
                    val zastarel: Boolean = false, val napakaShrambe: Boolean = false,
                    /** Pregled Grok 41, Z2: isti kljuc je sel storitvi pred manj kot minuto - brez klica. */
                    val pocakaj: Boolean = false,
                    /** Pregled Grok 41, Z2: storitev ni odgovorila - »ne velja« ali »zaklenjen« istega kljuca ostane. */
                    val ostalo: Boolean = false)

    /** Pregled Grok 41, Z2: »Preveri« in vpis po kljucu najvec enkrat na minuto (debrid in indeksator, [Usenet]). */
    internal val rocne = DebridPravila.RocnePreverbe()

    /** Stevec vpisov po storitvi: preverba traja do ~16 s, okno se zapre takoj - zmaga zadnji VPISANI, ne zadnji koncani. */
    private val stevecVpisov = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun vpisiKljuc(c: Context, p: String, vnos: String): IzidVpisa {
        val k = DebridPravila.kljucIzVnosa(vnos, p) ?: return IzidVpisa(DebridPravila.Preverba.OBLIKA, null)
        // Pregled Grok 41, Z2: isti kljuc najvec enkrat na minuto, skupaj s »Preveri« (vsak zavrnjen klic je poskus prijave).
        if (rocne.cez(p, DebridPravila.odtisStanja(k), System.currentTimeMillis()) > 0)
            return IzidVpisa(DebridPravila.Preverba.NI_ODGOVORA, null, pocakaj = true)
        // Pregled Grok 41, Z3: zapora po kljucu - tudi kljuc, ki ga je ta naprava medtem zamenjala z drugim.
        val prej = stanjeZa(c, p, DebridPravila.odtisStanja(k))?.first
        val st = stevecVpisov.merge(p, 1) { a, b -> a + b } ?: 1
        val izid = preveriKljuc(p, k)
        if (stevecVpisov[p] != st) { Log.i(TAG, "${DebridPravila.ime(p)}: medtem vpisan drug kljuc - ta izid se zavrze"); return IzidVpisa(izid.first, null, zastarel = true) }
        // Pregled Grok 41, Z2: ponoven vpis kljuca »ne velja« ali »zaklenjen« brez odgovora storitve (izpad, 429, 5xx) ga
        // ne odklene - prej je postal »nepreverjen« in ⚡, predvajanje in preverba na 10 min so spet klicali storitev.
        val s = DebridPravila.stanjePoVpisu(prej, izid.first) ?: return IzidVpisa(izid.first, izid.second)
        if (s == prej && !DebridPravila.smeKlicati(s) && DebridPravila.stanjePoRocniPreverbi(izid.first) == null) {
            Log.i(TAG, "${DebridPravila.ime(p)}: ponoven vpis brez odgovora storitve - stanje ostane")
            return IzidVpisa(izid.first, null, ostalo = true)
        }
        when (DebridPravila.obVpisu(s, kljucVelja(c, p), kljuc(c, p) == k)) {
            // Pregled Grok 33, F3: storitve ni bilo mogoce doseci - veljaven kljuc ostane, novi caka ob strani.
            DebridPravila.Vpis.OB_STRANI -> {
                if (!SporocilaSkrivnosti.shrani(c, imeCakajocega(p), k)) return IzidVpisa(izid.first, null, napakaShrambe = true)
                prefs(c).edit().remove(KLJUC_CAKA_ZAVRNJEN + p).apply()
                zadnjaPreverba[CAKA + p] = System.currentTimeMillis()
                Log.i(TAG, "${DebridPravila.ime(p)}: nov kljuc caka na preverbo, veljaven ostane")
                return IzidVpisa(izid.first, null, caka = true)
            }
            DebridPravila.Vpis.OHRANI -> { pozabiCakajocega(c, p); return IzidVpisa(izid.first, null, caka = true) }
            // Varovalo racunov: nov kljuc zaklenjenega racuna ne zamenja veljavnega - nic se ne shrani, okno pove zakaj.
            DebridPravila.Vpis.ZAVRNI -> return IzidVpisa(izid.first, null)
            DebridPravila.Vpis.ZAMENJAJ -> Unit
        }
        if (!shraniKljuc(c, p, k)) return IzidVpisa(izid.first, null, napakaShrambe = true)
        pozabiCakajocega(c, p)
        // Pregled Grok 41, Z5: »velja« iz vpisa (uporabnik, storitev potrdi) odpravi zaporo tudi na drugih napravah.
        zapisiStanje(c, p, k, s, izid.second, rocno = true)
        zadnjaPreverba[DebridPravila.odtisStanja(k)] = System.currentTimeMillis()
        if (DebridPravila.zaUskladitev(s)) UskladitevKljucev.sprozi(c)
        return IzidVpisa(izid.first, izid.second)
    }

    // ------------------------------------------------------------------ cakajoci kljuc (pregled Grok 33, F3)

    private const val CAKA = "caka_"
    private const val KLJUC_CAKA_ZAVRNJEN = "caka_zavrnjen_"
    private fun imeCakajocega(p: String) = "debrid_caka_$p"

    /** Na tej napravi caka nov, se nepreverjen kljuc (sifriran; ne gre na druge naprave) - dosedanji veljaven je v uporabi. */
    fun cakajoc(c: Context, p: String): Boolean = SporocilaSkrivnosti.ima(c, imeCakajocega(p))

    /** Zadnji cakajoci kljuc je storitev zavrnila (vrstica v oknu - do naslednjega vpisa ali odstranitve). */
    fun cakajocZavrnjen(c: Context, p: String): Boolean = prefs(c).getBoolean(KLJUC_CAKA_ZAVRNJEN + p, false)

    private fun pozabiCakajocega(c: Context, p: String) {
        if (SporocilaSkrivnosti.ima(c, imeCakajocega(p))) SporocilaSkrivnosti.pozabi(c, imeCakajocega(p))
        prefs(c).edit().remove(KLJUC_CAKA_ZAVRNJEN + p).apply()
    }

    /** Cakajoci kljuci: preverba (najvec na [DebridPravila.PREVERI_ZNOVA_MS]); potrjen zamenja veljavnega. true = sprememba. */
    private fun preveriCakajoce(c: Context): Boolean {
        var spremenjeno = false
        for (p in DebridPravila.VSI) {
            val zapis = SporocilaSkrivnosti.preberi(c, imeCakajocega(p)) ?: continue
            val k = DebridPravila.kljucIzVnosa(zapis, p)
            val zdaj = System.currentTimeMillis()
            if (k != null && !DebridPravila.preveriZdaj(DebridPravila.StanjeKljuca.NEPREVERJEN, zadnjaPreverba[CAKA + p] ?: 0L, zdaj)) continue
            zadnjaPreverba[CAKA + p] = zdaj
            // Pregled Grok 41, Z3: cakajoci kljuc z zaporo (ne velja, zaklenjen) se zavrze brez klica storitve.
            val zapora = k?.let { stanjeZa(c, p, DebridPravila.odtisStanja(it))?.first }?.takeIf { !DebridPravila.smeKlicati(it) }
            val (izid, paket) = when {
                k == null -> DebridPravila.Preverba.OBLIKA to null
                zapora == DebridPravila.StanjeKljuca.ZAKLENJEN -> DebridPravila.Preverba.ZAKLENJEN to null
                zapora != null -> DebridPravila.Preverba.NE_VELJA to null
                else -> preveriKljuc(p, k)
            }
            when (DebridPravila.poPreverbiCakajocega(izid)) {
                DebridPravila.Cakajoc.ZAMENJAJ -> {
                    if (k == null || !shraniKljuc(c, p, k)) continue
                    zapisiStanje(c, p, k, DebridPravila.StanjeKljuca.VELJA, paket)
                    zadnjaPreverba[DebridPravila.odtisStanja(k)] = zdaj
                    pozabiCakajocega(c, p)
                    Log.i(TAG, "${DebridPravila.ime(p)}: cakajoci kljuc potrjen - zamenjal je prejsnjega")
                    UskladitevKljucev.sprozi(c)
                    spremenjeno = true
                }
                DebridPravila.Cakajoc.ZAVRZI -> {
                    SporocilaSkrivnosti.pozabi(c, imeCakajocega(p))
                    prefs(c).edit().putBoolean(KLJUC_CAKA_ZAVRNJEN + p, true).apply()
                    Log.i(TAG, "${DebridPravila.ime(p)}: cakajoci kljuc zavrnjen - ostane prejsnji")
                    spremenjeno = true
                }
                DebridPravila.Cakajoc.CAKA -> Unit
            }
        }
        return spremenjeno
    }

    /**
     * Pregled Grok 33, F2: omrezna preverba kljuca samo z delovne niti in nikoli pod zaklepom usklajevanja. Prej je prejem
     * `secrets.sync` (Daljinec tece na glavni niti) klical preverbo na glavni niti - NetworkOnMainThreadException je tiho
     * postal »ni odgovora« in prejeti kljuc ni bil nikoli sprejet; pod zaklepom pa bi glavna nit cakala na omrezje (ANR).
     * Programska napaka je zdaj vidna v dnevniku, izid je varen (ni odgovora - nic se ne shrani).
     */
    internal fun smeOmrezje(tag: String): Boolean {
        val glavna = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        val podZaklepom = UskladitevKljucev.drziZaklep()
        if (!glavna && !podZaklepom) return true
        Log.e(tag, "preverba kljuca ni narejena: ${if (glavna) "glavna nit" else "pod zaklepom usklajevanja"} (programska napaka)")
        return false
    }

    /** Prejet kljuc, ki ga je storitev potrdila s te naprave ([preveriPrejetega]); shrani ga [shraniPrejetega] brez omrezja. */
    class Potrjen(val ponudnik: String, val kljuc: String, val paket: Int?, val zeShranjen: Boolean)

    /**
     * Kljuc z druge naprave (usklajevanje, »Poslji kljuce«): oblika in preverba pri storitvi s te naprave - omrezje, samo
     * delovna nit brez zaklepa ([smeOmrezje]). Potrjen je samo kljuc, ki ga storitev potrdi tudi s te naprave
     * ([DebridPravila.sprejmiPrejetega]); zavrnjen nikoli ne prepise veljavnega, brez povezave pocaka na naslednjo uskladitev.
     */
    fun preveriPrejetega(c: Context, p: String, vnos: String): Pair<DebridPravila.Preverba, Potrjen?> {
        val k = DebridPravila.kljucIzVnosa(vnos, p) ?: return DebridPravila.Preverba.OBLIKA to null
        // Pregled Grok 41, Z3: zapora po kljucu - tudi kljuc, ki ga ta naprava ne uporablja vec (prej samo shranjeni).
        val s = stanjeZa(c, p, DebridPravila.odtisStanja(k))?.first
        if (kljuc(c, p) == k && s == DebridPravila.StanjeKljuca.VELJA) return DebridPravila.Preverba.VELJA to Potrjen(p, k, null, true)
        // Varovalo racunov K1: kljuc, ki tu ne velja ali je racun zaklenjen - brez klica storitve.
        if (s == DebridPravila.StanjeKljuca.NE_VELJA) return DebridPravila.Preverba.NE_VELJA to null
        if (s == DebridPravila.StanjeKljuca.ZAKLENJEN) return DebridPravila.Preverba.ZAKLENJEN to null
        val (izid, paket) = preveriKljuc(p, k)
        if (!DebridPravila.sprejmiPrejetega(izid)) { Log.i(TAG, "prejeti kljuc ${DebridPravila.ime(p)}: $izid - ni uporabljen"); return izid to null }
        return izid to Potrjen(p, k, paket, false)
    }

    /** Shrani potrjen prejeti kljuc - brez omrezja (tudi pod zaklepom usklajevanja). false = shramba ni uspela. */
    fun shraniPrejetega(c: Context, pr: Potrjen): Boolean {
        if (pr.zeShranjen) return true
        if (!shraniKljuc(c, pr.ponudnik, pr.kljuc)) return false
        zapisiStanje(c, pr.ponudnik, pr.kljuc, DebridPravila.StanjeKljuca.VELJA, pr.paket)
        return true
    }

    /**
     * Kljuc z druge naprave - preverba in shranjevanje (delovna nit, brez zaklepa). Pregled Grok 33, F9: vrne izid preverbe
     * (VELJA = shranjen), da okno loci »zavrnjen« od »ni bilo mogoce preveriti«; napaka shrambe = ni odgovora (poslji znova).
     */
    fun sprejmiKljuc(c: Context, p: String, vnos: String): DebridPravila.Preverba {
        val (izid, pr) = preveriPrejetega(c, p, vnos)
        if (pr == null) return izid
        return if (shraniPrejetega(c, pr)) DebridPravila.Preverba.VELJA else DebridPravila.Preverba.NI_ODGOVORA
    }

    /**
     * Razveljavitev spremembe z druge naprave (pregled Grok 33, F2): prejsnji kljuc te naprave se vrne BREZ omrezja (prej
     * preverba pod zaklepom; brez povezave razveljavitev tiho ni uspela) - kot nepreverjen: uskladitev ga takoj preveri
     * ([preveriNepreverjene]) in sele potrjen gre spet drugim napravam.
     */
    fun obnoviKljuc(c: Context, p: String, vnos: String): Boolean {
        val k = DebridPravila.kljucIzVnosa(vnos, p) ?: return false
        val odtis = DebridPravila.odtisStanja(k)
        // Pregled Grok 41, Z3: kljuc z zaporo se vrne z njo - prej kot nepreverjen in uskladitev ga je takoj poklicala.
        val s = DebridPravila.stanjeObObnovi(stanjeZa(c, p, odtis)?.first)
        if (!shraniKljuc(c, p, k)) return false
        zapisiStanje(c, p, k, s, null)
        if (s == DebridPravila.StanjeKljuca.NEPREVERJEN) zadnjaPreverba.remove(odtis)
        return true
    }

    /**
     * Shranjeni kljuci brez stanja (pred posodobitvijo, prejeti v starejsi razlicici) ali nepreverjeni: preverba pri storitvi
     * (najvec na [DebridPravila.PREVERI_ZNOVA_MS]). Zavrnjen ostane shranjen z oznako »ne velja« (vrstica pove, da ga je treba
     * vpisati znova) in ne gre na druge naprave. Delovna nit. true = stanje katerega se je spremenilo.
     */
    fun preveriNepreverjene(c: Context): Boolean {
        var spremenjeno = false
        for (p in DebridPravila.VSI) {
            val k = kljuc(c, p) ?: continue
            val prej = stanjeKljuca(c, p)
            // Skupni primeri v2: najvec enkrat na 10 min ZA TA KLJUC (po odtisu), tudi kljuc brez stanja.
            val odtis = DebridPravila.odtisStanja(k)
            if (!DebridPravila.preveriZdaj(prej?.first, zadnjaPreverba[odtis] ?: 0L, System.currentTimeMillis())) continue
            zadnjaPreverba[odtis] = System.currentTimeMillis()
            val (izid, paket) = preveriKljuc(p, k)
            val s = DebridPravila.stanjePoPreverbi(izid) ?: DebridPravila.StanjeKljuca.NE_VELJA
            // Pregled Grok 41, Z5: zapis samo, ce sta kljuc in stanje med preverbo ostala ista - zapora, zapisana med klicem
            // (izid uporabe, druga naprava), ostane (prej jo je samodejna preverba prepisala).
            val zapisano = synchronized(zaklepStanja) {
                if ((s == prej?.first && paket == prej?.second) || kljuc(c, p) != k || stanjeKljuca(c, p)?.first != prej?.first) false
                else { zapisiStanje(c, p, k, s, paket ?: prej?.second); true }
            }
            if (zapisano) spremenjeno = true
        }
        // Pregled Grok 33, F3: kljuci, ki cakajo ob strani (vpisani brez povezave cez veljavnega).
        if (try { preveriCakajoce(c) } catch (e: Exception) { Log.w(TAG, "cakajoci: ${e.javaClass.simpleName}"); false }) spremenjeno = true
        // Indeksator Usenet enako (kljuc indeksatorja gre z istimi kljuci v ozji krog).
        if (try { Usenet.preveriNepreverjenega(c) } catch (_: Exception) { false }) spremenjeno = true
        return spremenjeno
    }

    /**
     * Med uporabo: zavrnjen kljuc (401) -> »ne velja«; povezava od storitve -> kljuc velja (stanje se osvezi samo).
     * Pregled Grok 33, F5: izid velja samo za kljuc, s katerim je sla zahteva ([kljucZahteve]), in za storitev, ki je
     * odgovorila ([ponudnikIzida], prazno = [p]) - prej je izid dobil trenutno shranjen kljuc: ob menjavi med dolgim
     * prenosom je nov (morda nepreverjen) kljuc dobil »velja« in sel v ozji krog, ali veljaven »ne velja«.
     */
    fun obIzidu(c: Context, p: String, kljucZahteve: String, ponudnikIzida: String, napaka: String, uspeh: Boolean) {
        val k = kljuc(c, p)
        if (k == null || !DebridPravila.izidZaKljuc(k, kljucZahteve, p, ponudnikIzida)) {
            // Pregled Grok 41, Z3: zavrnjen ali zaklenjen kljuc zahteve, ki ga je ta naprava medtem zamenjala ali odstranila,
            // dobi zaporo po kljucu (»Razveljavi« ali prejem ga ne vrne kot nepreverjenega); mesto ostane novemu kljucu.
            val zapora = when (napaka) {
                TorBoxPravila.NAPAKA_KLJUC -> DebridPravila.StanjeKljuca.NE_VELJA
                TorBoxPravila.NAPAKA_ZAKLENJEN -> DebridPravila.StanjeKljuca.ZAKLENJEN
                else -> null
            }
            if (zapora != null && (ponudnikIzida.isEmpty() || ponudnikIzida == p)) zapisiZaporo(c, p, DebridPravila.odtisStanja(kljucZahteve), zapora)
            Log.i(TAG, "${DebridPravila.ime(p)}: izid za drug kljuc ali storitev - stanje ostane"); return
        }
        // Pregled Grok 41, Z5 (namizje S6): branje in zapis pod zaklepom stanj; »velja« samo iz »nepreverjen« ali brez stanja -
        // pozen uspeh zahteve, ki je stekla pred zaporo, je prej zaporo odpravil in jo prek polja racuni odpravil v vsem krogu.
        var prej: DebridPravila.StanjeKljuca? = null
        val novo = synchronized(zaklepStanja) {
            val tu = stanjeKljuca(c, p)
            prej = tu?.first
            DebridPravila.stanjePoUporabi(tu?.first, napaka, uspeh)?.also {
                zapisiStanje(c, p, k, it, if (it == DebridPravila.StanjeKljuca.NE_VELJA) null else tu?.second)
            }
        }
        when (novo) {
            DebridPravila.StanjeKljuca.NE_VELJA ->
                Log.i(TAG, "${DebridPravila.ime(p)}: kljuc zavrnjen med uporabo - stanje »ne velja«, storitve ne klicemo vec")
            // Varovalo racunov: zaklenjen racun ali nova lokacija - od zdaj brez klicev, dokler uporabnik ne tapne »Preveri«.
            DebridPravila.StanjeKljuca.ZAKLENJEN -> Log.i(TAG, "${DebridPravila.ime(p)}: racun zaklenjen - storitve ne klicemo vec")
            null -> {
                if (uspeh && !DebridPravila.smeKlicati(prej)) Log.i(TAG, "${DebridPravila.ime(p)}: pozen uspeh ne odpravi zapore (samo »Preveri«)")
                return
            }
            else -> Unit
        }
        // Varovalo racunov: stanje takoj v ozji krog - druge naprave s tem kljucem storitve ne klicejo vec.
        UskladitevKljucev.sprozi(c)
    }

    /** Izbris kljuca z druge naprave (grob usklajevanja) ali razveljavitev - izbris tu in uskladitev. */
    fun pozabiKljuc(c: Context, p: String) {
        brisiKljuc(c, p)
        UskladitevKljucev.sprozi(c)
    }

    private fun brisiKljuc(c: Context, p: String) {
        if (p == DebridPravila.TORBOX) TorBox.pozabiKljuc(c) else SporocilaSkrivnosti.pozabi(c, imeSkrivnosti(p))
    }

    /**
     * Gumb »Odstrani« (pregled Grok 33, F1): kljuc, ki ga je storitev TU potrdila, se odstrani na vseh napravah ozjega
     * kroga; nepreverjen, zavrnjen ali neberljiv samo na tej napravi (druge obdrzijo svoj veljaven kljuc). true = izbris
     * gre tudi drugim napravam.
     */
    fun odstraniKljuc(c: Context, p: String): Boolean {
        val vKrog = UskladitevKljucevPravila.izbrisVKrog(kljucVelja(c, p))
        // Pregled Grok 33, F3: »Odstrani« pozabi tudi kljuc, ki caka ob strani.
        UskladitevKljucev.odstrani(c, UskladitevKljucevPravila.kljucDebrid(p), vKrog) { brisiKljuc(c, p); pozabiCakajocega(c, p) }
        return vKrog
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ stanje racunov v ozjem krogu (varovalo racunov)

    /** Dokoncna stanja racunov te naprave za ozji krog: storitev, odtis kljuca, stanje, cas - nikoli kljuc. */
    fun stanjaZaKrog(c: Context): JSONArray {
        val a = JSONArray()
        for (p in DebridPravila.VSI) {
            val k = kljuc(c, p) ?: continue
            val odtis = DebridPravila.odtisStanja(k)
            val zapis = prefs(c).getString(KLJUC_STANJE + p, null)
            val z = DebridPravila.stanjeIzZapisa(zapis, odtis) ?: continue
            // Pregled Grok 41, Z5: »r« = »velja« iz »Preveri« ali vpisa - samo tak odpravi zaporo na drugi napravi.
            if (DebridPravila.stanjeVKrog(z.first))
                a.put(JSONObject().put("p", p).put("o", odtis).put("s", z.first.name.lowercase(java.util.Locale.ROOT)).put("c", z.third)
                    .apply { if (z.first == DebridPravila.StanjeKljuca.VELJA && DebridPravila.rocnoIzZapisa(zapis)) put("r", true) })
        }
        try { Usenet.stanjeZaKrog(c)?.let { a.put(it) } } catch (_: Exception) { }
        return a
    }

    /**
     * Stanja racunov z naprave v ozjem krogu (brez omrezja): »ne velja« ali »zaklenjen« istega kljuca ustavi uporabo tudi tu,
     * »velja« (uporabnik je tam tapnil »Preveri«) jo spet dovoli. Vrne stevilo sprememb.
     */
    fun prejmiStanja(c: Context, a: JSONArray?): Int {
        if (a == null) return 0
        var n = 0
        val zdaj = System.currentTimeMillis()
        for (i in 0 until minOf(a.length(), 8)) {
            val o = a.optJSONObject(i) ?: continue
            val p = o.optString("p")
            val odtis = o.optString("o")
            val ob = o.optLong("c", 0L)
            val s = DebridPravila.stanjeIzImena(o.optString("s")) ?: continue
            val rocno = o.optBoolean("r", false)
            if (p == UsenetPravila.VRSTA) { if (try { Usenet.prejmiStanje(c, odtis, s, ob, zdaj, rocno) } catch (_: Exception) { false }) n++; continue }
            if (p !in DebridPravila.VSI) continue
            val k = kljuc(c, p) ?: continue
            if (DebridPravila.odtisStanja(k) != odtis) continue
            val tu = DebridPravila.stanjeIzZapisa(prefs(c).getString(KLJUC_STANJE + p, null), odtis)
            if (!DebridPravila.sprejmiStanje(tu?.third, s, ob, zdaj, stanjeZa(c, p, odtis)?.first, rocno)) continue
            zapisi(c, p, odtis, s, tu?.second, ob, rocno)
            Log.i(TAG, "${DebridPravila.ime(p)}: stanje racuna z druge naprave v ozjem krogu - ${s.name.lowercase(java.util.Locale.ROOT)}")
            n++
        }
        return n
    }

    /**
     * »Preveri« v oknu (varovalo racunov K1): edina nova preverba kljuca, ki ne velja ali je racun zaklenjen - najvec enkrat
     * na minuto za ta kljuc, skupaj z vpisom ([rocne], pregled Grok 41, Z2). Brez odgovora stanje ostane (ni samodejnih
     * ponovitev). Delovna nit. null = prezgodaj (pocakaj minuto).
     */
    fun preveriRocno(c: Context, p: String): DebridPravila.Preverba? {
        val k = kljuc(c, p) ?: return DebridPravila.Preverba.OBLIKA
        val zdaj = System.currentTimeMillis()
        if (rocne.cez(p, DebridPravila.odtisStanja(k), zdaj) > 0) return null
        val prej = stanjeKljuca(c, p)
        val (izid, paket) = preveriKljuc(p, k)
        val s = DebridPravila.stanjePoRocniPreverbi(izid) ?: return izid
        zapisiStanje(c, p, k, s, paket ?: prej?.second, rocno = true)
        zadnjaPreverba[DebridPravila.odtisStanja(k)] = zdaj
        Log.i(TAG, "${DebridPravila.ime(p)}: rocna preverba - $izid")
        // Varovalo racunov: tudi »ne velja« in »zaklenjen« gresta v ozji krog (stanje, ne kljuc).
        UskladitevKljucev.sprozi(c)
        return izid
    }

    /** »Samo takoj predvajljivo« (predpomnjeno): privzeto vklopljeno - uporabnik ne caka na prenos na streznik. */
    fun samoTakoj(c: Context): Boolean = prefs(c).getBoolean(KLJUC_SAMO_TAKOJ, true)
    fun nastaviSamoTakoj(c: Context, da: Boolean) { prefs(c).edit().putBoolean(KLJUC_SAMO_TAKOJ, da).apply() }

    fun prednostna(c: Context): String? = prefs(c).getString(KLJUC_PREDNOSTNA, null)?.takeIf { it in DebridPravila.VSI }
    fun nastaviPrednostno(c: Context, p: String) { prefs(c).edit().putString(KLJUC_PREDNOSTNA, p).apply() }

    fun vrstniRed(c: Context): List<String> = DebridPravila.vrstniRed(zKljucem(c), prednostna(c))

    /** Naloga 130: izbira »Debrid« ob zvrsteh - storitev, prek katere gredo torrenti; null = brez debrida ali brez kljuca. */
    fun aktivna(c: Context): String? = DebridPravila.aktivna(zKljucem(c), prednostna(c), prefs(c).getBoolean(KLJUC_IZKLOPLJENO, false))

    /** [p] = storitev (postane prednostna), null = »Brez debrida«. */
    fun nastaviAktivno(c: Context, p: String?) {
        val e = prefs(c).edit().putBoolean(KLJUC_IZKLOPLJENO, p == null)
        if (p != null) e.putString(KLJUC_PREDNOSTNA, p)
        e.apply()
    }

    /**
     * Naloga 130/136: kateri torrenti (hashi) so takoj predvajljivi - za oznako ⚡ in samodejno izbiro. TorBox: prava
     * preverba (checkcached). Real-Debrid in AllDebrid je nimata (gl. [DebridPravila]) - zanju torrenti, ki jih je storitev
     * ze vrnila kot pripravljene (na tej ali drugi napravi v ozjem krogu), brez dodatnih zahtev.
     */
    fun predpomnjeni(c: Context, hashi: List<String>): Set<String> {
        if (aktivna(c) == null) return emptySet()
        val sKljucem = zKljucem(c)
        val zapomnjeni = DebridPravila.zapomnjeniZa(zapomnjeni(c), hashi, sKljucem, System.currentTimeMillis())
        val torbox = if (DebridPravila.TORBOX in sKljucem) TorBox.predpomnjeni(c, hashi) else emptySet()
        if (zapomnjeni.isNotEmpty()) Log.i(TAG, "zapomnjeni pripravljeni: ${zapomnjeni.size} od ${hashi.size}")
        return torbox + zapomnjeni
    }

    // ------------------------------------------------------------------ naloga 136: zapomnjeni pripravljeni (RD, AD)

    private val zaklepZapomnjenih = Any()

    private fun zapomnjeni(c: Context): List<DebridPravila.Zapomnjen> =
        synchronized(zaklepZapomnjenih) { DebridPravila.zapomnjeniIzZapisa(prefs(c).getString(KLJUC_ZAPOMNJENI, null)) }

    private fun spremeniZapomnjene(c: Context, sprememba: (List<DebridPravila.Zapomnjen>) -> List<DebridPravila.Zapomnjen>) {
        synchronized(zaklepZapomnjenih) {
            val prej = DebridPravila.zapomnjeniIzZapisa(prefs(c).getString(KLJUC_ZAPOMNJENI, null))
            val potem = sprememba(prej)
            if (potem != prej) prefs(c).edit().putString(KLJUC_ZAPOMNJENI, DebridPravila.zapisZapomnjenih(potem)).apply()
        }
    }

    /** Po poskusu pri Real-Debrid ali AllDebrid: povezava = pripravljen (zapomni, deli), »ni predpomnjeno« = pozabi. */
    private fun zapomniIzid(c: Context, p: String, magnet: String, iz: TorBox.Izid) {
        if (p !in DebridPravila.ZAPOMNI) return
        val hash = TorBoxPravila.hashIzMagneta(magnet) ?: return
        val zdaj = System.currentTimeMillis()
        if (iz.url != null) {
            spremeniZapomnjene(c) { DebridPravila.zapomniPripravljen(it, hash, p, zdaj) }
            deliZapomnjen(c, DebridPravila.Zapomnjen(hash.lowercase(), p, zdaj + DebridPravila.ZAPOMNJEN_VELJA_MS))
        } else if (iz.napaka == DebridPravila.NAPAKA_NI_PREDPOMNJENO) {
            spremeniZapomnjene(c) { DebridPravila.pozabiPripravljen(it, hash, p) }
        }
    }

    /**
     * Novi pripravljeni torrent napravam v ozjem krogu - samo po sifrirani poti od naprave do naprave (seznam pove, kaj
     * uporabnik gleda). Brez odgovora, brez ponovitve: naprava, ki ni povezana, ga ne dobi.
     */
    private fun deliZapomnjen(c: Context, z: DebridPravila.Zapomnjen) {
        val (hash, ponudnik, ostane) = DebridPravila.zaPoslati(z, System.currentTimeMillis()) ?: return
        val link = try { LinkUpravitelj.pridobi(c.applicationContext) } catch (_: Throwable) { return }
        if (link.jeKrajevni() || !link.povezan) return
        val cilji = link.naprave.filter { n ->
            !link.jeTaNaprava(n) && OzjiKrog.vKrogu(c, n.id) && DebridPravila.smePoslatiKljuc(link.odjemalec.znaZascito(n.id), link.povezan)
        }
        if (cilji.isEmpty()) return
        val vnos = JSONObject().put("h", hash).put("p", ponudnik).put("ms", ostane)
        val parametri = JSONObject().put("items", JSONArray().put(vnos))
        for (n in cilji) link.ukaz(n.id, DebridPravila.DEJANJE_PREDPOMNJENI, parametri, 8_000, LinkOdjemalec.Odgovor { _, _ -> })
        Log.i(TAG, "pripravljen torrent deljen z ${cilji.size} napravami v ozjem krogu")
    }

    /** `debrid.cached` z druge naprave v ozjem krogu: veljavni vnosi gredo med zapomnjene (brez potrditve - ni skrivnost). */
    fun prejmiZapomnjene(c: Context, parametri: JSONObject): JSONObject {
        val a = parametri.optJSONArray("items") ?: JSONArray()
        val prejeti = (0 until minOf(a.length(), DebridPravila.NAJVEC_PREJETIH)).mapNotNull { a.optJSONObject(it) }
            .map { Triple(it.optString("h"), it.optString("p"), it.optLong("ms", 0L)) }
        var sprejetih = 0
        spremeniZapomnjene(c) { prej ->
            DebridPravila.prejetiZapomnjeni(prej, prejeti, System.currentTimeMillis()).also { sprejetih = it.count { z -> z !in prej } }
        }
        Log.i(TAG, "prejeti pripravljeni torrenti: $sprejetih novih od ${prejeti.size}")
        return JSONObject().put("accepted", sprejetih)
    }

    // ------------------------------------------------------------------ predvajanje

    /**
     * Povezava za [magnet] prek debrid storitev. [samoTakojPreglasi]: true/false preglasi nastavitev (gumb »Pocakaj na
     * prenos«), null = nastavitev uporabnika. Izid nosi storitev, ki je dala povezavo.
     */
    fun tok(c: Context, magnet: String, indeks: Int, samoTakojPreglasi: Boolean? = null, napredek: (Int) -> Unit = {},
            /** Neodvisni pregled 2: storitev, ki bo torrent prenesla (pred cakanjem) - za ime v sporocilu napredka. */
            obCakanju: (String) -> Unit = {}): TorBox.Izid {
        val red = vrstniRed(c)
        if (red.isEmpty()) return TorBox.Izid(napaka = TorBoxPravila.NAPAKA_KLJUC)
        val samoTakoj = samoTakojPreglasi ?: samoTakoj(c)
        val napake = ArrayList<String>()
        var opis = ""
        var cakajMs = 0L
        // Naloga 130: vec storitev s kljucem - vse vprasamo hkrati (samo predpomnjeno), med tistimi, ki imajo vsebino
        // pripravljeno, izmerimo odziv in hitrost do te naprave; predvaja najhitrejsa.
        // Pregled A4: (storitev, napaka) - sporocilo imenuje storitev, ki je res padla, ne vedno TorBoxa.
        val poStoritvah = ArrayList<Pair<String, String>>()
        if (red.size >= 2) {
            val izidi = vzporedno(c, red, magnet, indeks)
            // Pregled A1: preklic (nova izbira, Preklici) - brez meritev in brez drugega kroga.
            if (Thread.currentThread().isInterrupted) return TorBox.Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK, ponudnik = red.first())
            val uspesni = izidi.filter { it.second.url != null }
            if (uspesni.isNotEmpty()) {
                val izbrana = if (uspesni.size == 1) uspesni.first() else {
                    val meritve = uspesni.mapNotNull { (p, iz) -> izmeri(p, iz.url!!) }
                    meritve.forEach { prefs(c).edit().putString(KLJUC_MERITEV + it.ponudnik, DebridPravila.zapisMeritve(it, System.currentTimeMillis())).apply() }
                    Log.i(TAG, "meritve: " + meritve.joinToString { DebridPravila.ime(it.ponudnik) + " " + DebridPravila.opisMeritve(it) })
                    val p = DebridPravila.najhitrejsa(meritve)
                    uspesni.firstOrNull { it.first == p } ?: uspesni.first()
                }
                return TorBox.Izid(url = izbrana.second.url, ponudnik = izbrana.first)
            }
            for ((p, iz) in izidi) {
                napake += iz.napaka
                poStoritvah += p to iz.napaka
                if (iz.opis.isNotBlank()) opis = DebridPravila.ime(p) + ": " + iz.opis
                if (iz.cakajMs > cakajMs) cakajMs = iz.cakajMs
                Log.i(TAG, "${DebridPravila.ime(p)}: ${iz.napaka}")
            }
        }
        // 1. krog z eno storitvijo: samo predpomnjeno.
        if (red.size < 2) for (p in red) {
            val iz = poskus(c, p, magnet, indeks, true, napredek)
            if (iz.url != null) return TorBox.Izid(url = iz.url, ponudnik = p)
            napake += iz.napaka
            poStoritvah += p to iz.napaka
            if (iz.opis.isNotBlank()) opis = DebridPravila.ime(p) + ": " + iz.opis
            if (iz.cakajMs > cakajMs) cakajMs = iz.cakajMs
            Log.i(TAG, "${DebridPravila.ime(p)}: ${iz.napaka}")
        }
        // Pregled Grok 2: zavrnjen kljuc katere od storitev se ne izgubi - okno ga pove v dodatnem besedilu.
        val opisKljuca = poStoritvah.firstOrNull { it.second == TorBoxPravila.NAPAKA_KLJUC }
            ?.let { c.getString(si.safeer.tv.R.string.os_torbox_napaka_kljuc, DebridPravila.ime(it.first)) }
        // 2. krog: nobena je nima pripravljene - ce uporabnik caka, jo prenese prva storitev po vrstnem redu, ki je rekla
        // »ni predpomnjeno« (neodvisni pregled 2: tudi ce je prednostna padla na roku ali kljucu - prej »Pocakaj« brez cakanja).
        val cakalna = DebridPravila.cakalnaStoritev(samoTakoj, poStoritvah)
        if (cakalna != null && !Thread.currentThread().isInterrupted) {
            obCakanju(cakalna)
            val iz = poskus(c, cakalna, magnet, indeks, false, napredek)
            val o = if (iz.url == null && iz.opis.isBlank()) opisKljuca.orEmpty() else iz.opis
            return TorBox.Izid(iz.url, iz.napaka, o, iz.cakajMs, cakalna)
        }
        val razlog = DebridPravila.razlogPoStoritvah(poStoritvah)
        if (razlog == DebridPravila.NAPAKA_NI_PREDPOMNJENO && opisKljuca != null) opis = opisKljuca
        return TorBox.Izid(napaka = razlog, opis = opis, cakajMs = cakajMs, ponudnik = DebridPravila.ponudnikNapake(poStoritvah))
    }

    /** Vse storitve hkrati, samo predpomnjeno; kar v [DebridPravila.VZPOREDNO_ROK_MS] ne odgovori, je napaka streznika. */
    private fun vzporedno(c: Context, red: List<String>, magnet: String, indeks: Int): List<Pair<String, TorBox.Izid>> {
        val bazen = java.util.concurrent.Executors.newFixedThreadPool(red.size)
        try {
            val niti = red.map { p -> p to bazen.submit<TorBox.Izid> { poskus(c, p, magnet, indeks, true) {} } }
            val konec = System.currentTimeMillis() + DebridPravila.VZPOREDNO_ROK_MS
            return niti.map { (p, f) ->
                p to (try { f.get((konec - System.currentTimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) {
                        // Pregled A1: preklic - oznaka prekinitve ostane, da klicatelj [tok] ne nadaljuje.
                        Thread.currentThread().interrupt(); TorBox.Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK, ponudnik = p)
                    }
                    catch (_: Exception) { TorBox.Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK, ponudnik = p) })
            }
        } finally {
            // Pregled Grok 2: niti, ki se cakajo na odgovor, so prekinjene - po odgovoru ne naredijo nobenega koraka vec v
            // racunu ([preveriPreklic]) in pospravijo, kar so ustvarile. Ne cakamo nanje (preklic mora biti takojsen).
            bazen.shutdownNow()
        }
    }

    /**
     * Meritev povezave do CDN storitve: zahteva za prvih [DebridPravila.MERITEV_BAJTOV] (Range), cas do odgovora = ping,
     * nato najvec [DebridPravila.MERITEV_MS] branja. Naslova ne zapisemo (vsebuje zeton storitve). null = ni uspela.
     */
    private fun izmeri(p: String, url: String): DebridPravila.Meritev? {
        val zacetek = System.nanoTime()
        val pov = try { URL(url).openConnection() as HttpURLConnection } catch (_: Exception) { return null }
        return try {
            pov.connectTimeout = 4_000; pov.readTimeout = 3_000
            pov.setRequestProperty("Range", "bytes=0-${DebridPravila.MERITEV_BAJTOV - 1}")
            val koda = pov.responseCode
            if (koda !in 200..299) return null
            val odziv = System.nanoTime()
            val pingMs = (odziv - zacetek) / 1_000_000
            var bajtov = 0L
            val buf = ByteArray(64 * 1024)
            pov.inputStream.use { vhod ->
                while (bajtov < DebridPravila.MERITEV_BAJTOV && (System.nanoTime() - odziv) / 1_000_000 < DebridPravila.MERITEV_MS) {
                    val n = vhod.read(buf); if (n < 0) break
                    bajtov += n
                }
            }
            DebridPravila.Meritev(p, pingMs, bajtov, ((System.nanoTime() - odziv) / 1_000_000).coerceAtLeast(1))
        } catch (e: Exception) {
            Log.w(TAG, "meritev ${DebridPravila.ime(p)}: ${e.javaClass.simpleName}"); null
        } finally { pov.disconnect() }
    }

    /** Zadnja meritev storitve za okno »Debrid storitve« (null = je ni ali je starejsa od tedna). */
    fun zadnjaMeritev(c: Context, p: String): DebridPravila.Meritev? =
        DebridPravila.meritevIzZapisa(p, prefs(c).getString(KLJUC_MERITEV + p, null))
            ?.takeIf { DebridPravila.meritevVelja(it.second, System.currentTimeMillis()) }?.first

    private fun poskus(c: Context, p: String, magnet: String, indeks: Int, samoPredpomnjeno: Boolean, napredek: (Int) -> Unit): TorBox.Izid {
        val k = kljuc(c, p) ?: return TorBox.Izid(napaka = TorBoxPravila.NAPAKA_KLJUC, ponudnik = p)
        val iz = when (p) {
            DebridPravila.TORBOX -> TorBox.tok(c, magnet, indeks, napredek, samoPredpomnjeno)
            DebridPravila.REAL_DEBRID -> RealDebrid.tok(k, magnet, indeks, samoPredpomnjeno, napredek)
            else -> AllDebrid.tok(k, magnet, indeks, samoPredpomnjeno, napredek)
        }
        // Naloga 136: oznaka ⚡ za Real-Debrid in AllDebrid iz tega, kar je storitev pravkar odgovorila.
        try { zapomniIzid(c, p, magnet, iz) } catch (e: Exception) { Log.w(TAG, "zapomni: ${e.javaClass.simpleName}") }
        // Lastnik 25. 10. 2026: stanje kljuca sledi uporabi (401 -> »ne velja«, povezava -> velja).
        try { obIzidu(c, p, k, iz.ponudnik, iz.napaka, iz.url != null) } catch (e: Exception) { Log.w(TAG, "stanje kljuca: ${e.javaClass.simpleName}") }
        // Pregled Grok 41, Z8 (namizje S4): RD 9 - potrjen kljuc ostane, uporabnik izve, da racun tega ne omogoca (paket);
        // kljuc, ki ga storitev se ni potrdila, ne velja za to rabo.
        val izid = if (iz.napaka != DebridPravila.NAPAKA_DOVOLJENJE) iz
            else TorBox.Izid(iz.url, DebridPravila.napakaDovoljenja(stanjeKljuca(c, p)?.first), iz.opis, iz.cakajMs, iz.ponudnik)
        // Pregled A4: TorBox.tok storitve ne vpise - izid vedno nosi storitev, ki je odgovorila.
        return if (izid.ponudnik.isNotEmpty()) izid else TorBox.Izid(izid.url, izid.napaka, izid.opis, izid.cakajMs, p)
    }

    // ------------------------------------------------------------------ naloga 129: kljuci s telefona na TV (Link)

    /** Kljuci, ki jih je poslala druga naprava in cakajo na potrditev na tej (samo v pomnilniku, [DebridPravila.PREJETO_VELJA_MS]). */
    class Prejeti(val od: String, val kljuci: Map<String, String>, val prejetoMs: Long,
                  /** Naloga 141: indeksator Usenet (naslov, kljuc), ce ga je druga naprava poslala. */
                  val indeksator: Pair<String, String>? = null,
                  /** Webtor rezerva: pravi naslovi dodatkov s poverilnico (osebni zeton Webtorja ...) - kot kljuci. */
                  val dodatki: List<String> = emptyList())
    @Volatile private var cakajoci: Prejeti? = null
    /** Zaslon, ki zna vprasati za potrditev (GlasbaActivity), se prijavi; poklice se na glavni niti. */
    val poslusalciPrejetih = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /**
     * `debrid.keys` z druge naprave (ozji krog, sifrirano od naprave do naprave): kljuci se NE shranijo sami - na tej
     * napravi caka potrditev (zaslon Medijski center ali sporocilo, naj ga uporabnik odpre).
     */
    fun prejmiKljuce(c: Context, parametri: JSONObject, od: String): JSONObject {
        val k = parametri.optJSONObject("keys")
        val vnos = k?.keys()?.asSequence()?.associateWith { k.optString(it) }.orEmpty()
        val veljavni = DebridPravila.prejetiKljuci(vnos)
        val u = parametri.optJSONObject("usenet")
        val indeksator = UsenetPravila.prejetIndeksator(u?.optString("url"), u?.optString("key"))
        val d = parametri.optJSONArray("addons")
        val dodatki = SkrivnostiPravila.prejetiDodatki((0 until minOf(d?.length() ?: 0, 2 * SkrivnostiPravila.NAJVEC_PREJETIH)).map { d?.optString(it) })
        if (veljavni.isEmpty() && indeksator == null && dodatki.isEmpty()) return JSONObject().put("accepted", false).put("reason", "neveljavno")
        cakajoci = Prejeti(od, veljavni, System.currentTimeMillis(), indeksator, dodatki)
        Log.i(TAG, "prejeti kljuci: ${veljavni.keys.joinToString(",")}${if (indeksator != null) " + indeksator Usenet" else ""}" +
            "${if (dodatki.isNotEmpty()) " + dodatkov s poverilnico ${dodatki.size}" else ""} (cakajo na potrditev)")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (poslusalciPrejetih.isEmpty()) try {
                android.widget.Toast.makeText(c.applicationContext, c.getString(si.safeer.tv.R.string.os_debrid_prejeto_odpri), android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Throwable) { }
            for (p in poslusalciPrejetih) try { p() } catch (_: Throwable) { }
        }
        return JSONObject().put("accepted", true).put("pending", true)
    }

    /** Cakajoci prejeti kljuci (ce se veljajo), enkrat - [vzemiPrejete] jih odstrani. */
    fun vzemiPrejete(): Prejeti? {
        val p = cakajoci ?: return null
        cakajoci = null
        return p.takeIf { DebridPravila.prejetoVelja(it.prejetoMs, System.currentTimeMillis()) }
    }

    /** Webtor rezerva: v seznamu shranjenih [shraniPrejete] je dodatek s poverilnico »dodatek|<gostitelj>«. */
    const val PREDPONA_DODATKA = "dodatek|"

    /** Izid shranjevanja prejetih (pregled Grok 33, F9): storitve in »usenet« po izidu; dodatki so med shranjenimi. */
    class IzidPrejetih(val shranjeni: List<String>, val zavrnjeni: List<String>, val nepreverjeni: List<String>)

    /** Potrjeni prejeti kljuci: shranijo se kot vpisani tu (delovna nit). */
    fun shraniPrejete(c: Context, p: Prejeti): IzidPrejetih {
        // Lastnik 25. 10. 2026: prejeti kljuc se pred shranjevanjem preveri pri storitvi ([sprejmiKljuc]) - delovna nit.
        val izidi = LinkedHashMap<String, DebridPravila.Preverba>()
        for ((ponudnik, k) in p.kljuci) izidi[ponudnik] = sprejmiKljuc(c, ponudnik, k)
        p.indeksator?.let { (n, k) -> izidi[UsenetPravila.VRSTA] = Usenet.sprejmi(c, n, k) }
        // Webtor rezerva: dodatki s poverilnico - sifrirano kot nadomestek ([MedijskiViri.dodajDodatek]).
        val dodatki = p.dodatki.mapNotNull { n -> MedijskiViri.dodajDodatek(c, MedijskiViri.STREMIO, n, "")?.let { PREDPONA_DODATKA + Zasebno.gostitelj(n) } }
        fun z(v: DebridPravila.Prejem) = izidi.filterValues { DebridPravila.prejem(it) == v }.keys.toList()
        return IzidPrejetih(z(DebridPravila.Prejem.SHRANJEN) + dodatki, z(DebridPravila.Prejem.ZAVRNJEN), z(DebridPravila.Prejem.NI_PREVERJEN))
    }

    /**
     * Poslje vse vpisane kljuce napravi [n] - samo po sifrirani poti od naprave do naprave ([DebridPravila.smePoslatiKljuc]).
     * [nato] dobi na glavni niti: "ok" (caka na potrditev tam), "stara_dodatki" (kljuci da, dodatkov s poverilnico starejsa
     * razlicica ne zna), "ni_krog" (ni v ozjem krogu), "ni_zascite", "ni_dovoljeno",
     * "stara", "ni_povezave", "napaka".
     */
    fun posljiKljuce(c: Context, n: LinkOdjemalec.Naprava, nato: (String) -> Unit) {
        val link = LinkUpravitelj.pridobi(c.applicationContext)
        val glavna = android.os.Handler(android.os.Looper.getMainLooper())
        // Pregled A2: kljuci in indeksator gredo samo napravi v ozjem krogu TE naprave (kot [deliZapomnjen]) - preverjeno
        // tu, na posiljatelju; naprava zunaj kroga (npr. druzinski telefon) jih ne dobi, tudi ce ima zmoznost »remote«.
        if (link.jeTaNaprava(n) || !OzjiKrog.vKrogu(c, n.id)) {
            Log.i(TAG, "kljuci niso poslani: naprava ni v ozjem krogu")
            glavna.post { nato("ni_krog") }; return
        }
        if (!DebridPravila.smePoslatiKljuc(link.odjemalec.znaZascito(n.id), link.povezan)) {
            glavna.post { nato(if (!link.povezan) "ni_povezave" else "ni_zascite") }; return
        }
        val k = JSONObject()
        // Varovalo racunov K1: kljuc, ki ne velja ali je racun zaklenjen, ne gre na drugo napravo (tam bi ga preverila znova).
        for (p in DebridPravila.VSI) if (uporaben(c, p)) kljuc(c, p)?.let { k.put(p, it) }
        val parametri = JSONObject().put("keys", k)
        // Naloga 141: tudi indeksator Usenet (isto sifrirano pot; tam se shrani sele po potrditvi).
        Usenet.indeksator(c)?.let { parametri.put("usenet", JSONObject().put("url", it.osnova).put("key", it.kljuc)) }
        // Webtor rezerva: dodatki s poverilnico (osebni zeton Webtorja ...) gredo samo tu - ozji krog, sifrirano, potrditev.
        val dodatki = MedijskiViri.skrivniDodatki(c)
        if (dodatki.isNotEmpty()) parametri.put("addons", JSONArray(dodatki.take(SkrivnostiPravila.NAJVEC_PREJETIH)))
        // Pregled N6: starejsa razlicica polje »addons« prezre in vseeno odgovori »sprejeto« - povemo, da dodatkov ni dobila.
        val staraZaDodatke = dodatki.isNotEmpty() && UskladitevKljucevPravila.ZMOZNOST !in n.zmoznosti
        link.ukaz(n.id, DebridPravila.DEJANJE_KLJUCI, parametri, 8_000, LinkOdjemalec.Odgovor { izid, napaka ->
            val koda = when {
                izid?.optBoolean("ok") == true && izid.optJSONObject("data")?.optBoolean("accepted") == true ->
                    if (staraZaDodatke) "stara_dodatki" else "ok"
                izid?.optString("code") == si.safeer.tv.link.Daljinec.KODA_NI_DOVOLJENO -> "ni_dovoljeno"
                izid?.optString("code") == "neznano_dejanje" -> "stara"
                napaka == "ni_povezave" || napaka == "potek" -> "ni_povezave"
                else -> "napaka"
            }
            glavna.post { nato(koda) }
        })
    }

    // ------------------------------------------------------------------ skupni HTTP

    /**
     * Pregled Grok 2: preklicani poskus (nova izbira, »Preklici«, onStop - nit je prekinjena) ne sme vec nicesar ustvariti v
     * uporabnikovem racunu (addMagnet, selectFiles, magnet/upload, createtorrent): blokirajoc klic HTTP prekinitve ne vidi,
     * zato to preverimo pred vsakim takim korakom. Oznaka prekinitve ostane (klicatelj [tok] ne nadaljuje); kar je poskus ze
     * ustvaril, pospravi storitev sama (RealDebrid, AllDebrid).
     */
    fun preveriPreklic() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }

    /** Neodvisni pregled 4: kratki roki za pospravljanje (brisanje po neuspehu) - okno z napako ne caka 10 + 20 s. */
    const val POSPRAVI_POVEZAVA_MS = 3_000
    const val POSPRAVI_BRANJE_MS = 5_000

    /** [delo] (pospravljanje) gre ven tudi po preklicu: oznako prekinitve za cas dela odstranimo in jo nato vrnemo; napake pogoltne. */
    fun brezPrekinitve(delo: () -> Unit) {
        val prekinjen = Thread.interrupted()
        try { delo() } catch (_: Exception) { } finally { if (prekinjen) Thread.currentThread().interrupt() }
    }

    /**
     * Neodvisni pregled 5: torrenti (storitev:id), na katere uporabnik na tej napravi caka (»Pocakaj na prenos«) - poskus
     * »samo takoj« (tudi zapuscena nit preklicanega poskusa) jih ne izbrise. Samo v pomnilniku procesa, nekaj vnosov.
     */
    private val cakani = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun oznaciCakan(p: String, id: String) { if (cakani.size < 500) cakani += "$p:$id" }
    fun cakan(p: String, id: String): Boolean = "$p:$id" in cakani

    /** [datum] = glava `Date` odgovora (ura streznika, ms; 0 = je ni) - drugi pregled N3. */
    class Odgovor(val koda: Int, val telo: String, val datum: Long = 0L) {
        val json: JSONObject? get() = try { JSONObject(telo) } catch (_: Exception) { null }
        val seznam: JSONArray? get() = try { JSONArray(telo) } catch (_: Exception) { null }
        val uspeh: Boolean get() = koda in 200..299
    }

    /** Klic s kljucem v glavi (nikoli v naslovu); [obrazec] gre kot application/x-www-form-urlencoded. */
    fun klic(naslov: String, kljuc: String, metoda: String = "GET", obrazec: List<Pair<String, String>>? = null,
             povezavaMs: Int = 10_000, branjeMs: Int = 20_000): Odgovor {
        val p = URL(naslov).openConnection() as HttpURLConnection
        try {
            p.requestMethod = metoda
            p.connectTimeout = povezavaMs; p.readTimeout = branjeMs
            p.setRequestProperty("Accept", "application/json")
            p.setRequestProperty("Authorization", DebridPravila.glavaAvtorizacije(kljuc))
            if (obrazec != null) {
                p.doOutput = true
                p.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val telo = obrazec.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
                p.outputStream.use { it.write(telo.toByteArray(Charsets.UTF_8)) }
            }
            val koda = p.responseCode
            // Pregled Grok 8: odgovor najvec DebridPravila.NAJVEC_ODGOVORA bajtov (prej readBytes() brez meje) - vecji je napaka.
            if (p.contentLengthLong > DebridPravila.NAJVEC_ODGOVORA) throw java.io.IOException("odgovor prevelik")
            val telo = (if (koda in 200..299) p.inputStream else p.errorStream)?.use {
                (DebridPravila.beriOmejeno(it) ?: throw java.io.IOException("odgovor prevelik")).toString(Charsets.UTF_8)
            }.orEmpty()
            return Odgovor(koda, telo, p.date)
        } finally { p.disconnect() }
    }
}
