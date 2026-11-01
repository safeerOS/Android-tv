package si.safeer.tv.os

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lastnik 1. 11. 2026: Android 11 na TV (Philips) ne dovoli zapreti tujih aplikacij (killBackgroundProcesses vrne
 * »Invalid packageName« za vse - izmerjeno 13:24). Zaganjalnik se zato po soglasju uporabnika enkrat poveze na LASTEN adb
 * (127.0.0.1:5555, kot Shizuku) in zapira aplikacije z »am force-stop«. Tu so ciste funkcije (brez Androida, preizkus
 * tests/AdbLokalnoPravilaTest.kt): protokol ADB, oblika javnega kljuca in - najpomembnejse - kateri ukaz SME iti ven.
 */
object AdbLokalnoPravila {
    const val GOSTITELJ = "127.0.0.1"
    const val VRATA = 5555

    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_VERSION = 0x01000000
    const val MAX_PODATKOV = 4096
    const val AUTH_ZETON = 1
    const val AUTH_PODPIS = 2
    const val AUTH_JAVNI_KLJUC = 3

    /** Najvec bajtov v enem sporocilu, ki ga sprejmemo (zascita pred napacnim ali zlonamernim streznikom na vratih). */
    const val NAJVEC_SPREJETIH = 1024 * 1024

    /** SHA-1 DigestInfo (PKCS#1 v1.5): adbd preveri podpis zetona z RSA_verify(NID_sha1, ...), zeton je "povzetek". */
    val SHA1_DIGEST_INFO: ByteArray = intArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        .map { it.toByte() }.toByteArray()

    private val IME_PAKETA = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Pakete, ki jih nikoli ne zapremo: zaganjalnik sam, sistem, nastavitve, tipkovnica, storitve televizorja. */
    private val NIKOLI = setOf("android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
        "com.android.providers.tv", "com.google.android.gms", "com.android.phone", "com.android.bluetooth")

    /**
     * Ali smemo zapreti [paket]. Lastnik 1. 11. 2026: tudi nase aplikacije (TV Browser, Play Store, Predvajalnik), ko jih
     * uporabnik ne uporablja vec - nikoli pa zaganjalnika ([zaganjalnik]), aplikacije v ospredju ([vOspredju]) in tipkovnice.
     */
    fun smemoZapreti(paket: String, zaganjalnik: String, vOspredju: Set<String>, tipkovnica: String?): Boolean =
        IME_PAKETA.matches(paket) && paket.length <= 255 && paket != zaganjalnik && paket !in vOspredju &&
            paket != tipkovnica && paket !in NIKOLI && NIKOLI_PREDPONE.none { paket.startsWith(it) }

    /** Sistem televizorja (Android, Philips TP Vision, MediaTek): nikoli. */
    private val NIKOLI_PREDPONE = listOf("com.android.", "org.droidtv.", "com.mediatek.", "com.google.android.tvlauncher")

    /** Edini ukaz, ki gre na adb: »am force-stop <paket>«. Brez veljavnega imena paketa ni ukaza (null). */
    fun ukazZapri(paket: String): String? = if (IME_PAKETA.matches(paket) && paket.length <= 255) "shell:am force-stop $paket" else null

    /** Glava sporocila ADB (24 bajtov, little-endian) + podatki. */
    fun sporocilo(ukaz: Int, arg0: Int, arg1: Int, podatki: ByteArray = ByteArray(0)): ByteArray {
        val b = ByteBuffer.allocate(24 + podatki.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(ukaz).putInt(arg0).putInt(arg1).putInt(podatki.size).putInt(vsota(podatki)).putInt(ukaz xor -1)
        b.put(podatki)
        return b.array()
    }

    /** Kontrolna vsota starega protokola (vsota nepredznacenih bajtov). */
    fun vsota(podatki: ByteArray): Int { var s = 0; for (x in podatki) s += (x.toInt() and 0xff); return s }

    data class Glava(val ukaz: Int, val arg0: Int, val arg1: Int, val dolzina: Int, val vsota: Int, val magija: Int) {
        val veljavna: Boolean get() = magija == (ukaz xor -1) && dolzina in 0..NAJVEC_SPREJETIH
    }

    fun preberiGlavo(b: ByteArray): Glava {
        require(b.size == 24) { "glava ADB ima 24 bajtov" }
        val x = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return Glava(x.int, x.int, x.int, x.int, x.int, x.int)
    }

    /** Podatki za podpis zetona: DigestInfo SHA-1 + zeton (20 bajtov), podpisano z NONEwithRSA (PKCS#1). */
    fun zaPodpis(zeton: ByteArray): ByteArray? = if (zeton.size == 20) SHA1_DIGEST_INFO + zeton else null

    /**
     * Javni kljuc v obliki adbd (android_pubkey_encode): 64 besed modula, n0inv, modul in rr little-endian, eksponent;
     * nato base64 in » ime\0«. [modul] mora imeti 2048 bitov.
     */
    fun javniKljucAdb(modul: BigInteger, eksponent: Int, ime: String, base64: (ByteArray) -> String): ByteArray {
        require(modul.bitLength() == 2048) { "kljuc RSA mora imeti 2048 bitov" }
        val besed = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = modul.mod(r32).modInverse(r32).negate().mod(r32).toLong().toInt()
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(modul)
        val b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(besed).putInt(n0inv).put(malKonec(modul, 256)).put(malKonec(rr, 256)).putInt(eksponent)
        return (base64(b.array()) + " " + ime.filter { it.isLetterOrDigit() || it in "@._-" } + "\u0000").toByteArray(Charsets.US_ASCII)
    }

    /** Stevilo kot [dolzina] bajtov, little-endian (najmanj pomemben bajt prvi). */
    fun malKonec(x: BigInteger, dolzina: Int): ByteArray {
        val be = x.toByteArray().let { if (it.size > dolzina && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        require(be.size <= dolzina)
        val out = ByteArray(dolzina)
        for (i in be.indices) out[i] = be[be.size - 1 - i]
        return out
    }
}
