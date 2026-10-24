package si.safeer.tv.os

import android.util.Log

/**
 * Naloga 128: Real-Debrid ([DebridPravila], dokumentacija api.real-debrid.com, prebrano 24. 10. 2026).
 * addMagnet -> torrents/info (datoteke) -> selectFiles (izbrana datoteka) -> info (»downloaded«, links) -> unrestrict/link.
 * Preverjanja predpomnjenja Real-Debrid nima vec: torrent, ki ni takoj »downloaded«, pri »samo predpomnjeno« izbrisemo
 * iz uporabnikovega racuna (ga ne pustimo za sabo).
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

    private fun izbrisi(kljuc: String, id: String) {
        try { Debrid.klic("$O/torrents/delete/$id", kljuc, "DELETE") } catch (_: Exception) { }
    }

    fun tok(kljuc: String, magnet: String, indeks: Int, samoPredpomnjeno: Boolean, napredek: (Int) -> Unit): TorBox.Izid {
        var id = ""
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
                Thread.sleep(1_000); poskusov++
                info = Debrid.klic("$O/torrents/info/$id", kljuc)
            }
            if (!info.uspeh) { izbrisi(kljuc, id); return napaka(info) }
            val a = info.json?.optJSONArray("files")
            val datoteke = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }.map {
                TorBoxPravila.Datoteka(it.optLong("id"), it.optString("path").substringAfterLast('/'), it.optLong("bytes"), "")
            }
            val d = TorBoxPravila.izberiDatoteko(datoteke, indeks)
                ?: run { izbrisi(kljuc, id); return TorBox.Izid(napaka = TorBoxPravila.NAPAKA_NI_DATOTEKE, ponudnik = DebridPravila.REAL_DEBRID) }
            val izberi = Debrid.klic("$O/torrents/selectFiles/$id", kljuc, "POST", listOf("files" to d.id.toString()))
            if (!izberi.uspeh) { izbrisi(kljuc, id); return napaka(izberi) }
            // Predpomnjen torrent je »downloaded« takoj (nekaj sekund); sicer ga storitev sele prenasa.
            var cakano = 0L
            val rok = if (samoPredpomnjeno) 4_000L else TorBoxPravila.CAKAJ_NOVEGA_MS
            info = Debrid.klic("$O/torrents/info/$id", kljuc)
            while (info.uspeh && !DebridPravila.rdPripravljen(info.json?.optString("status").orEmpty()) && cakano < rok) {
                if (DebridPravila.rdKoncnaNapaka(info.json?.optString("status").orEmpty())) break
                if (!samoPredpomnjeno) napredek(info.json?.optInt("progress", 0) ?: 0)
                val korak = if (samoPredpomnjeno) 1_000L else TorBoxPravila.CAKAJ_KORAK_MS
                Thread.sleep(korak); cakano += korak
                info = Debrid.klic("$O/torrents/info/$id", kljuc)
            }
            val status = info.json?.optString("status").orEmpty()
            if (!DebridPravila.rdPripravljen(status)) {
                izbrisi(kljuc, id)
                return TorBox.Izid(napaka = when {
                    DebridPravila.rdKoncnaNapaka(status) -> TorBoxPravila.NAPAKA_NI_DATOTEKE
                    samoPredpomnjeno -> DebridPravila.NAPAKA_NI_PREDPOMNJENO
                    else -> TorBoxPravila.NAPAKA_PRENASA
                }, ponudnik = DebridPravila.REAL_DEBRID)
            }
            val povezava = info.json?.optJSONArray("links")?.optString(0).orEmpty()
            if (povezava.isBlank()) return TorBox.Izid(napaka = TorBoxPravila.NAPAKA_NI_DATOTEKE, ponudnik = DebridPravila.REAL_DEBRID)
            val odklep = Debrid.klic("$O/unrestrict/link", kljuc, "POST", listOf("link" to povezava))
            if (!odklep.uspeh) return napaka(odklep)
            val url = odklep.json?.optString("download").orEmpty()
            if (!url.startsWith("https://")) return napaka(odklep)
            Log.i(TAG, "tok pripravljen: ${Zasebno.zaDnevnik(url)}")
            TorBox.Izid(url = url, ponudnik = DebridPravila.REAL_DEBRID)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); if (id.isNotBlank()) izbrisi(kljuc, id)
            TorBox.Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK, ponudnik = DebridPravila.REAL_DEBRID)
        } catch (e: Exception) {
            Log.w(TAG, "Real-Debrid: ${e.javaClass.simpleName}")
            TorBox.Izid(napaka = TorBoxPravila.NAPAKA_STREZNIK, ponudnik = DebridPravila.REAL_DEBRID)
        }
    }
}
