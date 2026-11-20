package si.safeer.tv.os

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Vec YouTube racunov (lastnik 21. 11. 2026). WebView ima eno skupno zbirko piskotkov, zato posnetek prijave vsakega racuna
 * (niz piskotkov youtube.com) hranimo sami: AES-GCM s kljucem v Android Keystore, vezan na id racuna (AAD). Brez Keystore
 * posnetka ne shranimo (nikoli v navadno datoteko) - tak racun deluje samo, dokler je prijavljen v WebView.
 * Uvoz ([YouTubeUvoz]) bere piskotke iz teh posnetkov, ne iz WebView, zato prijava novega racuna ne odjavi starih.
 */
object YouTubeRacuni {
    private const val NASTAVITVE = "safeer_youtube_racuni"
    private const val KLJUC_SEZNAM = "racuni"
    private const val PREDPONA = "p."
    private const val ALIAS = "safeer-youtube-racuni"
    private const val SIFRA = "AES/GCM/NoPadding"

    data class Racun(val id: String, val ime: String)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)

    fun seznam(c: Context): List<Racun> = try {
        val a = JSONArray(prefs(c).getString(KLJUC_SEZNAM, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> Racun(o.optString("id"), o.optString("ime")) }?.takeIf { r -> r.id.isNotBlank() } }
    } catch (_: Exception) { emptyList() }

    private fun shraniSeznam(c: Context, s: List<Racun>) {
        val a = JSONArray(); s.forEach { a.put(JSONObject().put("id", it.id).put("ime", it.ime)) }
        prefs(c).edit().putString(KLJUC_SEZNAM, a.toString()).apply()
    }

    private fun kljuc(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
            generateKey()
        }
    } catch (_: Exception) { null }

    /** Piskotki racuna ali null (ni posnetka ali ga ni mogoce odsifrirati). */
    fun piskotki(c: Context, id: String): String? {
        val v = prefs(c).getString(PREDPONA + id, null) ?: return null
        val d = v.split(":"); if (d.size != 2) return null
        val k = kljuc() ?: return null
        return try {
            val ci = Cipher.getInstance(SIFRA)
            ci.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, Base64.decode(d[0], Base64.NO_WRAP)))
            ci.updateAAD(id.toByteArray(Charsets.UTF_8))
            String(ci.doFinal(Base64.decode(d[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    /**
     * Zabelezi (ali osvezi) racun iz piskotkov prijave; vrne racun ali null (ni piskotkov prijave, polno, ali ni Keystore).
     * Isti racun (isti SAPISID) se osvezi, ne podvoji.
     */
    @Synchronized
    fun zabelezi(c: Context, piskotki: String, imePredlog: String): Racun? {
        val id = YouTubeRacuniPravila.idRacuna(piskotki) ?: return null
        val sez = seznam(c)
        val obstojec = sez.firstOrNull { it.id == id }
        if (obstojec == null && sez.size >= YouTubeRacuniPravila.NAJVEC_RACUNOV) return null
        val k = kljuc() ?: return null
        val ci = Cipher.getInstance(SIFRA)
        ci.init(Cipher.ENCRYPT_MODE, k)
        ci.updateAAD(id.toByteArray(Charsets.UTF_8))
        val s = ci.doFinal(piskotki.toByteArray(Charsets.UTF_8))
        val v = Base64.encodeToString(ci.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(s, Base64.NO_WRAP)
        if (!prefs(c).edit().putString(PREDPONA + id, v).commit()) return null
        if (obstojec != null) return obstojec
        val r = Racun(id, YouTubeRacuniPravila.privzetoIme(YouTubeRacuniPravila.naslednja(sez.map { it.ime }, imePredlog), imePredlog))
        shraniSeznam(c, sez + r)
        return r
    }

    @Synchronized
    fun odstrani(c: Context, id: String) {
        prefs(c).edit().remove(PREDPONA + id).apply()
        shraniSeznam(c, seznam(c).filterNot { it.id == id })
    }

    @Synchronized
    fun odstraniVse(c: Context) { prefs(c).edit().clear().apply() }
}
