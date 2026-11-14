@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import org.json.JSONArray
import si.safeer.tv.R
import java.util.Locale

/**
 * Podnapisi v predvajalniku Safeer OS: datoteke ob videu (.srt, .vtt, .ass), ki jih pošlje naprava
 * ali so v istem torrentu, in podnapisi, vgrajeni v video (mkv, mp4). Uporabnik jih vklopi, izklopi
 * ali izbere jezik; izbira velja tudi za naslednje videe.
 *
 * Privzeto: podnapis, ki je edini ob videu, ali podnapis v jeziku naprave se prikaže sam - razen
 * če je uporabnik podnapise izklopil (to si zapomnimo, dokler jih spet ne vklopi).
 */
object Podnapisi {

    data class Podnapis(val url: String, val ime: String, val jezik: String = "", val oznaka: String = "", val mime: String = "")

    private const val NASTAVITVE = "safeer_podnapisi"
    private val PRIPONE = mapOf(
        "srt" to MimeTypes.APPLICATION_SUBRIP, "vtt" to MimeTypes.TEXT_VTT,
        "ass" to MimeTypes.TEXT_SSA, "ssa" to MimeTypes.TEXT_SSA,
    )
    private val MAPE = setOf("subs", "sub", "subtitles", "subtitle", "podnapisi")
    private val JEZIKI = mapOf(
        "sl" to "sl", "slv" to "sl", "slo" to "sl", "slovenian" to "sl", "slovene" to "sl", "slovensko" to "sl", "slovenscina" to "sl", "slovenščina" to "sl",
        "en" to "en", "eng" to "en", "english" to "en", "angleski" to "en",
        "de" to "de", "deu" to "de", "ger" to "de", "german" to "de", "deutsch" to "de",
        "hr" to "hr", "hrv" to "hr", "croatian" to "hr", "hrvatski" to "hr",
        "sr" to "sr", "srp" to "sr", "scc" to "sr", "serbian" to "sr", "srpski" to "sr",
        "bs" to "bs", "bos" to "bs", "bosnian" to "bs",
        "it" to "it", "ita" to "it", "italian" to "it", "italiano" to "it",
        "fr" to "fr", "fre" to "fr", "fra" to "fr", "french" to "fr",
        "es" to "es", "spa" to "es", "spanish" to "es", "espanol" to "es", "español" to "es",
        "pt" to "pt", "por" to "pt", "portuguese" to "pt",
        "hu" to "hu", "hun" to "hu", "hungarian" to "hu",
        "cs" to "cs", "cze" to "cs", "ces" to "cs", "czech" to "cs",
        "pl" to "pl", "pol" to "pl", "polish" to "pl",
        "ru" to "ru", "rus" to "ru", "russian" to "ru",
        "nl" to "nl", "dut" to "nl", "nld" to "nl", "dutch" to "nl",
        "mk" to "mk", "mkd" to "mk", "macedonian" to "mk",
    )
    private val ZASTAVICE = setOf("forced", "sdh", "cc", "hi", "default", "full")

    fun jePodnapis(ime: String): Boolean = ime.substringAfterLast('.', "").lowercase() in PRIPONE

    fun mime(ime: String): String = PRIPONE[ime.substringAfterLast('.', "").lowercase()] ?: MimeTypes.APPLICATION_SUBRIP

    /** (ISO koda ali "", dodatna oznaka) iz imena, npr. `Film.en.forced.srt` -> ("en", "forced"). */
    fun jezik(imeVidea: String, imePodnapisa: String): Pair<String, String> {
        val osnova = imeVidea.substringAfterLast('/').substringBeforeLast('.').lowercase()
        val ime = imePodnapisa.substringAfterLast('/').substringBeforeLast('.')
        val ostanek = if (ime.lowercase().startsWith(osnova)) ime.substring(osnova.length) else ime
        var koda = ""
        val oznake = mutableListOf<String>()
        for (del in ostanek.split(Regex("[\\s._\\-\\[\\]()]+"))) {
            val d = del.lowercase()
            if (d.isEmpty()) continue
            if (d in ZASTAVICE) oznake += d else if (koda.isEmpty() && d in JEZIKI) koda = JEZIKI.getValue(d)
        }
        return koda to oznake.joinToString(" ")
    }

    /** Relativne poti podnapisov iz [kandidati], ki sodijo k videu (enaka pravila kot core/podnapisi.py). */
    fun ujemajoci(imeVidea: String, kandidati: List<String>, samoEnVideo: Boolean): List<String> {
        val osnova = imeVidea.substringAfterLast('/').substringBeforeLast('.').lowercase()
        return kandidati.filter { rel ->
            if (!jePodnapis(rel)) return@filter false
            val deli = rel.replace('\\', '/').split('/')
            val ime = deli.last().lowercase()
            if (deli.size > 1 && deli[0].lowercase() !in MAPE) return@filter false
            ime.startsWith(osnova) || (deli.size > 2 && deli[1].lowercase() == osnova) || samoEnVideo
        }.sortedWith(compareBy({ it.count { c -> c == '/' } }, { it.lowercase() }))
    }

    /** Polje `subtitles` iz seznama datotek naprave (Safeer Control ga pošlje ob videih). */
    fun izSeznama(podatki: JSONArray?, s: DatotekeActivity.Streznik): List<Podnapis> {
        podatki ?: return emptyList()
        return (0 until podatki.length()).mapNotNull { i ->
            val o = podatki.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val ime = o.optString("name")
            Podnapis(s.url(id), ime, o.optString("lang"), o.optString("label"), mime(ime))
        }
    }

    /** Podnapisi za MediaItem. Edini podnapis ali tisti v jeziku naprave dobi oznako "privzeto". */
    fun konfiguracije(ctx: Context, seznam: List<Podnapis>): List<MediaItem.SubtitleConfiguration> {
        val mojJezik = jezikNaprave(ctx)
        return seznam.map { p ->
            val privzet = seznam.size == 1 || (p.jezik.isNotBlank() && p.jezik == mojJezik)
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(p.url))
                // Naslov kot id: razclenjevalnik ([Popravljalnik]) po njem najde zamik in uskladitev tega podnapisa.
                .setId(p.url)
                .setMimeType(p.mime.ifBlank { mime(p.ime) })
                .setLanguage(p.jezik.ifBlank { null })
                .setLabel(listOf(imeJezika(ctx, p.jezik), p.oznaka).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { p.ime })
                .setSelectionFlags((if (privzet) C.SELECTION_FLAG_DEFAULT else 0) or (if ("forced" in p.oznaka) C.SELECTION_FLAG_FORCED else 0))
                .build()
        }
    }

    private fun jezikNaprave(ctx: Context): String = ctx.resources.configuration.locales[0].language

    internal fun imeJezika(ctx: Context, koda: String): String =
        if (koda.isBlank()) "" else (@Suppress("DEPRECATION") Locale(koda)).getDisplayLanguage(ctx.resources.configuration.locales[0])
            .replaceFirstChar { it.titlecase(ctx.resources.configuration.locales[0]) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)

    /**
     * Rocni zamik po DATOTEKI podnapisov (14. 11. 2026: odmik je pri vsaki izdaji drug - ena zaostaja, druga prehiteva;
     * en zamik za vse filme bi pokvaril usklajene). Kljuc je odtis naslova podnapisa (Format.id iz
     * [konfiguracije]); vgrajeni podnapisi so del videa. Bere ga razclenjevalnik na niti nalaganja.
     */
    private val zamiki = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var zamikiNalozeni = false
    private const val PREDPONA_ZAMIKA = "zamik:"
    private const val NAJVEC_ZAMIKOV = 300

    /** Naslov podnapisa iz Format.id (Media3 v zdruzenem viru doda predpono »0:«); null = vgrajen podnapis. */
    internal fun naslovIzId(id: String?): String? {
        var t = id?.trim() ?: return null
        while (true) {
            if (t.startsWith("https://") || t.startsWith("http://")) return t
            val i = t.indexOf(':')
            if (i <= 0 || !t.substring(0, i).all { it.isDigit() }) return null
            t = t.substring(i + 1)
        }
    }

    private fun kljucZamika(naslov: String) = PREDPONA_ZAMIKA + SkrivnostiPravila.odtis(naslov).take(24)

    private fun naloziZamike(ctx: Context) {
        if (zamikiNalozeni) return
        for ((k, v) in prefs(ctx).all) if (k.startsWith(PREDPONA_ZAMIKA) && v is Long) zamiki[k] = ZamikPodnapisovPravila.omeji(v)
        zamikiNalozeni = true
    }

    /** Rocni zamik podnapisa z naslovom [naslov] v ms (0 = ni nastavljen ali vgrajen podnapis). */
    fun zamikMs(naslov: String?): Long = naslov?.let { zamiki[kljucZamika(it)] } ?: 0L

    fun nastaviZamikMs(ctx: Context, naslov: String, ms: Long) {
        naloziZamike(ctx)
        val v = ZamikPodnapisovPravila.omeji(ms)
        val k = kljucZamika(naslov)
        // Skupni zamik iz 13. 11. 2026 (»zamik_ms«, za vse filme) ne velja vec.
        val ured = prefs(ctx).edit().remove("zamik_ms")
        if (v == 0L) { zamiki.remove(k); ured.remove(k) } else {
            if (zamiki.size >= NAJVEC_ZAMIKOV && !zamiki.containsKey(k)) zamiki.keys.take(zamiki.size - NAJVEC_ZAMIKOV + 1).forEach { zamiki.remove(it); ured.remove(it) }
            zamiki[k] = v; ured.putLong(k, v)
        }
        ured.apply()
    }

    /** Naslov izbranega podnapisa, ce je iz datoteke ali dodatka; null = vgrajen ali izklopljen. */
    fun izbranNaslov(p: Player): String? =
        posnetki(p).firstOrNull { (g, i) -> g.isTrackSelected(i) }?.let { (g, i) -> naslovIzId(g.getTrackFormat(i).id) }

    /** Vrstica zamika v meniju podnapisov (prva vrstica, da je z daljincem takoj dosegljiva): samodejno ali rocni zamik. */
    fun vrsticaZamika(a: Activity, p: Player?): String {
        val naslov = p?.let { izbranNaslov(it) }
        val z = zamikMs(naslov)
        return if (naslov != null && z == 0L && usklajeni[naslov] != null) a.getString(R.string.podnapisi_zamik_samodejno)
        else a.getString(R.string.podnapisi_zamik, ZamikPodnapisovPravila.oblika(z))
    }

    /** Menu zamika izbranega podnapisa: +/- 0,1 / 0,5 / 2 s in ponastavitev; podnapisi se ponovno preberejo z novim zamikom. */
    fun pokaziZamik(a: Activity) {
        val naslov = GlasbaStoritev.predvajalnik?.let { izbranNaslov(it) }
        if (naslov == null) {
            android.widget.Toast.makeText(a, R.string.podnapisi_zamik_ne, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        naloziZamike(a)
        val koraki = listOf(100L, 500L, 2000L)
        val imena = mutableListOf<String>()
        val premiki = mutableListOf<Long?>()
        for (k in koraki) { imena += a.getString(R.string.podnapisi_kasneje, ZamikPodnapisovPravila.trajanje(k)); premiki += k }
        for (k in koraki) { imena += a.getString(R.string.podnapisi_prej, ZamikPodnapisovPravila.trajanje(k)); premiki += -k }
        imena += a.getString(R.string.podnapisi_zamik_ponastavi); premiki += null
        AlertDialog.Builder(a).setTitle(a.getString(R.string.podnapisi_zamik, ZamikPodnapisovPravila.oblika(zamikMs(naslov))))
            .setItems(imena.toTypedArray()) { d, k ->
                val korak = premiki[k]
                val nov = if (korak == null) 0L else ZamikPodnapisovPravila.spremeni(zamikMs(naslov), korak)
                nastaviZamikMs(a, naslov, nov)
                d.dismiss()
                if (GlasbaStoritev.ponovnoNaloziPodnapise()) {
                    android.widget.Toast.makeText(a, a.getString(R.string.podnapisi_zamik, ZamikPodnapisovPravila.oblika(nov)), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(a, R.string.podnapisi_zamik_ne, android.widget.Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    // ------------------------------------------------------------------ samodejna uskladitev (14. 11. 2026)

    /** Casi napisov reference po naslovu izbranega podnapisa (pripravljeno pred dodajanjem v tok); najvec 8. */
    private val reference = java.util.concurrent.ConcurrentHashMap<String, List<UskladitevPodnapisovPravila.Napis>>()
    /** Sprejeta poravnava po naslovu (ponovno branje istih podnapisov je ne racuna znova); zavrnjena se ne ponavlja. */
    private val usklajeni = java.util.concurrent.ConcurrentHashMap<String, UskladitevPodnapisovPravila.Izid>()
    private val zavrnjeni = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun kandidat(p: Stremio.PodnapisDodatka) = UskladitevPodnapisovPravila.Kandidat(p.url, p.jezik, p.izdaja, p.ujemanje)

    /** Podnapis v [jezik], katerega izdaja se z videom ujema najbolje (ob enakem ujemanju prvi po dodatku). */
    fun najboljsi(najdeni: List<Stremio.PodnapisDodatka>, jezik: String, imeDatoteke: String?, visinaVidea: Int): Stremio.PodnapisDodatka? {
        val k = UskladitevPodnapisovPravila.najboljsi(najdeni.map { kandidat(it) }, jezik, imeDatoteke, visinaVidea) ?: return null
        return najdeni.firstOrNull { it.url == k.url }
    }

    /**
     * Pred dodajanjem [izbran] v tok: ce je iz druge izdaje, kot jo ima video, prenese podnapis ujemajoce se izdaje (kateri
     * koli jezik) iz istega odgovora dodatkov in shrani njegove case - po njih [Popravljalnik] podnapis poravna. Klic iz niti.
     */
    fun pripraviUskladitev(izbran: Stremio.PodnapisDodatka, najdeni: List<Stremio.PodnapisDodatka>, imeDatoteke: String?, visinaVidea: Int) {
        if (reference.containsKey(izbran.url) || usklajeni.containsKey(izbran.url)) return
        val ref = UskladitevPodnapisovPravila.referenca(najdeni.map { kandidat(it) }, kandidat(izbran), imeDatoteke, visinaVidea) ?: return
        val bajti = Stremio.prenesiPodnapis(ref.url) ?: return
        val casi = try { casiNapisov(bajti, if (ref.url.substringBefore('?').lowercase().endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP) }
            catch (_: Exception) { emptyList() }
        if (casi.size < UskladitevPodnapisovPravila.NAJMANJ_NAPISOV) return
        if (reference.size >= 8) reference.clear()
        zavrnjeni.remove(izbran.url)
        reference[izbran.url] = casi
        android.util.Log.i("SafeerPodnapisi", "uskladitev: referenca ${ref.jezik} »${ref.izdaja.take(80)}« (${casi.size} napisov) za »${izbran.izdaja.take(80)}«")
    }

    /** Casi napisov iz datoteke (isti bralniki kot predvajalnik, SRT najprej pociscen). */
    private fun casiNapisov(podatki: ByteArray, mime: String): List<UskladitevPodnapisovPravila.Napis> {
        val format = androidx.media3.common.Format.Builder().setSampleMimeType(mime).build()
        val bralnik = androidx.media3.extractor.text.DefaultSubtitleParserFactory().create(format)
        val vhod = (if (mime == MimeTypes.APPLICATION_SUBRIP) srtPocisti(podatki) else besedilo(podatki)).toByteArray(Charsets.UTF_8)
        val out = ArrayList<UskladitevPodnapisovPravila.Napis>()
        bralnik.parse(vhod, 0, vhod.size, androidx.media3.extractor.text.SubtitleParser.OutputOptions.allCues()) { c ->
            if (out.size <= UskladitevPodnapisovPravila.NAJVEC_NAPISOV) out.add(napis(c))
        }
        return out
    }

    private fun napis(c: androidx.media3.extractor.text.CuesWithTiming): UskladitevPodnapisovPravila.Napis {
        val s = c.startTimeUs / 1000
        val d = if (c.durationUs == C.TIME_UNSET || c.durationUs <= 0) UskladitevPodnapisovPravila.PRIVZETO_TRAJANJE_MS else c.durationUs / 1000
        return UskladitevPodnapisovPravila.Napis(s, s + d)
    }

    /** Poravnava napisov [zbrani] podnapisa [naslov] na referenco; null = ni reference ali ni sprejeta. */
    private fun uskladi(naslov: String, zbrani: List<androidx.media3.extractor.text.CuesWithTiming>): UskladitevPodnapisovPravila.Izid? {
        usklajeni[naslov]?.let { if (it.razmerje.size == zbrani.size) return it }
        if (naslov in zavrnjeni) return null
        val ref = reference[naslov] ?: return null
        val vir = zbrani.map { napis(it) }
        val t0 = android.os.SystemClock.elapsedRealtime()
        val iz = try { UskladitevPodnapisovPravila.poravnaj(vir, ref) } catch (_: OutOfMemoryError) { null } catch (_: RuntimeException) { null }
        val ok = UskladitevPodnapisovPravila.sprejmi(iz, vir.size)
        android.util.Log.i("SafeerPodnapisi", "uskladitev: napisov ${vir.size}, referenca ${ref.size}, pokritost " +
            "${iz?.let { "%.2f -> %.2f".format(Locale.ROOT, it.pokritostPrej, it.pokritostPo) } ?: "-"}, odsekov ${iz?.odsekov ?: 0}, " +
            "${android.os.SystemClock.elapsedRealtime() - t0} ms, ${if (ok) "uporabljena" else "zavrnjena"}")
        if (ok && iz != null) {
            if (usklajeni.size >= 8) usklajeni.clear()
            usklajeni[naslov] = iz
            return iz
        }
        zavrnjeni.add(naslov)
        return null
    }

    /** Shranjena izbira (izklopljeno / zadnji izbrani jezik) za predvajalnik. Kliči ob ustvarjanju predvajalnika. */
    fun uveljavi(ctx: Context, p: Player) {
        val n = prefs(ctx)
        naloziZamike(ctx)
        val jeziki = listOfNotNull(n.getString("jezik", null)?.ifBlank { null }, jezikNaprave(ctx)).distinct()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, n.getBoolean("izklop", false))
            .setPreferredTextLanguages(*jeziki.toTypedArray())
            .build()
    }

    /** Podnapisni posnetki, ki jih predvajalnik zna prikazati (datoteke ob videu in vgrajeni). */
    fun posnetki(p: Player): List<Pair<Tracks.Group, Int>> =
        try {
            p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        } catch (_: Throwable) { emptyList() }

    fun imaPodnapise(p: Player?): Boolean = p != null && posnetki(p).isNotEmpty()

    fun vklopljeni(p: Player): Boolean = posnetki(p).any { (g, i) -> g.isTrackSelected(i) }

    /** Izbira: Izklopljeno ali eden od podnapisov. Izbira velja tudi za naslednje videe. */
    fun izberi(a: Activity, p: Player, poIzbiri: () -> Unit = {}) {
        val posnetki = posnetki(p)
        if (posnetki.isEmpty()) return
        val imena = listOf(a.getString(R.string.podnapisi_izklop)) + posnetki.mapIndexed { k, (g, i) ->
            val f = g.getTrackFormat(i)
            listOfNotNull(f.label?.ifBlank { null } ?: imeJezika(a, f.language.orEmpty()).ifBlank { null },
                if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) a.getString(R.string.podnapisi_prisilni) else null)
                .joinToString(" · ").ifBlank { a.getString(R.string.podnapisi_st, k + 1) }
        }
        // Zamik je PRVA vrstica (lastnik 14. 11. 2026: gumba ni nasel - bil je zadnji, pod vsemi jeziki).
        val trenutni = posnetki.indexOfFirst { (g, i) -> g.isTrackSelected(i) } + 2
        AlertDialog.Builder(a).setTitle(R.string.podnapisi_naslov)
            .setSingleChoiceItems((listOf(vrsticaZamika(a, p)) + imena).toTypedArray(), trenutni) { d, izbira ->
                if (izbira == 0) { d.dismiss(); pokaziZamik(a); return@setSingleChoiceItems }
                val k = izbira - 1
                val gradnik = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                if (k == 0) {
                    gradnik.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    prefs(a).edit().putBoolean("izklop", true).apply()
                } else {
                    val (g, i) = posnetki[k - 1]
                    val jezik = g.getTrackFormat(i).language.orEmpty().substringBefore('-')
                    gradnik.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    if (jezik.isNotBlank() && jezik != "und") gradnik.setPreferredTextLanguages(jezik, jezikNaprave(a))
                    prefs(a).edit().putBoolean("izklop", false).putString("jezik", if (jezik == "und") "" else jezik).apply()
                }
                p.trackSelectionParameters = gradnik.build()
                d.dismiss()
                poIzbiri()
            }.show()
    }

    // ------------------------------------------------------------------ podnapisi iz dodatkov (naloga 72)

    /** Zadnji jezik podnapisov iz dodatka (ISO 639-1); "" = ni se bil izbran. */
    fun jezikDodatka(ctx: Context): String = prefs(ctx).getString("dodatek_jezik", "") ?: ""

    /** Jezik za samodejno iskanje (dodatek_jezik, ali jezik naprave, ce je prazno). Vrne null, ce so podnapisi izklopljeni. */
    fun jezikZaSamodejno(ctx: Context): String? {
        val n = prefs(ctx)
        if (n.getBoolean("izklop", false)) return null
        val d = n.getString("dodatek_jezik", "") ?: ""
        return d.ifEmpty { jezikNaprave(ctx) }
    }

    /** Podnapis iz dodatka za MediaItem: OpenSubtitles vraca SRT (naslov brez koncnice), .vtt je WebVTT. */
    fun izDodatka(ctx: Context, p: Stremio.PodnapisDodatka): Podnapis =
        Podnapis(p.url, listOf(imeJezika(ctx, p.jezik), p.dodatek).filter { it.isNotBlank() }.joinToString(" · "), p.jezik, "",
            if (p.url.substringBefore('?').lowercase().endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)

    /** Podnapisi v jeziku [jezik] so dodani: vklopimo jih in jezik si zapomnimo (tudi za naslednje filme). */
    fun vklopiJezik(ctx: Context, p: Player, jezik: String) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguages(jezik, jezikNaprave(ctx)).build()
        prefs(ctx).edit().putBoolean("izklop", false).putString("jezik", jezik).putString("dodatek_jezik", jezik).apply()
    }

    /** Izklop iz izbire podnapisov dodatka: izklopljeno ostane tudi za naslednje filme (dodatka ne sprasujemo sami). */
    fun izklopiDodatek(ctx: Context, p: Player) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        prefs(ctx).edit().putBoolean("izklop", true).putString("dodatek_jezik", "").apply()
    }

    /** Hitri vklop/izklop (tipka CC na daljincu): izklopi, ali vklopi zadnje oziroma prve. */
    fun preklopi(a: Activity, p: Player) {
        val posnetki = posnetki(p)
        if (posnetki.isEmpty()) return
        val gradnik = p.trackSelectionParameters.buildUpon()
        if (vklopljeni(p)) {
            gradnik.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            prefs(a).edit().putBoolean("izklop", true).apply()
        } else {
            gradnik.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            // Brez ujemanja jezika izbirnik ne bi izbral nič: izberemo prvega.
            val jezik = prefs(a).getString("jezik", null)
            val (g, i) = posnetki.firstOrNull { (g, i) -> jezik != null && g.getTrackFormat(i).language?.startsWith(jezik) == true } ?: posnetki.first()
            gradnik.clearOverridesOfType(C.TRACK_TYPE_TEXT).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            prefs(a).edit().putBoolean("izklop", false).apply()
        }
        p.trackSelectionParameters = gradnik.build()
    }

    // ------------------------------------------------------------------ popravilo (kot VLC)

    private val CAS_SRT = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})")

    /**
     * Odtis OpenSubtitles (moviehash): velikost + vsota 64-bitnih stevil (little-endian) prvih 64 KiB in zadnjih 64 KiB,
     * mod 2^64, 16 hex malih crk.
     */
    fun odtis(velikost: Long, zacetek: ByteArray, konec: ByteArray): String {
        var vsota: Long = velikost
        for (i in 0 until 65536 step 8) {
            if (i + 7 < zacetek.size) {
                var v = 0L
                for (j in 0..7) v = v or ((zacetek[i + j].toLong() and 0xFF) shl (j * 8))
                vsota += v
            }
            if (i + 7 < konec.size) {
                var v = 0L
                for (j in 0..7) v = v or ((konec[i + j].toLong() and 0xFF) shl (j * 8))
                vsota += v
            }
        }
        return String.format(Locale.ROOT, "%016x", vsota)
    }

    private val odtisi = java.util.concurrent.ConcurrentHashMap<String, Triple<String, Long, String?>>()

    /** Naloga PN-1: izracun odtisa iz toka HTTP/Usenet (64 KB na zacetku in na koncu). Rok 5 s, predpomnjeno v pomnilniku. */
    fun odtisToka(url: String, glave: Map<String, String>): Triple<String, Long, String?>? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val u = url.trim()
        odtisi[u]?.let { return it }

        try {
            val c1 = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c1.connectTimeout = 5000
            c1.connectTimeout = 5000; c1.readTimeout = 5000
            c1.instanceFollowRedirects = false // Ne sledimo preusmeritvam slepo, predvajalnik uporablja ze razresen URL
            glave.forEach { (k, v) -> c1.setRequestProperty(k, v) }
            c1.setRequestProperty("Range", "bytes=0-65535")
            
            val koda = c1.responseCode
            if (koda !in 200..299) {
                android.util.Log.d("SafeerPodnapisi", "zacetek: HTTP $koda")
                return null
            }
            
            var velikost = 0L
            val cr = c1.getHeaderField("Content-Range")
            if (cr != null) velikost = cr.substringAfterLast('/').toLongOrNull() ?: 0L
            if (velikost < 131072) {
                android.util.Log.d("SafeerPodnapisi", "tok je krajsi od 128 KB")
                return null
            }

            val zacetekBajti = ByteArray(65536)
            var prebrano = 0
            c1.inputStream.use { s ->
                while (prebrano < 65536) {
                    val n = s.read(zacetekBajti, prebrano, 65536 - prebrano)
                    if (n < 0) break
                    prebrano += n
                }
            }
            if (prebrano < 65536) {
                android.util.Log.d("SafeerPodnapisi", "zacetek: prebrano premalo bajtov ($prebrano)")
                return null
            }

            val c2 = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c2.connectTimeout = 5000
            c2.connectTimeout = 5000; c2.readTimeout = 5000
            c2.instanceFollowRedirects = false
            glave.forEach { (k, v) -> c2.setRequestProperty(k, v) }
            c2.setRequestProperty("Range", "bytes=${velikost - 65536}-${velikost - 1}")

            val koda2 = c2.responseCode
            if (koda2 !in 200..299) {
                android.util.Log.d("SafeerPodnapisi", "konec: HTTP $koda2")
                return null
            }

            val konecBajti = ByteArray(65536)
            prebrano = 0
            c2.inputStream.use { s ->
                while (prebrano < 65536) {
                    val n = s.read(konecBajti, prebrano, 65536 - prebrano)
                    if (n < 0) break
                    prebrano += n
                }
            }
            if (prebrano < 65536) {
                android.util.Log.d("SafeerPodnapisi", "konec: prebrano premalo bajtov ($prebrano)")
                return null
            }

            val hash = odtis(velikost, zacetekBajti, konecBajti)
            val r = Triple(hash, velikost, null as String?)
            odtisi[u] = r
            return r
        } catch (e: Exception) {
            android.util.Log.d("SafeerPodnapisi", "napaka branja toka: ${e.message}")
            return null
        }
    }

    /** Besedilo podnapisa: UTF-8 (z BOM ali brez), UTF-16 z BOM, sicer Windows-1250 (stari slovenski podnapisi). */
    fun besedilo(podatki: ByteArray): String {
        if (podatki.size >= 2 && podatki[0] == 0xFF.toByte() && podatki[1] == 0xFE.toByte()) return String(podatki, 2, podatki.size - 2, Charsets.UTF_16LE)
        if (podatki.size >= 2 && podatki[0] == 0xFE.toByte() && podatki[1] == 0xFF.toByte()) return String(podatki, 2, podatki.size - 2, Charsets.UTF_16BE)
        val zacetek = if (podatki.size >= 3 && podatki[0] == 0xEF.toByte() && podatki[1] == 0xBB.toByte() && podatki[2] == 0xBF.toByte()) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(podatki, zacetek, podatki.size - zacetek)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            String(podatki, java.nio.charset.Charset.forName("windows-1250"))
        }
    }

    /**
     * SRT, kot ga preberejo vsi predvajalniki: zaporedne številke znova (npr. "1 " s presledkom ExoPlayer
     * zavrne), časi z vejico, UTF-8. Če v besedilu ni nobenega veljavnega bloka, vrne prazen niz.
     */
    fun srtPocisti(podatki: ByteArray): String {
        val izid = StringBuilder()
        var n = 0
        for (blok in besedilo(podatki).replace("\r\n", "\n").replace('\r', '\n').trim().split(Regex("\n\\s*\n"))) {
            val deli = blok.split('\n')
            val i = deli.indexOfFirst { it.contains("-->") }
            if (i < 0) continue
            val casa = CAS_SRT.findAll(deli[i]).toList()
            if (casa.size < 2) continue
            val besedilo = deli.drop(i + 1).map { it.trimEnd() }.filter { it.isNotBlank() }
            if (besedilo.isEmpty()) continue
            fun cas(m: MatchResult) = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                m.groupValues[3].toInt(), m.groupValues[4].padEnd(3, '0').toInt())
            if (n > 0) izid.append('\n')
            n++
            izid.append(n).append('\n').append(cas(casa[0])).append(" --> ").append(cas(casa[1])).append('\n')
                .append(besedilo.joinToString("\n")).append('\n')
        }
        return izid.toString()
    }

    /** Ovoj ExoPlayerjevih bralnikov podnapisov: SRT popravimo, SSA/VTT prekodiramo v UTF-8, nato preberejo oni. */
    class Popravljalnik(private val osnova: androidx.media3.extractor.text.SubtitleParser.Factory =
                            androidx.media3.extractor.text.DefaultSubtitleParserFactory()) : androidx.media3.extractor.text.SubtitleParser.Factory {
        override fun supportsFormat(format: androidx.media3.common.Format) = osnova.supportsFormat(format)
        override fun getCueReplacementBehavior(format: androidx.media3.common.Format) = osnova.getCueReplacementBehavior(format)
        override fun create(format: androidx.media3.common.Format): androidx.media3.extractor.text.SubtitleParser {
            val bralnik = osnova.create(format)
            val mime = format.sampleMimeType
            if (mime != MimeTypes.APPLICATION_SUBRIP && mime != MimeTypes.TEXT_SSA && mime != MimeTypes.TEXT_VTT) return bralnik
            return object : androidx.media3.extractor.text.SubtitleParser {
                override fun parse(data: ByteArray, offset: Int, length: Int,
                                   outputOptions: androidx.media3.extractor.text.SubtitleParser.OutputOptions,
                                   output: androidx.media3.common.util.Consumer<androidx.media3.extractor.text.CuesWithTiming>) {
                    val izvirnik = data.copyOfRange(offset, offset + length)
                    val popravljeno = try {
                        (if (mime == MimeTypes.APPLICATION_SUBRIP) srtPocisti(izvirnik) else besedilo(izvirnik)).toByteArray(Charsets.UTF_8)
                    } catch (_: Throwable) { ByteArray(0) }
                    val podatki = if (popravljeno.isEmpty()) izvirnik else popravljeno
                    // Podnapis iz datoteke ali dodatka: samodejna uskladitev z ujemajoco izdajo, nato rocni zamik te datoteke.
                    val naslov = naslovIzId(format.id)
                    val z = zamikMs(naslov) * 1000L
                    if (naslov == null || (z == 0L && reference[naslov] == null && usklajeni[naslov] == null)) {
                        bralnik.parse(podatki, 0, podatki.size, outputOptions, output)
                        return
                    }
                    val zbrani = ArrayList<androidx.media3.extractor.text.CuesWithTiming>()
                    bralnik.parse(podatki, 0, podatki.size, outputOptions) { zbrani.add(it) }
                    val iz = uskladi(naslov, zbrani)
                    for ((i, c) in zbrani.withIndex()) {
                        val r = iz?.razmerje?.get(i) ?: 1.0
                        val s = if (r == 1.0) c.startTimeUs else Math.round(r * c.startTimeUs)
                        val d = if (r == 1.0 || c.durationUs == C.TIME_UNSET) c.durationUs else Math.round(r * c.durationUs)
                        val premik = (iz?.zamikMs?.get(i) ?: 0L) * 1000L + z
                        ZamikPodnapisovPravila.zamakni(s, d, premik)?.let { (s2, d2) ->
                            output.accept(androidx.media3.extractor.text.CuesWithTiming(c.cues, s2, d2))
                        }
                    }
                }
                override fun getCueReplacementBehavior(): Int = bralnik.cueReplacementBehavior
                override fun reset() = bralnik.reset()
            }
        }
    }

    /** Prikaz podnapisov nad sliko (besedilo z belimi črkami in obrobo; slike PGS izpustimo). */
    class Prikaz(ctx: Context) : Player.Listener {
        val pogled: TextView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (si.safeer.tv.ChromiumEngineView.naDotik(ctx)) 18f else 26f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            // Poltemno ozadje: berljivo tudi nad svetlo sliko in nad gumbi na telefonu.
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12f; setColor(0x99000000.toInt()) }
            setPadding(24, 6, 24, 8)
            visibility = View.GONE
        }

        override fun onCues(cueGroup: CueGroup) {
            val b = SpannableStringBuilder()
            for (c in cueGroup.cues) {
                val t = c.text ?: continue
                if (b.isNotEmpty()) b.append('\n')
                b.append(t)
            }
            pogled.text = b
            pogled.visibility = if (b.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}
