package si.safeer.tv.os

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import si.safeer.tv.R
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Safeer Chat: sporocila med napravami v Safeer Linku (telefon, tablica, TV, racunalnik).
 * Sredisce jih le posreduje (in pocaka, ce naprava ni povezana); hrani jih vsaka naprava sama
 * v Sporocilih, kot kanal "Safeer Link", en pogovor na napravo.
 */
object KlepetLinka {
    const val KANAL = "safeer-link"
    const val VRSTA = "safeer"
    const val ZMOZNOST = "chat"
    private const val OBVESTILA = "safeer_sporocila"

    /** Zaslon Sporocila se prijavi, da ob novem sporocilu takoj osvezi prikaz. */
    val poslusalci = CopyOnWriteArraySet<() -> Unit>()
    /** Pogovor, ki je trenutno odprt na zaslonu (zanj ne pokazemo obvestila). */
    @Volatile var odprtPogovor: String? = null

    fun zagotoviKanal(s: SporocilaShramba) {
        if (s.kanali().none { it.id == KANAL }) s.dodajKanal(SporocilaShramba.Kanal(KANAL, VRSTA, "Safeer Link", "povezan", JSONObject()))
    }

    /** Cas posiljatelja v nasi obliki (UTC, do sekunde); ob nerazumljivem casu cas prejema. */
    fun cas(iso: String): String {
        val t = iso.trim()
        return if (Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d.*""").matches(t) && (t.endsWith("Z") || t.contains("+00:00")))
            t.take(19) + "+00:00" else SporocilaKanali.cas(null)
    }

    /** optString vrne "null" za JSON null; tega ne smemo vzeti za naslov naprave. */
    private fun niz(o: JSONObject, kljuc: String): String = if (o.isNull(kljuc)) "" else o.optString(kljuc).trim()

    /** chat.send s sredisca (na niti povezave). */
    fun prejmi(c: Context, json: JSONObject) {
        val telo = json.optJSONObject("payload") ?: return
        val besedilo = telo.optString("text").take(16 * 1024)
        // Pogovor je fizicna naprava (kljuc), ce jo hub pozna - ne glede na to, katera aplikacija na njej pise.
        val od = niz(json, "sender_device").ifBlank { niz(json, "sender") }
        if (besedilo.isBlank() || od.isBlank()) return
        // Ime, kot ga uporabnik vidi v seznamu naprav (vzdevek), sicer ime, ki ga je dal hub.
        val posiljatelj = niz(json, "sender")
        val izSeznama = try {
            LinkUpravitelj.pridobi(c).naprave.firstOrNull { it.id == posiljatelj || (it.naprava.isNotBlank() && it.naprava == od) }?.ime
        } catch (_: Throwable) { null }
        val cas = cas(telo.optString("created_at"))
        val s = SporocilaShramba(c)
        val znano = s.pogovori().firstOrNull { it.kanalId == KANAL && it.id == od }?.ime
        val ime = izSeznama?.takeIf { it.isNotBlank() } ?: znano?.takeIf { it.isNotBlank() } ?: niz(json, "sender_name").ifBlank { od }
        zagotoviKanal(s)
        val id = "chat:" + json.optString("id").ifBlank { System.nanoTime().toString() }
        if (s.sporocila(KANAL, od).any { it.id == id }) return          // isto sporocilo dvakrat (ponovna dostava)
        val odprt = odprtPogovor == od
        s.shraniSporocilo(KANAL, SporocilaShramba.Sporocilo(id, od, "noter", besedilo, cas))
        s.posodobiPogovor(SporocilaShramba.Pogovor(od, KANAL, od, ime, "", besedilo.replace('\n', ' ').take(240),
            if (odprt) 0 else 1, cas), pristej = true)
        for (p in poslusalci) try { p() } catch (_: Throwable) { }
        if (!odprt) obvesti(c, od, ime, besedilo)
    }

    /** Nova naprava v pogovoru (uporabnik ji pise prvi): pogovor obstaja, preden je kaj poslano. */
    fun zacniPogovor(s: SporocilaShramba, napravaId: String, ime: String): SporocilaShramba.Pogovor {
        zagotoviKanal(s)
        s.pogovori().firstOrNull { it.kanalId == KANAL && it.id == napravaId }?.let { obstojeci ->
            if (ime.isBlank() || ime == obstojeci.ime) return obstojeci
            // Uporabnik je izbral napravo po imenu iz seznama: pogovor naj ga nosi tudi naprej.
            val p = obstojeci.copy(ime = ime, neprebrano = 0)
            s.posodobiPogovor(p, pristej = true)
            return p
        }
        val p = SporocilaShramba.Pogovor(napravaId, KANAL, napravaId, ime, "", "", 0, SporocilaKanali.cas(null))
        s.posodobiPogovor(p, pristej = false)
        return p
    }

    /** Poslje po Linku; vrne sporocilo za lokalni zapis ali vrze izjemo (brez povezave, zavrnjeno). */
    fun poslji(c: Context, pogovorId: String, besedilo: String): Pair<SporocilaShramba.Sporocilo, String> {
        val cas = SporocilaKanali.cas(null)
        val izid = LinkUpravitelj.pridobi(c).odjemalec.posljiKlepet(pogovorId, besedilo, cas)
        if (izid != "accepted" && izid != "queued") throw NapakaKlepeta(izid)
        return SporocilaShramba.Sporocilo("chat:ven:" + System.nanoTime(), pogovorId, "ven", besedilo, cas) to izid
    }

    class NapakaKlepeta(val koda: String) : Exception(koda)

    private fun obvesti(c: Context, od: String, ime: String, besedilo: String) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(OBVESTILA) == null) {
                nm.createNotificationChannel(NotificationChannel(OBVESTILA, c.getString(R.string.os_meni_sporocila),
                    NotificationManager.IMPORTANCE_DEFAULT))
            }
            val namera = Intent(c, SporocilaActivity::class.java).putExtra(SporocilaActivity.EXTRA_POGOVOR, od)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(c, od.hashCode(), namera, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val g = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, OBVESTILA) else android.app.Notification.Builder(c)
            val obvestilo = g.setSmallIcon(R.drawable.os_ikona_sporocila)
                .setContentTitle(ime).setContentText(besedilo.take(200))
                .setStyle(android.app.Notification.BigTextStyle().bigText(besedilo.take(1000)))
                .setContentIntent(pi).setAutoCancel(true).build()
            nm.notify(od.hashCode(), obvestilo)
        } catch (_: Throwable) {
            // Brez dovoljenja za obvestila sporocilo vseeno ostane v Sporocilih.
        }
    }
}
