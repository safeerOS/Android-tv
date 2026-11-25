package si.safeer.tv.os

/**
 * Preizkus cistih pravil za zvok pri prenosu zaslona ([ZaslonZvok]).
 * Vsako preverjanje ima svoje ime; ob prvi napaki vrze AssertionError.
 */
private var stevecPreverjanj = 0

private fun preveri(ime: String, pogoj: Boolean) {
    stevecPreverjanj++
    if (!pogoj) {
        throw AssertionError("Napaka pri preverjanju [$ime]")
    }
}

private fun <T> preveriEnako(ime: String, pricakovano: T, dejansko: T) {
    stevecPreverjanj++
    if (pricakovano != dejansko) {
        throw AssertionError("Napaka pri preverjanju [$ime]: pricakovano=$pricakovano, dejansko=$dejansko")
    }
}

fun main() {
    // 1. Konstante.
    preveriEnako("konstanta NAJVEC_KANALOV", 8, ZaslonZvok.NAJVEC_KANALOV)
    preveriEnako("konstanta CILJNI_MEDPOMNILNIK_MS", 60, ZaslonZvok.CILJNI_MEDPOMNILNIK_MS)
    preveriEnako("konstanta NAJMANJ_MEDPOMNILNIK_MS", 20, ZaslonZvok.NAJMANJ_MEDPOMNILNIK_MS)
    preveriEnako("konstanta NAJVEC_MEDPOMNILNIK_MS", 200, ZaslonZvok.NAJVEC_MEDPOMNILNIK_MS)

    // 2. Veljavni kanali (robni in obicajni primeri).
    preveriEnako("kanali -10 -> 2", 2, ZaslonZvok.kanaliVeljavni(-10))
    preveriEnako("kanali -1 -> 2", 2, ZaslonZvok.kanaliVeljavni(-1))
    preveriEnako("kanali 0 -> 2", 2, ZaslonZvok.kanaliVeljavni(0))
    preveriEnako("kanali 1 -> 1", 1, ZaslonZvok.kanaliVeljavni(1))
    preveriEnako("kanali 2 -> 2", 2, ZaslonZvok.kanaliVeljavni(2))
    preveriEnako("kanali 3 -> 2", 2, ZaslonZvok.kanaliVeljavni(3))
    preveriEnako("kanali 4 -> 4 (kvadro)", 4, ZaslonZvok.kanaliVeljavni(4))
    preveriEnako("kanali 5 -> 6 (5.1)", 6, ZaslonZvok.kanaliVeljavni(5))
    preveriEnako("kanali 6 -> 6 (5.1)", 6, ZaslonZvok.kanaliVeljavni(6))
    preveriEnako("kanali 7 -> 8 (7.1)", 8, ZaslonZvok.kanaliVeljavni(7))
    preveriEnako("kanali 8 -> 8 (7.1)", 8, ZaslonZvok.kanaliVeljavni(8))
    preveriEnako("kanali 9 -> 8 (omejeno na NAJVEC_KANALOV)", 8, ZaslonZvok.kanaliVeljavni(9))
    preveriEnako("kanali 64 -> 8", 8, ZaslonZvok.kanaliVeljavni(64))

    // 3. Imena razporedov kanalov.
    preveriEnako("razpored -1 -> stereo", "stereo", ZaslonZvok.imeRazporeda(-1))
    preveriEnako("razpored 0 -> stereo", "stereo", ZaslonZvok.imeRazporeda(0))
    preveriEnako("razpored 1 -> mono", "mono", ZaslonZvok.imeRazporeda(1))
    preveriEnako("razpored 2 -> stereo", "stereo", ZaslonZvok.imeRazporeda(2))
    preveriEnako("razpored 3 -> stereo", "stereo", ZaslonZvok.imeRazporeda(3))
    preveriEnako("razpored 4 -> kvadro", "kvadro", ZaslonZvok.imeRazporeda(4))
    preveriEnako("razpored 5 -> 5.1", "5.1", ZaslonZvok.imeRazporeda(5))
    preveriEnako("razpored 6 -> 5.1", "5.1", ZaslonZvok.imeRazporeda(6))
    preveriEnako("razpored 7 -> 7.1", "7.1", ZaslonZvok.imeRazporeda(7))
    preveriEnako("razpored 8 -> 7.1", "7.1", ZaslonZvok.imeRazporeda(8))
    preveriEnako("razpored 9 -> 7.1", "7.1", ZaslonZvok.imeRazporeda(9))

    // 4. Velikost vzorca v bajtih (16-bit in float32).
    preveriEnako("vzorec mono 16-bit = 2 B", 2, ZaslonZvok.velikostVzorca(1, false))
    preveriEnako("vzorec mono float32 = 4 B", 4, ZaslonZvok.velikostVzorca(1, true))
    preveriEnako("vzorec stereo 16-bit = 4 B", 4, ZaslonZvok.velikostVzorca(2, false))
    preveriEnako("vzorec stereo float32 = 8 B", 8, ZaslonZvok.velikostVzorca(2, true))
    preveriEnako("vzorec kvadro 16-bit = 8 B", 8, ZaslonZvok.velikostVzorca(4, false))
    preveriEnako("vzorec 5.1 16-bit = 12 B", 12, ZaslonZvok.velikostVzorca(6, false))
    preveriEnako("vzorec 5.1 float32 = 24 B", 24, ZaslonZvok.velikostVzorca(6, true))
    preveriEnako("vzorec 7.1 16-bit = 16 B", 16, ZaslonZvok.velikostVzorca(8, false))
    preveriEnako("vzorec 7.1 float32 = 32 B", 32, ZaslonZvok.velikostVzorca(8, true))
    preveriEnako("vzorec kanali 0 (stereo) 16-bit = 4 B", 4, ZaslonZvok.velikostVzorca(0, false))
    preveriEnako("vzorec kanali 5 (5.1) float32 = 24 B", 24, ZaslonZvok.velikostVzorca(5, true))

    // 5. Velikost medpomnilnika v bajtih.
    preveriEnako(
        "medpomnilnik 48000 Hz stereo 16-bit 60 ms = 11520 B",
        11520,
        ZaslonZvok.medpomnilnikBajtov(48000, 2, false, 60, 0),
    )
    preveriEnako(
        "medpomnilnik hz=0 uporabi 48000 Hz -> 11520 B",
        11520,
        ZaslonZvok.medpomnilnikBajtov(0, 2, false, 60, 0),
    )
    preveriEnako(
        "medpomnilnik hz=-44100 uporabi 48000 Hz -> 11520 B",
        11520,
        ZaslonZvok.medpomnilnikBajtov(-44100, 2, false, 60, 0),
    )
    preveriEnako(
        "medpomnilnik ciljMs pod 20 ms omejen na 20 ms -> 3840 B",
        3840,
        ZaslonZvok.medpomnilnikBajtov(48000, 2, false, 5, 0),
    )
    preveriEnako(
        "medpomnilnik ciljMs nad 200 ms omejen na 200 ms -> 38400 B",
        38400,
        ZaslonZvok.medpomnilnikBajtov(48000, 2, false, 500, 0),
    )
    preveriEnako(
        "medpomnilnik najmanjSistema ima prednost (16000 > 11520)",
        16000,
        ZaslonZvok.medpomnilnikBajtov(48000, 2, false, 60, 16000),
    )
    preveriEnako(
        "medpomnilnik neporavnan najmanjSistema se poravna navzgor na 4 B",
        16004,
        ZaslonZvok.medpomnilnikBajtov(48000, 2, false, 60, 16001),
    )
    val med51 = ZaslonZvok.medpomnilnikBajtov(48000, 6, true, 60, 0)
    preveriEnako("medpomnilnik 5.1 float32 60 ms = 69120 B", 69120, med51)
    preveri("medpomnilnik 5.1 float32 poravnan na 24 B", med51 % 24 == 0)

    val med51Sis = ZaslonZvok.medpomnilnikBajtov(48000, 6, true, 60, 100000)
    preveriEnako("medpomnilnik 5.1 float32 z najmanjSistema 100000 -> 100008 B", 100008, med51Sis)
    preveri("medpomnilnik 5.1 float32 z najmanjSistema poravnan na 24 B", med51Sis % 24 == 0)
    preveri("medpomnilnik 5.1 float32 >= najmanjSistema", med51Sis >= 100000)

    val medNizkaHz = ZaslonZvok.medpomnilnikBajtov(1, 6, true, 20, -2)
    preveriEnako("medpomnilnik pri hz=1 vedno vsaj en cel vzorec (24 B)", 24, medNizkaHz)

    // 6. Zakasnitev v milisekundah.
    preveriEnako(
        "zakasnitev 4800 B stereo 16-bit pri 48000 Hz = 25 ms",
        25L,
        ZaslonZvok.zakasnitevMs(4800, 48000, 2, false),
    )
    preveriEnako(
        "zakasnitev 11520 B stereo 16-bit pri 48000 Hz = 60 ms",
        60L,
        ZaslonZvok.zakasnitevMs(11520, 48000, 2, false),
    )
    preveriEnako(
        "zakasnitev 4800 B pri hz=0 uporabi 48000 Hz = 25 ms",
        25L,
        ZaslonZvok.zakasnitevMs(4800, 0, 2, false),
    )
    preveriEnako(
        "zakasnitev 0 B = 0 ms",
        0L,
        ZaslonZvok.zakasnitevMs(0, 48000, 2, false),
    )
    preveriEnako(
        "zakasnitev negativnih bajtov = 0 ms",
        0L,
        ZaslonZvok.zakasnitevMs(-1000, 48000, 2, false),
    )
    preveriEnako(
        "zakasnitev 23040 B 5.1 float32 pri 48000 Hz = 20 ms",
        20L,
        ZaslonZvok.zakasnitevMs(23040, 48000, 6, true),
    )
    preveriEnako(
        "zakasnitev mono 16-bit pri 44100 Hz za 88200 B = 1000 ms",
        1000L,
        ZaslonZvok.zakasnitevMs(88200, 44100, 1, false),
    )

    // 7. Izpustitev zaostalega zvoka.
    preveriEnako("izpustiStarega pri 0 ms (privzeta meja 120)", false, ZaslonZvok.izpustiStarega(0L))
    preveriEnako("izpustiStarega pri 120 ms (natanko na meji)", false, ZaslonZvok.izpustiStarega(120L))
    preveriEnako("izpustiStarega pri 121 ms (cez privzeto mejo)", true, ZaslonZvok.izpustiStarega(121L))
    preveriEnako("izpustiStarega z lastno mejo 50 ms pri 50 ms", false, ZaslonZvok.izpustiStarega(50L, 50L))
    preveriEnako("izpustiStarega z lastno mejo 50 ms pri 51 ms", true, ZaslonZvok.izpustiStarega(51L, 50L))

    // 8. Izbira zapisa float32 glede na SDK in kanale.
    preveriEnako("jeFloat32 sdk 20 mono -> false", false, ZaslonZvok.jeFloat32(20, 1))
    preveriEnako("jeFloat32 sdk 20 stereo -> false", false, ZaslonZvok.jeFloat32(20, 2))
    preveriEnako("jeFloat32 sdk 21 mono -> true", true, ZaslonZvok.jeFloat32(21, 1))
    preveriEnako("jeFloat32 sdk 21 stereo -> true", true, ZaslonZvok.jeFloat32(21, 2))
    preveriEnako("jeFloat32 sdk 30 kanali 0 (stereo) -> true", true, ZaslonZvok.jeFloat32(30, 0))
    preveriEnako("jeFloat32 sdk 30 kanali 3 (stereo) -> true", true, ZaslonZvok.jeFloat32(30, 3))
    preveriEnako("jeFloat32 sdk 30 kvadro (4) -> false", false, ZaslonZvok.jeFloat32(30, 4))
    preveriEnako("jeFloat32 sdk 30 5.1 (6) -> false", false, ZaslonZvok.jeFloat32(30, 6))
    preveriEnako("jeFloat32 sdk 33 7.1 (8) -> false", false, ZaslonZvok.jeFloat32(33, 8))

    // 9. Pretvorba float32 v PCM 16-bit.
    val vhodOsnovni = floatArrayOf(0.0f, 1.0f, -1.0f, 0.5f, -0.25f)
    val izhodOsnovni = ShortArray(5)
    val zapisanihOsnovni = ZaslonZvok.pretvoriFloatVPcm16(vhodOsnovni, izhodOsnovni, 5)
    preveriEnako("pcm16 stevilo zapisanih osnovnih", 5, zapisanihOsnovni)
    preveriEnako("pcm16 0.0 -> 0", 0.toShort(), izhodOsnovni[0])
    preveriEnako("pcm16 1.0 -> 32767", 32767.toShort(), izhodOsnovni[1])
    preveriEnako("pcm16 -1.0 -> -32767", (-32767).toShort(), izhodOsnovni[2])
    preveriEnako("pcm16 0.5 -> 16384", 16384.toShort(), izhodOsnovni[3])
    preveriEnako("pcm16 -0.25 -> -8192", (-8192).toShort(), izhodOsnovni[4])

    // Clip +-1.5, neskoncnost in NaN.
    val vhodRobni = floatArrayOf(1.5f, -1.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
    val izhodRobni = ShortArray(5)
    val zapisanihRobni = ZaslonZvok.pretvoriFloatVPcm16(vhodRobni, izhodRobni, 5)
    preveriEnako("pcm16 stevilo zapisanih robnih", 5, zapisanihRobni)
    preveriEnako("pcm16 clip +1.5 -> 32767", 32767.toShort(), izhodRobni[0])
    preveriEnako("pcm16 clip -1.5 -> -32767", (-32767).toShort(), izhodRobni[1])
    preveriEnako("pcm16 NaN -> 0", 0.toShort(), izhodRobni[2])
    preveriEnako("pcm16 +Infinity -> 32767", 32767.toShort(), izhodRobni[3])
    preveriEnako("pcm16 -Infinity -> -32767", (-32767).toShort(), izhodRobni[4])

    // Izhod krajsi od vhoda.
    val dolgVhod = floatArrayOf(1.0f, -1.0f, 0.5f, -0.5f, 0.25f)
    val kratekIzhod = ShortArray(2)
    val zapisanihKratek = ZaslonZvok.pretvoriFloatVPcm16(dolgVhod, kratekIzhod, 5)
    preveriEnako("pcm16 izhod krajsi od vhoda vrne dolzino izhoda", 2, zapisanihKratek)
    preveriEnako("pcm16 izhod krajsi [0]", 32767.toShort(), kratekIzhod[0])
    preveriEnako("pcm16 izhod krajsi [1]", (-32767).toShort(), kratekIzhod[1])

    // Vhod krajsi od izhoda: preostanek izhoda ostane nedotaknjen.
    val kratekVhod = floatArrayOf(1.0f)
    val dolgIzhod = shortArrayOf(99, 77, 55)
    val zapisanihVhod = ZaslonZvok.pretvoriFloatVPcm16(kratekVhod, dolgIzhod, 3)
    preveriEnako("pcm16 vhod krajsi od izhoda vrne dolzino vhoda", 1, zapisanihVhod)
    preveriEnako("pcm16 vhod krajsi [0] prepisan", 32767.toShort(), dolgIzhod[0])
    preveriEnako("pcm16 vhod krajsi [1] nedotaknjen", 77.toShort(), dolgIzhod[1])

    // Stevilo manjse od obeh in stevilo <= 0.
    val delniIzhod = shortArrayOf(11, 22, 33)
    preveriEnako("pcm16 stevilo=1 zapise samo enega", 1, ZaslonZvok.pretvoriFloatVPcm16(dolgVhod, delniIzhod, 1))
    preveriEnako("pcm16 stevilo=1 pusti [1] nedotaknjen", 22.toShort(), delniIzhod[1])
    preveriEnako("pcm16 stevilo=0 zapise 0", 0, ZaslonZvok.pretvoriFloatVPcm16(dolgVhod, delniIzhod, 0))
    preveriEnako("pcm16 stevilo=-3 zapise 0", 0, ZaslonZvok.pretvoriFloatVPcm16(dolgVhod, delniIzhod, -3))

    // Izpust zaostanka brez poka (25. 11. 2026: lastnik je pri filmu slisal »treske, poke«). Sinus 440 Hz, mono s16;
    // izpust 13,7 ms (ni cel val) - brez preliva je skok med zadnjim predvajanim in prvim naslednjim vzorcem velik.
    run {
        val hz = 48000
        val n = 4800
        val val16 = ByteArray(n * 2)
        for (k in 0 until n) {
            val v = (Math.sin(2 * Math.PI * 440.0 * k / hz) * 20000).toInt()
            val16[2 * k] = (v and 0xff).toByte(); val16[2 * k + 1] = ((v shr 8) and 0xff).toByte()
        }
        fun vzorec(b: ByteArray, i: Int) = ((b[2 * i].toInt() and 0xff) or (b[2 * i + 1].toInt() shl 8)).toShort().toInt()
        fun najvecjiSkok(izpusti: (ZaslonZvok.VrstaZvoka) -> Unit): Int {
            val v = ZaslonZvok.VrstaZvoka()
            v.dodaj(val16.copyOf(), 0, 1000, null, false)       // 500 vzorcev ze »predvajanih«
            v.dodaj(val16.copyOf(), 1000, val16.size - 1000, null, false)
            val pred = ByteArray(1000); val p0 = v.prvi()!!; System.arraycopy(p0.podatki, p0.od, pred, 0, 1000); v.porabi(1000)
            izpusti(v)
            val po = ByteArray(v.bajtov); var i = 0
            while (v.prvi() != null) { val k = v.prvi()!!; val d = k.konec - k.od; System.arraycopy(k.podatki, k.od, po, i, d); i += d; v.porabi(d) }
            val vse = pred + po
            var skok = 0
            for (k in 1 until vse.size / 2) skok = maxOf(skok, Math.abs(vzorec(vse, k) - vzorec(vse, k - 1)))
            return skok
        }
        val izpust = 1316                       // 658 vzorcev = 13,7 ms
        val brez = najvecjiSkok { it.izpusti(izpust) }
        val zPrelivom = najvecjiSkok { it.izpustiPreliv(izpust, 768) }  // 8 ms
        val naravni = (2 * Math.PI * 440.0 / hz * 20000).toInt() + 2
        preveri("izpust brez preliva naredi pok (skok $brez)", brez > 3 * naravni)
        preveri("izpust s prelivom brez poka (skok $zPrelivom, naravni $naravni)", zPrelivom <= 2 * naravni)
        val v = ZaslonZvok.VrstaZvoka()
        v.dodaj(ByteArray(100), 0, 100, null, false)
        preveriEnako("premalo za preliv -> navaden izpust", 40, v.izpustiPreliv(40, 64))
    }

    preveri("skupaj vsaj 40 preverjanj", stevecPreverjanj >= 40)
    println("ZaslonZvokTest: OK")
}
