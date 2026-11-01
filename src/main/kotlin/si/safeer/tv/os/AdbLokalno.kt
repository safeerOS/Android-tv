package si.safeer.tv.os

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * Lasten adb na tej napravi (lastnik 1. 11. 2026): po soglasju uporabnika zaganjalnik zapira aplikacije, ki jih ne
 * uporablja vec (»am force-stop«), ker Android 11 na TV tujih aplikacij drugace ne dovoli zapreti.
 *
 * Varnost: povezava SAMO na 127.0.0.1 ([AdbLokalnoPravila.GOSTITELJ]); zasebni kljuc RSA je v Android Keystore in nikoli
 * ne zapusti naprave; ven gre SAMO ukaz iz [AdbLokalnoPravila.ukazZapri] (preverjeno ime paketa). Ob prvi povezavi
 * Android sam vprasa uporabnika (»Dovoli razhroscevanje«) - brez njegove potrditve se nic ne zgodi. Nikoli na glavni niti.
 */
object AdbLokalno {
    private const val TAG = "SafeerAdbLokalno"
    private const val PREFS = "safeer_os"
    private const val KLJUC_SOGLASJA = "adb_soglasje"
    private const val VZDEVEK = "safeer_adb_lokalno"
    private const val ROK_POVEZAVE_MS = 2_000
    private const val ROK_BRANJA_MS = 5_000
    /** Ob prvi povezavi Android pokaze okno za potrditev - uporabnik ima toliko casa. */
    private const val ROK_POTRDITVE_MS = 60_000

    fun soglasje(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KLJUC_SOGLASJA, false)

    fun nastaviSoglasje(c: Context, da: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KLJUC_SOGLASJA, da).apply()
    }

    /** Ali je adb na tej napravi dosegljiv (vrata odprta). Ne potrjuje, da nas sprejme. */
    fun dosegljiv(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(AdbLokalnoPravila.GOSTITELJ, AdbLokalnoPravila.VRATA), ROK_POVEZAVE_MS); true }
    } catch (_: Exception) { false }

    private fun kljuc(): Pair<PrivateKey, RSAPublicKey> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(VZDEVEK)) {
            val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            g.initialize(KeyGenParameterSpec.Builder(VZDEVEK, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build())
            g.generateKeyPair()
        }
        val zasebni = ks.getKey(VZDEVEK, null) as PrivateKey
        val javni = ks.getCertificate(VZDEVEK).publicKey as RSAPublicKey
        return zasebni to javni
    }

    private fun preberi(vhod: DataInputStream): Pair<AdbLokalnoPravila.Glava, ByteArray> {
        val g = ByteArray(24); vhod.readFully(g)
        val glava = AdbLokalnoPravila.preberiGlavo(g)
        if (!glava.veljavna) throw java.io.IOException("neveljavna glava ADB")
        val podatki = ByteArray(glava.dolzina); vhod.readFully(podatki)
        return glava to podatki
    }

    /**
     * Zapre [paketi] (ze preverjene s [AdbLokalnoPravila.smemoZapreti]). Vrne seznam zaprtih. Brez soglasja ne naredi nicesar.
     * [prvic]: dovoli daljsi rok, ker Android pokaze okno za potrditev.
     */
    fun zapri(c: Context, paketi: List<String>, prvic: Boolean = false): List<String> {
        if (!soglasje(c) || (paketi.isEmpty() && !prvic)) return emptyList()
        val ukazi = paketi.mapNotNull { p -> AdbLokalnoPravila.ukazZapri(p)?.let { p to it } }
        if (ukazi.isEmpty() && !prvic) return emptyList()
        zadnjiIzid = IZID_NAPAKA
        val zaprti = ArrayList<String>()
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(AdbLokalnoPravila.GOSTITELJ, AdbLokalnoPravila.VRATA), ROK_POVEZAVE_MS)
                s.soTimeout = ROK_BRANJA_MS
                val izhod: OutputStream = s.getOutputStream()
                val vhod = DataInputStream(s.getInputStream())
                izhod.write(AdbLokalnoPravila.sporocilo(AdbLokalnoPravila.A_CNXN, AdbLokalnoPravila.A_VERSION,
                    AdbLokalnoPravila.MAX_PODATKOV, "host::\u0000".toByteArray(Charsets.US_ASCII)))
                val (zasebni, javni) = kljuc()
                var podpisano = false
                var povezano = false
                for (krog in 0 until 6) {
                    val (g, d) = preberi(vhod)
                    when (g.ukaz) {
                        AdbLokalnoPravila.A_CNXN -> { povezano = true; break }
                        AdbLokalnoPravila.A_AUTH -> if (g.arg0 == AdbLokalnoPravila.AUTH_ZETON) {
                            if (!podpisano) {
                                val zaPodpis = AdbLokalnoPravila.zaPodpis(d) ?: throw java.io.IOException("zeton ADB ni 20 bajtov")
                                val sig = Signature.getInstance("NONEwithRSA").apply { initSign(zasebni); update(zaPodpis) }.sign()
                                izhod.write(AdbLokalnoPravila.sporocilo(AdbLokalnoPravila.A_AUTH, AdbLokalnoPravila.AUTH_PODPIS, 0, sig))
                                podpisano = true
                            } else {
                                // Kljuc se ni potrjen: Android pokaze okno »Dovoli razhroscevanje?« - uporabnik odloci.
                                if (!prvic) { zadnjiIzid = IZID_NI_POTRJENO; Log.i(TAG, "adb: kljuc ni potrjen - potrebna je potrditev uporabnika"); return emptyList() }
                                val jk = AdbLokalnoPravila.javniKljucAdb(javni.modulus, javni.publicExponent.toInt(), "safeer@tv") {
                                    Base64.encodeToString(it, Base64.NO_WRAP)
                                }
                                izhod.write(AdbLokalnoPravila.sporocilo(AdbLokalnoPravila.A_AUTH, AdbLokalnoPravila.AUTH_JAVNI_KLJUC, 0, jk))
                                s.soTimeout = ROK_POTRDITVE_MS
                            }
                        }
                        else -> throw java.io.IOException("nepricakovan odgovor ADB")
                    }
                }
                if (!povezano) { zadnjiIzid = IZID_NI_POTRJENO; return emptyList() }
                zadnjiIzid = IZID_POVEZANO
                s.soTimeout = ROK_BRANJA_MS
                var lokalni = 0
                for ((paket, ukaz) in ukazi) {
                    lokalni++
                    izhod.write(AdbLokalnoPravila.sporocilo(AdbLokalnoPravila.A_OPEN, lokalni, 0, (ukaz + "\u0000").toByteArray(Charsets.US_ASCII)))
                    var oddaljeni = 0
                    var konec = false
                    for (krog in 0 until 50) {
                        val (g, _) = preberi(vhod)
                        if (g.arg1 != lokalni) continue
                        when (g.ukaz) {
                            AdbLokalnoPravila.A_OKAY -> oddaljeni = g.arg0
                            AdbLokalnoPravila.A_WRTE -> { oddaljeni = g.arg0
                                izhod.write(AdbLokalnoPravila.sporocilo(AdbLokalnoPravila.A_OKAY, lokalni, oddaljeni)) }
                            AdbLokalnoPravila.A_CLSE -> {
                                if (oddaljeni != 0) izhod.write(AdbLokalnoPravila.sporocilo(AdbLokalnoPravila.A_CLSE, lokalni, oddaljeni))
                                konec = true
                            }
                        }
                        if (konec) break
                    }
                    if (konec && oddaljeni != 0) zaprti.add(paket)
                }
            }
        } catch (e: java.net.ConnectException) {
            zadnjiIzid = IZID_NI_DOSEGLJIV
            Log.i(TAG, "adb: vrata niso odprta (omrezno razhroscevanje izklopljeno)")
        } catch (e: Exception) {
            Log.i(TAG, "adb: ${e.javaClass.simpleName}")
        }
        if (zaprti.isNotEmpty()) Log.i(TAG, "adb: zaprto ${zaprti.joinToString(", ")}")
        return zaprti
    }

    const val IZID_POVEZANO = "povezano"
    const val IZID_NI_POTRJENO = "ni_potrjeno"
    const val IZID_NI_DOSEGLJIV = "ni_dosegljiv"
    const val IZID_NAPAKA = "napaka"

    /** Izid zadnje povezave (za nastavitve in dnevnik). */
    @Volatile var zadnjiIzid: String = ""
        private set

    /** Prva povezava po soglasju: Android pokaze okno »Dovoli razhroscevanje« (uporabnik ima 60 s). Vrne izid. */
    fun povezi(c: Context): String { zapri(c, emptyList(), prvic = true); return zadnjiIzid }
}
