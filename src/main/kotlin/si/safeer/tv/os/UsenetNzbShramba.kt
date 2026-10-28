package si.safeer.tv.os

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Lastnik 28. 10. 2026 (kvota indeksatorja): NZB predvajanih albumov na napravi in izdaje, ki niso vec na voljo. Kljuc je
 * odtis imena in velikosti izdaje - na disku in v nastavitvah ni imen. NZB vsebuje samo dele (message-id), nikoli kljuca
 * indeksatorja (ta je samo v povezavi, ki ostane v pomnilniku - pregled Grok 41). Shramba je v noBackupFilesDir.
 */
object UsenetNzbShramba {
    private const val MAPA = "usenet_nzb"
    private const val KLJUC_NEUSPELI = "usenet_neuspeli_albumi"
    private val zaklep = Any()

    fun odtis(naslov: String, velikost: Long): String =
        MessageDigest.getInstance("SHA-256").digest((naslov.trim() + "\n" + velikost).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun mapa(c: Context) = File(c.noBackupFilesDir, MAPA)
    private fun datoteka(c: Context, naslov: String, velikost: Long) = File(mapa(c), odtis(naslov, velikost) + ".nzb")

    /** Shranjen NZB albuma ali null; uporaba osvezi cas (najstarejsi gre prvi). */
    fun preberi(c: Context, naslov: String, velikost: Long): ByteArray? = synchronized(zaklep) {
        if (naslov.isBlank()) return null
        val f = datoteka(c, naslov, velikost)
        try { if (!f.isFile) null else f.readBytes().also { f.setLastModified(System.currentTimeMillis()) } } catch (_: Exception) { null }
    }

    fun shrani(c: Context, naslov: String, velikost: Long, nzb: ByteArray): Unit = synchronized(zaklep) {
        if (naslov.isBlank() || nzb.size > UsenetLastniPravila.NZB_NAJVEC_ENA) return
        try {
            val m = mapa(c).apply { mkdirs() }
            val zac = File(m, odtis(naslov, velikost) + ".tmp")
            zac.writeBytes(nzb)
            zac.renameTo(datoteka(c, naslov, velikost))
            val vse = (m.listFiles() ?: emptyArray()).filter { it.name.endsWith(".nzb") }
            val brisi = UsenetLastniPravila.zaBrisanje(vse.map { Triple(it.name, it.length(), it.lastModified()) }).toSet()
            vse.filter { it.name in brisi }.forEach { it.delete() }
        } catch (_: Exception) { }
    }

    fun odstrani(c: Context, naslov: String, velikost: Long): Unit = synchronized(zaklep) {
        try { datoteka(c, naslov, velikost).delete() } catch (_: Exception) { }
        Unit
    }

    private fun neuspeli(c: Context): Map<String, Long> = try {
        val o = org.json.JSONObject(c.getSharedPreferences("safeer_os", Context.MODE_PRIVATE).getString(KLJUC_NEUSPELI, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optLong(it) }
    } catch (_: Exception) { emptyMap() }

    /** Izdaja ni vec na voljo (trajna napaka): 30 dni se ne pokaze med zadetki. */
    fun zabeleziNeuspel(c: Context, naslov: String, velikost: Long): Unit = synchronized(zaklep) {
        if (naslov.isBlank()) return
        val zdaj = System.currentTimeMillis()
        val nov = UsenetLastniPravila.pocistiNeuspele(neuspeli(c) + (odtis(naslov, velikost) to zdaj), zdaj)
        c.getSharedPreferences("safeer_os", Context.MODE_PRIVATE).edit()
            .putString(KLJUC_NEUSPELI, org.json.JSONObject(nov as Map<*, *>).toString()).apply()
    }

    fun jeNeuspel(c: Context, naslov: String, velikost: Long): Boolean {
        val t = neuspeli(c)[odtis(naslov, velikost)] ?: return false
        return UsenetLastniPravila.pocistiNeuspele(mapOf("x" to t), System.currentTimeMillis()).isNotEmpty()
    }
}
