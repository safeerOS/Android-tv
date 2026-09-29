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
                .setMimeType(p.mime.ifBlank { mime(p.ime) })
                .setLanguage(p.jezik.ifBlank { null })
                .setLabel(listOf(imeJezika(ctx, p.jezik), p.oznaka).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { p.ime })
                .setSelectionFlags((if (privzet) C.SELECTION_FLAG_DEFAULT else 0) or (if ("forced" in p.oznaka) C.SELECTION_FLAG_FORCED else 0))
                .build()
        }
    }

    private fun jezikNaprave(ctx: Context): String = ctx.resources.configuration.locales[0].language

    private fun imeJezika(ctx: Context, koda: String): String =
        if (koda.isBlank()) "" else Locale(koda).getDisplayLanguage(ctx.resources.configuration.locales[0])
            .replaceFirstChar { it.titlecase(ctx.resources.configuration.locales[0]) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)

    /** Shranjena izbira (izklopljeno / zadnji izbrani jezik) za predvajalnik. Kliči ob ustvarjanju predvajalnika. */
    fun uveljavi(ctx: Context, p: Player) {
        val n = prefs(ctx)
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
        val trenutni = posnetki.indexOfFirst { (g, i) -> g.isTrackSelected(i) } + 1
        AlertDialog.Builder(a).setTitle(R.string.podnapisi_naslov)
            .setSingleChoiceItems(imena.toTypedArray(), trenutni) { d, k ->
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
                    bralnik.parse(podatki, 0, podatki.size, outputOptions, output)
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
            setBackgroundColor(0x00000000)
            setPadding(24, 6, 24, 6)
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
