package si.safeer.tv.os

import android.content.Context
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Naloga 87 (57b): predpomnilnik metapodatkov na drugi napravi v Safeer Linku (racunalnik s Safeer OS / Control, tablica,
 * telefon), ki se javi z zmoznostjo [PredpomnilnikPravila.ZMOZNOST_LINK]. Brez novih vrat: ukazi gredo po obstojeci povezavi
 * s srediscem (control.command, [LinkOdjemalec.ukaz]) in, kjer naprava zascito zna, po preverjeni sifrirani seji (ZascitaLinka).
 *
 * Protokol (params ukaza; odgovor control.result {ok, message, data}):
 * - `cache.get` {key} -> data {found, time, value (base64)}; key = »vrsta/odtis SHA-1« ([PredpomnilnikPravila.kljucLink]).
 * - `cache.put` {key, time, ttl_ms, value (base64)} -> data {stored}; naprava sama omeji velikost (LRU) in TTL.
 * - `cache.stat` {} -> data {free, used, limit} v bajtih (za izbiro mesta).
 *
 * Branje caka najvec [PredpomnilnikPravila.LINK_ROK_MS] (300 ms), potem notranji ali omrezje; po 3 neodgovorih 60 s mirujemo
 * ([PredpomnilnikPravila.linkSme]). Vsebina zasebnih dodatkov in kategorij nikoli ne gre na drugo napravo ([PredpomnilnikPravila.smeNaLink]).
 */
object LinkPredpomnilnik {
    @Volatile private var napak = 0
    @Volatile private var napakaOb = 0L
    private val zapisovVTeku = AtomicInteger(0)

    /** Izbrana naprava v Linku ali null (mesto ni naprava v Linku). */
    fun naprava(c: Context): String? = UsbPredpomnilnik.mesto(c).takeIf { it.nacin == PredpomnilnikPravila.Nacin.LINK }?.naprava

    private fun povezava(c: Context): LinkUpravitelj? =
        try { LinkUpravitelj.pridobi(c).takeIf { it.povezan && !it.jeKrajevni() } } catch (_: Throwable) { null }

    private fun napaka() { napak++; napakaOb = System.currentTimeMillis() }

    /** (cas zapisa, bajti) ali null - nikoli dlje od 300 ms, nikoli z glavne niti. */
    fun beri(c: Context, vrsta: String, odtis: String): Pair<Long, ByteArray>? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val id = naprava(c) ?: return null
        val kljuc = PredpomnilnikPravila.kljucLink(vrsta, odtis) ?: return null
        if (!PredpomnilnikPravila.linkSme(napak, napakaOb, System.currentTimeMillis())) return null
        val link = povezava(c) ?: return null
        val izid = AtomicReference<JSONObject?>(null)
        val gotovo = CountDownLatch(1)
        link.odjemalec.ukaz(id, "cache.get", JSONObject().put("key", kljuc), potekMs = 2_000) { r, _ -> izid.set(r); gotovo.countDown() }
        val pravocasno = try { gotovo.await(PredpomnilnikPravila.LINK_ROK_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        val r = izid.get()
        if (!pravocasno || r == null) { napaka(); return null }
        napak = 0
        val d = r.optJSONObject("data") ?: return null
        if (!r.optBoolean("ok") || !d.optBoolean("found")) return null
        val b = try { Base64.decode(d.optString("value"), Base64.NO_WRAP) } catch (_: Throwable) { return null }
        if (b.isEmpty() || b.size > PredpomnilnikPravila.LINK_NAJVEC_BAJTOV) return null
        return d.optLong("time") to b
    }

    /** Zapis na napravo v ozadju (brez cakanja); najvec [PredpomnilnikPravila.LINK_ZAPISOV_HKRATI] hkrati, ostale izpustimo. */
    fun shrani(c: Context, vrsta: String, odtis: String, bajti: ByteArray, ustvarjeno: Long, zasebno: Boolean) {
        if (!PredpomnilnikPravila.smeNaLink(vrsta, bajti.size, zasebno)) return
        val id = naprava(c) ?: return
        val kljuc = PredpomnilnikPravila.kljucLink(vrsta, odtis) ?: return
        if (!PredpomnilnikPravila.linkSme(napak, napakaOb, System.currentTimeMillis())) return
        val link = povezava(c) ?: return
        if (zapisovVTeku.incrementAndGet() > PredpomnilnikPravila.LINK_ZAPISOV_HKRATI) { zapisovVTeku.decrementAndGet(); return }
        val params = JSONObject().put("key", kljuc).put("time", ustvarjeno).put("ttl_ms", PredpomnilnikPravila.ttl(vrsta))
            .put("value", Base64.encodeToString(bajti, Base64.NO_WRAP))
        try { link.odjemalec.ukaz(id, "cache.put", params, potekMs = 5_000) { _, _ -> zapisovVTeku.decrementAndGet() } }
        catch (_: Throwable) { zapisovVTeku.decrementAndGet() }
    }

    /** Za izbiro mesta: prosti prostor naprave [id] (bajti) ali null (ne odgovori v 1,5 s). Odgovor na glavni niti. */
    fun stat(c: Context, id: String, naprej: (Long?) -> Unit) {
        val link = povezava(c) ?: run { naprej(null); return }
        link.odjemalec.ukaz(id, "cache.stat", JSONObject(), potekMs = 1_500) { r, _ ->
            naprej(r?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")?.optLong("free", -1L)?.takeIf { it >= 0 })
        }
    }

    /** Naprave v Linku z vlogo predpomnilnik (id, ime), brez te naprave. Brez omrezja. */
    fun naprave(c: Context): List<Pair<String, String>> = try {
        val link = LinkUpravitelj.pridobi(c)
        val jaz = Identiteta.id(c)
        PredpomnilnikPravila.napraveZaIzbiro(link.naprave.map { Triple(it.id, it.ime, it.zmoznosti) }, jaz)
    } catch (_: Throwable) { emptyList() }
}
