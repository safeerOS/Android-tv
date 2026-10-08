package si.safeer.tv.os

private fun preveri(pogoj: Boolean, sporocilo: String) = check(pogoj) { sporocilo }

private const val OPIS = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major></specVersion>
<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>Zvocnik A</friendlyName><manufacturer>Izdelovalec</manufacturer><modelName>Zvocnik A</modelName>
<UDN>uuid:FFB8</UDN><serviceList>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/upnp/control/rendertransport1</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>upnp/control/rendercontrol1</controlURL><SCPDURL>/upnp/rendercontrolSCPD.xml</SCPDURL></service>
</serviceList></device></root>"""

fun main() {
    // SSDP: male crke glav, LOCATION samo na napravi, ki je odgovorila
    val odg = "HTTP/1.1 200 OK\r\nlocation: http://192.168.1.60:49152/description.xml\r\nst: x\r\n\r\n"
    preveri(DlnaPravila.lokacija(odg, "192.168.1.60") == "http://192.168.1.60:49152/description.xml", "lokacija")
    preveri(DlnaPravila.lokacija(odg, "192.168.1.50") == null, "tuj gostitelj")
    preveri(DlnaPravila.lokacija("HTTP/1.1 200 OK\r\nLOCATION: file:///etc/passwd\r\n", "x") == null, "shema")

    val z = DlnaPravila.razcleniOpis(OPIS.toByteArray(), "http://192.168.1.60:49152/description.xml")
    preveri(z != null, "opis")
    z!!
    preveri(z.ime == "Zvocnik A" && z.udn == "uuid:FFB8" && z.naslov == "192.168.1.60", "polja $z")
    preveri(z.avUrl == "http://192.168.1.60:49152/upnp/control/rendertransport1", "av ${z.avUrl}")
    preveri(z.rcUrl == "http://192.168.1.60:49152/upnp/control/rendercontrol1", "rc ${z.rcUrl}")
    preveri(z.rcOpis == "http://192.168.1.60:49152/upnp/rendercontrolSCPD.xml", "opis rc ${z.rcOpis}")
    // opis storitve na drugem gostitelju ne velja (glasnost ostane privzeta), zvocnik pa ostane uporaben
    val tujOpis = DlnaPravila.razcleniOpis(OPIS.replace("/upnp/rendercontrolSCPD.xml", "http://evil.example/s.xml").toByteArray(), "http://192.168.1.60:49152/description.xml")
    preveri(tujOpis != null && tujOpis.rcOpis == "", "tuj opis storitve")
    // kontrolni naslov na drugem gostitelju zavrnemo
    val tuj = OPIS.replace("/upnp/control/rendertransport1", "http://evil.example/x")
    preveri(DlnaPravila.razcleniOpis(tuj.toByteArray(), "http://192.168.1.60:49152/description.xml") == null, "tuj av")
    // DTD / entitete zavrnemo
    val dtd = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x \"y\">]>" + OPIS.substringAfter("?>")
    preveri(DlnaPravila.razcleniOpis(dtd.toByteArray(), "http://192.168.1.60:49152/d.xml") == null, "dtd")

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
    // Strezniku zvocnika (pregled 8. 10. 2026): nezadovoljiv obseg -> 416 (ne cela datoteka); samo zvocnik sme brati;
    // nasih ponudnikov vsebine (fileprovider) ne ponujamo.
    preveri(DlnaPravila.nezadovoljivObseg("bytes=200-", 100) && DlnaPravila.nezadovoljivObseg("bytes=-0", 100), "416")
    preveri(!DlnaPravila.nezadovoljivObseg("bytes=10-19", 100) && !DlnaPravila.nezadovoljivObseg(null, 100), "ne 416")
    preveri(!DlnaPravila.nezadovoljivObseg("bytes=0-", -1) && !DlnaPravila.nezadovoljivObseg("items=0-1", 100), "neznana velikost ali oblika")
    preveri(DlnaPravila.istiNaslov("192.168.1.50", "192.168.1.50") && DlnaPravila.istiNaslov("::ffff:192.168.1.50", "192.168.1.50"), "isti")
    preveri(!DlnaPravila.istiNaslov("192.168.1.51", "192.168.1.50") && !DlnaPravila.istiNaslov(null, "192.168.1.50") &&
        !DlnaPravila.istiNaslov("192.168.1.50", ""), "drug")
    preveri(DlnaPravila.lastenPonudnik("si.safeer.os.fileprovider", "si.safeer.os") && DlnaPravila.lastenPonudnik("si.safeer.os", "si.safeer.os"), "lasten")
    preveri(!DlnaPravila.lastenPonudnik("media", "si.safeer.os") && !DlnaPravila.lastenPonudnik(null, "si.safeer.os") &&
        !DlnaPravila.lastenPonudnik("si.safeer.os2.x", "si.safeer.os"), "tuj")

    // Cas UPnP (H:MM:SS, tudi z delci sekunde) <-> milisekunde; »NOT_IMPLEMENTED« in prazno = neznano
    preveri(DlnaPravila.casVMs("0:01:23") == 83_000L && DlnaPravila.casVMs("00:01:23.500") == 83_500L, "cas v ms")
    preveri(DlnaPravila.casVMs("1:00:00") == 3_600_000L && DlnaPravila.casVMs("NOT_IMPLEMENTED") == -1L && DlnaPravila.casVMs("") == -1L, "cas neznan")
    preveri(DlnaPravila.casVMs("0:99:00") == -1L && DlnaPravila.casVMs("-0:00:05") == -1L, "cas neveljaven")
    preveri(DlnaPravila.casZaPreskok(83_400L) == "0:01:23" && DlnaPravila.casZaPreskok(3_661_000L) == "1:01:01", "cas za preskok")
    preveri(DlnaPravila.casZaPreskok(-5L) == "0:00:00", "cas za preskok ni negativen")

    // Vec vrednosti iz enega odgovora (GetPositionInfo): en klic zvocniku namesto treh
    val polozaj = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetPositionInfoResponse xmlns:u="x"><Track>1</Track>""" +
        """<TrackDuration>0:03:20</TrackDuration><TrackURI>http://primer.example/a.mp3?x=1&amp;y=2</TrackURI><RelTime>0:01:05</RelTime></u:GetPositionInfoResponse></s:Body></s:Envelope>"""
    val v = DlnaPravila.vrednosti(polozaj.toByteArray(), listOf("RelTime", "TrackDuration", "TrackURI", "Manjka"))
    preveri(v["RelTime"] == "0:01:05" && v["TrackDuration"] == "0:03:20" && v["TrackURI"] == "http://primer.example/a.mp3?x=1&y=2" && v["Manjka"] == "", "vrednosti $v")
    val napakaVec = try { DlnaPravila.vrednosti(napaka.toByteArray(), listOf("RelTime")); "" } catch (e: IllegalStateException) { e.message ?: "" }
    preveri(napakaVec.contains("701"), "fault pri vec vrednostih")

    // Najvecja glasnost iz opisa storitve RenderingControl (zvocniki imajo razlicne lestvice); brez podatka 100
    val scpd = """<scpd xmlns="urn:schemas-upnp-org:service-1-0"><serviceStateTable><stateVariable sendEvents="no"><name>Mute</name></stateVariable>""" +
        """<stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>40</maximum><step>1</step></allowedValueRange></stateVariable></serviceStateTable></scpd>"""
    preveri(DlnaPravila.najGlasnost(scpd.toByteArray()) == 40, "najvecja glasnost")
    preveri(DlnaPravila.najGlasnost(scpd.replace("<maximum>40</maximum>", "").toByteArray()) == 100, "brez obsega")
    preveri(DlnaPravila.najGlasnost(scpd.replace("<maximum>40</maximum>", "<maximum>0</maximum>").toByteArray()) == 100, "nesmiseln obseg")
    preveri(DlnaPravila.najGlasnost("ni xml".toByteArray()) == 100, "neveljaven opis")
    // lestvica, ki je zvocnik ne pove, ni »100«: klicatelj mora vedeti, da je neznana (najmanjsi koraki, branje znova)
    preveri(DlnaPravila.najGlasnostAliNic(scpd.toByteArray()) == 40, "znana lestvica")
    preveri(DlnaPravila.najGlasnostAliNic(scpd.replace("<maximum>40</maximum>", "").toByteArray()) == null &&
        DlnaPravila.najGlasnostAliNic("ni xml".toByteArray()) == null, "neznana lestvica je null")
    println("DlnaPravilaTest: OK")
}
