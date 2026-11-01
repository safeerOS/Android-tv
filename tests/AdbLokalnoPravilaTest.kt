package si.safeer.tv.os

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

private var napak = 0
private fun preveri(opis: String, pogoj: Boolean) { if (pogoj) println("  OK   $opis") else { napak++; println("  NAPAKA  $opis") } }

/** Lastnik 1. 11. 2026: zapiranje aplikacij prek lastnega adb - kaj sme ven in ali adbd razume nas kljuc. */
fun main() {
    val P = AdbLokalnoPravila
    println("== kaj smemo zapreti")
    preveri("tuja aplikacija", P.smemoZapreti("org.smarttube.stable", "si.safeer.os", emptySet(), null))
    preveri("nas TV Browser (lastnik: tudi lastne)", P.smemoZapreti("si.safeer.tv", "si.safeer.os", emptySet(), null))
    preveri("zaganjalnik nikoli", !P.smemoZapreti("si.safeer.os", "si.safeer.os", emptySet(), null))
    preveri("aplikacija v ospredju nikoli", !P.smemoZapreti("com.anthropic.claude", "si.safeer.os", setOf("com.anthropic.claude"), null))
    preveri("tipkovnica nikoli", !P.smemoZapreti("com.google.android.inputmethod.latin", "si.safeer.os", emptySet(), "com.google.android.inputmethod.latin"))
    preveri("sistem nikoli", !P.smemoZapreti("com.android.systemui", "si.safeer.os", emptySet(), null))
    preveri("android nikoli", !P.smemoZapreti("android", "si.safeer.os", emptySet(), null))
    preveri("Philips sistem nikoli", !P.smemoZapreti("org.droidtv.playtv", "si.safeer.os", emptySet(), null))
    preveri("Philips viri nikoli", !P.smemoZapreti("com.philips.sourceenabler", "si.safeer.os", emptySet(), null))
    preveri("Vewd (HbbTV) nikoli", !P.smemoZapreti("com.vewd.core.browserui", "si.safeer.os", emptySet(), null))
    preveri("com.android.* nikoli", !P.smemoZapreti("com.android.vending", "si.safeer.os", emptySet(), null))
    preveri("Play Store Safeer sme", P.smemoZapreti("si.safeer.trgovina", "si.safeer.os", emptySet(), null))

    println("== storitve (dostopnost, obvestila): teh paketov ne zapiramo")
    preveri("dva paketa iz niza", P.paketiStoritev("com.philips.sourceenabler/com.philips.sourceenabler.SourceService:si.safeer.os/si.safeer.tv.link.VnosStoritev") == setOf("com.philips.sourceenabler", "si.safeer.os"))
    preveri("kratek zapis razreda", P.paketiStoritev("a.b/.C") == setOf("a.b"))
    preveri("prazno in null", P.paketiStoritev(null).isEmpty() && P.paketiStoritev("").isEmpty())
    preveri("brez posevnice ni paket", P.paketiStoritev("nekaj").isEmpty())

    println("== ukaz: samo am force-stop z veljavnim imenom")
    preveri("veljaven ukaz", P.ukazZapri("org.smarttube.stable") == "shell:am force-stop org.smarttube.stable")
    preveri("vrivanje ukaza zavrnjeno", P.ukazZapri("a.b; reboot") == null)
    preveri("presledek zavrnjen", P.ukazZapri("a.b c") == null)
    preveri("&& zavrnjen", P.ukazZapri("a.b&&rm") == null)
    preveri("brez pike zavrnjen", P.ukazZapri("reboot") == null)
    preveri("nova vrstica zavrnjena", P.ukazZapri("a.b\nreboot") == null)
    preveri("vrivanje ni dovoljeno zapreti", !P.smemoZapreti("a.b;reboot", "si.safeer.os", emptySet(), null))

    println("== sporocilo ADB")
    val m = P.sporocilo(P.A_OPEN, 1, 0, "shell:x\u0000".toByteArray())
    val g = P.preberiGlavo(m.copyOfRange(0, 24))
    preveri("glava: ukaz, magija, dolzina", g.ukaz == P.A_OPEN && g.veljavna && g.dolzina == 8 && g.arg0 == 1)
    preveri("pokvarjena magija ni veljavna", !AdbLokalnoPravila.Glava(P.A_OKAY, 0, 0, 0, 0, 0).veljavna)
    preveri("prevelika dolzina ni veljavna", !AdbLokalnoPravila.Glava(P.A_WRTE, 1, 1, P.NAJVEC_SPREJETIH + 1, 0, P.A_WRTE xor -1).veljavna)
    preveri("podpis samo za 20-bajtni zeton", P.zaPodpis(ByteArray(19)) == null && P.zaPodpis(ByteArray(20))!!.size == 35)

    println("== javni kljuc v obliki adbd")
    val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val pub = kp.public as RSAPublicKey
    val zapis = P.javniKljucAdb(pub.modulus, pub.publicExponent.toInt(), "safeer@tv") { java.util.Base64.getEncoder().encodeToString(it) }
    val besedilo = String(zapis, Charsets.US_ASCII)
    preveri("konca z imenom in nicelnim bajtom", besedilo.endsWith(" safeer@tv\u0000"))
    val surovo = java.util.Base64.getDecoder().decode(besedilo.substringBefore(' '))
    preveri("dolzina 524 bajtov", surovo.size == 524)
    val b = ByteBuffer.wrap(surovo).order(ByteOrder.LITTLE_ENDIAN)
    val besed = b.int; val n0inv = b.int
    val modulLe = ByteArray(256).also { b.get(it) }; val rrLe = ByteArray(256).also { b.get(it) }; val eksp = b.int
    fun izLe(x: ByteArray) = BigInteger(1, x.reversedArray())
    preveri("64 besed", besed == 64)
    preveri("modul se ujema", izLe(modulLe) == pub.modulus)
    preveri("eksponent", eksp == pub.publicExponent.toInt())
    val r32 = BigInteger.ONE.shiftLeft(32)
    preveri("n0inv * n0 = -1 mod 2^32", BigInteger.valueOf(n0inv.toLong() and 0xffffffffL).multiply(pub.modulus.mod(r32)).mod(r32) == r32.subtract(BigInteger.ONE))
    preveri("rr = 2^4096 mod n", izLe(rrLe) == BigInteger.ONE.shiftLeft(4096).mod(pub.modulus))
    preveri("kljuc ni 2048 bitov -> napaka", try { P.javniKljucAdb(BigInteger.valueOf(65537), 3, "x") { "" }; false } catch (_: IllegalArgumentException) { true })

    println("== podpis zetona (kot ga preveri adbd: RSA_verify SHA-1)")
    val zeton = ByteArray(20) { it.toByte() }
    val sig = java.security.Signature.getInstance("NONEwithRSA").apply { initSign(kp.private); update(P.zaPodpis(zeton)!!) }.sign()
    val v = java.security.Signature.getInstance("SHA1withRSA")
    // adbd preveri, da je zeton »povzetek SHA-1«: dekodiran podpis mora biti DigestInfo(SHA-1) + zeton.
    val dek = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(javax.crypto.Cipher.DECRYPT_MODE, kp.public) }.doFinal(sig)
    preveri("podpis = DigestInfo SHA-1 + zeton", dek.contentEquals(P.SHA1_DIGEST_INFO + zeton))
    if (v.algorithm.isEmpty()) napak++

    if (napak == 0) println("AdbLokalnoPravilaTest: OK") else { println("Napak: $napak"); kotlin.system.exitProcess(1) }
}
