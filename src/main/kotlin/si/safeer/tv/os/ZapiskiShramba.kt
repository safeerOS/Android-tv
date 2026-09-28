package si.safeer.tv.os

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Cista Kotlin shramba zapiskov. Androidov del hrani [kodirano] v SharedPreferences, pravila za
 * ustvarjanje, urejanje, iskanje in brisanje pa lahko preverimo v navadnem JVM.
 */
class ZapiskiShramba(
    zapisano: String = "",
    private val zdaj: () -> Long = { System.currentTimeMillis() }
) {
    data class Zapisek(
        val id: Long,
        val naslov: String,
        val vsebina: String,
        val spremenjen: Long
    )

    private val zapiski = razberi(zapisano).toMutableList()

    fun seznam(iskanje: String = ""): List<Zapisek> {
        val niz = iskanje.trim()
        return zapiski.asSequence()
            .filter { niz.isEmpty() || it.naslov.contains(niz, ignoreCase = true) || it.vsebina.contains(niz, ignoreCase = true) }
            .sortedWith(compareByDescending<Zapisek> { it.spremenjen }.thenByDescending { it.id })
            .toList()
    }

    fun najdi(id: Long): Zapisek? = zapiski.firstOrNull { it.id == id }

    fun shrani(id: Long? = null, naslov: String, vsebina: String): Zapisek {
        val cas = zdaj()
        val obstojeci = id?.let(::najdi)
        val novId = obstojeci?.id ?: naslednjiId(cas)
        val z = Zapisek(novId, naslov.trim(), vsebina, cas)
        if (obstojeci == null) zapiski.add(z) else zapiski[zapiski.indexOf(obstojeci)] = z
        return z
    }

    fun izbrisi(id: Long): Boolean = zapiski.removeAll { it.id == id }

    /** Razlicica zapisa je v prvi vrstici; polja so Base64, zato varno ohranimo tabulatorje in nove vrstice. */
    fun kodirano(): String = buildString {
        append("Z1")
        for (z in zapiski) {
            append('\n').append(z.id).append('\t').append(z.spremenjen).append('\t')
            append(kodiraj(z.naslov)).append('\t').append(kodiraj(z.vsebina))
        }
    }

    private fun naslednjiId(cas: Long): Long {
        var id = cas.coerceAtLeast(1L)
        val obstojeci = zapiski.asSequence().map { it.id }.toHashSet()
        while (id in obstojeci) id++
        return id
    }

    companion object {
        private fun kodiraj(vrednost: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(vrednost.toByteArray(StandardCharsets.UTF_8))

        private fun odkodiraj(vrednost: String): String =
            String(Base64.getUrlDecoder().decode(vrednost), StandardCharsets.UTF_8)

        private fun razberi(zapisano: String): List<Zapisek> {
            if (zapisano.isBlank()) return emptyList()
            val vrstice = zapisano.lineSequence().toList()
            if (vrstice.firstOrNull() != "Z1") return emptyList()
            return vrstice.drop(1).mapNotNull { vrstica ->
                try {
                    val p = vrstica.split('\t', limit = 4)
                    if (p.size != 4) null else Zapisek(p[0].toLong(), odkodiraj(p[2]), odkodiraj(p[3]), p[1].toLong())
                } catch (_: Exception) {
                    null // Pokvarjen posamezen zapis ne sme skriti vseh ostalih.
                }
            }
        }
    }
}
