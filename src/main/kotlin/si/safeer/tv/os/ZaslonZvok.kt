package si.safeer.tv.os

import kotlin.math.roundToInt

/**
 * Cista pravila za zvok pri prenosu zaslona na TV (kanali, velikost vzorca, medpomnilnik,
 * zakasnitev, opuscanje zaostalega zvoka, izbira zapisa in pretvorba float32 v PCM 16-bit).
 * Brez odvisnosti od Androida, da se da preizkusiti na navadnem JVM.
 */
object ZaslonZvok {
    /** Najvecje stevilo podprtih zvocnih kanalov (7.1). */
    const val NAJVEC_KANALOV = 8

    /** Ciljna dolzina zvocnega medpomnilnika v milisekundah. */
    const val CILJNI_MEDPOMNILNIK_MS = 60

    /** Najmanjsa dovoljena dolzina medpomnilnika v milisekundah. */
    const val NAJMANJ_MEDPOMNILNIK_MS = 20

    /** Najvecja dovoljena dolzina medpomnilnika v milisekundah. */
    const val NAJVEC_MEDPOMNILNIK_MS = 200

    private const val PRIVZETA_FREKVENCA_HZ = 48000
    private const val MIN_SDK_FLOAT32 = 21
    private const val BAJTOV_NA_KANAL_PCM16 = 2
    private const val BAJTOV_NA_KANAL_FLOAT32 = 4
    private const val PCM16_NAJVEC = 32767.0f

    /**
     * Preslika stevilo kanalov v podprto vrednost iz obmocja 1..[NAJVEC_KANALOV]:
     * <= 0 postane 2 (stereo), 3 postane 2, 4 ostane 4 (kvadro), 5 postane 6 (5.1),
     * 7 postane 8 (7.1), vrednosti nad [NAJVEC_KANALOV] se omejijo na [NAJVEC_KANALOV].
     */
    fun kanaliVeljavni(kanali: Int): Int = when {
        kanali <= 0 -> 2
        kanali == 1 -> 1
        kanali <= 3 -> 2
        kanali == 4 -> 4
        kanali <= 6 -> 6
        else -> NAJVEC_KANALOV
    }

    /** Vrne berljivo ime razporeda kanalov po preslikavi s [kanaliVeljavni]. */
    fun imeRazporeda(kanali: Int): String = when (kanaliVeljavni(kanali)) {
        1 -> "mono"
        2 -> "stereo"
        4 -> "kvadro"
        6 -> "5.1"
        else -> "7.1"
    }

    /**
     * Stevilo bajtov za en zvocni vzorec cez vse veljavne kanale
     * (16-bit PCM = 2 B na kanal, float32 = 4 B na kanal).
     */
    fun velikostVzorca(kanali: Int, float32: Boolean): Int {
        val bajtovNaKanal = if (float32) BAJTOV_NA_KANAL_FLOAT32 else BAJTOV_NA_KANAL_PCM16
        return kanaliVeljavni(kanali) * bajtovNaKanal
    }

    /**
     * Izracuna velikost zvocnega medpomnilnika v bajtih:
     * max(najmanjSistemaBajtov, (hz * omejenCiljMs / 1000) * velikostVzorca),
     * pri cemer je rezultat vedno vsaj en cel vzorec in poravnan navzgor na [velikostVzorca].
     * Neveljavna frekvenca (<= 0) uporabi privzeto 48000 Hz, ciljMs se omeji na
     * [[NAJMANJ_MEDPOMNILNIK_MS], [NAJVEC_MEDPOMNILNIK_MS]].
     */
    fun medpomnilnikBajtov(
        hz: Int,
        kanali: Int,
        float32: Boolean,
        ciljMs: Int,
        najmanjSistemaBajtov: Int,
    ): Int {
        val frekvenca = if (hz <= 0) PRIVZETA_FREKVENCA_HZ else hz
        val ms = ciljMs.coerceIn(NAJMANJ_MEDPOMNILNIK_MS, NAJVEC_MEDPOMNILNIK_MS)
        val vzorecBajtov = velikostVzorca(kanali, float32)
        val vzorcev = ((frekvenca.toLong() * ms.toLong()) / 1000L).coerceAtLeast(1L)
        val ciljBajtov = vzorcev * vzorecBajtov.toLong()
        val zahtevano = maxOf(najmanjSistemaBajtov.toLong(), ciljBajtov)
            .coerceAtLeast(vzorecBajtov.toLong())
        val poravnano = ((zahtevano + vzorecBajtov.toLong() - 1L) / vzorecBajtov.toLong()) *
            vzorecBajtov.toLong()
        val najvecPoravnano = (Int.MAX_VALUE / vzorecBajtov).toLong() * vzorecBajtov.toLong()
        return poravnano.coerceAtMost(najvecPoravnano).toInt()
    }

    /**
     * Izracuna, koliko milisekund zvoka caka v medpomnilniku za dano stevilo bajtov.
     * Nepozitivno stevilo bajtov vrne 0; frekvenca <= 0 uporabi 48000 Hz.
     */
    fun zakasnitevMs(bajtovVMedpomnilniku: Int, hz: Int, kanali: Int, float32: Boolean): Long {
        if (bajtovVMedpomnilniku <= 0) return 0L
        val frekvenca = if (hz <= 0) PRIVZETA_FREKVENCA_HZ else hz
        val vzorecBajtov = velikostVzorca(kanali, float32)
        val bajtovNaSekundo = frekvenca.toLong() * vzorecBajtov.toLong()
        return (bajtovVMedpomnilniku.toLong() * 1000L) / bajtovNaSekundo
    }

    /**
     * Vrne true, kadar zvok zaostaja vec kot [mejaMs] milisekund in je bolje izpustiti star
     * okvir kot dovoliti zamik za sliko.
     */
    fun izpustiStarega(zakasnitevMs: Long, mejaMs: Long = 120L): Boolean = zakasnitevMs > mejaMs

    /**
     * Float32 uporabimo samo na Android API 21+ ter pri najvec dveh veljavnih kanalih
     * (mono ali stereo); pri starejsih sistemih ali veckanalnem zvoku uporabimo 16-bit PCM.
     */
    fun jeFloat32(sdk: Int, kanali: Int): Boolean =
        sdk >= MIN_SDK_FLOAT32 && kanaliVeljavni(kanali) <= 2

    /**
     * Pretvori vzorce float32 v signed 16-bit PCM: NaN gre v 0, ostale vrednosti se omejijo
     * na [-1.0, 1.0] in zaokrozijo na Short. Nikoli ne preseze dolzine vhoda ali izhoda
     * in vrne stevilo dejansko zapisanih vzorcev.
     */
    fun pretvoriFloatVPcm16(vhod: FloatArray, izhod: ShortArray, stevilo: Int): Int {
        if (stevilo <= 0) return 0
        val zaZapis = minOf(stevilo, vhod.size, izhod.size)
        for (i in 0 until zaZapis) {
            val vzorec = vhod[i]
            izhod[i] = if (vzorec.isNaN()) {
                0
            } else {
                (vzorec.coerceIn(-1.0f, 1.0f) * PCM16_NAJVEC).roundToInt().toShort()
            }
        }
        return zaZapis
    }

    /** Vecja razlika med zigom in zigom, izracunanim iz prejsnjega ziga in "bajt" (ms), pomeni vrzel: nov odsek. */
    const val ZVEZNOST_MS = 40L

    /**
     * Ali se zvok z zigom [zigMs] in stevcem [bajt] zvezno nadaljuje iz prejsnjega ziga: pricakovan zig je
     * [prejZigMs] + trajanje bajtov med obema. Vrzel (izpusceni ali neposlani tihi vzorci, poravnava ure
     * na racunalniku) nad [ZVEZNOST_MS] ali stevec nazaj -> false.
     */
    fun zvezenOdsek(prejZigMs: Long, prejBajt: Long, zigMs: Long, bajt: Long, hz: Int, kanali: Int, float32: Boolean): Boolean {
        if (bajt < prejBajt) return false
        val frekvenca = if (hz <= 0) PRIVZETA_FREKVENCA_HZ else hz
        val vzorcev = (bajt - prejBajt) / velikostVzorca(kanali, float32)
        val razlika = zigMs - (prejZigMs + vzorcev * 1000L / frekvenca)
        return razlika <= ZVEZNOST_MS && razlika >= -ZVEZNOST_MS
    }

    /**
     * Meja za izpust zaostalega zvoka: namerni zamik zvoka (tisina v vrsti) + dvakratni ciljni medpomnilnik.
     * Fiksnih 120 ms bi odvrglo namerno zakasnjen zvok (pregled S3/Z3).
     */
    fun mejaIzpustaMs(namerniZamikMs: Long): Long = maxOf(namerniZamikMs, 0L) + 2L * CILJNI_MEDPOMNILNIK_MS

    /** Koliko ms zvoka izpustiti, da zaostanek pade na namerni zamik + ciljni medpomnilnik; 0, ce ni cez mejo. */
    fun zaIzpustMs(zaostanekMs: Long, namerniZamikMs: Long): Long =
        if (izpustiStarega(zaostanekMs, mejaIzpustaMs(namerniZamikMs))) {
            zaostanekMs - (maxOf(namerniZamikMs, 0L) + CILJNI_MEDPOMNILNIK_MS)
        } else {
            0L
        }

    /** Stevilo bajtov za [ms] zvoka, vedno cel vzorec; nepozitivno -> 0, frekvenca <= 0 -> 48000 Hz. */
    fun bajtovZaMs(ms: Long, hz: Int, kanali: Int, float32: Boolean): Int {
        if (ms <= 0L) return 0
        val frekvenca = if (hz <= 0) PRIVZETA_FREKVENCA_HZ else hz
        val vzorecBajtov = velikostVzorca(kanali, float32).toLong()
        val bajtov = (frekvenca.toLong() * ms / 1000L) * vzorecBajtov
        val najvec = (Int.MAX_VALUE.toLong() / vzorecBajtov) * vzorecBajtov
        return minOf(bajtov, najvec).toInt()
    }

    /**
     * Okvirji, ki so v AudioTracku zapisani in se niso predvajani. Glava predvajanja (getPlaybackHeadPosition)
     * je 32-bitna in se po ~24 h pri 48 kHz prelije, zato racunamo po modulu 2^32.
     */
    fun vTrackuOkvirjev(zapisanihOkvirjev: Long, glavaPredvajanja: Int): Long {
        val razlika = (zapisanihOkvirjev - (glavaPredvajanja.toLong() and 0xFFFFFFFFL)) and 0xFFFFFFFFL
        return if (razlika > Int.MAX_VALUE.toLong()) 0L else razlika
    }

    /** Skupni zaostanek zvoka v ms: okvirji v AudioTracku + bajti v nasi vrsti. */
    fun zaostanekMs(vTrackuOkvirjev: Long, bajtovVVrsti: Int, hz: Int, kanali: Int, float32: Boolean): Long {
        val frekvenca = if (hz <= 0) PRIVZETA_FREKVENCA_HZ else hz
        val vTracku = if (vTrackuOkvirjev <= 0L) 0L else vTrackuOkvirjev * 1000L / frekvenca
        return vTracku + zakasnitevMs(bajtovVVrsti, frekvenca, kanali, float32)
    }

    /** Zig zajema bajta [odmikBajtov] znotraj okvirja z zigom [zig] (zacetek okvirja). */
    fun zigPoOdmiku(zig: Long, odmikBajtov: Int, hz: Int, kanali: Int, float32: Boolean): Long {
        if (odmikBajtov <= 0) return zig
        val frekvenca = if (hz <= 0) PRIVZETA_FREKVENCA_HZ else hz
        val vzorcev = (odmikBajtov / velikostVzorca(kanali, float32)).toLong()
        return zig + vzorcev * 1000L / frekvenca
    }

    /**
     * Vrsta zvoka pred AudioTrackom. WRITE_NON_BLOCKING lahko vzame manj, kot ponudimo: ostanek ostane tu
     * (pregled Z2), ne gre v nic. Ni sinhronizirana: uporablja jo samo nit toka.
     */
    class VrstaZvoka {
        /**
         * Del okvirja: podatki[od until konec]; [zig] velja za bajt [zacetek] (null = tisina ali brez ziga).
         * [novOdsek]: od tega kosa naprej zvok ni zvezen s prejsnjim (vrzel, tisina, izpust), zig je treba zapisati znova.
         */
        class Kos(val podatki: ByteArray, val zacetek: Int, val konec: Int, val zig: Long?, var novOdsek: Boolean) {
            var od: Int = zacetek
        }

        private val kosi = ArrayDeque<Kos>()
        private var naslednjiNov = true

        /** Bajti, ki se cakajo v vrsti. */
        var bajtov = 0
            private set

        fun dodaj(podatki: ByteArray, od: Int, dolzina: Int, zig: Long?, novOdsek: Boolean) {
            if (dolzina <= 0 || od < 0 || od + dolzina > podatki.size) return
            kosi.addLast(Kos(podatki, od, od + dolzina, zig, novOdsek || naslednjiNov))
            naslednjiNov = false
            bajtov += dolzina
        }

        /** Naslednji dodani kos zacne nov odsek (npr. za vstavljeno tisino). */
        fun prekini() {
            naslednjiNov = true
        }

        fun prvi(): Kos? = kosi.firstOrNull()

        /** Prvi kos je AudioTrack sprejel za [n] bajtov. */
        fun porabi(n: Int) {
            val kos = kosi.firstOrNull() ?: return
            val vzeto = minOf(maxOf(n, 0), kos.konec - kos.od)
            kos.od += vzeto
            bajtov -= vzeto
            if (kos.od >= kos.konec) kosi.removeFirst()
        }

        /** Izpusti do [n] najstarejsih bajtov (tudi cez vec kosov); vrne, koliko jih je izpustil. Za izpustom nov odsek. */
        fun izpusti(n: Int): Int {
            var ostane = maxOf(n, 0)
            var izpusceno = 0
            while (ostane > 0 && kosi.isNotEmpty()) {
                val kos = kosi.first()
                val vzeto = minOf(ostane, kos.konec - kos.od)
                kos.od += vzeto
                ostane -= vzeto
                izpusceno += vzeto
                if (kos.od >= kos.konec) kosi.removeFirst()
            }
            bajtov -= izpusceno
            if (izpusceno > 0) {
                val glava = kosi.firstOrNull()
                if (glava != null) glava.novOdsek = true else naslednjiNov = true
            }
            return izpusceno
        }

        fun pocisti() {
            kosi.clear()
            bajtov = 0
            naslednjiNov = true
        }
    }
}
