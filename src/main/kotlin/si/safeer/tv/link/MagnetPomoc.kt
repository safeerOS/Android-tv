package si.safeer.tv.link

import android.content.Context
import android.util.Log
import org.json.JSONObject
import si.safeer.tv.os.MagnetMotor
import java.util.concurrent.ConcurrentHashMap

/**
 * Zakon solidarnosti za torrente tudi med telefoni, tablicami in televizorji (lastnik, 3. 10. 2026: "ce televizor ni
 * sposoben dodatkov predvajati in so jih sposobne ostale naprave v krogu, bi jih moral prositi za pomoc"). Naprava s
 * prostorom torrent prenasa in ga po domacem omrezju pretaka napravi, ki ga sama ne zmore - enako kot racunalnik s
 * Safeer Control (`magnet.stream`, `magnet.list`, `magnet.remove`).
 *
 * Ukazi daljinca tecejo na glavni niti, branje metapodatkov torrenta pa traja do minute. Zato prvi `magnet.stream`
 * delo le zacne in odgovori `{pending: true}`; naprava vprasa znova cez dve sekundi in dobi `{server, path, name, file,
 * size}` (kot od racunalnika) ali napako. Kar naprava tako prenese, je zacasno: [MagnetMotor.pocistiZacasne] ga odstrani,
 * ko ga 48 ur nihce ne predvaja.
 */
object MagnetPomoc {
    /** Zmoznost v prijavi v Link: naprava zna torrent pretakati drugim. */
    const val ZMOZNOST = "torrent"
    private const val TAG = "SafeerMagnetPomoc"
    private const val NAJDLJE_MS = 150_000L
    private const val ODGOVOR_VELJA_MS = 60_000L

    private class Delo(val zacetek: Long) {
        @Volatile var izid: Daljinec.Izid? = null
        @Volatile var koncano = 0L
    }

    private val dela = ConcurrentHashMap<String, Delo>()

    fun tok(context: Context, p: JSONObject, posiljatelj: String): Daljinec.Izid {
        if (!MagnetMotor.naVoljo) return Daljinec.Izid(false, "Pretakanje tu ni na voljo", koda = "ni_podprto")
        val uri = p.optString("uri")
        val hash = if (uri.length <= 8192) MagnetMotor.hash(uri) else null
        if (hash == null) return Daljinec.Izid(false, "Torrenta ni mogoce pretakati", koda = "ni_magnet")
        val datoteka = if (p.has("file") && !p.isNull("file")) p.optInt("file", -1) else -1
        val zdaj = android.os.SystemClock.elapsedRealtime()
        dela.entries.removeIf { (_, d) -> (d.koncano != 0L && zdaj - d.koncano > ODGOVOR_VELJA_MS) || zdaj - d.zacetek > NAJDLJE_MS + ODGOVOR_VELJA_MS }
        val kljuc = "$hash|$datoteka|$posiljatelj"
        dela[kljuc]?.let { d ->
            d.izid?.let { izid -> if (!izid.ok) dela.remove(kljuc); return izid }
            return Daljinec.Izid(true, "Pripravljam tok", JSONObject().put("pending", true))
        }
        // Naprava pomaga, kolikor zmore: prazna baterija, varcevanje, pregrevanje ali film, ki ga sama predvaja, so razlog za "ne".
        val pomoc = Zmogljivost.porocilo(context).optJSONObject("pomoc")
        if (pomoc?.optBoolean("lahko", true) == false)
            return Daljinec.Izid(false, "Naprava ta trenutek ne more pomagati", koda = pomoc.optString("razlog").ifBlank { "preobremenjen" })
        // Na mobilnih podatkih ne prenasamo filmov za druge.
        val omrezje = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (omrezje == null || omrezje.activeNetwork == null || omrezje.isActiveNetworkMetered)
            return Daljinec.Izid(false, "Naprava ni na domacem omrezju", koda = "varcevanje")
        val delo = Delo(zdaj)
        dela[kljuc] = delo
        val app = context.applicationContext
        Thread({
            delo.izid = try {
                val t = MagnetMotor.pripraviTok(app, uri, datoteka)
                val streznik = DatotekeStreznik.streznikZa(app, posiljatelj) ?: throw IllegalStateException("napaka")
                Log.i(TAG, "Pretakam ${t.datoteka.ime} za $posiljatelj")
                Daljinec.Izid(true, "Naprava pretaka: " + t.datoteka.ime.substringAfterLast('/'), JSONObject()
                    .put("server", streznik).put("path", "/magnet/" + t.skrivnost)
                    .put("name", t.datoteka.ime.substringAfterLast('/')).put("file", t.datoteka.i).put("size", t.datoteka.velikost))
            } catch (e: Throwable) {
                Log.i(TAG, "magnet.stream za $posiljatelj: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                val koda = e.message?.takeIf { it in setOf("ni_magnet", "ni_metapodatkov", "ni_predvajljivo", "ni_prostora", "ni_podprto") } ?: "napaka"
                Daljinec.Izid(false, "Torrenta ni mogoce pretakati", koda = koda)
            }
            delo.koncano = android.os.SystemClock.elapsedRealtime()
        }, "safeer-magnet-pomoc").apply { isDaemon = true; start() }
        return Daljinec.Izid(true, "Pripravljam tok", JSONObject().put("pending", true))
    }

    fun seznam(context: Context): Daljinec.Izid {
        val a = try { MagnetMotor.seznamZacasnih(context) } catch (e: Throwable) { org.json.JSONArray() }
        return Daljinec.Izid(true, "${a.length()} prenosov", JSONObject().put("items", a))
    }

    fun odstrani(context: Context, p: JSONObject): Daljinec.Izid {
        if (!p.has("id")) return Daljinec.Izid(false, "Manjka prenos", koda = "ni_prenosa")
        val id = p.optInt("id", -1)
        val app = context.applicationContext
        // Odstranitev dela z diskom: ne na glavni niti. Odgovor je takojsen - naprava seznam prebere znova.
        if (MagnetMotor.seznamZacasnihIdji(app).none { it == id }) return Daljinec.Izid(false, "Tega prenosa ni mogoce odstraniti", koda = "ni_prenosa")
        Thread({ try { MagnetMotor.odstraniZacasnega(app, id) } catch (e: Throwable) { Log.i(TAG, "magnet.remove: ${e.message}") } }, "safeer-magnet-odstrani")
            .apply { isDaemon = true; start() }
        return Daljinec.Izid(true, "Odstranjeno z naprave")
    }
}
