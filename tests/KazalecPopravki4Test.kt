// Popravki slepega pregleda TV4 (15. 11. 2026): TV4-2 (zastarela zastavica OK ne sme klikniti na skritem kazalcu) in TV4-3 (YouTube za TV
// po shemi, gostitelju in poti). Cista pravila; integracija v TvKeyRouter je NEPREVERJENA na napravi (tests/preveri_tv_popravki3.py
// preveri samo, da jo koda uporablja). Brez popravka (okNovPritisk/okSamoPokazi ohranita prejsnje stanje, jeYoutubeZaTv je podniz) preizkus PADE.
//   tests/run_splet_tests.sh
package si.safeer.tv

import si.safeer.tv.KazalecPravila as K
import kotlin.system.exitProcess

private var padlo = 0
private var preverb = 0

private fun p(pogoj: Boolean, opis: String) {
    preverb++
    if (pogoj) println("OK   $opis") else { padlo++; println("PADE $opis") }
}

/** Model tipke OK v TvKeyRouter: isti klici KazalecPravila kot v usmerjevalniku. */
private class Ok {
    var s = K.OkStanje()
    var skrit = false
    fun keydown(ponovitev: Int) {
        if (ponovitev == 0) s = K.okNovPritisk(s)                        // zgoraj v dispatch()
        if (K.tipkaSamoPokaze(skrit, true)) { skrit = false; s = K.okSamoPokazi(s); return }
        if (ponovitev == 0) s = K.okCakaKlik(s)                          // navaden kratek OK ob vidnem kazalcu
    }
    /** Spust, ki pride do obdelave (vrstica 68 usmerjevalnika). */
    fun keyup(): K.OkSpust {
        val r = K.okSpust(s.cakaKlik, s.pogoltniSpust)
        s = K.OkStanje(false, false)   // klik porabi cakaKlik, spust porabi pogoltni
        return r
    }
}

fun main() {
    // TV4-2: navaden OK = klik; samo pokazi = pogoltnjen spust brez klika
    Ok().apply { keydown(0); p(keyup() == K.OkSpust.KLIK, "TV4-2: OK ob vidnem kazalcu: klik ob spustu") }
    Ok().apply { skrit = true; keydown(0); p(!s.cakaKlik && s.pogoltniSpust, "TV4-2: samo pokazi: klik ne caka, spust pogoltnjen"); p(keyup() == K.OkSpust.POGOLTNI, "TV4-2: samo pokazi: spust ni klik") }
    // izgubljen spust: OK dol (klik caka), spust ni prisel do obdelave, kazalec se skrije, nov OK -> samo pokazi, NE klik
    Ok().apply {
        keydown(0)                      // vidni kazalec, caka klik
        p(s.cakaKlik, "TV4-2: pogoj izgubljenega spusta: klik caka")
        skrit = true                    // ura mirovanja skrije kazalec; spust se je izgubil (zaslon, sloj ...)
        keydown(0)
        p(!s.cakaKlik, "TV4-2: zastarel cakaKlik po novem pritisku ni ostal")
        p(keyup() == K.OkSpust.POGOLTNI, "TV4-2: po izgubljenem spustu prvi OK ob skritem kazalcu NE klikne na skritem mestu")
    }
    // izgubljen spust, kazalec ostane viden: naslednji OK klikne enkrat (ne dvakrat)
    Ok().apply { keydown(0); keydown(0); p(keyup() == K.OkSpust.KLIK, "TV4-2: izgubljen spust, viden kazalec: nov OK klikne"); p(keyup() == K.OkSpust.PREPUSTI, "TV4-2: po kliku ni zastarele zastavice") }
    // zastarel okSpustPogoltni ne sme pogolzniti naslednjega navadnega spusta
    Ok().apply { skrit = true; keydown(0); skrit = false; keydown(0); p(keyup() == K.OkSpust.KLIK, "TV4-2: zastarel pogoltnjen spust ne pogoltne naslednjega navadnega OK") }
    // drzanje (ponovitve) stanja ne spremeni
    Ok().apply { keydown(0); val pred = s; keydown(1); keydown(2); p(s == pred, "TV4-2: ponovitve drzane tipke ne spremenijo stanja") }
    p(K.okNovPritisk(K.OkStanje(true, true)) == K.OkStanje(false, false), "TV4-2: okNovPritisk pocisti obe zastavici")
    p(K.okSamoPokazi(K.OkStanje(true, false)) == K.OkStanje(false, true), "TV4-2: okSamoPokazi pocisti cakaKlik in postavi samo pogoltni spust")

    // TV4-3: YouTube za TV po shemi, gostitelju in poti
    fun yt(u: String?) = K.jeYoutubeZaTv(u)
    p(yt("https://www.youtube.com/tv") && yt("https://youtube.com/tv") && yt("https://www.youtube.com/tv#/watch?v=abc") && yt("https://www.youtube.com/tv?x=1")
        && yt("https://www.youtube.com/tv/") && yt("HTTPS://WWW.YOUTUBE.COM/TV") && yt("https://www.youtube.com:443/tv"), "TV4-3: pravi naslovi YouTube za TV")
    p(!yt("https://evil.example/youtube.com/tv") && !yt("https://evil.example/?u=youtube.com/tv") && !yt("https://evil.example/#youtube.com/tv")
        && !yt("https://evil.example/x#/youtube.com/tv") && !yt("https://youtube.com.evil.example/tv") && !yt("https://notyoutube.com/tv"), "TV4-3: podniz na tujem gostitelju ne velja (replaceState trik)")
    p(!yt("http://www.youtube.com/tv") && !yt("ftp://www.youtube.com/tv") && !yt("www.youtube.com/tv") && !yt("file:///youtube.com/tv") && !yt(null) && !yt(""), "TV4-3: samo https")
    p(!yt("https://www.youtube.com/tvx") && !yt("https://www.youtube.com/watch?v=tv") && !yt("https://www.youtube.com/") && !yt("https://www.youtube.com#/tv") && !yt("https://www.youtube.com?x=/tv")
        && !yt("https://music.youtube.com/tv") && !yt("https://m.youtube.com/tv") && !yt("https://tv.youtube.com/tv") && !yt("https://www.youtube.com/a/tv"), "TV4-3: druga pot ali poddomena ne velja")
    p(!yt("https://www.youtube.com/tv/../x") && !yt("https://www.youtube.com/tv/%2e%2e/x") && !yt("https://www.youtube.com@evil.example/tv") && !yt("https://www.youtube.com\\@evil.example/tv"), "TV4-3: .., %2e in userinfo ne ujamejo")

    println("KazalecPopravki4Test: $preverb preverb, $padlo padlo")
    if (padlo > 0) exitProcess(1)
}
