package si.safeer.tv.os

import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Zvocniki v omrezju (UPnP/DLNA MediaRenderer, npr. JBL BAR 300, Kodi, pametni TV) - cisto pravila brez
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
        var av: String? = null; var rc: String? = null
        val storitve = naprava.getElementsByTagNameNS("*", "service")
        for (i in 0 until storitve.length) {
            val s = storitve.item(i) as? Element ?: continue
            val tip = s.besedilo("serviceType"); val kontrola = s.besedilo("controlURL")
            if (kontrola.isBlank()) continue
            if (tip.startsWith("urn:schemas-upnp-org:service:AVTransport:") && av == null) av = razresi(osnova, kontrola)
            if (tip.startsWith("urn:schemas-upnp-org:service:RenderingControl:") && rc == null) rc = razresi(osnova, kontrola)
        }
        val udn = naprava.besedilo("UDN")
        if (av == null || rc == null || udn.isBlank()) return null
        return Zvocnik(naprava.besedilo("friendlyName").ifBlank { naprava.besedilo("modelName") },
            naprava.besedilo("manufacturer"), naprava.besedilo("modelName"), udn, URI(opisUrl).host ?: "", av, rc)
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

    /** Vrednost iz odgovora SOAP; napaka UPnP (Fault) -> IllegalStateException z opisom. */
    fun vrednost(odgovor: ByteArray, ime: String): String {
        val doc = dokument(odgovor)
        val napaka = doc.getElementsByTagNameNS("*", "Fault")
        if (napaka.length > 0) {
            val e = napaka.item(0) as Element
            throw IllegalStateException("UPnP ${e.besedilo("errorCode")}: ${e.besedilo("errorDescription")}".trim())
        }
        return if (ime.isEmpty()) "" else doc.documentElement.besedilo(ime)
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

    /** Ali lahko zvocnik predvaja ta vir sam: samo javni http(s) naslovi (ne content://, ne pripeti TLS). */
    fun primernVir(url: String): Boolean = url.startsWith("http://", true) || url.startsWith("https://", true)
}
