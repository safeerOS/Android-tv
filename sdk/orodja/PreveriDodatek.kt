package si.safeer.tv.sdk

import si.safeer.tv.cast.JsonLahki
import si.safeer.tv.os.DodatkiProtokol
import si.safeer.tv.os.OsPravila
import java.net.HttpURLConnection
import java.net.URL

/**
 * Orodje za preverjanje dodatka (protokol Safeer v1): razvijalec pred objavo vidi, kako bo Safeer ravnal z njegovim
 * dodatkom. Uporablja ISTO kodo kot aplikacija (DodatkiProtokol, OsPravila.jePlacljivoObvestilo), zato orodje in naprava
 * ne moreta soditi razlicno.
 *
 *   sdk/preveri_dodatek.sh https://moj-dodatek.example/manifest.json [--vsebina movie:tt0111161] [--dejanja]
 *
 * Izid: vrstice OK / OPOZORILO / NAPAKA; izhod 1, ce je kaksna NAPAKA. Dejanja (ki lahko kaj spremenijo pri dodatku)
 * se poklicejo samo z --dejanja.
 */
class PreveriDodatek(private val prenos: (String) -> Odziv?) {
    class Odziv(val koda: Int, val telo: String, val ms: Long)

    val vrstice = ArrayList<String>()
    var napak = 0
        private set

    private fun ok(s: String) { vrstice += "OK         $s" }
    private fun opozorilo(s: String) { vrstice += "OPOZORILO  $s" }
    private fun napaka(s: String) { napak++; vrstice += "NAPAKA     $s" }

    /** Prenos z meritvijo: velikost in cas po mejah protokola (5). */
    private fun prenesi(url: String, kaj: String): String? {
        val o = prenos(url) ?: run { napaka("$kaj: dodatek ni odgovoril"); return null }
        if (o.koda !in 200..299) { napaka("$kaj: HTTP ${o.koda}"); return null }
        if (o.telo.length > DodatkiProtokol.NAJVEC_ODGOVOR_B) napaka("$kaj: odgovor vecji od 1 MiB - Safeer ga zavrze")
        if (o.ms > 20_000) napaka("$kaj: odgovor po ${o.ms} ms - Safeer caka najvec 20 s")
        else if (o.ms > 5_000) opozorilo("$kaj: pocasen odgovor (${o.ms} ms)")
        if (!JsonLahki.strogObjekt(o.telo)) { napaka("$kaj: odgovor ni veljaven JSON objekt"); return null }
        return o.telo
    }

    fun preveri(manifestUrl: String, vsebina: Pair<String, String>? = null, dejanja: Boolean = false): Int {
        val osnova = manifestUrl.trim().removeSuffix("/manifest.json").trimEnd('/')
        if (!manifestUrl.trim().startsWith("https://")) opozorilo("manifest ni na https - razsiritve delajo samo v razvojnem nacinu na krajevnem naslovu")
        val besedilo = prenesi("$osnova/manifest.json", "manifest") ?: return napak
        val m = JsonLahki.objekt(besedilo)!!
        for (polje in listOf("id", "name", "version")) if (m.niz(polje).isNullOrBlank()) napaka("manifest: manjka $polje")
        if (m.nizi("resources").isEmpty() && m.surovo("resources") == null) napaka("manifest: manjka resources")
        if (m.nizi("types").isEmpty()) opozorilo("manifest: types je prazen")
        ok("manifest: ${m.nizAli("name")} ${m.nizAli("version")}")

        val r = DodatkiProtokol.preberi(besedilo)
        if (r == null) ok("razsiritve safeer: jih ni (navaden dodatek Stremio - dela tudi v Safeerju)")
        else {
            when {
                r.novejsi -> opozorilo("safeer.protocol ${r.protokol} je novejsi od ${DodatkiProtokol.VERZIJA} - ta Safeer razsiritve prezre")
                r.protokol == 0 -> napaka("safeer.protocol manjka - razsiritve so prezrte")
                else -> ok("razsiritve safeer v${r.protokol}: dejanj ${r.dejanja.size}, map ${r.mape.size}, nastavitev ${r.nastavitve.size}, enrich ${r.dopolni.joinToString(",").ifBlank { "-" }}")
            }
            for (o in r.opozorila) opozorilo("safeer: $o")
            if (r.imaSkrivnosti && !manifestUrl.trim().startsWith("https://")) opozorilo("nastavitve s skrivnostjo: Safeer jih po http ne poslje")
            for (mapa in r.mape) {
                val t = prenesi(DodatkiProtokol.naslovMape(osnova, mapa.id), "mapa ${mapa.id}") ?: continue
                val v = DodatkiProtokol.preberiMapo(t)
                if (v == null) napaka("mapa ${mapa.id}: odgovor ni mapa") else ok("mapa ${mapa.id}: elementov ${v.elementi.size}" + if (v.naprej.isNotBlank()) ", ima naslednjo stran" else "")
            }
        }

        if (vsebina != null) {
            val (tip, id) = vsebina
            preveriTokove(osnova, tip, id)
            if (r != null && tip in r.dopolni) {
                val t = prenesi(DodatkiProtokol.naslovDopolni(osnova, tip, id), "enrich $tip/$id")
                if (t != null) { if (JsonLahki.objekt(t)?.objekt("meta") == null) napaka("enrich: manjka meta") else ok("enrich $tip/$id") }
            }
            if (r != null && dejanja) for (d in DodatkiProtokol.dejanjaZa(r, tip)) {
                val t = prenesi(DodatkiProtokol.naslovDejanja(osnova, d.id, tip, id), "dejanje ${d.id}") ?: continue
                val o = DodatkiProtokol.preberiDejanje(t)
                if (o == null) napaka("dejanje ${d.id}: neveljaven odgovor") else ok("dejanje ${d.id}: ok=${o.ok}, sporocilo=${o.sporocilo?.za("en").orEmpty().take(60)}")
            } else if (r != null && r.dejanja.isNotEmpty()) ok("dejanja: niso poklicana (dodaj --dejanja)")
        }
        return napak
    }

    /** Tokovi vsebine: predvajljivi, samo placilni zid (Safeer skrije - naloga 120), http namesto https. */
    private fun preveriTokove(osnova: String, tip: String, id: String) {
        val t = prenesi("$osnova/stream/${enc(tip)}/${enc(id)}.json", "stream $tip/$id") ?: return
        val o = JsonLahki.objekt(t) ?: return
        val tokovi = o.surovo("streams")?.let { JsonLahki.elementi(it) }.orEmpty().mapNotNull { JsonLahki.objekt(it) }
        var predvajljivih = 0
        var placilnih = 0
        for (s in tokovi) {
            val url = s.nizAli("url")
            if (url.isNotBlank() || s.nizAli("infoHash").isNotBlank()) {
                predvajljivih++
                if (url.startsWith("http://")) opozorilo("stream: tok po http (${s.nizAli("name").take(30)}) - na nekaterih napravah ga omrezje zavrne")
            } else if (OsPravila.jePlacljivoObvestilo(listOf(s.nizAli("name"), s.nizAli("title"), s.nizAli("description"), s.nizAli("externalUrl")).joinToString(" "))) placilnih++
        }
        when {
            predvajljivih > 0 -> ok("stream $tip/$id: predvajljivih $predvajljivih")
            placilnih > 0 -> opozorilo("stream $tip/$id: samo placilni zid - Safeer to vsebino uporabniku skrije (7 dni)")
            else -> opozorilo("stream $tip/$id: noben tok - Safeer vsebino oznaci, da ni na voljo")
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        /** Pravi prenos po omrezju (orodje v ukazni vrstici). */
        fun omrezje(url: String): Odziv? = try {
            val zac = System.currentTimeMillis()
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json")
            val koda = c.responseCode
            val telo = (if (koda in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            c.disconnect()
            Odziv(koda, telo, System.currentTimeMillis() - zac)
        } catch (_: Exception) { null }
    }
}

fun main(args: Array<String>) {
    val url = args.firstOrNull { !it.startsWith("--") }
    if (url == null) { println("Uporaba: preveri_dodatek.sh <naslov manifest.json> [--vsebina tip:id] [--dejanja]"); System.exit(2); return }
    val i = args.indexOf("--vsebina")
    val vsebina = args.getOrNull(i + 1)?.takeIf { i >= 0 && ':' in it }?.let { it.substringBefore(':') to it.substringAfter(':') }
    val p = PreveriDodatek { PreveriDodatek.omrezje(it) }
    val napak = p.preveri(url, vsebina, "--dejanja" in args)
    p.vrstice.forEach { println(it) }
    println(if (napak == 0) "Dodatek je pripravljen za Safeer." else "Napak: $napak")
    System.exit(if (napak == 0) 0 else 1)
}
