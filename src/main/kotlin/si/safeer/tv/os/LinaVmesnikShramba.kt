package si.safeer.tv.os

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import si.safeer.tv.os.LinaVmesnikPravila as P

/**
 * Shramba lokalnega vmesnika za Lino (pravila: [LinaVmesnikPravila]). Zapiski: zdruzeni po skupnih pravilih, na disku
 * kot JSON zapisov v1. Kljuci asistenta: Keystore + AES-GCM kot pri Usenet ponudniku (AAD = ime kljuca, sifrata ni
 * mogoce prestaviti pod drugo ime); brez Keystore se kljuc NE shrani (nikoli v navadno datoteko). Vrednosti kljucev
 * nikoli v dnevnik.
 */
class LinaVmesnikShramba private constructor(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE)
    private val zaklep = Any()

    private val trajni = P.TrajniZapiski(
        beri = { prefs.getString(K_ZAPISKI, null) },
        pisi = { nov ->
            val staro = prefs.getString(K_ZAPISKI, null)
            // commit, ne apply: izid »sprejeto« mora pomeniti, da je na disku.
            val uspelo = prefs.edit().putString(K_ZAPISKI, nov).commit()
            // Neuspeh: SharedPreferences obdrzi novo vrednost v pomnilniku - povrnemo staro, da je kasnejsi commit drugega
            // kljuca ne zapise na disk.
            if (!uspelo) prefs.edit().apply { if (staro == null) remove(K_ZAPISKI) else putString(K_ZAPISKI, staro) }.apply()
            uspelo
        }
    )

    fun zapiski(): List<P.Zapisek> = synchronized(zaklep) { trajni.zapiski() }

    /** Zdruzi vhod z obstojecimi (skupna pravila); vrne zdruzeno stanje ali null, ce bi shramba presegla meje ali zapis ne uspe. */
    fun sprejmi(vhod: List<P.Zapisek>): List<P.Zapisek>? = synchronized(zaklep) { trajni.sprejmi(vhod) }

    // ------------------------------------------------------------------ kljuci asistenta

    fun imenaKljucev(): List<String> = synchronized(zaklep) {
        prefs.all.keys.filter { it.startsWith(PREDPONA_KLJUCA) }.map { it.removePrefix(PREDPONA_KLJUCA) }.filter(P::veljavnoImeKljuca).sorted()
    }

    /** Shrani kljuc; false, ce ime ali vrednost ni veljavna, je kljucev prevec ali ni Keystore. */
    fun nastaviKljuc(ime: String, vrednost: String): Boolean = synchronized(zaklep) {
        if (!P.veljavnoImeKljuca(ime) || !P.veljavnaVrednostKljuca(vrednost)) return false
        if (prefs.getString(PREDPONA_KLJUCA + ime, null) == null && imenaKljucev().size >= P.NAJVEC_KLJUCEV) return false
        val k = kljuc() ?: return false
        try {
            val c = Cipher.getInstance(SIFRA)
            c.init(Cipher.ENCRYPT_MODE, k)
            c.updateAAD(ime.toByteArray(Charsets.UTF_8))
            val s = c.doFinal(vrednost.toByteArray(Charsets.UTF_8))
            val v = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(s, Base64.NO_WRAP)
            val staro = prefs.getString(PREDPONA_KLJUCA + ime, null)
            val ok = prefs.edit().putString(PREDPONA_KLJUCA + ime, v).commit()
            // commit() = false: SharedPreferences obdrzi novo vrednost v pomnilniku, na disku je ni - vrnemo staro (CodeRabbit).
            if (!ok) { val e = prefs.edit(); if (staro == null) e.remove(PREDPONA_KLJUCA + ime) else e.putString(PREDPONA_KLJUCA + ime, staro); e.apply() }
            ok
        } catch (_: Exception) { false }
    }

    fun beriKljuc(ime: String): String? = synchronized(zaklep) {
        if (!P.veljavnoImeKljuca(ime)) return null
        val d = (prefs.getString(PREDPONA_KLJUCA + ime, null) ?: return null).split(":")
        if (d.size != 2) return null
        val k = kljuc() ?: return null
        try {
            val c = Cipher.getInstance(SIFRA)
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, Base64.decode(d[0], Base64.NO_WRAP)))
            c.updateAAD(ime.toByteArray(Charsets.UTF_8))
            String(c.doFinal(Base64.decode(d[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { null }   // razveljavljen kljuc (nov zaklep zaslona, obnova): Lina vpise kljuc znova
    }

    fun izbrisiKljuc(ime: String): Boolean = synchronized(zaklep) {
        if (!P.veljavnoImeKljuca(ime)) return false
        val staro = prefs.getString(PREDPONA_KLJUCA + ime, null)
        val ok = prefs.edit().remove(PREDPONA_KLJUCA + ime).commit()
        if (!ok && staro != null) prefs.edit().putString(PREDPONA_KLJUCA + ime, staro).apply()   // glej nastaviKljuc
        ok
    }

    private fun kljuc(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build())
            generateKey()
        }
    } catch (_: Exception) { null }

    companion object {
        private const val DATOTEKA = "safeer_lina_vmesnik"
        private const val ALIAS = "safeer-lina-asistent"
        private const val SIFRA = "AES/GCM/NoPadding"
        private const val K_ZAPISKI = "zapiski"
        private const val PREDPONA_KLJUCA = "k:"

        @Volatile private var primerek: LinaVmesnikShramba? = null

        /** Klic po spremembi zapiskov prek vmesnika (CastReceiverService jih tako objavi krogu); samo zapiski, nikoli kljuci. */
        @Volatile var poSpremembi: (() -> Unit)? = null

        fun get(context: Context): LinaVmesnikShramba =
            primerek ?: synchronized(this) { primerek ?: LinaVmesnikShramba(context).also { primerek = it } }
    }
}
