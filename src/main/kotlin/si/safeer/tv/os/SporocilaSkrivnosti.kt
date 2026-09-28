package si.safeer.tv.os

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Gesla in zetoni kanalov Sporocil. Sifrirani s kljucem v Android Keystore (AES-GCM, kljuc nikoli
 * ne zapusti strojne hrambe); v nastavitvah je samo sifrirano besedilo. Nikoli v bazi ali dnevniku.
 */
object SporocilaSkrivnosti {
    private const val ALIAS = "safeer_sporocila_v1"
    private const val DATOTEKA = "safeer_sporocila_skrivnosti"

    private fun kljuc(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    fun shrani(c: Context, ime: String, vrednost: String): Boolean = try {
        val sifra = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, kljuc()) }
        val ct = sifra.doFinal(vrednost.toByteArray(Charsets.UTF_8))
        val zapis = Base64.encodeToString(sifra.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        c.applicationContext.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE).edit().putString(ime, zapis).commit()
    } catch (_: Throwable) { false }

    fun preberi(c: Context, ime: String): String? = try {
        val zapis = c.applicationContext.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE).getString(ime, null)
        if (zapis == null) null else {
            val (iv, ct) = zapis.split(":", limit = 2)
            val sifra = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, kljuc(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            String(sifra.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    } catch (_: Throwable) { null }

    fun pozabi(c: Context, ime: String) {
        c.applicationContext.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE).edit().remove(ime).apply()
    }
}
