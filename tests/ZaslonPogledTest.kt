package si.safeer.tv.os

/** Preizkus [ZaslonPogled]: velikost slike, ki jo naprava pove racunalniku za loceni zaslon. */
fun main() {
    val vse: (Int, Int) -> Boolean = { _, _ -> true }

    // 1) Dekodirnik zmore vse: povrsina naprave, sodi stranici.
    check(ZaslonPogled.velikost(2340, 1080, vse) == (2340 to 1080)) { "telefon lezece" }
    check(ZaslonPogled.velikost(1920, 1200, vse) == (1920 to 1200)) { "tablica 16:10" }
    check(ZaslonPogled.velikost(2241, 1079, vse) == (2240 to 1078)) { "sodi stranici" }
    check(ZaslonPogled.velikost(1280, 576, vse) == (1280 to 576))
    check(ZaslonPogled.velikost(1080, 2340, vse) == (1080 to 2340)) { "pokoncna povrsina ostane pokoncna" }

    // 2) Dekodirnik polne velikosti ne zmore: prvo manjse z istim razmerjem, ki ga zmore.
    val do1080p: (Int, Int) -> Boolean = { w, h -> w * h <= 1920 * 1088 }
    val v = ZaslonPogled.velikost(2560, 1600, do1080p)!!
    check(v.first * v.second <= 1920 * 1088 && v.first % 2 == 0 && v.second % 2 == 0) { "zmanjsano: $v" }
    check(kotlin.math.abs(v.first.toFloat() / v.second - 1.6f) < 0.01f) { "razmerje ostane: $v" }
    check(v == (1706 to 1066)) { "prvi korak, ki gre: $v" }
    var poskusi = 0
    check(ZaslonPogled.velikost(2340, 1080) { _, _ -> poskusi++; false } == null) { "dekodirnik ne zmore nicesar" }
    check(poskusi == 7) { "vsak korak enkrat: $poskusi" }

    // 3) Meje: premajhna povrsina in neveljavne mere ne dajo nicesar; prevelika se zmanjsa pod mejo.
    check(ZaslonPogled.velikost(0, 1080, vse) == null && ZaslonPogled.velikost(2340, -1, vse) == null)
    check(ZaslonPogled.velikost(480, 320, vse) == null) { "premajhna povrsina" }
    check(ZaslonPogled.velikost(639, 400, vse) == null)
    check(ZaslonPogled.velikost(640, 360, vse) == (640 to 360))
    check(ZaslonPogled.velikost(3840, 2160, vse) == (3840 to 2160))
    val velika = ZaslonPogled.velikost(5120, 2880, vse)!!
    check(velika.first <= ZaslonPogled.NAJVEC_DOLGA && velika.second <= ZaslonPogled.NAJVEC_KRATKA) { "nad mejo: $velika" }
    check(velika == (3840 to 2160)) { "5120 x 2880 -> $velika" }

    // 3b) Povrsina za sliko: okno brez izreza kamere, vedno (daljsa, krajsa) - tudi pred zasukom v lezece.
    check(ZaslonPogled.povrsina(1280, 576, 42, 0, 0, 0) == (1238 to 576)) { "lezece z izrezom levo" }
    check(ZaslonPogled.povrsina(576, 1280, 0, 42, 0, 0) == (1238 to 576)) { "pokoncno z izrezom zgoraj - ista povrsina" }
    check(ZaslonPogled.povrsina(2340, 1080, 0, 0, 96, 0) == (2244 to 1080)) { "izrez desno" }
    check(ZaslonPogled.povrsina(1920, 1080, 0, 0, 0, 0) == (1920 to 1080)) { "brez izreza" }
    check(ZaslonPogled.povrsina(1920, 1200, -5, 0, 0, 0) == (1920 to 1200)) { "negativen rob ne poveca povrsine" }

    // 3c) Merilo vsebine, ki ga izbere uporabnik: po cetrtinah, med 1 in 3.
    check(ZaslonPogled.merilo(1.25f) == 1.25f && ZaslonPogled.merilo(2f + ZaslonPogled.KORAK_MERILA) == 2.25f)
    check(ZaslonPogled.merilo(0.75f) == 1f && ZaslonPogled.merilo(-3f) == 1f) { "najmanj 1" }
    check(ZaslonPogled.merilo(3.25f) == 3f && ZaslonPogled.merilo(99f) == 3f) { "najvec 3" }
    check(ZaslonPogled.merilo(1.6f) == 1.5f && ZaslonPogled.merilo(1.9f) == 2f) { "na najblizjo cetrtino" }
    check(ZaslonPogled.merilo(Float.NaN) == 1f)

    // 4) Vrsta naprave.
    check(ZaslonPogled.vrsta(naDotik = false, najmanjsaSirinaDp = 540) == "tv")
    check(ZaslonPogled.vrsta(naDotik = true, najmanjsaSirinaDp = 360) == "phone")
    check(ZaslonPogled.vrsta(naDotik = true, najmanjsaSirinaDp = 600) == "tablet")
    check(ZaslonPogled.vrsta(naDotik = true, najmanjsaSirinaDp = 800) == "tablet")

    println("ZaslonPogledTest: V REDU")
}
