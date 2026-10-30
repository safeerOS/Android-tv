package si.safeer.tv.os

import si.safeer.tv.cast.JsonLahki
import java.net.URLEncoder

/**
 * Naloga 42 A: vreme v glavi zaganjalnika Pokrajina. Vir je Open-Meteo (open-meteo.com, prost vmesnik brez kljuca,
 * podatki CC BY 4.0). Kot pri besedilih pesmi gre zahteva SAMO, ce je uporabnik sam vpisal kraj: brez kraja vremena ni
 * (tudi ne nadomestka na zaslonu) in Safeer nikogar ne vprasa. Lokacije naprave ne beremo - tujemu strezniku gre le
 * ime kraja (iskanje) oziroma koordinate kraja, ki ga je izbral uporabnik.
 * Ciste funkcije (tests/VremePravilaTest.kt).
 */
object VremePravila {
    const val ISKANJE = "https://geocoding-api.open-meteo.com/v1/search"
    const val NAPOVED = "https://api.open-meteo.com/v1/forecast"
    /** Vreme se osvezi najvec tako pogosto (Open-Meteo osvezuje trenutne podatke na 15 min). */
    const val OSVEZI_MS = 30L * 60 * 1000
    /** Starejse vreme se ne pokaze (raje nic kot vceraj). */
    const val ZASTARELO_MS = 3L * 60 * 60 * 1000

    data class Kraj(val ime: String, val drzava: String, val sirina: Double, val dolzina: Double)
    data class Vreme(val stopinj: Int, val koda: Int)

    /** Vrste vremena za ikono (WMO koda Open-Meteo). */
    const val JASNO = "jasno"
    const val DELNO = "delno"
    const val OBLACNO = "oblacno"
    const val MEGLA = "megla"
    const val DEZ = "dez"
    const val SNEG = "sneg"
    const val NEVIHTA = "nevihta"

    fun vrsta(koda: Int): String = when (koda) {
        0 -> JASNO
        1, 2 -> DELNO
        3 -> OBLACNO
        45, 48 -> MEGLA
        in 51..67, in 80..82 -> DEZ
        in 71..77, 85, 86 -> SNEG
        in 95..99 -> NEVIHTA
        else -> OBLACNO
    }

    /** Naslov iskanja kraja; [jezik] je jezik vmesnika (imena krajev v njem). Prazen kraj: null (ni zahteve). */
    fun naslovIskanja(kraj: String, jezik: String): String? {
        val k = kraj.trim().replace(Regex("\\s+"), " ").take(80)
        if (k.length < 2) return null
        val j = jezik.lowercase().takeIf { Regex("[a-z]{2}").matches(it) } ?: "en"
        return "$ISKANJE?name=" + URLEncoder.encode(k, "UTF-8") + "&count=1&language=$j&format=json"
    }

    /** Prvi zadetek iskanja ali null (ni zadetka, pokvarjen odgovor, koordinate zunaj obsega). */
    fun razcleniKraj(json: String): Kraj? {
        val o = JsonLahki.objekt(json) ?: return null
        val prvi = JsonLahki.elementi(o.surovo("results") ?: return null)?.firstOrNull() ?: return null
        val r = JsonLahki.objekt(prvi) ?: return null
        val sirina = r.stevilo("latitude") ?: return null
        val dolzina = r.stevilo("longitude") ?: return null
        if (sirina !in -90.0..90.0 || dolzina !in -180.0..180.0) return null
        val ime = r.nizAli("name").trim().take(80)
        if (ime.isEmpty()) return null
        return Kraj(ime, r.nizAli("country").trim().take(60), sirina, dolzina)
    }

    /** Naslov trenutnega vremena za kraj (koordinate na dve decimalki - kraj, ne naslov). */
    fun naslovNapovedi(k: Kraj): String =
        "$NAPOVED?latitude=" + String.format(java.util.Locale.ROOT, "%.2f", k.sirina) +
            "&longitude=" + String.format(java.util.Locale.ROOT, "%.2f", k.dolzina) +
            "&current=temperature_2m,weather_code&timezone=auto"

    fun razcleniVreme(json: String): Vreme? {
        val o = JsonLahki.objekt(json) ?: return null
        val t = o.objekt("current") ?: return null
        val stopinj = t.stevilo("temperature_2m") ?: return null
        val koda = t.stevilo("weather_code") ?: return null
        if (stopinj.isNaN() || stopinj < -90 || stopinj > 60) return null
        return Vreme(Math.round(stopinj).toInt(), koda.toInt())
    }

    /** En dan tedenske napovedi (lastnik 30. 10. 2026: klik na temperaturo pokaze teden v zgornjem pasu). */
    data class Dan(val datum: String, val max: Int, val min: Int, val koda: Int)

    fun naslovTedna(k: Kraj): String =
        "$NAPOVED?latitude=" + String.format(java.util.Locale.ROOT, "%.2f", k.sirina) +
            "&longitude=" + String.format(java.util.Locale.ROOT, "%.2f", k.dolzina) +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=7"

    fun razcleniTeden(json: String): List<Dan> {
        val d = JsonLahki.objekt(json)?.objekt("daily") ?: return emptyList()
        val dnevi = d.nizi("time")
        fun stevila(kljuc: String): List<Double?> =
            d.surovo(kljuc)?.let { JsonLahki.elementi(it) }?.map { it.trim().toDoubleOrNull() } ?: emptyList()
        val najvec = stevila("temperature_2m_max")
        val najmanj = stevila("temperature_2m_min")
        val kode = stevila("weather_code")
        return dnevi.indices.mapNotNull { i ->
            val a = najvec.getOrNull(i) ?: return@mapNotNull null
            val b = najmanj.getOrNull(i) ?: return@mapNotNull null
            val c = kode.getOrNull(i) ?: return@mapNotNull null
            if (a.isNaN() || b.isNaN() || a < -90 || a > 60 || b < -90 || b > 60) return@mapNotNull null
            if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(dnevi[i])) return@mapNotNull null
            Dan(dnevi[i], Math.round(a).toInt(), Math.round(b).toInt(), c.toInt())
        }.take(7)
    }

    /** »6° / 1°« z znakom minus. */
    fun napisDneva(d: Dan): String {
        fun st(x: Int) = if (x < 0) "−" + (-x) else x.toString()
        return st(d.max) + "° / " + st(d.min) + "°"
    }

    /** Ali je zapis iz [casMs] se dovolj svez za prikaz. */
    fun svezo(casMs: Long, zdajMs: Long): Boolean = casMs > 0 && zdajMs - casMs in 0 until ZASTARELO_MS

    fun osvezi(casMs: Long, zdajMs: Long): Boolean = casMs <= 0 || zdajMs - casMs !in 0 until OSVEZI_MS

    /** »20 °C« - brez decimalk; minus je pravi znak minus, da ga bralnik zaslona prebere. */
    fun napis(v: Vreme): String = (if (v.stopinj < 0) "−" + (-v.stopinj) else v.stopinj.toString()) + " °C"
}
