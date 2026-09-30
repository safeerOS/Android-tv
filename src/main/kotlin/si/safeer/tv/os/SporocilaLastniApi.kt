package si.safeer.tv.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Date
import java.util.UUID

/**
 * Lastni API: uporabnik ima zeton svoje aplikacije.
 *  - Matrix (Element, Beeper, lastni Synapse): uradni Client-Server API v3 (whoami, /sync, send, receipt).
 *  - Telegram Bot: uradni Bot API (getMe, getUpdates, sendMessage). Bot prejema sporocila ljudi, ki mu pisejo.
 * Oba odprta, brez licenc. Zeton je v SporocilaSkrivnosti, stanje sinhronizacije v stanje_kanala (JSON).
 * Enaka shema kot core/sporocila/matrix.py in telegram_bot.py v Safeer OS (namizje).
 */
object SporocilaLastniApi {
    private const val NAJVEC_IMEN = 400

    private fun http(url: String, metoda: String, glave: Map<String, String>, telo: String?): Pair<Int, String> {
        val u = URL(url)
        require(u.protocol == "https" || u.host == "localhost" || u.host.startsWith("127.")) { "https" }
        val p = u.openConnection() as HttpURLConnection
        p.connectTimeout = 15000; p.readTimeout = 35000; p.requestMethod = metoda
        p.setRequestProperty("Accept", "application/json")
        for ((k, v) in glave) p.setRequestProperty(k, v)
        if (telo != null) {
            p.doOutput = true; p.setRequestProperty("Content-Type", "application/json")
            p.outputStream.use { it.write(telo.toByteArray()) }
        }
        val koda = p.responseCode
        val tok = if (koda >= 400) p.errorStream else p.inputStream
        val besedilo = tok?.bufferedReader()?.use { it.readText() }.orEmpty()
        return koda to besedilo
    }

    private fun zeton(c: Context, k: SporocilaShramba.Kanal): String =
        SporocilaSkrivnosti.preberi(c, k.vrsta + ":" + k.id) ?: throw SporocilaKanali.ManjkaSkrivnost()

    // ------------------------------------------------------------------ Matrix
    private fun matrix(c: Context, k: SporocilaShramba.Kanal, pot: String, metoda: String = "GET", telo: JSONObject? = null, zetonRocno: String? = null): JSONObject {
        val (koda, b) = http(k.nastavitve.getString("streznik").trimEnd('/') + "/_matrix/client/v3" + pot, metoda,
            mapOf("Authorization" to "Bearer " + (zetonRocno ?: zeton(c, k))), telo?.toString())
        if (koda == 401 || koda == 403) throw SporocilaKanali.NapakaPrijave()
        if (koda >= 400) throw java.io.IOException("matrix $koda")
        return if (b.isBlank()) JSONObject() else JSONObject(b)
    }

    /** Preveri streznik + zeton; vrne uporabnikov ID (@ime:streznik). */
    fun preveriMatrix(c: Context, k: SporocilaShramba.Kanal, zeton: String): String {
        val id = matrix(c, k, "/account/whoami", zetonRocno = zeton).optString("user_id")
        if (id.isBlank()) throw SporocilaKanali.NapakaPrijave()
        return id
    }

    private val filterMatrix = JSONObject().put("room", JSONObject().put("timeline", JSONObject().put("limit", 30))
        .put("ephemeral", JSONObject().put("types", JSONArray())).put("state", JSONObject().put("types", JSONArray(listOf("m.room.name", "m.room.member", "m.room.canonical_alias"))).put("lazy_load_members", true)))
        .put("presence", JSONObject().put("types", JSONArray())).put("account_data", JSONObject().put("types", JSONArray(listOf("m.direct")))).toString()

    fun sinhronizirajMatrix(c: Context, s: SporocilaShramba, k: SporocilaShramba.Kanal) {
        val stanje = s.stanjeKanala(k.id)
        val jaz = k.nastavitve.optString("uporabnik")
        val zacetni = stanje.optString("since").isBlank()
        enSyncMatrix(c, s, k, stanje, jaz)
        // Synapse zacetni /sync nekaj minut streze iz predpomnilnika; inkrementalni takoj prinese vmesno (preverjeno na Synapse).
        if (zacetni && stanje.optString("since").isNotBlank()) enSyncMatrix(c, s, k, stanje, jaz)
        s.shraniStanjeKanala(k.id, stanje)
    }

    private fun enSyncMatrix(c: Context, s: SporocilaShramba, k: SporocilaShramba.Kanal, stanje: JSONObject, jaz: String) {
        val imena = stanje.optJSONObject("imena") ?: JSONObject().also { stanje.put("imena", it) }
        val sobeOsebe = stanje.optJSONObject("sobe") ?: JSONObject().also { stanje.put("sobe", it) }
        var pot = "/sync?timeout=0&filter=" + URLEncoder.encode(filterMatrix, "UTF-8")
        stanje.optString("since").takeIf { it.isNotBlank() }?.let { pot += "&since=" + URLEncoder.encode(it, "UTF-8") }
        val odg = matrix(c, k, pot)
        stanje.put("since", odg.optString("next_batch", stanje.optString("since")))
        val direktne = HashMap<String, String>()
        val ad = odg.optJSONObject("account_data")?.optJSONArray("events")
        for (i in 0 until (ad?.length() ?: 0)) {
            val d = ad!!.getJSONObject(i); if (d.optString("type") != "m.direct") continue
            val vsebina = d.optJSONObject("content") ?: continue
            for (uid in vsebina.keys()) { val sobe = vsebina.optJSONArray(uid) ?: continue; for (j in 0 until sobe.length()) direktne[sobe.getString(j)] = uid }
        }
        val join = odg.optJSONObject("rooms")?.optJSONObject("join") ?: JSONObject()
        for (rid in join.keys()) {
            val soba = join.getJSONObject(rid)
            val info = sobeOsebe.optJSONObject(rid) ?: JSONObject().put("ime", "").put("clani", JSONObject()).also { sobeOsebe.put(rid, it) }
            val clani = info.optJSONObject("clani") ?: JSONObject().also { info.put("clani", it) }
            val dogodki = ArrayList<JSONObject>()
            soba.optJSONObject("state")?.optJSONArray("events")?.let { for (i in 0 until it.length()) dogodki.add(it.getJSONObject(i)) }
            val casovnica = soba.optJSONObject("timeline")?.optJSONArray("events")
            for (i in 0 until (casovnica?.length() ?: 0)) dogodki.add(casovnica!!.getJSONObject(i))
            for (d in dogodki) {
                val vsebina = d.optJSONObject("content") ?: JSONObject()
                when (d.optString("type")) {
                    "m.room.name" -> vsebina.optString("name").takeIf { it.isNotBlank() }?.let { info.put("ime", it) }
                    "m.room.member" -> {
                        val uid = d.optString("state_key"); if (uid.isBlank()) continue
                        if (vsebina.optString("membership") in listOf("join", "invite")) {
                            val ime = vsebina.optString("displayname").ifBlank { uid }; clani.put(uid, ime); if (imena.length() < NAJVEC_IMEN) imena.put(uid, ime)
                        } else clani.remove(uid)
                    }
                }
            }
            val drugi = clani.keys().asSequence().filter { it != jaz }.toList()
            val oseba = direktne[rid] ?: if (drugi.size == 1) drugi[0] else rid
            val imeSobe = info.optString("ime").ifBlank { if (oseba == rid) drugi.take(3).joinToString(", ") { clani.optString(it, it) }.ifBlank { rid } else imena.optString(oseba, clani.optString(oseba, oseba)) }
            info.put("oseba", oseba)
            var zadnje: SporocilaShramba.Sporocilo? = null; var novihNoter = 0; var zadnjiEvent = info.optString("zadnji_event")
            for (i in 0 until (casovnica?.length() ?: 0)) {
                val d = casovnica!!.getJSONObject(i); if (d.optString("type") != "m.room.message") continue
                val vsebina = d.optJSONObject("content") ?: JSONObject()
                var besedilo = vsebina.optString("body")
                if (vsebina.optString("msgtype") in listOf("m.image", "m.file", "m.video", "m.audio")) besedilo = "📎 $besedilo"
                val od = d.optString("sender"); val ven = od == jaz
                val sp = SporocilaShramba.Sporocilo(d.optString("event_id"), rid, if (ven) "ven" else "noter", besedilo,
                    SporocilaKanali.cas(Date(d.optLong("origin_server_ts"))))
                s.shraniSporocilo(k.id, sp); zadnje = sp; if (!ven) novihNoter++; zadnjiEvent = sp.id
            }
            info.put("zadnji_event", zadnjiEvent)
            val prej = s.pogovori().firstOrNull { it.kanalId == k.id && it.id == rid }
            s.posodobiPogovor(SporocilaShramba.Pogovor(rid, k.id, oseba, imeSobe, if (oseba == rid) imeSobe else "",
                zadnje?.besedilo ?: prej?.zadnje ?: "", novihNoter, zadnje?.cas ?: prej?.cas ?: ""), pristej = true)
        }
        val leave = odg.optJSONObject("rooms")?.optJSONObject("leave") ?: JSONObject()
        for (rid in leave.keys()) sobeOsebe.remove(rid)
    }

    fun posljiMatrix(c: Context, k: SporocilaShramba.Kanal, soba: String, besedilo: String): SporocilaShramba.Sporocilo {
        val txn = "safeer-" + UUID.randomUUID().toString().replace("-", "")
        val o = matrix(c, k, "/rooms/" + URLEncoder.encode(soba, "UTF-8") + "/send/m.room.message/" + txn, "PUT",
            JSONObject().put("msgtype", "m.text").put("body", besedilo))
        return SporocilaShramba.Sporocilo(o.optString("event_id").ifBlank { txn }, soba, "ven", besedilo, SporocilaKanali.cas(null))
    }

    fun oznaciPrebranoMatrix(c: Context, s: SporocilaShramba, k: SporocilaShramba.Kanal, soba: String) {
        val zadnji = s.stanjeKanala(k.id).optJSONObject("sobe")?.optJSONObject(soba)?.optString("zadnji_event").orEmpty()
        if (zadnji.isNotBlank()) matrix(c, k, "/rooms/" + URLEncoder.encode(soba, "UTF-8") + "/receipt/m.read/" + URLEncoder.encode(zadnji, "UTF-8"), "POST", JSONObject())
    }

    // ------------------------------------------------------------------ Telegram Bot
    private fun telegram(c: Context, k: SporocilaShramba.Kanal, metoda: String, telo: JSONObject, zetonRocno: String? = null): Any? {
        val (koda, b) = http("https://api.telegram.org/bot" + (zetonRocno ?: zeton(c, k)) + "/" + metoda, "POST", emptyMap(), telo.toString())
        if (koda == 401 || koda == 404) throw SporocilaKanali.NapakaPrijave()
        val o = if (b.isBlank()) JSONObject() else JSONObject(b)
        if (!o.optBoolean("ok")) throw java.io.IOException(o.optString("description", "telegram"))
        return o.opt("result")
    }

    /** Preveri zeton bota; vrne @uporabnisko ime bota. */
    fun preveriTelegram(c: Context, k: SporocilaShramba.Kanal, zeton: String): String {
        val bot = telegram(c, k, "getMe", JSONObject(), zeton) as? JSONObject ?: throw SporocilaKanali.NapakaPrijave()
        if (bot.optLong("id") == 0L) throw SporocilaKanali.NapakaPrijave()
        k.nastavitve.put("bot_id", bot.optLong("id"))
        return bot.optString("username")
    }

    private fun imeUporabnika(u: JSONObject): String =
        listOf(u.optString("first_name"), u.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { u.optString("username").takeIf { it.isNotBlank() }?.let { "@$it" } ?: u.optLong("id").toString() }

    fun sinhronizirajTelegram(c: Context, s: SporocilaShramba, k: SporocilaShramba.Kanal) {
        val stanje = s.stanjeKanala(k.id)
        val botId = k.nastavitve.optLong("bot_id")
        var offset = stanje.optLong("offset")
        val posodobitve = telegram(c, k, "getUpdates", JSONObject().put("offset", offset).put("timeout", 0)
            .put("allowed_updates", JSONArray(listOf("message", "edited_message")))) as? JSONArray ?: JSONArray()
        val novi = HashMap<String, Int>(); val zadnja = HashMap<String, SporocilaShramba.Sporocilo>(); val klepeti = HashMap<String, Triple<String, String, String>>()
        for (i in 0 until posodobitve.length()) {
            val u = posodobitve.getJSONObject(i)
            offset = maxOf(offset, u.optLong("update_id") + 1)
            val m = u.optJSONObject("message") ?: u.optJSONObject("edited_message") ?: continue
            val klepet = m.optJSONObject("chat") ?: continue
            val cid = klepet.optLong("id").toString(); if (cid == "0") continue
            val od = m.optJSONObject("from") ?: JSONObject()
            val zasebni = klepet.optString("type") == "private"
            val oseba = if (zasebni) od.optLong("id").toString().ifBlank { cid } else "skupina:$cid"
            val ime = if (zasebni) imeUporabnika(od) else klepet.optString("title").ifBlank { cid }
            klepeti[cid] = Triple(oseba, ime, if (zasebni) "" else ime)
            var besedilo = m.optString("text").ifBlank { m.optString("caption") }
            for (vrsta in listOf("document", "photo", "video", "audio", "voice")) if (m.has(vrsta) && besedilo.isBlank()) besedilo = "📎 $vrsta"
            if (!zasebni) besedilo = imeUporabnika(od) + ": " + besedilo
            val lasten = botId != 0L && od.optLong("id") == botId
            val sp = SporocilaShramba.Sporocilo(m.optLong("message_id").toString(), cid, if (lasten) "ven" else "noter", besedilo,
                SporocilaKanali.cas(Date(m.optLong("date") * 1000)))
            s.shraniSporocilo(k.id, sp); zadnja[cid] = sp
            if (!lasten) novi[cid] = (novi[cid] ?: 0) + 1
        }
        for ((cid, info) in klepeti) {
            val z = zadnja[cid]!!
            s.posodobiPogovor(SporocilaShramba.Pogovor(cid, k.id, info.first, info.second, info.third, z.besedilo, novi[cid] ?: 0, z.cas), pristej = true)
        }
        stanje.put("offset", offset); s.shraniStanjeKanala(k.id, stanje)
    }

    fun posljiTelegram(c: Context, k: SporocilaShramba.Kanal, klepet: String, besedilo: String): SporocilaShramba.Sporocilo {
        val o = telegram(c, k, "sendMessage", JSONObject().put("chat_id", klepet.toLongOrNull() ?: klepet).put("text", besedilo)) as? JSONObject ?: JSONObject()
        return SporocilaShramba.Sporocilo(o.optLong("message_id").takeIf { it != 0L }?.toString() ?: System.nanoTime().toString(), klepet, "ven", besedilo,
            if (o.optLong("date") != 0L) SporocilaKanali.cas(Date(o.optLong("date") * 1000)) else SporocilaKanali.cas(null))
    }
}
