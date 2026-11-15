package si.safeer.tv

private fun check(condition: Boolean, msg: String) {
    if (!condition) throw AssertionError(msg)
}

private fun potInt(t_ms: Long, v0: Float, vMax: Float, T_ms: Long): Float {
    if (t_ms <= 0L) return 0f
    val t = t_ms / 1000f
    val t_posp = T_ms / 1000f
    if (t <= t_posp) {
        val a = (vMax - v0) / t_posp
        return v0 * t + 0.5f * a * t * t
    } else {
        val potPosp = v0 * t_posp + 0.5f * (vMax - v0) * t_posp
        return potPosp + vMax * (t - t_posp)
    }
}

fun main() {
    check(KazalecGibanje.smerIzTipke(19) == KazalecGibanje.GOR && KazalecGibanje.smerIzTipke(20) == KazalecGibanje.DOL &&
        KazalecGibanje.smerIzTipke(21) == KazalecGibanje.LEVO && KazalecGibanje.smerIzTipke(22) == KazalecGibanje.DESNO) { "smerIzTipke: dpad" }
    check(KazalecGibanje.smerIzTipke(23) == 0 && KazalecGibanje.smerIzTipke(66) == 0) { "smerIzTipke: OK in Enter nista smer" }
    var preverbe = 0
    val N = KazalecGibanje.Nastavitve()
    
    // 1. Korak
    var s = KazalecGibanje.Stanje(100f, 100f)
    var iz = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    check(iz.stanje.x == 124f && iz.stanje.y == 100f, "T1.1"); preverbe++
    iz = KazalecGibanje.tik(iz.stanje, 100, 1920, 1080)
    check(iz.stanje.x == 124f, "T1.2"); preverbe++
    
    // 2. Drzanje 1s
    s = KazalecGibanje.Stanje(0f, 0f)
    iz = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    var trenutniCas = 16L
    while (trenutniCas <= 1000L) {
        if (trenutniCas >= 450 && trenutniCas % 50 < 16) {
            iz = KazalecGibanje.pritisk(iz.stanje, KazalecGibanje.DESNO, true, trenutniCas, 1920, 1080)
        }
        iz = KazalecGibanje.tik(iz.stanje, trenutniCas, 1920, 1080)
        if (trenutniCas == 1000L) break
        trenutniCas += 16L
        if (trenutniCas > 1000L) trenutniCas = 1000L
    }
    check(kotlin.math.abs(iz.stanje.x - (N.korak + potInt(1000L - N.zamikDrzanjaMs, N.zacetnaHitrost, N.najvecjaHitrost, N.pospesevanjeMs))) <= 1.5f, "T2: ${iz.stanje.x}"); preverbe++
    
    // 3. Enako s tiki na 33ms
    s = KazalecGibanje.Stanje(0f, 0f)
    var iz3 = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    trenutniCas = 33L
    while (trenutniCas <= 1000L) {
        if (trenutniCas >= 450 && trenutniCas % 50 < 33) {
            iz3 = KazalecGibanje.pritisk(iz3.stanje, KazalecGibanje.DESNO, true, trenutniCas, 1920, 1080)
        }
        iz3 = KazalecGibanje.tik(iz3.stanje, trenutniCas, 1920, 1080)
        if (trenutniCas == 1000L) break
        trenutniCas += 33L
        if (trenutniCas > 1000L) trenutniCas = 1000L
    }
    check(kotlin.math.abs(iz3.stanje.x - iz.stanje.x) <= 1.5f, "T3: diff=${kotlin.math.abs(iz3.stanje.x - iz.stanje.x)}"); preverbe++
    
    // 4. Diagonala
    s = KazalecGibanje.Stanje(100f, 100f)
    var izDiag = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    izDiag = KazalecGibanje.pritisk(izDiag.stanje, KazalecGibanje.DOL, false, 0, 1920, 1080)
    izDiag = KazalecGibanje.tik(izDiag.stanje, 500, 1920, 1080)
    val dxMove = izDiag.stanje.x - 124f
    val dyMove = izDiag.stanje.y - 124f
    check(kotlin.math.abs(dxMove - dyMove) <= 0.5f, "T4.1 dx=$dxMove dy=$dyMove"); preverbe++
    val dpEnaSmer = potInt(500 - 160, N.zacetnaHitrost, N.najvecjaHitrost, N.pospesevanjeMs)
    val lenDiag = kotlin.math.sqrt(dxMove * dxMove + dyMove * dyMove)
    check(kotlin.math.abs(lenDiag - dpEnaSmer) <= 1.5f, "T4.2 len=$lenDiag dp=$dpEnaSmer"); preverbe++
    
    // 5. LEVO + DESNO
    s = KazalecGibanje.Stanje(500f, 500f)
    var izLD = KazalecGibanje.pritisk(s, KazalecGibanje.LEVO, false, 0, 1920, 1080)
    izLD = KazalecGibanje.pritisk(izLD.stanje, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    izLD = KazalecGibanje.tik(izLD.stanje, 500, 1920, 1080)
    check(izLD.stanje.x == 500f - 24f + 24f, "T5 x=${izLD.stanje.x}"); preverbe++
    
    // 6. Spust
    s = KazalecGibanje.Stanje(100f, 100f)
    var izSpust = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    izSpust = KazalecGibanje.tik(izSpust.stanje, 500, 1920, 1080)
    izSpust = KazalecGibanje.spust(izSpust.stanje, KazalecGibanje.DESNO, 500, 1920, 1080)
    check(!izSpust.tece, "T6.1"); preverbe++
    val xPoSpustu = izSpust.stanje.x
    izSpust = KazalecGibanje.tik(izSpust.stanje, 1000, 1920, 1080)
    check(izSpust.stanje.x == xPoSpustu, "T6.2"); preverbe++
    
    // 7. Spust vkljucuje gibanje
    s = KazalecGibanje.Stanje(100f, 100f)
    var izSpust2 = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    izSpust2 = KazalecGibanje.tik(izSpust2.stanje, 480, 1920, 1080)
    val x480 = izSpust2.stanje.x
    izSpust2 = KazalecGibanje.spust(izSpust2.stanje, KazalecGibanje.DESNO, 500, 1920, 1080)
    check(izSpust2.stanje.x > x480, "T7 x=${izSpust2.stanje.x}"); preverbe++
    
    // 8. Rob DOL
    s = KazalecGibanje.Stanje(500f, 1079f)
    var izRob = KazalecGibanje.pritisk(s, KazalecGibanje.DOL, false, 0, 1920, 1080)
    check(izRob.drsiY == N.korakDrsenja && izRob.stanje.y == 1079f, "T8.1"); preverbe++
    var vsotaDrsiY = izRob.drsiY
    trenutniCas = 16L
    while (trenutniCas <= 1000L) {
        if (trenutniCas >= 450 && trenutniCas % 50 < 16) {
            izRob = KazalecGibanje.pritisk(izRob.stanje, KazalecGibanje.DOL, true, trenutniCas, 1920, 1080)
            vsotaDrsiY += izRob.drsiY
        }
        izRob = KazalecGibanje.tik(izRob.stanje, trenutniCas, 1920, 1080)
        vsotaDrsiY += izRob.drsiY
        if (trenutniCas == 1000L) break
        trenutniCas += 16L
        if (trenutniCas > 1000L) trenutniCas = 1000L
    }
    check(kotlin.math.abs(vsotaDrsiY - 1416f) <= 2f, "T8.2 vsota=$vsotaDrsiY"); preverbe++
    
    // 9. Rob DESNO na dnu
    s = KazalecGibanje.Stanje(500f, 1079f)
    var izRobD = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    var vsotaDrsiY9 = izRobD.drsiY
    var vsotaDrsiX9 = izRobD.drsiX
    trenutniCas = 16L
    while (trenutniCas <= 1000L) {
        izRobD = KazalecGibanje.tik(izRobD.stanje, trenutniCas, 1920, 1080)
        vsotaDrsiY9 += izRobD.drsiY
        vsotaDrsiX9 += izRobD.drsiX
        if (trenutniCas == 1000L) break
        trenutniCas += 16L
        if (trenutniCas > 1000L) trenutniCas = 1000L
    }
    check(vsotaDrsiY9 == 0f, "T9.1"); preverbe++
    check(vsotaDrsiX9 == 0f, "T9.2 dx=$vsotaDrsiX9"); preverbe++
    
    // 10. Rob GOR na dnu
    s = KazalecGibanje.Stanje(500f, 1079f)
    var izRobG = KazalecGibanje.pritisk(s, KazalecGibanje.GOR, false, 0, 1920, 1080)
    izRobG = KazalecGibanje.tik(izRobG.stanje, 500, 1920, 1080)
    check(izRobG.drsiY == 0f, "T10"); preverbe++
    
    // 11. Rob dokler ne pride
    s = KazalecGibanje.Stanje(500f, 1080f - 200f)
    var izRob11 = KazalecGibanje.pritisk(s, KazalecGibanje.DOL, false, 0, 1920, 1080)
    check(izRob11.drsiY == 0f, "T11.1"); preverbe++
    var prisel = false
    var kdajDrsi = false
    trenutniCas = 16L
    while (trenutniCas <= 1000L) {
        if (trenutniCas >= 450 && trenutniCas % 50 < 16) {
            izRob11 = KazalecGibanje.pritisk(izRob11.stanje, KazalecGibanje.DOL, true, trenutniCas, 1920, 1080)
        }
        izRob11 = KazalecGibanje.tik(izRob11.stanje, trenutniCas, 1920, 1080)
        if (izRob11.stanje.y == 1079f && !prisel) {
            prisel = true
        }
        if (prisel && izRob11.drsiY > 0f) kdajDrsi = true
        if (trenutniCas == 1000L) break
        trenutniCas += 16L
        if (trenutniCas > 1000L) trenutniCas = 1000L
    }
    check(prisel && kdajDrsi, "T11.2"); preverbe++
    
    // 12. Varovalo
    s = KazalecGibanje.Stanje(100f, 100f)
    var izVar = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    izVar = KazalecGibanje.tik(izVar.stanje, 2000, 1920, 1080)
    check(izVar.stanje.smeri == 0 && !izVar.tece, "T12.1"); preverbe++
    
    val s700 = KazalecGibanje.Stanje(100f, 100f)
    var iz700 = KazalecGibanje.pritisk(s700, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    iz700 = KazalecGibanje.tik(iz700.stanje, 700, 1920, 1080)
    check(izVar.stanje.x == iz700.stanje.x, "T12.2 x2000=${izVar.stanje.x} x700=${iz700.stanje.x}"); preverbe++
    
    s = KazalecGibanje.Stanje(100f, 100f)
    var izVar2 = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    izVar2 = KazalecGibanje.pritisk(izVar2.stanje, KazalecGibanje.DESNO, true, 1000, 1920, 1080)
    izVar2 = KazalecGibanje.tik(izVar2.stanje, 2000, 1920, 1080)
    
    val s1200 = KazalecGibanje.Stanje(100f, 100f)
    var iz1200 = KazalecGibanje.pritisk(s1200, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    iz1200 = KazalecGibanje.pritisk(iz1200.stanje, KazalecGibanje.DESNO, true, 1000, 1920, 1080)
    iz1200 = KazalecGibanje.tik(iz1200.stanje, 1200, 1920, 1080)
    check(izVar2.stanje.x == iz1200.stanje.x, "T12.3"); preverbe++
    
    // 13. Ponovitev ne doda koraka
    s = KazalecGibanje.Stanje(100f, 100f)
    var izPon1 = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    izPon1 = KazalecGibanje.tik(izPon1.stanje, 100, 1920, 1080)
    val xPredPon = izPon1.stanje.x
    izPon1 = KazalecGibanje.pritisk(izPon1.stanje, KazalecGibanje.DESNO, true, 100, 1920, 1080)
    check(izPon1.stanje.x == xPredPon, "T13"); preverbe++
    
    // 14. Ustavi
    s = KazalecGibanje.Stanje(100f, 100f)
    var izUst = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    izUst = KazalecGibanje.tik(izUst.stanje, 100, 1920, 1080)
    val sUst = KazalecGibanje.ustavi(izUst.stanje)
    check(sUst.smeri == 0, "T14.1"); preverbe++
    val izUst2 = KazalecGibanje.tik(sUst, 500, 1920, 1080)
    check(izUst2.stanje.x == izUst.stanje.x, "T14.2"); preverbe++
    
    // 15. Hitrost
    val h0 = KazalecGibanje.hitrost(0)
    val h1 = KazalecGibanje.hitrost(1)
    val h2 = KazalecGibanje.hitrost(2)
    val h7 = KazalecGibanje.hitrost(7)
    val hm1 = KazalecGibanje.hitrost(-1)
    check(h0.najvecjaHitrost < h1.najvecjaHitrost && h1.najvecjaHitrost < h2.najvecjaHitrost, "T15.1"); preverbe++
    // Hi Browser 5.5 na TV: najvecja hitrost ~3300 px/s pri 1920, kratek pritisk ~22 px (ogled/hi-kazalec-1411/MERITEV.md)
    check(N.najvecjaHitrost in 3000f..3600f && N.korak in 18f..30f, "T15.4 privzeto kot izmerjeni Hi Browser"); preverbe++
    check(h7 == h1, "T15.2"); preverbe++
    check(hm1 == h1, "T15.3"); preverbe++
    
    // 16. Cas nazaj in sirina = 0
    s = KazalecGibanje.Stanje(100f, 100f)
    var izCas = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 100, 1920, 1080)
    izCas = KazalecGibanje.tik(izCas.stanje, 50, 1920, 1080)
    check(izCas.stanje.x == 124f, "T16.1"); preverbe++
    
    var izNula = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 100, 0, 1080)
    check(izNula.stanje.x == 0f && izNula.stanje.y == 0f, "T16.2"); preverbe++
    
    // 17. Cistost (isti klic = isti izid)
    s = KazalecGibanje.Stanje(100f, 100f)
    val i1 = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    val i2 = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    check(i1 == i2, "T17.1"); preverbe++
    check(s.x == 100f && s.y == 100f, "T17.2"); preverbe++
    
    // --- Pregled 14. 11. 2026: stirje primeri, ki jih prva razlicica ni pokrila (vsak brez popravka pade) ---

    // 18. Cas dogodka tipke je starejsi od zadnje slicice: isti odsek poti se ne steje dvakrat
    s = KazalecGibanje.Stanje(0f, 0f)
    var izA = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    izA = KazalecGibanje.tik(izA.stanje, 500, 1920, 1080)
    var izB = izA
    izA = KazalecGibanje.pritisk(izA.stanje, KazalecGibanje.DESNO, true, 490, 1920, 1080)
    izA = KazalecGibanje.tik(izA.stanje, 516, 1920, 1080)
    izB = KazalecGibanje.tik(izB.stanje, 516, 1920, 1080)
    check(kotlin.math.abs(izA.stanje.x - izB.stanje.x) <= 0.01f, "T18.1 ura nazaj ob ponovitvi: ${izA.stanje.x} proti ${izB.stanje.x}"); preverbe++
    var izC = KazalecGibanje.spust(izB.stanje, KazalecGibanje.DESNO, 505, 1920, 1080)
    check(izC.stanje.x == izB.stanje.x && !izC.tece, "T18.2 spust s starejsim casom"); preverbe++
    izC = KazalecGibanje.tik(izC.stanje, 600, 1920, 1080)
    check(izC.stanje.x == izB.stanje.x, "T18.3 po spustu miruje"); preverbe++

    // 19. Ponovitev smeri po ustavi(): brez koraka in z uro drzanja od zdaj (kazalec ne stece z najvecjo hitrostjo)
    s = KazalecGibanje.Stanje(0f, 0f)
    var izU = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 1920, 1080)
    izU = KazalecGibanje.pritisk(izU.stanje, KazalecGibanje.DESNO, true, 450, 1920, 1080)
    izU = KazalecGibanje.tik(izU.stanje, 600, 1920, 1080)
    val sU = KazalecGibanje.ustavi(izU.stanje)
    var izU2 = KazalecGibanje.pritisk(sU, KazalecGibanje.DESNO, true, 650, 1920, 1080)
    check(izU2.stanje.x == sU.x && izU2.stanje.smeri == KazalecGibanje.DESNO, "T19.1 ponovitev po ustavi brez koraka"); preverbe++
    izU2 = KazalecGibanje.tik(izU2.stanje, 700, 1920, 1080)
    check(izU2.stanje.x == sU.x, "T19.2 pred zamikom miruje: ${izU2.stanje.x} proti ${sU.x}"); preverbe++

    // 20. Druga smer v rob med dolgim drzanjem prve: drsenje te osi zacne po zamiku od NJENEGA pritiska
    s = KazalecGibanje.Stanje(500f, 1079f)
    var izD = KazalecGibanje.pritisk(s, KazalecGibanje.DESNO, false, 0, 100000, 1080)
    var cas20 = 450L
    while (cas20 <= 2000L) {
        izD = KazalecGibanje.pritisk(izD.stanje, KazalecGibanje.DESNO, true, cas20, 100000, 1080)
        cas20 += 50L
    }
    izD = KazalecGibanje.pritisk(izD.stanje, KazalecGibanje.DOL, false, 2000, 100000, 1080)
    check(izD.drsiY == N.korakDrsenja, "T20.1 korak drsenja ob pritisku v rob: ${izD.drsiY}"); preverbe++
    izD = KazalecGibanje.tik(izD.stanje, 2100, 100000, 1080)
    check(izD.drsiY == 0f, "T20.2 pred zamikom ne drsi: ${izD.drsiY}"); preverbe++
    izD = KazalecGibanje.tik(izD.stanje, 2176, 100000, 1080)
    check(izD.drsiY > 0f && izD.drsiY < 12f, "T20.3 drsenje zacne pocasi: ${izD.drsiY}"); preverbe++

    // 21. Prihod na rob med gibanjem: ura drsenja zacne ob prihodu (461 ms), ne ob zacetku drzanja
    s = KazalecGibanje.Stanje(500f, 880f)
    var izP = KazalecGibanje.pritisk(s, KazalecGibanje.DOL, false, 0, 1920, 1080)
    var vsota21 = 0f
    var prvi21 = -1f
    var cas21 = 16L
    while (cas21 <= 1000L) {
        if (cas21 >= 450 && cas21 % 50 < 16) {
            izP = KazalecGibanje.pritisk(izP.stanje, KazalecGibanje.DOL, true, cas21, 1920, 1080)
            if (izP.drsiY > 0f && prvi21 < 0f) prvi21 = izP.drsiY
            vsota21 += izP.drsiY
        }
        izP = KazalecGibanje.tik(izP.stanje, cas21, 1920, 1080)
        if (izP.drsiY > 0f && prvi21 < 0f) prvi21 = izP.drsiY
        vsota21 += izP.drsiY
        if (cas21 == 1000L) break
        cas21 += 16L
        if (cas21 > 1000L) cas21 = 1000L
    }
    check(prvi21 > 0f && prvi21 < 15f, "T21.1 prvi tik drsenja po prihodu je majhen: $prvi21"); preverbe++
    // pricakovano iz modela (prej trdo 650 pri 1600 px/s): prihod na spodnji rob (1079) iz 880, nato drsenje do 1000 ms
    var prihod21 = 0L
    while (N.korak + potInt(prihod21, N.zacetnaHitrost, N.najvecjaHitrost, N.pospesevanjeMs) < 1079f - 880f) prihod21++
    val pricakovano21 = potInt(1000L - N.zamikDrzanjaMs - prihod21, N.zacetnoDrsenje, N.najvecjeDrsenje, N.pospesevanjeDrsenjaMs)
    check(kotlin.math.abs(vsota21 - pricakovano21) <= 14f, "T21.2 vsota drsenja od prihoda do 1 s: $vsota21 (pricakovano $pricakovano21)"); preverbe++

    // Lastnik 15. 11. 2026 (24ur.com z dna: ~40 pritiskov do roba): od sredine zaslona do roba najvec ~1,5 s drzanja; en pritisk +
    // ponovitve tipke na 50 ms (sistemski ritem), slicice po 16 ms - kot VirtualPointerView.tikSlicice. Izmerjen cas v ms.
    for (smer in intArrayOf(KazalecGibanje.DOL, KazalecGibanje.GOR)) {
        val zacetekY = 540f
        var izS = KazalecGibanje.pritisk(KazalecGibanje.Stanje(960f, zacetekY), smer, false, 0L, 1920, 1080)
        var t = 0L
        var doRoba = -1L
        while (t < 3000L && doRoba < 0L) {
            t += 16L
            if (t % 48L < 16L) izS = KazalecGibanje.pritisk(izS.stanje, smer, true, t, 1920, 1080)
            izS = KazalecGibanje.tik(izS.stanje, t, 1920, 1080)
            val y = izS.stanje.y
            if ((smer == KazalecGibanje.DOL && y >= 1079f) || (smer == KazalecGibanje.GOR && y <= 0f)) doRoba = t
        }
        check(doRoba in 1L..1500L, "T30 od sredine do roba (smer $smer) v najvec 1,5 s: $doRoba ms"); preverbe++
    }
    // Rob zaslona: konica lahko natancno na robu (brez odmika 10 px).
    run {
        val iz31 = KazalecGibanje.pritisk(KazalecGibanje.Stanje(5f, 500f), KazalecGibanje.LEVO, false, 0L, 1920, 1080)
        check(iz31.stanje.x == 0f, "T31 konica doseze levi rob (0): ${iz31.stanje.x}"); preverbe++
    }

    println("KazalecGibanjeTest: OK ($preverbe preverb)")
}
