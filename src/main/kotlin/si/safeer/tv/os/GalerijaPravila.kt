package si.safeer.tv.os

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Cista pravila galerije, locena od Android UI-ja, da jih lahko preverimo na navadnem JVM. */
object GalerijaPravila {
    enum class SkupinaDatuma { DANES, VCERAJ, DATUM, BREZ_DATUMA }

    /** Telefon pokaze stiri stolpce; na vecjem zaslonu ostanejo ploscice blizu 100 dp. */
    fun stolpci(sirinaDp: Int): Int =
        if (sirinaDp < 600) 4 else (sirinaDp / 105).coerceIn(6, 8)

    /** Galerijske zbirke in mape z vecino slik/videov se privzeto odprejo kot mreza. */
    fun jeMrezaPrivzeto(namigi: List<String>, vrste: List<String>): Boolean {
        val znana = namigi.any {
            val n = it.lowercase()
            n == "media:image" || n == "media:video" || n == "local:image" || n == "local:video" ||
                n.contains("dcim") || n.contains("screenshots") || n.contains("screenshot") ||
                n.contains("slike") || n.contains("pictures") || n.contains("images") || n.contains("photos") ||
                n.contains("videi") || n.contains("videos")
        }
        if (znana) return true
        if (vrste.isEmpty()) return false
        return vrste.count { it == "image" || it == "video" } * 2 > vrste.size
    }

    fun skupinaDatuma(casMs: Long, zdajMs: Long, cona: ZoneId): SkupinaDatuma {
        if (casMs <= 0) return SkupinaDatuma.BREZ_DATUMA
        val datum = Instant.ofEpochMilli(casMs).atZone(cona).toLocalDate()
        val danes = Instant.ofEpochMilli(zdajMs).atZone(cona).toLocalDate()
        return when (datum) {
            danes -> SkupinaDatuma.DANES
            danes.minusDays(1) -> SkupinaDatuma.VCERAJ
            else -> SkupinaDatuma.DATUM
        }
    }

    /** Ključ skupine je krajevni koledarski dan, zato polnoc in poletni cas ne razbijeta skupin. */
    fun dan(casMs: Long, cona: ZoneId): Long =
        if (casMs <= 0) Long.MIN_VALUE else Instant.ofEpochMilli(casMs).atZone(cona).toLocalDate().toEpochDay()

    /** 106000 -> 1:46; daljsi posnetki dobijo tudi ure. */
    fun trajanje(casMs: Long): String {
        val skupaj = (casMs.coerceAtLeast(0) / 1000)
        val sekunde = skupaj % 60
        val minute = (skupaj / 60) % 60
        val ure = skupaj / 3600
        return if (ure > 0) "%d:%02d:%02d".format(ure, minute, sekunde)
        else "%d:%02d".format(minute, sekunde)
    }

    fun datumIzDneva(dan: Long): LocalDate? =
        if (dan == Long.MIN_VALUE) null else LocalDate.ofEpochDay(dan)
}
