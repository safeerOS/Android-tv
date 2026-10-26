package si.safeer.tv.link

import si.safeer.tv.link.LinkDatotekePravila as P
import java.io.File

// Pregled 99, F1, V1, V2 (26. 10. 2026): kopija brez lokacije se res naredi, je brez XMP, vrste z lokacijo ne gredo surove.

private var napak = 0

private fun preveriEnako(ime: String, pricakovano: Any?, dejansko: Any?) {
    if (pricakovano == dejansko) println("OK     $ime")
    else { println("NAPAKA $ime: pricakovano <$pricakovano>, dobljeno <$dejansko>"); napak++ }
}

private const val XMP = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><rdf:Description " +
    "xmlns:exif=\"http://ns.adobe.com/exif/1.0/\" exif:GPSLatitude=\"46,3.0N\" exif:GPSLongitude=\"14,30.0E\"/></rdf:RDF></x:xmpmeta>"

private fun l(s: String) = s.toByteArray(Charsets.ISO_8859_1)
private fun seg(m: Int, telo: ByteArray) =
    byteArrayOf(0xFF.toByte(), m.toByte(), ((telo.size + 2) shr 8).toByte(), (telo.size + 2).toByte()) + telo
private fun be(n: Int) = java.nio.ByteBuffer.allocate(4).putInt(n).array()
private fun le(n: Int) = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(n).array()
private fun kos(t: String, d: ByteArray) = be(d.size) + l(t) + d + ByteArray(4)
private fun ima(b: ByteArray, s: String) = String(b, Charsets.ISO_8859_1).contains(s)
private fun zacasna(b: ByteArray) = File.createTempFile("link-test", ".bin").also { it.writeBytes(b); it.deleteOnExit() }
private fun glava(vararg s: Any): ByteArray {
    val o = java.io.ByteArrayOutputStream()
    for (x in s) when (x) { is Int -> o.write(x); is String -> o.write(l(x)) }
    return o.toByteArray().copyOf(16)
}

/** V2: vrsta z lokacijo pod laznim imenom (.bak) ne sme po poti NEPOSREDNO. */
private fun preizkusVrst() {
    val primeri = listOf(
        Triple("TIFF/DNG/NEF/ARW II*", glava(0x49, 0x49, 0x2A, 0x00), "image/tiff"),
        Triple("TIFF MM", glava(0x4D, 0x4D, 0x00, 0x2A), "image/tiff"),
        Triple("ORF IIRO", glava("IIRO", 8, 0, 0, 0), "image/tiff"),
        Triple("RW2 IIU", glava(0x49, 0x49, 0x55, 0x00), "image/tiff"),
        Triple("RAF", glava("FUJIFILMCCD-RAW "), "image/x-fuji-raf"),
        Triple("MKV/WebM", glava(0x1A, 0x45, 0xDF, 0xA3), "video/x-matroska"),
        Triple("AVI", glava("RIFF", 0, 0, 0, 0, "AVI "), "video/x-msvideo"),
        Triple("MOV brez ftyp", glava(0, 0, 0, 8, "wide"), "video/quicktime"),
        Triple("MOV moov", glava(0, 0, 0, 8, "moov"), "video/quicktime"))
    for ((ime, g, mime) in primeri) {
        preveriEnako("vrstaPoGlavi $ime", mime, P.vrstaPoGlavi(g))
        val m = P.vrstaPoGlavi(g) ?: "application/octet-stream"
        for (sdk in listOf(28, 34)) preveriEnako("$ime sdk $sdk ni NEPOSREDNO", true, P.potVsebineFs(m, sdk, false) != P.PotVsebine.NEPOSREDNO)
    }
    for (mime in listOf("image/x-canon-cr2", "image/x-nikon-nef", "image/x-sony-arw", "image/x-adobe-dng", "image/tiff")) {
        preveriEnako("nosiLokacijo $mime", true, P.nosiLokacijo(mime))
        for (sdk in listOf(28, 34)) preveriEnako("$mime sdk $sdk ni NEPOSREDNO", true, P.potVsebineFs(mime, sdk, false) != P.PotVsebine.NEPOSREDNO)
    }
    // brez spremembe za vrste brez lokacije in za ze znane vrste
    for (mime in listOf("image/gif", "image/bmp", "image/x-icon", "image/svg+xml", "application/pdf", "text/plain"))
        preveriEnako("nosiLokacijo $mime", false, P.nosiLokacijo(mime))
    preveriEnako("GIF ostane NEPOSREDNO", P.PotVsebine.NEPOSREDNO, P.potVsebineFs("image/gif", 34, false))
    preveriEnako("PDF ostane NEPOSREDNO", P.PotVsebine.NEPOSREDNO, P.potVsebineFs("application/pdf", 34, false))
    preveriEnako("JPEG v MediaStore sdk 34", P.PotVsebine.MEDIASTORE, P.potVsebineFs("image/jpeg", 34, true))
    preveriEnako("vrstaPoGlavi JPEG", "image/jpeg", P.vrstaPoGlavi(glava(0xFF, 0xD8, 0xFF, 0xE0)))
    preveriEnako("vrstaPoGlavi WebP", "image/webp", P.vrstaPoGlavi(glava("RIFF", 0, 0, 0, 0, "WEBP")))
    preveriEnako("vrstaPoGlavi HEIC", "image/heic", P.vrstaPoGlavi(glava(0, 0, 0, 24, "ftypheic")))
    preveriEnako("vrstaPoGlavi MP4", "video/mp4", P.vrstaPoGlavi(glava(0, 0, 0, 24, "ftypisom")))
    preveriEnako("besedilo ni slika", null, P.vrstaPoGlavi(l("hello world 1234")))
}

/** V1: odstraniXmp odstrani XMP iz JPEG, PNG in WebP, ostalo ohrani; neznano ali pokvarjeno -> false. */
private fun preizkusXmp() {
    val exif = seg(0xE1, l("Exif\u0000\u0000MM\u0000*\u0000\u0000\u0000\u0008\u0000\u0000"))
    val xmpSeg = seg(0xE1, l("http://ns.adobe.com/xap/1.0/\u0000") + l(XMP))
    val razSeg = seg(0xE1, l("http://ns.adobe.com/xmp/extension/\u0000") + l("0123456789ABCDEF0123456789ABCDEF") + ByteArray(8) + l(XMP))
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + seg(0xE0, l("JFIF\u0000\u0001\u0001")) + exif + xmpSeg + razSeg +
        byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    val v = zacasna(jpeg)
    val c = File.createTempFile("link-cilj", ".bin").also { it.deleteOnExit() }
    preveriEnako("izvirni JPEG ima XMP", true, P.vsebujeXmp(v))
    preveriEnako("JPEG odstraniXmp", true, P.odstraniXmp(v, c, "image/jpeg"))
    val cj = c.readBytes()
    preveriEnako("JPEG kopija brez XMP", false, P.vsebujeXmp(c) || ima(cj, "GPSLatitude"))
    preveriEnako("JPEG ohrani EXIF", true, ima(cj, "Exif\u0000\u0000"))
    preveriEnako("JPEG dolzina = brez obeh XMP", jpeg.size - xmpSeg.size - razSeg.size, cj.size)

    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + kos("IHDR", ByteArray(13)) +
        kos("iTXt", l("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000") + l(XMP)) + kos("tEXt", l("Comment\u0000ok")) +
        kos("IDAT", ByteArray(5)) + kos("IEND", ByteArray(0))
    preveriEnako("PNG odstraniXmp", true, P.odstraniXmp(zacasna(png), c, "image/png"))
    val cp = c.readBytes()
    preveriEnako("PNG kopija brez XMP", false, P.vsebujeXmp(c) || ima(cp, "GPSLatitude"))
    preveriEnako("PNG ohrani ostalo", true, ima(cp, "Comment\u0000ok") && ima(cp, "IEND"))

    val vp8x = l("VP8X") + le(10) + byteArrayOf(0x0C, 0, 0, 0) + ByteArray(6)
    val vp8 = l("VP8 ") + le(3) + byteArrayOf(1, 2, 3, 0)
    val x = l(XMP)
    val xc = l("XMP ") + le(x.size) + x + (if (x.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
    val telo = l("WEBP") + vp8x + vp8 + xc
    preveriEnako("WebP odstraniXmp", true, P.odstraniXmp(zacasna(l("RIFF") + le(telo.size) + telo), c, "image/webp"))
    val cw = c.readBytes()
    preveriEnako("WebP kopija brez XMP", false, P.vsebujeXmp(c))
    preveriEnako("WebP velikost RIFF", cw.size - 8, java.nio.ByteBuffer.wrap(cw, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
    preveriEnako("WebP zastavica XMP izbrisana, ICC ostane", 0x08, cw[20].toInt() and 0x0C)

    preveriEnako("pokvarjen JPEG -> false", false, P.odstraniXmp(zacasna(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x12)), c, "image/jpeg"))
    preveriEnako("neznana vrsta -> false", false, P.odstraniXmp(v, c, "image/heic"))
}

/** F1 in V1: pripraviKopijo res naredi kopijo (prej predpona "k" -> izjema), brez XMP; prevelika ne; nic ne ostane. */
private fun preizkusKopije() {
    val mapa = java.nio.file.Files.createTempDirectory("link-brez-lokacije").toFile()
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + seg(0xE1, l("http://ns.adobe.com/xap/1.0/\u0000") + l(XMP)) +
        byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 9, 0xFF.toByte(), 0xD9.toByte())
    val k = P.pripraviKopijo(mapa, { jpeg.inputStream() }, "image/jpeg")
    preveriEnako("kopija narejena", true, k != null)
    preveriEnako("kopija brez XMP", false, k == null || P.vsebujeXmp(k))
    preveriEnako("v mapi samo kopija", 1, mapa.listFiles()?.size)
    k?.delete()
    val velika = object : java.io.InputStream() {
        var n = 0L
        override fun read(): Int = if (n++ < P.NAJVEC_KOPIJE + 10) 0 else -1
        override fun read(b: ByteArray, o: Int, d: Int): Int { if (n > P.NAJVEC_KOPIJE + 10) return -1; n += d; return d }
    }
    preveriEnako("prevelika -> null", null, P.pripraviKopijo(mapa, { velika }, "image/jpeg"))
    preveriEnako("vir null -> null", null, P.pripraviKopijo(mapa, { null }, "image/jpeg"))
    preveriEnako("brez XMP-podpore (HEIC) -> null", null, P.pripraviKopijo(mapa, { jpeg.inputStream() }, "image/heic"))
    preveriEnako("mapa prazna po neuspehih", 0, mapa.listFiles()?.size)
    mapa.delete()
}

fun main() {
    preizkusVrst()
    preizkusXmp()
    preizkusKopije()
    if (napak == 0) println("LinkDatotekeLokacijaTest: VSE OK")
    else { println("LinkDatotekeLokacijaTest: NAPAK $napak"); kotlin.system.exitProcess(1) }
}
