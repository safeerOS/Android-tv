package si.safeer.tv.os

import android.content.Context
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPMessage
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility

/**
 * Kanali Sporocil (enako kot core/sporocila na racunalniku): e-posta (IMAP + SMTP) in Chatwoot.
 * Pogovor je oseba; sinhronizacija bere z "peek" in ne oznaci pisem kot prebrana.
 */
object SporocilaKanali {
    class NapakaPrijave : Exception()
    class ManjkaSkrivnost : Exception()

    private const val PRVIC = 200
    private const val TIMEOUT = "20000"

    /** Znani ponudniki: IMAP, SMTP, namig ("aplikacije" = geslo za aplikacije, "oauth" = geslo ni dovoljeno). */
    val PONUDNIKI = mapOf(
        "gmail.com" to Triple("imap.gmail.com", "smtp.gmail.com", "aplikacije"),
        "googlemail.com" to Triple("imap.gmail.com", "smtp.gmail.com", "aplikacije"),
        "yahoo.com" to Triple("imap.mail.yahoo.com", "smtp.mail.yahoo.com", "aplikacije"),
        "icloud.com" to Triple("imap.mail.me.com", "smtp.mail.me.com", "aplikacije"),
        "me.com" to Triple("imap.mail.me.com", "smtp.mail.me.com", "aplikacije"),
        "gmx.net" to Triple("imap.gmx.net", "mail.gmx.net", ""),
        "siol.net" to Triple("imap.siol.net", "mail.siol.net", ""),
        "t-2.net" to Triple("imap.t-2.net", "smtp.t-2.net", ""),
        "outlook.com" to Triple("outlook.office365.com", "smtp.office365.com", "oauth"),
        "hotmail.com" to Triple("outlook.office365.com", "smtp.office365.com", "oauth"),
        "live.com" to Triple("outlook.office365.com", "smtp.office365.com", "oauth"))

    fun streznik(naslov: String): Triple<String, String, String> {
        val d = naslov.substringAfterLast("@").lowercase(Locale.ROOT).trim()
        return PONUDNIKI[d] ?: Triple("imap.$d", "smtp.$d", "")
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'+00:00'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun cas(d: Date?): String = synchronized(iso) { iso.format(d ?: Date()) }

    // ------------------------------------------------------------------ e-posta
    private fun seja(imap: Boolean, port: Int): Session {
        val p = Properties()
        if (imap) {
            p["mail.store.protocol"] = "imaps"; p["mail.imaps.ssl.checkserveridentity"] = "true"
            p["mail.imaps.connectiontimeout"] = TIMEOUT; p["mail.imaps.timeout"] = TIMEOUT; p["mail.imaps.writetimeout"] = TIMEOUT
            p["mail.imaps.partialfetch"] = "true"; p["mail.imaps.fetchsize"] = "65536"
        } else if (port == 587 || port == 25) {
            p["mail.smtp.starttls.enable"] = "true"; p["mail.smtp.starttls.required"] = "true"; p["mail.smtp.auth"] = "true"
            p["mail.smtp.ssl.checkserveridentity"] = "true"
            p["mail.smtp.connectiontimeout"] = TIMEOUT; p["mail.smtp.timeout"] = TIMEOUT; p["mail.smtp.writetimeout"] = TIMEOUT
        } else {
            p["mail.smtps.auth"] = "true"; p["mail.smtps.ssl.checkserveridentity"] = "true"
            p["mail.smtps.connectiontimeout"] = TIMEOUT; p["mail.smtps.timeout"] = TIMEOUT; p["mail.smtps.writetimeout"] = TIMEOUT
        }
        return Session.getInstance(p)
    }

    private fun geslo(c: Context, k: SporocilaShramba.Kanal): String =
        SporocilaSkrivnosti.preberi(c, k.vrsta + ":" + k.id) ?: throw ManjkaSkrivnost()

    /** Preveri prijavo IMAP (ob dodajanju kanala). */
    fun preveriEposto(n: JSONObject, geslo: String) {
        val store = seja(true, n.optInt("imap_vrata", 993)).getStore("imaps")
        try {
            store.connect(n.getString("imap"), n.optInt("imap_vrata", 993), n.optString("uporabnik", n.getString("naslov")), geslo)
        } catch (_: AuthenticationFailedException) { throw NapakaPrijave() }
        finally { try { store.close() } catch (_: Throwable) {} }
    }

    private fun besedilo(p: Part, globina: Int = 0): Pair<String, Boolean> {
        if (globina > 6) return "" to false
        if (Part.ATTACHMENT.equals(p.disposition, true)) return "" to false
        if (p.isMimeType("text/plain")) return (p.content as? String ?: "") to true
        if (p.isMimeType("text/html")) return brezHtml(p.content as? String ?: "") to false
        if (p.isMimeType("multipart/*")) {
            val mp = p.content as Multipart
            var html = ""
            for (i in 0 until mp.count) {
                val (b, plain) = besedilo(mp.getBodyPart(i), globina + 1)
                if (plain && b.isNotBlank()) return b to true
                if (html.isBlank()) html = b
            }
            return html to false
        }
        return "" to false
    }

    fun brezHtml(h: String): String = h
        .replace(Regex("(?is)<(script|style)\\b.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n").replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()

    fun brezCitata(b: String): String {
        val vrstice = ArrayList<String>()
        for (v in b.lines()) {
            if (Regex("^\\s*(On|Dne|Am|Le|El|Il)\\b.{0,200}(wrote|napisal|schrieb|écrit|escribió|scritto)", RegexOption.IGNORE_CASE).containsMatchIn(v)) break
            if (v.trimStart().startsWith(">")) continue
            vrstice.add(v)
        }
        return vrstice.joinToString("\n").trim().ifBlank { b.trim() }
    }

    private fun sinhronizirajEposto(c: Context, s: SporocilaShramba, k: SporocilaShramba.Kanal) {
        val n = k.nastavitve
        val st = s.stanjeKanala(k.id)
        val niti = st.optJSONObject("niti") ?: JSONObject()
        var zadnji = st.optLong("zadnji_uid", 0L)
        val store = seja(true, n.optInt("imap_vrata", 993)).getStore("imaps")
        try {
            store.connect(n.getString("imap"), n.optInt("imap_vrata", 993), n.optString("uporabnik", n.getString("naslov")), geslo(c, k))
        } catch (_: AuthenticationFailedException) { throw NapakaPrijave() }
        try {
            val mapa = store.getFolder("INBOX") as IMAPFolder
            mapa.open(Folder.READ_ONLY)
            val sporocila: Array<Message> = if (zadnji <= 0) {
                val vseh = mapa.messageCount
                if (vseh == 0) emptyArray() else mapa.getMessages(maxOf(1, vseh - PRVIC + 1), vseh)
            } else mapa.getMessagesByUID(zadnji + 1, UIDFolder.LASTUID)
            val fp = FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID) }
            mapa.fetch(sporocila, fp)
            val lasten = n.getString("naslov").lowercase(Locale.ROOT)
            for (m in sporocila.sortedBy { mapa.getUID(it) }) {
                val uid = mapa.getUID(m)
                if (uid <= zadnji) continue
                (m as? IMAPMessage)?.peek = true
                val od = (m.from?.firstOrNull() as? InternetAddress)
                val komu = (m.getRecipients(Message.RecipientType.TO)?.firstOrNull() as? InternetAddress)
                val moje = od?.address?.lowercase(Locale.ROOT) == lasten
                val oseba = ((if (moje) komu else od)?.address ?: "neznan").lowercase(Locale.ROOT)
                val ime = ((if (moje) komu else od)?.personal ?: "")
                val zadeva = try { MimeUtility.decodeText(m.subject ?: "") } catch (_: Throwable) { m.subject ?: "" }
                val telo = try { brezCitata(besedilo(m).first).take(20000) } catch (_: Throwable) { "" }
                val cas = cas(m.sentDate ?: m.receivedDate)
                val prebrano = m.flags.contains(Flags.Flag.SEEN)
                s.shraniSporocilo(k.id, SporocilaShramba.Sporocilo("uid:$uid", oseba, if (moje) "ven" else "noter", telo, cas))
                s.posodobiPogovor(SporocilaShramba.Pogovor(oseba, k.id, oseba, ime, zadeva, telo.replace("\n", " ").take(240),
                    if (!moje && !prebrano) 1 else 0, cas), pristej = true)
                val nit = niti.optJSONObject(oseba) ?: JSONObject()
                val uidi = nit.optJSONArray("uidi") ?: JSONArray()
                uidi.put(uid); while (uidi.length() > 50) uidi.remove(0)
                nit.put("zadeva", zadeva).put("message_id", (m as? MimeMessage)?.messageID ?: "").put("uidi", uidi)
                niti.put(oseba, nit)
                zadnji = maxOf(zadnji, uid)
            }
            mapa.close(false)
        } finally { try { store.close() } catch (_: Throwable) {} }
        s.shraniStanjeKanala(k.id, JSONObject().put("zadnji_uid", zadnji).put("niti", niti))
    }

    private fun posljiEposto(c: Context, s: SporocilaShramba, k: SporocilaShramba.Kanal, oseba: String, besedilo: String): SporocilaShramba.Sporocilo {
        val n = k.nastavitve
        val nit = s.stanjeKanala(k.id).optJSONObject("niti")?.optJSONObject(oseba) ?: JSONObject()
        val port = n.optInt("smtp_vrata", 465)
        val seja = seja(false, port)
        val msg = MimeMessage(seja)
        msg.setFrom(InternetAddress(n.getString("naslov")))
        msg.setRecipient(Message.RecipientType.TO, InternetAddress(oseba))
        val zadeva = nit.optString("zadeva")
        msg.setSubject(if (zadeva.lowercase(Locale.ROOT).startsWith("re:")) zadeva else if (zadeva.isNotBlank()) "Re: $zadeva" else "Sporočilo", "UTF-8")
        nit.optString("message_id").takeIf { it.isNotBlank() }?.let { msg.setHeader("In-Reply-To", it); msg.setHeader("References", it) }
        msg.setText(besedilo, "UTF-8")
        msg.sentDate = Date()
        val protokol = if (port == 587 || port == 25) "smtp" else "smtps"
        val t = seja.getTransport(protokol)
        try {
            t.connect(n.getString("smtp"), port, n.optString("uporabnik", n.getString("naslov")), geslo(c, k))
        } catch (_: AuthenticationFailedException) { throw NapakaPrijave() }
        try { msg.saveChanges(); t.sendMessage(msg, msg.allRecipients) } finally { t.close() }
        return SporocilaShramba.Sporocilo("mid:" + (msg.messageID ?: System.nanoTime().toString()), oseba, "ven", besedilo, cas(Date()))
    }

    private fun oznaciEposto(c: Context, s: SporocilaShramba, k: SporocilaShramba.Kanal, oseba: String) {
        val uidi = s.stanjeKanala(k.id).optJSONObject("niti")?.optJSONObject(oseba)?.optJSONArray("uidi") ?: return
        if (uidi.length() == 0) return
        val n = k.nastavitve
        val store = seja(true, n.optInt("imap_vrata", 993)).getStore("imaps")
        store.connect(n.getString("imap"), n.optInt("imap_vrata", 993), n.optString("uporabnik", n.getString("naslov")), geslo(c, k))
        try {
            val mapa = store.getFolder("INBOX") as IMAPFolder
            mapa.open(Folder.READ_WRITE)
            val sez = (0 until uidi.length()).mapNotNull { mapa.getMessageByUID(uidi.getLong(it)) }.toTypedArray()
            if (sez.isNotEmpty()) mapa.setFlags(sez, Flags(Flags.Flag.SEEN), true)
            mapa.close(false)
        } finally { try { store.close() } catch (_: Throwable) {} }
    }

    // ------------------------------------------------------------------ Chatwoot (uradni Application API)
    private fun chatwoot(c: Context, k: SporocilaShramba.Kanal, pot: String, metoda: String = "GET", telo: JSONObject? = null): Any? {
        val n = k.nastavitve
        val url = URL(n.getString("url").trimEnd('/') + "/api/v1/accounts/" + n.getInt("account_id") + pot)
        require(url.protocol == "https")
        val povezava = url.openConnection() as HttpURLConnection
        povezava.connectTimeout = 15000; povezava.readTimeout = 15000; povezava.requestMethod = metoda
        povezava.setRequestProperty("api_access_token", geslo(c, k))
        povezava.setRequestProperty("Accept", "application/json")
        if (telo != null) {
            povezava.doOutput = true; povezava.setRequestProperty("Content-Type", "application/json")
            povezava.outputStream.use { it.write(telo.toString().toByteArray()) }
        }
        if (povezava.responseCode == 401) throw NapakaPrijave()
        val besedilo = povezava.inputStream.bufferedReader().use { it.readText() }
        return if (besedilo.isBlank()) null else if (besedilo.trimStart().startsWith("[")) JSONArray(besedilo) else JSONObject(besedilo)
    }

    private fun seznam(o: Any?): JSONArray = when (o) {
        is JSONArray -> o
        is JSONObject -> o.optJSONObject("data")?.optJSONArray("payload") ?: o.optJSONArray("payload") ?: JSONArray()
        else -> JSONArray()
    }

    private fun casIz(v: Any?): String = when (v) {
        is Number -> cas(Date(v.toLong() * 1000))
        is String -> v
        else -> cas(null)
    }

    fun preveriChatwoot(c: Context, k: SporocilaShramba.Kanal) { chatwoot(c, k, "/conversations?status=all&page=1") }

    private fun sinhronizirajChatwoot(c: Context, s: SporocilaShramba, k: SporocilaShramba.Kanal) {
        val pogovori = seznam(chatwoot(c, k, "/conversations?status=all&page=1"))
        for (i in 0 until pogovori.length()) {
            val p = pogovori.getJSONObject(i)
            val id = p.optString("id")
            val stik = p.optJSONObject("meta")?.optJSONObject("sender") ?: JSONObject()
            val zadnje = p.optJSONObject("last_non_activity_message") ?: JSONObject()
            s.posodobiPogovor(SporocilaShramba.Pogovor(id, k.id, stik.optString("email").ifBlank { stik.optString("phone_number") },
                stik.optString("name"), "", zadnje.optString("content"), p.optInt("unread_count"), casIz(zadnje.opt("created_at"))), pristej = false)
            val sporocila = seznam(chatwoot(c, k, "/conversations/$id/messages"))
            for (j in 0 until sporocila.length()) {
                val m = sporocila.getJSONObject(j)
                if (m.optInt("message_type") > 1) continue      // dejavnosti in predloge niso sporocila
                s.shraniSporocilo(k.id, SporocilaShramba.Sporocilo(m.optString("id"), id,
                    if (m.optInt("message_type") == 1) "ven" else "noter", m.optString("content"), casIz(m.opt("created_at"))))
            }
        }
    }

    // ------------------------------------------------------------------ skupno
    fun sinhroniziraj(c: Context, s: SporocilaShramba) {
        for (k in s.kanali()) {
            if (k.vrsta == KlepetLinka.VRSTA) continue      // Safeer Chat pride sam po Linku
            try {
                when (k.vrsta) {
                    "email" -> sinhronizirajEposto(c, s, k)
                    "chatwoot" -> sinhronizirajChatwoot(c, s, k)
                }
                s.stanje(k.id, "povezan")
            } catch (e: Throwable) {
                s.stanje(k.id, razlog(e))
            }
        }
    }

    fun razlog(e: Throwable): String = when (e) {
        is ManjkaSkrivnost -> "napaka:geslo"
        is KlepetLinka.NapakaKlepeta -> if (e.koda == "ni_naprave" || e.koda == "meja") "napaka" else "napaka:omrezje"
        is NapakaPrijave, is AuthenticationFailedException -> "napaka:prijava"
        is java.io.IOException, is javax.mail.MessagingException -> "napaka:omrezje"
        else -> "napaka"
    }

    /** Vrne "queued", ce naprava v Linku ni povezana in bo sporocilo dobila kasneje, sicer "". */
    fun poslji(c: Context, s: SporocilaShramba, kanalId: String, pogovorId: String, besedilo: String): String {
        val k = s.kanali().first { it.id == kanalId }
        var izid = ""
        val sp = when (k.vrsta) {
            KlepetLinka.VRSTA -> KlepetLinka.poslji(c, pogovorId, besedilo).also { izid = it.second }.first
            "email" -> posljiEposto(c, s, k, pogovorId, besedilo)
            else -> {
                val o = chatwoot(c, k, "/conversations/$pogovorId/messages", "POST",
                    JSONObject().put("content", besedilo).put("message_type", "outgoing").put("private", false)) as? JSONObject
                SporocilaShramba.Sporocilo(o?.optString("id") ?: System.nanoTime().toString(), pogovorId, "ven", besedilo, cas(null))
            }
        }
        s.shraniSporocilo(kanalId, sp)
        s.posodobiZadnje(kanalId, pogovorId, besedilo, sp.cas)
        return if (izid == "queued") "queued" else ""
    }

    fun oznaciPrebrano(c: Context, s: SporocilaShramba, kanalId: String, pogovorId: String) {
        s.oznaciPrebrano(kanalId, pogovorId)
        val k = s.kanali().firstOrNull { it.id == kanalId } ?: return
        try {
            if (k.vrsta == KlepetLinka.VRSTA) return
            if (k.vrsta == "email") oznaciEposto(c, s, k, pogovorId)
            else chatwoot(c, k, "/conversations/$pogovorId/update_last_seen", "POST", JSONObject())
        } catch (_: Throwable) {}
    }
}
