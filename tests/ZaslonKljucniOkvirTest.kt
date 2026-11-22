package si.safeer.tv.os

/**
 * Preizkus cistih pravil za zahtevo po kljucnem okvirju ([ZaslonKljucniOkvir]).
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

private fun preveriOdlocitev(
    ime: String,
    pricakovanoPoslji: Boolean,
    pricakovanoCakajMs: Long,
    dejansko: ZaslonKljucniOkvir.Odlocitev,
) {
    preveriEnako("$ime (poslji)", pricakovanoPoslji, dejansko.poslji)
    preveriEnako("$ime (cakajMs)", pricakovanoCakajMs, dejansko.cakajMs)
}

fun main() {
    // 1. Konstante.
    preveriEnako("konstanta NAJMANJ_RAZMIK_MS", 500L, ZaslonKljucniOkvir.NAJMANJ_RAZMIK_MS)
    preveriEnako("konstanta NAJVEC_RAZMIK_MS", 4000L, ZaslonKljucniOkvir.NAJVEC_RAZMIK_MS)
    preveriEnako("konstanta ZACETNI_RAZMIK_MS", 500L, ZaslonKljucniOkvir.ZACETNI_RAZMIK_MS)
    preveriEnako("konstanta TISINA_PO_USPEHU_MS", 2000L, ZaslonKljucniOkvir.TISINA_PO_USPEHU_MS)

    // 2. Prva zahteva gre takoj, druga cez 100 ms je zadrzana s cakajMs 400.
    val p = ZaslonKljucniOkvir.Presojevalec()
    preveriEnako("zacetni stevec zaporednih je 0", 0, p.zaporednihNeuspelih)
    preveriOdlocitev(
        "prva zahteva gre takoj",
        true,
        0L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 1000L),
    )
    preveriEnako("stevec po prvi zahtevi je 1", 1, p.zaporednihNeuspelih)
    preveriOdlocitev(
        "druga zahteva cez 100 ms je zadrzana (400 ms)",
        false,
        400L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 1100L),
    )
    preveriEnako("zadrzana zahteva ne poveca stevca", 1, p.zaporednihNeuspelih)
    preveriOdlocitev(
        "druga zahteva cez 499 ms caka se 1 ms",
        false,
        1L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 1499L),
    )

    // 3. Eksponentni zamik: razmik se podvaja 500, 1000, 2000, 4000, 4000.
    // 2. poslana zahteva pri t = 1500 (+500 ms) -> naslednji razmik 1000 ms.
    preveriOdlocitev(
        "druga zahteva pri +500 ms gre skozi",
        true,
        0L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 1500L),
    )
    preveriEnako("stevec po drugi zahtevi je 2", 2, p.zaporednihNeuspelih)
    preveriOdlocitev(
        "tretja zahteva pri +500 ms (razmik 1000) caka 500 ms",
        false,
        500L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 2000L),
    )

    // 3. poslana zahteva pri t = 2500 (+1000 ms) -> naslednji razmik 2000 ms.
    preveriOdlocitev(
        "tretja zahteva pri +1000 ms gre skozi",
        true,
        0L,
        p.presodi(ZaslonKljucniOkvir.Razlog.IZGUBA_PAKETA, 2500L),
    )
    preveriEnako("stevec po tretji zahtevi je 3", 3, p.zaporednihNeuspelih)
    preveriOdlocitev(
        "cetrta zahteva pri +1000 ms (razmik 2000) caka 1000 ms",
        false,
        1000L,
        p.presodi(ZaslonKljucniOkvir.Razlog.ZASTOJ_SLIKE, 3500L),
    )

    // 4. poslana zahteva pri t = 4500 (+2000 ms) -> naslednji razmik 4000 ms.
    preveriOdlocitev(
        "cetrta zahteva pri +2000 ms gre skozi",
        true,
        0L,
        p.presodi(ZaslonKljucniOkvir.Razlog.ZASTOJ_SLIKE, 4500L),
    )
    preveriEnako("stevec po cetrti zahtevi je 4", 4, p.zaporednihNeuspelih)
    preveriOdlocitev(
        "peta zahteva pri +3999 ms (razmik 4000) caka 1 ms",
        false,
        1L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 8499L),
    )

    // 5. poslana zahteva pri t = 8500 (+4000 ms) -> naslednji razmik ostane omejen na 4000 ms.
    preveriOdlocitev(
        "peta zahteva pri +4000 ms gre skozi",
        true,
        0L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 8500L),
    )
    preveriEnako("stevec po peti zahtevi je 5", 5, p.zaporednihNeuspelih)
    preveriOdlocitev(
        "sesta zahteva pri +2500 ms (omejeno na 4000) caka 1500 ms",
        false,
        1500L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 11000L),
    )
    preveriOdlocitev(
        "sesta zahteva pri +4000 ms gre skozi (strop 4000)",
        true,
        0L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 12500L),
    )
    preveriEnako("stevec po sesti zahtevi je 6", 6, p.zaporednihNeuspelih)

    // 4. Uspeh vrne razmik na 500, stevec na 0 in doda 2000 ms tisine.
    p.uspeh(13000L)
    preveriEnako("uspeh ponastavi stevec na 0", 0, p.zaporednihNeuspelih)
    preveriOdlocitev(
        "samodejna zahteva takoj ob uspehu caka 2000 ms",
        false,
        2000L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 13000L),
    )
    preveriOdlocitev(
        "samodejna zahteva 700 ms po uspehu caka 1300 ms",
        false,
        1300L,
        p.presodi(ZaslonKljucniOkvir.Razlog.IZGUBA_PAKETA, 13700L),
    )
    preveriOdlocitev(
        "samodejna zahteva 1999 ms po uspehu caka 1 ms",
        false,
        1L,
        p.presodi(ZaslonKljucniOkvir.Razlog.ZASTOJ_SLIKE, 14999L),
    )
    preveriOdlocitev(
        "samodejna zahteva tocno 2000 ms po uspehu gre skozi",
        true,
        0L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 15000L),
    )
    preveriEnako("stevec po prvi zahtevi po uspehu je 1", 1, p.zaporednihNeuspelih)
    preveriOdlocitev(
        "razmik po uspehu je spet 500 ms (cez 100 ms caka 400 ms)",
        false,
        400L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 15100L),
    )
    preveriOdlocitev(
        "druga zahteva po uspehu cez 500 ms gre skozi",
        true,
        0L,
        p.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 15500L),
    )

    // 5. ROCNO ob tisini in ob dolgem zamiku vseeno gre, razen ce je < 100 ms od zadnje zahteve.
    val pRocno = ZaslonKljucniOkvir.Presojevalec()
    pRocno.uspeh(1000L)
    preveriOdlocitev(
        "ROCNO ob tisini brez prejsnje zahteve gre takoj",
        true,
        0L,
        pRocno.presodi(ZaslonKljucniOkvir.Razlog.ROCNO, 1050L),
    )
    preveriOdlocitev(
        "ROCNO cez 40 ms je zadrzano (60 ms)",
        false,
        60L,
        pRocno.presodi(ZaslonKljucniOkvir.Razlog.ROCNO, 1090L),
    )
    preveriOdlocitev(
        "ROCNO cez 99 ms je zadrzano (1 ms)",
        false,
        1L,
        pRocno.presodi(ZaslonKljucniOkvir.Razlog.ROCNO, 1149L),
    )
    preveriOdlocitev(
        "ROCNO cez tocno 100 ms gre skozi tudi med tisino",
        true,
        0L,
        pRocno.presodi(ZaslonKljucniOkvir.Razlog.ROCNO, 1150L),
    )
    preveriOdlocitev(
        "samodejna zahteva med tisino po ROCNO je se vedno zadrzana",
        false,
        1800L,
        pRocno.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 1200L),
    )
    // ROCNO po samodejni zahtevi: zadrzano pod 100 ms, dovoljeno pri 100 ms.
    val pRocnoPoSam = ZaslonKljucniOkvir.Presojevalec()
    preveriOdlocitev(
        "samodejna zahteva pri t=5000 gre takoj",
        true,
        0L,
        pRocnoPoSam.presodi(ZaslonKljucniOkvir.Razlog.IZGUBA_PAKETA, 5000L),
    )
    preveriOdlocitev(
        "ROCNO 50 ms po samodejni zahtevi caka 50 ms",
        false,
        50L,
        pRocnoPoSam.presodi(ZaslonKljucniOkvir.Razlog.ROCNO, 5050L),
    )
    preveriOdlocitev(
        "ROCNO 100 ms po samodejni zahtevi gre skozi",
        true,
        0L,
        pRocnoPoSam.presodi(ZaslonKljucniOkvir.Razlog.ROCNO, 5100L),
    )

    // 6. Cas, ki gre nazaj: obravnavan kot zadnji znani cas, brez sesutja ali napacnega rezultata.
    val pCas = ZaslonKljucniOkvir.Presojevalec()
    preveriOdlocitev(
        "cas nazaj: prva zahteva pri t=2000 gre takoj",
        true,
        0L,
        pCas.presodi(ZaslonKljucniOkvir.Razlog.ZASTOJ_SLIKE, 2000L),
    )
    preveriOdlocitev(
        "cas nazaj: preverjanje pri t=2200 caka 300 ms",
        false,
        300L,
        pCas.presodi(ZaslonKljucniOkvir.Razlog.ZASTOJ_SLIKE, 2200L),
    )
    preveriOdlocitev(
        "cas nazaj: klic s t=1800 se steje kot t=2200 (caka 300 ms)",
        false,
        300L,
        pCas.presodi(ZaslonKljucniOkvir.Razlog.ZASTOJ_SLIKE, 1800L),
    )
    preveriOdlocitev(
        "cas nazaj: klic z negativnim casom -100 se steje kot t=2200 (caka 300 ms)",
        false,
        300L,
        pCas.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, -100L),
    )
    preveriOdlocitev(
        "cas nazaj: klic pri t=2500 po skoku nazaj gre normalno skozi",
        true,
        0L,
        pCas.presodi(ZaslonKljucniOkvir.Razlog.ZASTOJ_SLIKE, 2500L),
    )
    preveriOdlocitev(
        "cas nazaj: ROCNO s casom nazaj (t=2000 pri zadnjem t=2500) caka 100 ms",
        false,
        100L,
        pCas.presodi(ZaslonKljucniOkvir.Razlog.ROCNO, 2000L),
    )
    // Uspeh s casom nazaj se prav tako uskladi na zadnji znani cas (2500).
    pCas.uspeh(1000L)
    preveriOdlocitev(
        "cas nazaj: tisina po uspehu s casom nazaj tece od 2500 (pri 3500 caka 1000 ms)",
        false,
        1000L,
        pCas.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, 3500L),
    )

    // 7. Ponastavitev vrne vse v zacetno stanje.
    p.ponastavi()
    preveriEnako("ponastavi nastavi stevec na 0", 0, p.zaporednihNeuspelih)
    preveriOdlocitev(
        "po ponastavi gre prva zahteva takoj tudi pri casu 0",
        true,
        0L,
        p.presodi(ZaslonKljucniOkvir.Razlog.IZGUBA_PAKETA, 0L),
    )
    preveriEnako("po ponastavi in eni zahtevi je stevec 1", 1, p.zaporednihNeuspelih)
    preveriOdlocitev(
        "po ponastavi je razmik spet 500 ms (pri t=200 caka 300 ms)",
        false,
        300L,
        p.presodi(ZaslonKljucniOkvir.Razlog.IZGUBA_PAKETA, 200L),
    )

    // 8. Vsi samodejni razlogi (NAPAKA_DEKODIRNIKA, IZGUBA_PAKETA, ZASTOJ_SLIKE) se obnasajo enako.
    val pNapaka = ZaslonKljucniOkvir.Presojevalec()
    val pIzguba = ZaslonKljucniOkvir.Presojevalec()
    val pZastoj = ZaslonKljucniOkvir.Presojevalec()

    val koraki = longArrayOf(0L, 100L, 500L, 1000L, 1500L, 3500L)
    for (t in koraki) {
        val oNapaka = pNapaka.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, t)
        val oIzguba = pIzguba.presodi(ZaslonKljucniOkvir.Razlog.IZGUBA_PAKETA, t)
        val oZastoj = pZastoj.presodi(ZaslonKljucniOkvir.Razlog.ZASTOJ_SLIKE, t)
        preveriEnako("enak izid NAPAKA vs IZGUBA pri t=$t", oNapaka, oIzguba)
        preveriEnako("enak izid NAPAKA vs ZASTOJ pri t=$t", oNapaka, oZastoj)
    }
    pNapaka.uspeh(4000L)
    pIzguba.uspeh(4000L)
    pZastoj.uspeh(4000L)
    val tTisina = 5200L
    preveriEnako(
        "enak izid med tisino NAPAKA vs IZGUBA",
        pNapaka.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, tTisina),
        pIzguba.presodi(ZaslonKljucniOkvir.Razlog.IZGUBA_PAKETA, tTisina),
    )
    preveriEnako(
        "enak izid med tisino NAPAKA vs ZASTOJ",
        pNapaka.presodi(ZaslonKljucniOkvir.Razlog.NAPAKA_DEKODIRNIKA, tTisina),
        pZastoj.presodi(ZaslonKljucniOkvir.Razlog.ZASTOJ_SLIKE, tTisina),
    )

    preveri("skupaj vsaj 30 preverjanj", stevecPreverjanj >= 30)
    println("ZaslonKljucniOkvirTest: OK")
}
