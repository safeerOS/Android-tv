package si.safeer.tv.os

/**
 * Zmoznosti televizorja za `screen.start` ("codecs", "maxsize"), sestavljene iz seznama dekoderjev naprave.
 * Brez Androida (seznam pride prek [ViriDekoderjev]), da se da preizkusiti na JVM.
 */
object ZaslonZmoznosti {
    /** Meji racunalnika (link_daljinec KODEKI_NAJVEC, KODEK_NAJVEC_ZNAKOV): kar je cez, racunalnik prezre v celoti. */
    const val KODEKI_NAJVEC = 8
    const val KODEK_NAJVEC_ZNAKOV = 16

    const val MIME_HEVC = "video/hevc"
    /** HEVC ponudimo samo za 4K: zaradi tega ga sploh zelimo, manjse zmore ze H.264. */
    const val HEVC_SIRINA = 3840
    const val HEVC_VISINA = 2160
    /** H.264 ostane, kot je bil doslej brez "maxsize" (racunalnik: H.264 do 1080p). */
    const val H264_SIRINA = 1920
    const val H264_VISINA = 1080

    /** En dekoder za en mime (MediaCodecInfo z vec vrstami da vec vnosov). */
    interface Dekoder {
        val mime: String
        val koder: Boolean
        /** true/false, kadar naprava to pove; null, kadar ne vemo (pred Androidom 10 in brez imena). */
        val strojni: Boolean?
        val najSirina: Int
        val najVisina: Int
        fun podpira(sirina: Int, visina: Int): Boolean
    }

    fun interface ViriDekoderjev {
        fun dekoderji(): List<Dekoder>
    }

    /** Prazen izid pomeni: v screen.start ne dodamo nicesar, racunalnik dela kot doslej. */
    data class Izid(val codecs: List<String>, val maxsize: Map<String, List<Int>>) {
        val prazen: Boolean get() = codecs.isEmpty() && maxsize.isEmpty()
    }

    val PRAZNO = Izid(emptyList(), emptyMap())

    /**
     * Izid za screen.start. HEVC samo, ce ga dekoder (ne koder, ne znano programski) potrdi za 3840x2160 in ga
     * gledalec v tej razlicici se sme ponuditi ([hevcDovoljen]). Brez HEVC prazno, da H.264 ostane natanko kot prej.
     */
    fun sestavi(viri: ViriDekoderjev, hevcDovoljen: Boolean = true): Izid {
        if (!hevcDovoljen) return PRAZNO
        // Napaka seznama ne sme ustaviti zaslona: brez kljucev racunalnik ostane pri H.264 do 1080p.
        val seznam = try { viri.dekoderji() } catch (_: Throwable) { return PRAZNO }
        val hevc = seznam.any { d ->
            try { hevc4k(d) } catch (_: Throwable) { false }
        }
        if (!hevc) return PRAZNO
        val codecs = ZaslonKodek.seznam(true)
            .filter { it.length <= KODEK_NAJVEC_ZNAKOV }
            .take(KODEKI_NAJVEC)
        val maxsize = linkedMapOf(
            "hevc" to listOf(HEVC_SIRINA, HEVC_VISINA),
            "h264" to listOf(H264_SIRINA, H264_VISINA),
        )
        return Izid(codecs, maxsize)
    }

    private fun hevc4k(d: Dekoder): Boolean =
        !d.koder && d.strojni != false && d.mime.equals(MIME_HEVC, ignoreCase = true) &&
            d.najSirina >= HEVC_SIRINA && d.najVisina >= HEVC_VISINA && d.podpira(HEVC_SIRINA, HEVC_VISINA)
}
