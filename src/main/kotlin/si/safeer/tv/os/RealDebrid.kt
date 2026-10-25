package si.safeer.tv.os

import android.util.Log

/**
 * Naloga 128: Real-Debrid ([DebridPravila], dokumentacija api.real-debrid.com, prebrano 24. 10. 2026).
 * addMagnet -> torrents/info (datoteke) -> selectFiles (izbrana datoteka) -> info (»downloaded«, links) -> unrestrict/link.
 * Preverjanja predpomnjenja Real-Debrid nima vec: torrent, ki ni takoj »downloaded«, pri »samo predpomnjeno« izbrisemo
 * iz uporabnikovega racuna (ga ne pustimo za sabo). Pri »Pocakaj na prenos« ostane tudi po roku (pregled A3).
 * Grok 1: pri »samo predpomnjeno« pospravi [tok] v `finally` - tudi ob napaki omrezja, roku in praznih povezavah (prej samo ob
 * preklicu; mrtev prenos je ostal v racunu in zasedal aktivna mesta in fair-use).
 */
object RealDebrid {
    private const val TAG = "SafeerRealDebrid"
    private const val O = DebridPravila.RD_OSNOVA

    private fun napaka(o: Debrid.Odgovor): TorBox.Izid {
        val j = o.json
        val koda = j?.optInt("error_code", -999)?.takeIf { it != -999 }
        return TorBox.Izid(napaka = DebridPravila.rdVrstaNapake(o.koda, koda), opis = j?.optString("error").orEmpty().take(200),
            ponudnik = DebridPravila.REAL_DEBRID)
    }

    /** Brisanje gre ven tudi po preklicu: oznako prekinitve za cas klica odstranimo in jo nato vrnemo. */
    private fun izbrisi(kljuc: String, id: String) {
        val prekinjen = Thread.interrupted()
        try { Debrid.klic("$O/torrents/delete/$id", kljuc, "DELETE") } catch (_: Exception) { }
        finally { if (prekinjen) Thread.currentThread().interrupt() }
    }

    fun tok(kljuc: String, magnet: String, indeks: Int, samoPredpomnjeno: Boolean, napredek: (Int) -> Unit): TorBox.Izid {
        var id = ""
        // Grok 1: torrent je pripravljen (»downloaded« s povezavo) - ostane v racunu (predpomnjen, ne zaseda aktivnega mesta).
        var pripravljen = false
        fun pospravi() { if (id.isNotBlank()) { izbrisi(kljuc, id); id = "" } }
        return try {
            val dodaj = Debrid.klic("$O/torrents/addMagnet", kljuc, "POST", listOf("magnet" to magnet))
            if (!dodaj.uspeh) return napaka(dodaj)
            id = dodaj.json?.optString("id").orEmpty()
            if (id.isBlank()) return napaka(dodaj)
            // Datoteke torrenta (pretvorba magneta traja nekaj sekund).
            var info = Debrid.klic("$O/torrents/info/$id", kljuc)
            var poskusov = 0
            while (info.uspeh && info.json?.optJSONArray("files")?.length() ?: 0 == 0 && poskusov < 10 &&
                !DebridPravila.rdKoncnaNapaka(info.json?.optString("status").orEmpty())) {
                if (Thread.interrupted()) throw InterruptedException()
                Thread.sleep(1_000); poskusov++
                info = Debrid.klic("$O/torrents/info/$id", kljuc)
            }
            if (!info.uspeh) { pospravi(); return napaka(info) }
            val a = info.json?.optJSONArray("files")
            val datoteke = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }.map {
                TorBoxPravila.Datoteka(it.optLong("id"), it.optString("path").substringAfterLast('/'), it.optLong("bytes"), "")
            }
            val d = TorBoxPravila.izberiDatoteko(datoteke, indeks)
                ?: run { pospravi(); return TorBox.Izid(napaka = TorBoxPravila.NAPAKA_NI_DATOTEKE, ponudnik = DebridPravila.REAL_DEBRID) }
            val izberi = Debrid.klic("$O/torrents/selectFiles/$id", kljuc, "POST", listOf("files" to d.id.toString()))
            if (!izberi.uspeh) { pospravi(); return napaka(izberi) }
            // Predpomnjen torrent je »downloaded« takoj (nekaj sekund); sicer ga storitev sele prenasa.
            var cakano = 0L
            val rok = if (samoPredpomnjeno) 4_000L else TorBoxPravila.CAKAJ_NOVEGA_MS
            info = Debrid.klic("$O/torrents/info/$id", kljuc)
            while (info.uspeh && !DebridPravila.rdPripravljen(info.json?.optString("status").orEmpty()) && cakano < rok) {
                if (DebridPravila.rdKoncnaNapaka(info.json?.optString("status").orEmpty())) break
                // Pregled A1: preklic (nova izbira, Preklici) ustavi cakanje takoj.
                if (Thread.interrupted()) throw InterruptedException()
                if (!samoPredpomnjeno) napredek(info.json?.optInt("progress", 0) ?: 0)
                val korak = if (samoPredpomnjeno) 1_000L else TorBoxPravila.CAKAJ_KORAK_MS
                Thread.sleep(korak); cakano += korak
                info = Debrid.klic("$O/torrents/info/$id", kljuc)
            }
            val status = info.json?.optString("status").orEmpty()
            if (!DebridPravila.rdPripravljen(status)) {
                // Pregled A3: uporabnik je izbral »Pocakaj na prenos« - torrent ostane v racunu in se prenasa naprej, da
                // naslednji poskus (kot obljubi sporocilo) predvaja takoj. Izbrisemo samo nepredpomnjenega (»samo takoj«) in pokvarjenega.
                if (samoPredpomnjeno || DebridPravila.rdKoncnaNapaka(status)) pospravi()
                return TorBox.Izid(napaka = when {
                    DebridPravila.rdKoncnaNapaka(status) -> TorBoxPravila.NAPAKA_NI_DATOTEKE
                    samoPredpomnjeno -> DebridPravila.NAPAKA_NI_PREDPOMNJENO
                    else -> TorBoxPravila.NAPAKA_PRENASA
                }, ponudnik = DebridPravila.REAL_DEBRID)
            }
            val povezava = info.json?.optJSONArray("links")?.optString(0).orEmpty()
            if (povezava.isBlank()) return TorBox.Izid(napaka = TorBoxPravila.NAPAKA_NI_DATOTEKE, ponudnik = DebridPravila.REAL_DEBRID)
            pripravljen = true
            val odklep = Debrid.klic("$O/unrestrict/link", kljuc, "POST", listOf("link" to povezava))
            if (!odklep.uspeh) return napaka(odklep)
            val url = odklep.json?.optString("download").orEmpty()
            if (!url.startsWith("https://")) return napaka(odklep)
            Log.i(TAG, "tok pripravljen: ${Zasebno.zaDnevnik(url)}")
            TorBox.Izid(url = url, ponudnik = DebridPravila.REAL_DEBRID)
        } catch (e: InterruptedException) {
            // Preklic: torrent, na katerega je uporabnik cakal, ostane in se prenasa naprej (pregled A3).
            Thread.currentThread().interrupt()
            TorBox.Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK, ponudnik = DebridPravila.REAL_DEBRID)
        } catch (e: Exception) {
            Log.w(TAG, "Real-Debrid: ${e.javaClass.simpleName}")
            TorBox.Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK, ponudnik = DebridPravila.REAL_DEBRID)
        } finally {
            // Grok 1: »samo takoj predvajljivo« - torrent, ki ni pripravljen, ne ostane v racunu ne glede na izhod (preklic,
            // napaka omrezja ali rok, prazne povezave po »downloaded«).
            if (samoPredpomnjeno && !pripravljen) pospravi()
        }
    }
}
