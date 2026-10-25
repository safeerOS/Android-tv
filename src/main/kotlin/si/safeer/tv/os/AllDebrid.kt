package si.safeer.tv.os

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Naloga 128: AllDebrid ([DebridPravila], dokumentacija docs.alldebrid.com, prebrano 24. 10. 2026).
 * magnet/upload (`ready`) -> magnet/files (drevo n/s/l/e) -> link/unlock (link ali delayed -> link/delayed).
 * Posebne tocke za predpomnjenje ni: magnet z `ready = false` pri »samo predpomnjeno« izbrisemo (magnet/delete).
 */
object AllDebrid {
    private const val TAG = "SafeerAllDebrid"
    private const val O = DebridPravila.AD_OSNOVA

    private fun napaka(o: Debrid.Odgovor, e: JSONObject? = o.json?.optJSONObject("error")): TorBox.Izid =
        TorBox.Izid(napaka = DebridPravila.adVrstaNapake(o.koda, e?.optString("code")), opis = e?.optString("message").orEmpty().take(200),
            ponudnik = DebridPravila.ALLDEBRID)

    private fun uspeh(o: Debrid.Odgovor) = o.uspeh && o.json?.optString("status") == "success"

    /** Brisanje gre ven tudi po preklicu: oznako prekinitve za cas klica odstranimo in jo nato vrnemo. */
    private fun izbrisi(kljuc: String, id: Long) {
        val prekinjen = Thread.interrupted()
        try { Debrid.klic("$O/magnet/delete", kljuc, "POST", listOf("id" to id.toString())) } catch (_: Exception) { }
        finally { if (prekinjen) Thread.currentThread().interrupt() }
    }

    /** Datoteke iz drevesa magnet/files: (datoteka, povezava) - mape (e) se razvijejo. */
    private fun razvij(a: JSONArray?, v: MutableList<Pair<TorBoxPravila.Datoteka, String>>) {
        for (i in 0 until (a?.length() ?: 0)) {
            val n = a?.optJSONObject(i) ?: continue
            val otroci = n.optJSONArray("e")
            if (otroci != null) razvij(otroci, v)
            else n.optString("l").takeIf { it.isNotBlank() }?.let {
                v += TorBoxPravila.Datoteka(v.size.toLong(), n.optString("n"), n.optLong("s"), "") to it
            }
        }
    }

    fun tok(kljuc: String, magnet: String, indeks: Int, samoPredpomnjeno: Boolean, napredek: (Int) -> Unit): TorBox.Izid {
        var id = -1L
        return try {
            // Pregled Grok 2: preklican poskus ne doda nicesar vec (klic HTTP prekinitve ne vidi).
            Debrid.preveriPreklic()
            val nalozi = Debrid.klic("$O/magnet/upload", kljuc, "POST", listOf("magnets[]" to DebridPravila.magnetAliHash(magnet)))
            if (!uspeh(nalozi)) return napaka(nalozi)
            val m = nalozi.json?.optJSONObject("data")?.optJSONArray("magnets")?.optJSONObject(0) ?: return napaka(nalozi)
            m.optJSONObject("error")?.let { return napaka(nalozi, it) }
            id = m.optLong("id", -1L)
            if (id < 0) return napaka(nalozi)
            var pripravljen = m.optBoolean("ready")
            if (!pripravljen && samoPredpomnjeno) { izbrisi(kljuc, id); return TorBox.Izid(napaka = DebridPravila.NAPAKA_NI_PREDPOMNJENO, ponudnik = DebridPravila.ALLDEBRID) }
            // Ni predpomnjen in uporabnik caka: stanje do »ready« (koda 4), koncne napake (>= 5) brez cakanja.
            var cakano = 0L
            while (!pripravljen && cakano <= TorBoxPravila.CAKAJ_NOVEGA_MS) {
                // Pregled A1: preklic (nova izbira, Preklici) ustavi cakanje takoj.
                if (Thread.interrupted()) throw InterruptedException()
                Thread.sleep(TorBoxPravila.CAKAJ_KORAK_MS); cakano += TorBoxPravila.CAKAJ_KORAK_MS
                val st = Debrid.klic("https://api.alldebrid.com/v4.1/magnet/status", kljuc, "POST", listOf("id" to id.toString()))
                if (!uspeh(st)) return napaka(st)
                val mm = st.json?.optJSONObject("data")?.let { it.optJSONObject("magnets") ?: it.optJSONArray("magnets")?.optJSONObject(0) }
                val koda = mm?.optInt("statusCode", 0) ?: 0
                if (koda >= 5) { izbrisi(kljuc, id); return TorBox.Izid(napaka = TorBoxPravila.NAPAKA_NI_DATOTEKE, ponudnik = DebridPravila.ALLDEBRID) }
                pripravljen = koda == 4
                val vel = mm?.optLong("size", 0L) ?: 0L
                if (!pripravljen && vel > 0) napredek(((mm?.optLong("downloaded", 0L) ?: 0L) * 100 / vel).toInt().coerceIn(0, 100))
            }
            if (!pripravljen) return TorBox.Izid(napaka = TorBoxPravila.NAPAKA_PRENASA, ponudnik = DebridPravila.ALLDEBRID)
            val dat = Debrid.klic("$O/magnet/files", kljuc, "POST", listOf("id[]" to id.toString()))
            if (!uspeh(dat)) return napaka(dat)
            val seznam = ArrayList<Pair<TorBoxPravila.Datoteka, String>>()
            razvij(dat.json?.optJSONObject("data")?.optJSONArray("magnets")?.optJSONObject(0)?.optJSONArray("files"), seznam)
            val d = TorBoxPravila.izberiDatoteko(seznam.map { it.first }, indeks)
                ?: return TorBox.Izid(napaka = TorBoxPravila.NAPAKA_NI_DATOTEKE, ponudnik = DebridPravila.ALLDEBRID)
            val povezava = seznam[d.id.toInt()].second
            Debrid.preveriPreklic()
            val odklep = Debrid.klic("$O/link/unlock", kljuc, "POST", listOf("link" to povezava))
            if (!uspeh(odklep)) return napaka(odklep)
            val data = odklep.json?.optJSONObject("data")
            var url = data?.optString("link").orEmpty()
            val zamik = data?.optLong("delayed", 0L) ?: 0L
            var poskusov = 0
            while (url.isBlank() && zamik > 0 && poskusov < 30) {
                if (Thread.interrupted()) throw InterruptedException()
                Thread.sleep(2_000); poskusov++
                val z = Debrid.klic("$O/link/delayed", kljuc, "POST", listOf("id" to zamik.toString()))
                if (!uspeh(z)) return napaka(z)
                val zd = z.json?.optJSONObject("data")
                when (zd?.optInt("status")) {
                    2 -> url = zd.optString("link")
                    3 -> return TorBox.Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK, ponudnik = DebridPravila.ALLDEBRID)
                }
            }
            if (!url.startsWith("https://")) return TorBox.Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK, ponudnik = DebridPravila.ALLDEBRID)
            Log.i(TAG, "tok pripravljen: ${Zasebno.zaDnevnik(url)}")
            TorBox.Izid(url = url, ponudnik = DebridPravila.ALLDEBRID)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            TorBox.Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK, ponudnik = DebridPravila.ALLDEBRID)
        } catch (e: Exception) {
            Log.w(TAG, "AllDebrid: ${e.javaClass.simpleName}")
            TorBox.Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK, ponudnik = DebridPravila.ALLDEBRID)
        }
    }
}
