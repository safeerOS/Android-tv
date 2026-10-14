package si.safeer.tv.os

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Teamwork na Androidu - pravila brez Androida (preizkus: tests/TeamworkTest.kt).
 *
 * Isti protokol kot Safeer OS na racunalniku (core/link_teamwork.py): naprava, ki ima datoteko (lastnik), je vir resnice;
 * ta naprava ji ukaz `files.session` javlja, ali je datoteka tu odprta, shranitev pa poslje kot popravek
 * `POST /popravek/<id>`. Razreza po vsebini (link_delta) tu ni, zato gre cela datoteka z vrednostjo sha256 (»celota«) -
 * lastnik jo preveri in ob sporu (nekdo je medtem shranil) obdrzi obe razlicici.
 */
object TeamworkPravila {
    /** Mora se ujemati z MAGIJA v core/link_teamwork.py. */
    val MAGIJA = "SFTW1\n".toByteArray(Charsets.US_ASCII)

    fun sha256(datoteka: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        datoteka.inputStream().use { v ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = v.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Glava popravka »celota«: MAGIJA, dolzina glave (4 B, big-endian), JSON. Za njo sledijo bajti datoteke. */
    fun glavaCelote(velikost: Long, sha: String, osnova: String): ByteArray {
        val json = JSONObject().put("celota", true).put("velikost", velikost).put("sha256", sha).put("osnova", osnova)
            .toString().toByteArray(Charsets.UTF_8)
        val o = ByteArrayOutputStream()
        o.write(MAGIJA)
        o.write(byteArrayOf((json.size ushr 24).toByte(), (json.size ushr 16).toByte(), (json.size ushr 8).toByte(), json.size.toByte()))
        o.write(json)
        return o.toByteArray()
    }

    /** Pot popravka na strezniku naprave: osnova + /popravek/<id> (prek Huba /cast/popravek/<id>). */
    fun naslovPopravka(osnova: String, id: String, kodiraj: (String) -> String): String =
        osnova.trimEnd('/') + "/popravek/" + kodiraj(id)

    /** Kaj naj naprava naredi po odgovoru lastnika na `files.session`. */
    data class Odziv(val teamwork: Boolean, val drugi: List<String>, val sha: String, val obvestilo: String,
                     val novaRazlicica: Boolean)

    /**
     * [d] je `data` odgovora lastnika; [prejTeamwork] in [znaniSha] sta stanje te naprave; [lokalnoSpremenjeno] pove,
     * ali je datoteka tu spremenjena in se ni poslana (takrat nove razlicice ne prenesemo - spor resi lastnik).
     * »@tukaj« v seznamu pomeni napravo lastnika (njeno ime je [imeLastnika]).
     */
    fun odziv(d: JSONObject, prejTeamwork: Boolean, znaniSha: String, odprto: Boolean, lokalnoSpremenjeno: Boolean,
              imeLastnika: String): Odziv {
        val drugi = mutableListOf<String>()
        val arr = d.optJSONArray("drugi")
        if (arr != null) for (i in 0 until arr.length()) {
            val x = arr.optString(i)
            if (x.isNotBlank()) drugi.add(kratkoIme(if (x == "@tukaj") imeLastnika else x))
        }
        val tw = d.optBoolean("teamwork") && odprto
        val sha = d.optString("sha256")
        val nova = sha.isNotBlank() && sha != znaniSha && !lokalnoSpremenjeno
        val obvestilo = when {
            tw && !prejTeamwork -> "zacetek"
            !tw && prejTeamwork && odprto -> "konec"
            nova -> "nova"
            else -> ""
        }
        return Odziv(tw, drugi, sha, obvestilo, nova)
    }

    /** »Safeer OS (OnePlus)« -> »OnePlus« (kot kratko_ime na racunalniku). */
    fun kratkoIme(ime: String): String {
        val m = Regex("""\s*Safeer [^()]*\((.+)\)\s*""").matchEntire(ime)
        return m?.groupValues?.get(1)?.trim() ?: ime.trim()
    }

    /**
     * Podmapa za datoteko [id] z naprave: datoteki z istim imenom iz razlicnih map se ne smeta prekriti (pregled 14. 10.:
     * sicer bi seja ene poslala vsebino druge v napacno datoteko na racunalniku).
     */
    fun mapaZaId(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    /** Varno ime datoteke za mapo te aplikacije (brez locil poti in skritih imen). */
    fun varnoIme(ime: String): String {
        var s = ime.replace("/", "_").replace("\\", "_").replace("\u0000", "").trim()
        while (s.startsWith(".")) s = "_" + s.substring(1)
        s = s.take(180).trim()
        return if (s.isEmpty() || s == "." || s == "..") "datoteka" else s
    }
}
