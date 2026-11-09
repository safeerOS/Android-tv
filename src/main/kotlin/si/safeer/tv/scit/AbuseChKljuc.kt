package si.safeer.tv.scit

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Uporabnikov kljuc abuse.ch (Auth-Key) za seznam URLhaus. Lastnik 9. 11. 2026: abuse.ch od 21. 10. 2026 seznama ne da
 * brez kljuca (HTTP 410); kljuc je brezplacen za nepridobitno rabo in vsak uporabnik vpise SVOJEGA - v aplikaciji ga ni.
 *
 * Sifriran s kljucem v Android Keystore (AES-GCM), v DATOTEKI (ne SharedPreferences): bere ga tudi proces :scit, ki bi
 * imel v SharedPreferences zastarelo kopijo iz casa svojega zagona. Kljuc nikoli v dnevnik, na zaslon ali drugam.
 */
object AbuseChKljuc {
    private const val ALIAS = "safeer_abusech_v1"
    private const val DATOTEKA = "abusech.kljuc"

    /** Naslov seznama URLhaus s kljucem v poti (abuse.ch: »Auth-Key in the URL«); {KLJUC} zamenja agent seznamov. */
    const val URL_URLHAUS = "https://urlhaus-api.abuse.ch/v2/files/exports/{KLJUC}/hostfile.txt"

    @Volatile
    private var app: Context? = null

    fun init(c: Context) { app = c.applicationContext }

    private fun datoteka(c: Context) = File(File(c.filesDir, "scit"), DATOTEKA)

    private fun kljucKeystore(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    /** Shrani ze preverjen in ociscen kljuc ([AbuseChPravila.ocisti]); vrne false ob napaki. */
    fun shrani(c: Context, kljuc: String): Boolean = try {
        val sifra = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, kljucKeystore()) }
        val ct = sifra.doFinal(kljuc.toByteArray(Charsets.UTF_8))
        val d = datoteka(c)
        d.parentFile?.mkdirs()
        val zacasna = File.createTempFile(DATOTEKA, ".tmp", d.parentFile)
        zacasna.writeText(Base64.encodeToString(sifra.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP))
        if (zacasna.renameTo(d)) true else { zacasna.delete(); false }
    } catch (_: Throwable) { false }

    /** Kljuc ali null (ni vpisan ali ni berljiv). Vsakic prebere datoteko - velja tudi za proces :scit. */
    fun preberi(c: Context): String? = try {
        val d = datoteka(c)
        if (!d.isFile || d.length() > 4096) null else {
            val (iv, ct) = d.readText().split(":", limit = 2)
            val sifra = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, kljucKeystore(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            String(sifra.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8).takeIf { AbuseChPravila.veljavnaOblika(it) }
        }
    } catch (_: Throwable) { null }

    /** Za agenta seznamov (brez Context): kljuc ali null, ce ga ni ali proces se ni klical [init]. */
    fun zaVir(): String? = app?.let { preberi(it) }

    fun ima(c: Context): Boolean = datoteka(c).isFile

    /**
     * CodeRabbit 9. 11. 2026: preverba traja (omrezje); ce uporabnik medtem kljuc odstrani, starejsa preverba kljuca ne sme
     * obnoviti. [generacija] pred preverbo, [shraniZaGeneracijo] shrani samo, ce se ni nic spremenilo.
     */
    private val zaklep = Any()
    @Volatile
    private var gen = 0

    fun generacija(): Int = gen

    fun shraniZaGeneracijo(c: Context, kljuc: String, g: Int): Boolean = synchronized(zaklep) { g == gen && shrani(c, kljuc) }

    fun pozabi(c: Context) = synchronized(zaklep) {
        gen++
        try { datoteka(c).delete() } catch (_: Throwable) { }
        Unit
    }

    /**
     * Preveri kljuc pri abuse.ch (prenos seznama s kljucem, HTTPS, brez preusmeritev). Vrne izid; kljuc ne gre v dnevnik.
     * Klic iz ozadja (omrezje).
     */
    fun preveri(kljuc: String): AbuseChPravila.Preverba {
        if (!AbuseChPravila.veljavnaOblika(kljuc)) return AbuseChPravila.Preverba.OBLIKA
        val p = try {
            java.net.URL(URL_URLHAUS.replace("{KLJUC}", kljuc)).openConnection() as java.net.HttpURLConnection
        } catch (_: Throwable) { return AbuseChPravila.Preverba.NI_ODGOVORA }
        return try {
            p.instanceFollowRedirects = false
            p.useCaches = false
            p.connectTimeout = 10_000
            p.readTimeout = 15_000
            p.setRequestProperty("User-Agent", "Safeer")
            AbuseChPravila.izKode(p.responseCode)
        } catch (_: Throwable) {
            AbuseChPravila.Preverba.NI_ODGOVORA
        } finally {
            p.disconnect()
        }
    }
}
