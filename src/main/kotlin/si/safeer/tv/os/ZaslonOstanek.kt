package si.safeer.tv.os

/**
 * Ostanek toka H.264/HEVC med okvirji (neobdelan konec zadnjega okvirja do zacetka naslednje enote NAL).
 *
 * Slepi pregled 18. 11. 2026, N5: okvir brez zacetne kode NAL (pokvarjen tok ali ne-video podatki) je ostal v ostanku
 * in se mu je dodajal naslednji (vsak do 8 MB): na TV z 2 GB bi pomnilnik rasel do OOM. Zdaj ostanek ne preseze
 * [NAJVEC] bajtov; ce bi ga, stari del odpade (tok brez zacetne kode tako ali tako ni uporaben).
 */
internal object ZaslonOstanek {
    /** Dva najvecja okvirja (8 MB), da ena enota NAL, ki prestopi mejo okvirja, se vedno ostane cela. */
    const val NAJVEC = 16 * 1024 * 1024

    fun dodaj(ostanek: ByteArray, telo: ByteArray): ByteArray =
        if (ostanek.size.toLong() + telo.size <= NAJVEC) ostanek + telo else telo
}
