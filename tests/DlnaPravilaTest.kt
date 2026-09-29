package si.safeer.tv.os

private fun preveri(pogoj: Boolean, sporocilo: String) = check(pogoj) { sporocilo }

private const val OPIS = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major></specVersion>
<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>JBL BAR 300</friendlyName><manufacturer>Harman</manufacturer><modelName>JBL BAR 300</modelName>
<UDN>uuid:FFB8</UDN><serviceList>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/rendertransport1</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>upnp/control/rendercontrol1</controlURL></service>
</serviceList></device></root>"""

fun main() {
    // SSDP: male crke glav, LOCATION samo na napravi, ki je odgovorila
    val odg = "HTTP/1.1 200 OK\r\nlocation: http://192.168.0.229:49152/description.xml\r\nst: x\r\n\r\n"
    preveri(DlnaPravila.lokacija(odg, "192.168.0.229") == "http://192.168.0.229:49152/description.xml", "lokacija")
    preveri(DlnaPravila.lokacija(odg, "192.168.0.50") == null, "tuj gostitelj")
    preveri(DlnaPravila.lokacija("HTTP/1.1 200 OK\r\nLOCATION: file:///etc/passwd\r\n", "x") == null, "shema")

    val z = DlnaPravila.razcleniOpis(OPIS.toByteArray(), "http://192.168.0.229:49152/description.xml")
    preveri(z != null, "opis")
    z!!
    preveri(z.ime == "JBL BAR 300" && z.udn == "uuid:FFB8" && z.naslov == "192.168.0.229", "polja $z")
    preveri(z.avUrl == "http://192.168.0.229:49152/upnp/control/rendertransport1", "av ${z.avUrl}")
    preveri(z.rcUrl == "http://192.168.0.229:49152/upnp/control/rendercontrol1", "rc ${z.rcUrl}")
    // kontrolni naslov na drugem gostitelju zavrnemo
    val tuj = OPIS.replace("/upnp/control/rendertransport1", "http://evil.example/x")
    preveri(DlnaPravila.razcleniOpis(tuj.toByteArray(), "http://192.168.0.229:49152/description.xml") == null, "tuj av")
    // DTD / entitete zavrnemo
    val dtd = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x \"y\">]>" + OPIS.substringAfter("?>")
    preveri(DlnaPravila.razcleniOpis(dtd.toByteArray(), "http://192.168.0.229:49152/d.xml") == null, "dtd")

    // SOAP in DIDL z ubezanimi znaki
    val didl = DlnaPravila.didl("http://h/a?b=1&c=2", "Čaj & <kava>", "\"Izvajalec\"", "audio/mpeg")
    preveri(didl.contains("<dc:title>Čaj &amp; &lt;kava&gt;</dc:title>"), "didl naslov")
    preveri(didl.contains("xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"") && didl.contains("<res protocolInfo"), "didl ns")
    val soap = DlnaPravila.soap(DlnaPravila.AV, "SetAVTransportURI", listOf("InstanceID" to "0", "CurrentURIMetaData" to didl))
    preveri(soap.contains("&lt;DIDL-Lite") && soap.contains("s:encodingStyle="), "soap")

    val ok = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetVolumeResponse xmlns:u="x"><CurrentVolume>30</CurrentVolume></u:GetVolumeResponse></s:Body></s:Envelope>"""
    preveri(DlnaPravila.vrednost(ok.toByteArray(), "CurrentVolume") == "30", "vrednost")
    val napaka = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode><errorDescription>Transition not available</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
    val sporocilo = try { DlnaPravila.vrednost(napaka.toByteArray(), ""); "" } catch (e: IllegalStateException) { e.message ?: "" }
    preveri(sporocilo.contains("701") && sporocilo.contains("Transition"), "fault $sporocilo")

    preveri(DlnaPravila.mime("https://x/p.flac?t=1") == "audio/flac" && DlnaPravila.mime("https://x/radio") == "audio/mpeg", "mime")
    preveri(DlnaPravila.primernVir("https://prenos.jamendo.com/x.mp3") && !DlnaPravila.primernVir("content://media/1"), "vir")
    preveri(!DlnaPravila.primernVir("https://192.168.0.20:8443/d/x.mp3") && DlnaPravila.primernVir("http://192.168.0.20:8000/x.mp3"), "https doma")
    preveri(DlnaPravila.lokalniVir("content://media/external/audio/1") && DlnaPravila.zaZvocnik("/sdcard/Music/a.mp3"), "lokalno")
    preveri(DlnaPravila.obseg("bytes=10-19", 100) == 10L..19L && DlnaPravila.obseg("bytes=-10", 100) == 90L..99L, "obseg")
    preveri(DlnaPravila.obseg("bytes=90-", 100) == 90L..99L && DlnaPravila.obseg("bytes=200-", 100) == null, "obseg2")
    println("DlnaPravilaTest: OK")
}
