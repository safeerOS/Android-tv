package si.safeer.tv.os

import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Zvocniki v omrezju (UPnP/DLNA MediaRenderer, npr. zvocna letev, Kodi, pametni TV) - cisto pravila brez
 * Androida (preizkus: tests/DlnaPravilaTest.kt). Samo odprti standard UPnP AV, brez vmesnikov proizvajalcev.
 * Enaka pravila kot core/dlna_zvocniki.py v Safeer OS za Linux.
 */
object DlnaPravila {
    const val ST = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val NAJVEC_OPISA = 256 * 1024

    data class Zvocnik(
        val ime: String, val proizvajalec: String, val model: String, val udn: String,
        val naslov: String, val avUrl: String, val rcUrl: String,
        /** Naslov opisa storitve RenderingControl (SCPD) - iz njega preberemo lestvico glasnosti; "" = ni znan. */
        val rcOpis: String = "",
    )

    fun poizvedba(): ByteArray = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $ST\r\n\r\n").toByteArray(Charsets.US_ASCII)

    /** LOCATION iz odgovora SSDP - samo, ce kaze na napravo, ki je odgovorila (ne na tujega gostitelja). */
    fun lokacija(odgovor: String, izvorIp: String): String? {
        val glave = odgovor.replace("\r\n", "\n").split("\n").drop(1)
            .mapNotNull { v -> v.indexOf(':').takeIf { it > 0 }?.let { v.substring(0, it).trim().lowercase() to v.substring(it + 1).trim() } }
            .toMap()
        val loc = glave["location"] ?: return null
        return try {
            val u = URI(loc)
            if ((u.scheme == "http" || u.scheme == "https") && u.host.equals(izvorIp, ignoreCase = true)) loc else null
        } catch (_: Exception) { null }
    }

    private fun dokument(xml: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // brez zunanjih entitet in DTD (opis prihaja iz omrezja)
        try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml))

    private fun Element.besedilo(ime: String): String {
        val n = getElementsByTagNameNS("*", ime)
        return if (n.length > 0) (n.item(0).textContent ?: "").trim() else ""
    }

    /** Kontrolni naslov mora ostati na isti napravi (brez preusmeritev drugam). */
    fun razresi(osnova: String, pot: String): String? = try {
        val o = URI(osnova)
        val r = o.resolve(pot.trim())
        if (r.scheme == o.scheme && r.host.equals(o.host, true) && r.port == o.port) r.toString() else null
    } catch (_: Exception) { null }

    /** Iz description.xml naredi zvocnik (ali null, ce ni MediaRenderer z AVTransport in RenderingControl). */
    fun razcleniOpis(xml: ByteArray, opisUrl: String): Zvocnik? {
        if (xml.size > NAJVEC_OPISA) return null
        val doc = try { dokument(xml) } catch (_: Exception) { return null }
        val koren = doc.documentElement ?: return null
        val naprava = koren.getElementsByTagNameNS("*", "device").item(0) as? Element ?: return null
        val osnova = koren.besedilo("URLBase").ifBlank { opisUrl }
        if (razresi(opisUrl, osnova) == null && osnova != opisUrl) return null
        var av: String? = null; var rc: String? = null; var rcOpis = ""
        val storitve = naprava.getElementsByTagNameNS("*", "service")
        for (i in 0 until storitve.length) {
            val s = storitve.item(i) as? Element ?: continue
            val tip = s.besedilo("serviceType"); val kontrola = s.besedilo("controlURL")
            if (kontrola.isBlank()) continue
            if (tip.startsWith("urn:schemas-upnp-org:service:AVTransport:") && av == null) av = razresi(osnova, kontrola)
            if (tip.startsWith("urn:schemas-upnp-org:service:RenderingControl:") && rc == null) {
                rc = razresi(osnova, kontrola)
                // Opis storitve mora biti na isti napravi kot vse drugo; sicer ga ne beremo (glasnost ostane privzeta).
                rcOpis = s.besedilo("SCPDURL").takeIf { it.isNotBlank() }?.let { razresi(osnova, it) } ?: ""
            }
        }
        val udn = naprava.besedilo("UDN")
        if (av == null || rc == null || udn.isBlank()) return null
        return Zvocnik(naprava.besedilo("friendlyName").ifBlank { naprava.besedilo("modelName") },
            naprava.besedilo("manufacturer"), naprava.besedilo("modelName"), udn, URI(opisUrl).host ?: "", av, rc, rcOpis)
    }

    fun xml(t: String): String = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun soap(storitev: String, dejanje: String, argumenti: List<Pair<String, String>>): String {
        val arg = argumenti.joinToString("") { (k, v) -> "<$k>${xml(v)}</$k>" }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$dejanje xmlns:u=\"$storitev\">$arg</u:$dejanje></s:Body></s:Envelope>"
    }

    fun didl(url: String, naslov: String, izvajalec: String, mime: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${xml(naslov)}</dc:title><upnp:artist>${xml(izvajalec)}</upnp:artist>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:${xml(mime)}:*\">${xml(url)}</res></item></DIDL-Lite>"

    private fun preveriNapako(doc: org.w3c.dom.Document) {
        val napaka = doc.getElementsByTagNameNS("*", "Fault")
        if (napaka.length > 0) {
            val e = napaka.item(0) as Element
            throw IllegalStateException("UPnP ${e.besedilo("errorCode")}: ${e.besedilo("errorDescription")}".trim())
        }
    }

    /** Vrednost iz odgovora SOAP; napaka UPnP (Fault) -> IllegalStateException z opisom. */
    fun vrednost(odgovor: ByteArray, ime: String): String {
        val doc = dokument(odgovor)
        preveriNapako(doc)
        return if (ime.isEmpty()) "" else doc.documentElement.besedilo(ime)
    }

    /** Vec vrednosti iz enega odgovora (manjkajoca = ""): en klic zvocniku namesto vec. Napaka UPnP kot pri [vrednost]. */
    fun vrednosti(odgovor: ByteArray, imena: List<String>): Map<String, String> {
        val doc = dokument(odgovor)
        preveriNapako(doc)
        val koren = doc.documentElement
        return imena.associateWith { koren.besedilo(it) }
    }

    /** Cas UPnP (»H:MM:SS«, lahko z delci sekunde) v milisekundah; -1 = neznan (»NOT_IMPLEMENTED«, prazno, neveljavno). */
    fun casVMs(cas: String): Long {
        val m = Regex("(\\d{1,5}):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,6}))?").matchEntire(cas.trim()) ?: return -1L
        val (ure, minute, sekunde, delci) = m.destructured
        if (minute.toInt() > 59 || sekunde.toInt() > 59) return -1L
        val ms = if (delci.isEmpty()) 0L else delci.padEnd(3, '0').take(3).toLong()
        return ure.toLong() * 3_600_000L + minute.toLong() * 60_000L + sekunde.toLong() * 1_000L + ms
    }

    /** Milisekunde v cilj preskoka (Seek, REL_TIME): cele sekunde, »H:MM:SS«. */
    fun casZaPreskok(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000L
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600L, (s % 3600L) / 60L, s % 60L)
    }

    /** Najvecja glasnost iz opisa storitve RenderingControl (obseg spremenljivke Volume); brez podatka 100. */
    fun najGlasnost(scpd: ByteArray): Int = najGlasnostAliNic(scpd) ?: 100

    /** Kot [najGlasnost], a null, kadar zvocnik lestvice ne pove ali opisa ni mogoce razcleniti - »100« bi bil ugib. */
    fun najGlasnostAliNic(scpd: ByteArray): Int? {
        if (scpd.size > NAJVEC_OPISA) return null
        val doc = try { dokument(scpd) } catch (_: Exception) { return null }
        val spremenljivke = doc.getElementsByTagNameNS("*", "stateVariable")
        for (i in 0 until spremenljivke.length) {
            val e = spremenljivke.item(i) as? Element ?: continue
            if (e.besedilo("name") != "Volume") continue
            val obseg = e.getElementsByTagNameNS("*", "allowedValueRange").item(0) as? Element ?: return null
            return obseg.besedilo("maximum").toIntOrNull()?.takeIf { it in 1..1000 }
        }
        return null
    }

    /** Vrsta zvoka iz naslova (za protocolInfo); privzeto MP3, kar zna vsak zvocnik. */
    fun mime(url: String, znan: String = ""): String {
        if (znan.startsWith("audio/")) return znan
        val pot = url.substringBefore('?').lowercase()
        return when {
            pot.endsWith(".flac") -> "audio/flac"
            pot.endsWith(".ogg") || pot.endsWith(".oga") -> "audio/ogg"
            pot.endsWith(".m4a") || pot.endsWith(".aac") -> "audio/mp4"
            pot.endsWith(".wav") -> "audio/wav"
            else -> "audio/mpeg"
        }
    }

    /** Ali lahko zvocnik vir potegne sam: http(s) naslov. https na domacem naslovu (datoteke racunalnika
     *  prek Safeer Controla s pripetim potrdilom) zvocnik ne more preveriti - te ne. */
    fun primernVir(url: String): Boolean {
        val u = url.trim()
        if (u.startsWith("http://", true)) return true
        if (!u.startsWith("https://", true)) return false
        val gostitelj = try { URI(u).host.orEmpty() } catch (_: Exception) { return false }
        return !jeDomaciNaslov(gostitelj)
    }

    /** Datoteka na tej napravi (content://, file:// ali pot) - streze jo majhen streznik te naprave. */
    fun lokalniVir(url: String): Boolean =
        url.startsWith("content://", true) || url.startsWith("file://", true) || url.startsWith("/")

    fun zaZvocnik(url: String): Boolean = primernVir(url) || lokalniVir(url)

    fun jeDomaciNaslov(gostitelj: String): Boolean {
        val h = gostitelj.lowercase()
        if (h == "localhost" || h.endsWith(".local")) return true
        val d = h.split('.').mapNotNull { it.toIntOrNull() }
        if (d.size != 4) return false
        return d[0] == 10 || d[0] == 127 || (d[0] == 192 && d[1] == 168) || (d[0] == 172 && d[1] in 16..31) ||
            (d[0] == 169 && d[1] == 254)
    }

    /** Obseg iz glave Range (bytes=a-b, bytes=a-, bytes=-n); null = cela datoteka ali neveljavno. */
    fun obseg(glava: String?, velikost: Long): LongRange? {
        if (glava == null || velikost <= 0) return null
        val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire(glava.trim()) ?: return null
        val (a, b) = m.destructured
        if (a.isEmpty() && b.isEmpty()) return null
        val od: Long; val doo: Long
        if (a.isEmpty()) { od = (velikost - b.toLong()).coerceAtLeast(0); doo = velikost - 1 }
        else { od = a.toLong(); doo = if (b.isEmpty()) velikost - 1 else minOf(b.toLong(), velikost - 1) }
        return if (od > doo || od >= velikost) null else od..doo
    }
}
