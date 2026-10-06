package si.safeer.tv.os

import kotlin.math.abs

/** Preizkus [ZaslonPovecava]: povecava slike racunalnika z dvema prstoma, premik povecane slike in drsenje. */
fun main() {
    fun blizu(a: Float, b: Float) = abs(a - b) < 0.01f

    // Telefon lezece 2340 x 1080, racunalnik 2560 x 1440: slika 1920 x 1080 na sredini (levo 210).
    fun nova() = ZaslonPovecava(2f).apply { nastaviOsnovo(2340, 1080, 210, 0, 1920, 1080) }

    // 1) Prsta mirujeta (tresenje pod pragom): nic se ne zgodi.
    var p = nova()
    check(blizu(p.merilo, 1f) && blizu(p.levo, 210f) && blizu(p.vrh, 0f) && !p.povecano)
    p.zacni(1000f, 500f, 300f)
    check(p.premakni(1004f, 510f, 310f) == 0f && p.nacin == ZaslonPovecava.Nacin.NEODLOCENO) { "tresenje ne sme biti kretnja" }
    check(blizu(p.merilo, 1f) && blizu(p.levo, 210f))

    // 2) Vzporeden poteg gor/dol na nepovecani sliki je drsenje vsebine, slika ostane pri miru.
    p = nova()
    p.zacni(1000f, 500f, 300f)
    check(p.premakni(1000f, 530f, 305f) == 30f) { "prvi premik drsenja vrne vso pot od zacetka" }
    check(p.nacin == ZaslonPovecava.Nacin.DRSENJE)
    check(p.premakni(1000f, 580f, 290f) == 50f && p.premakni(1000f, 560f, 400f) == -20f) { "drsenje vraca razlike" }
    check(blizu(p.merilo, 1f) && blizu(p.levo, 210f) && blizu(p.vrh, 0f)) { "med drsenjem se slika ne poveca, tudi ce se razmik spremeni" }
    p.koncaj()
    check(p.nacin == ZaslonPovecava.Nacin.NEODLOCENO)

    // 3) Prsta narazen: povecava; tocka slike pod sredino prstov ostane pod njo. Brez skoka ob odlocitvi.
    p = nova()
    p.zacni(1170f, 540f, 200f)
    check(p.premakni(1170f, 540f, 300f) == 0f && p.nacin == ZaslonPovecava.Nacin.POVECAVA)
    check(blizu(p.merilo, 1f)) { "ob odlocitvi za povecavo slika ne sme skociti" }
    p.premakni(1170f, 540f, 600f)
    check(blizu(p.merilo, 2f) && p.povecano)
    check(blizu(p.levo, -750f) && blizu(p.vrh, -540f)) { "sredina slike mora ostati pod prsti: ${p.levo}, ${p.vrh}" }
    // Tocka (delez slike) pod prsti pred in po: 0,5 / 0,5.
    check(blizu((1170f - p.levo) / (1920f * p.merilo), 0.5f) && blizu((540f - p.vrh) / (1080f * p.merilo), 0.5f))

    // 4) Povecana slika se premika s prsti, a ne cez rob: ob robu ni praznine.
    check(p.premakni(1170f + 5000f, 540f, 600f) == 0f)
    check(blizu(p.levo, 0f)) { "desno cez rob: levi rob slike na levem robu povrsine" }
    p.premakni(1170f - 9000f, 540f, 600f)
    check(blizu(p.levo, 2340f - 3840f)) { "levo cez rob: desni rob slike na desnem robu povrsine" }

    // 4b) Ob desnem robu sta cez sliko gumba: povecana slika se sme odmakniti izpod njiju (desni spodnji kot
    //     racunalnika mora biti dosegljiv), nepovecana ostane, kjer je.
    p.nastaviOdmikDesno(114f)
    p.premakni(1170f - 20000f, 540f, 600f)
    check(blizu(p.levo, 2340f - 3840f - 114f)) { "desni rob slike se sme odmakniti za pas gumbov: ${p.levo}" }
    p.ponastavi()
    check(blizu(p.levo, 210f)) { "pri merilu 1 pas gumbov ne premakne slike" }

    // 5) Navpicno na robu: ostanek potega je drsenje vsebine; v drugo smer se najprej premakne slika.
    p = nova()
    p.povecajNa(2f, 1170f, 0f)                                   // povecano okoli zgornjega roba: vrh = 0
    check(blizu(p.merilo, 2f) && blizu(p.vrh, 0f) && blizu(p.levo, -750f))
    p.zacni(1000f, 300f, 300f)
    check(p.nacin == ZaslonPovecava.Nacin.POVECAVA) { "povecana slika: dva prsta jo premikata" }
    check(p.premakni(1000f, 350f, 300f) == 50f && blizu(p.vrh, 0f)) { "na zgornjem robu poteg dol drsi vsebino" }
    check(p.premakni(1000f, 300f, 300f) == 0f && blizu(p.vrh, -50f)) { "poteg gor najprej premakne sliko" }
    check(p.premakni(1000f, 300f - 2000f, 300f) == -(2000f - 1030f)) { "ko slika pride do spodnjega roba, ostanek drsi" }
    check(blizu(p.vrh, 1080f - 2160f))

    // 6) Med premikanjem povecane slike prsta nista popolnoma enako narazen: merilo ostane (mrtvi pas).
    p = nova()
    p.povecajNa(2f, 1170f, 540f)
    p.zacni(1000f, 500f, 300f)
    p.premakni(1040f, 520f, 309f)
    p.premakni(1080f, 500f, 291f)
    check(blizu(p.merilo, 2f)) { "slika med premikanjem ne sme dihati: ${p.merilo}" }
    check(blizu(p.levo, -750f + 80f))
    p.premakni(1080f, 500f, 400f)                                 // zdaj zares narazen: od tu sledi razmiku, brez skoka
    check(blizu(p.merilo, 2f))
    p.premakni(1080f, 500f, 600f)
    check(blizu(p.merilo, 3f))

    // 7) Meje: najvec 4x, najmanj 1x; skoraj nepovecana slika po koncu kretnje skoci na cel zaslon.
    p = nova()
    p.zacni(1170f, 540f, 100f)
    p.premakni(1170f, 540f, 200f)
    p.premakni(1170f, 540f, 5000f)
    check(blizu(p.merilo, ZaslonPovecava.NAJVEC))
    p.premakni(1170f, 540f, 10f)
    check(blizu(p.merilo, 1f) && blizu(p.levo, 210f) && blizu(p.vrh, 0f)) { "pri merilu 1 je slika natanko v osnovni legi" }
    p.premakni(1170f, 540f, 208f)                                 // 200 -> 208: merilo 1,04
    check(p.merilo > 1.03f && p.merilo < 1.05f)
    p.koncaj()
    check(blizu(p.merilo, 1f) && !p.povecano && blizu(p.levo, 210f)) { "1,04x ni povecava: nazaj na cel zaslon" }
    p.povecajNa(1.5f, 1170f, 540f)
    p.koncaj()
    check(blizu(p.merilo, 1.5f)) { "prava povecava po koncu kretnje ostane" }
    p.ponastavi()
    check(blizu(p.merilo, 1f) && blizu(p.levo, 210f) && blizu(p.vrh, 0f))

    // 8) Telefon pokonci 1080 x 2340: slika 1080 x 607 na sredini (vrh 866). Povecana 2x je se vedno nizja od
    //    povrsine: navpicno ostane na sredini, vodoravno se premika, navpicni poteg drsi vsebino.
    p = ZaslonPovecava(2f).apply { nastaviOsnovo(1080, 2340, 0, 866, 1080, 607) }
    p.povecajNa(2f, 540f, 1169.5f)
    check(blizu(p.levo, -540f) && blizu(p.vrh, 866f - 303.5f)) { "nizja slika ostane navpicno na sredini: ${p.vrh}" }
    p.zacni(500f, 1100f, 300f)
    check(p.premakni(560f, 1140f, 300f) == 40f) { "navpicno slika nima kam: poteg drsi vsebino" }
    check(blizu(p.levo, -480f) && blizu(p.vrh, 866f - 303.5f))

    // 9) Vrtenje zaslona med povecavo: merilo ostane, lega se omeji na novo povrsino.
    p = nova()
    p.povecajNa(3f, 2340f, 1080f)
    check(blizu(p.levo, 2340f - 5760f) && blizu(p.vrh, 1080f - 3240f))
    p.nastaviOsnovo(1080, 2340, 0, 866, 1080, 607)
    check(blizu(p.merilo, 3f))
    check(p.levo <= 0f && p.levo >= 1080f - 3240f) { "po vrtenju slika ne sme pustiti praznine: ${p.levo}" }
    check(blizu(p.vrh, 866f + (607f - 1821f) / 2f))

    // 10) Zapolni zaslon: namizje 16:9 na daljsem zaslonu telefona - slika cez vso sirino, na sredini po visini.
    p = nova()
    check(p.lahkoZapolni && blizu(p.meriloZapolni(), 2340f / 1920f)) { "merilo zapolnitve: ${p.meriloZapolni()}" }
    p.zapolni()
    check(p.povecano && blizu(p.merilo, 2340f / 1920f) && blizu(p.levo, 0f)) { "slika mora segati od roba do roba: ${p.levo}" }
    check(blizu(p.vrh, (1080f - 1080f * 2340f / 1920f) / 2f)) { "navpicno na sredini: ${p.vrh}" }
    p.koncaj()
    check(p.povecano && p.zapolnjeno) { "zapolnjena slika po koncu kretnje ne sme skociti nazaj" }
    p.povecajNa(3f, 1170f, 540f)
    check(!p.zapolnjeno) { "povecano prek zapolnitve ni vec osnovna lega" }
    p.ponastavi()
    check(!p.povecano && !p.zapolnjeno && blizu(p.levo, 210f) && blizu(p.vrh, 0f)) { "cela slika" }
    // Slika, ki zaslon ze zapolni (namizje 16:9 na zaslonu 16:9), nima cesa zapolniti.
    val enaka = ZaslonPovecava(2f).apply { nastaviOsnovo(1920, 1080, 0, 0, 1920, 1080) }
    check(!enaka.lahkoZapolni && blizu(enaka.meriloZapolni(), 1f))
    enaka.zapolni()
    check(!enaka.povecano)
    // Tablica 16:10 (1920 x 1200) z namizjem 16:9: praznina zgoraj in spodaj, zapolni se po visini.
    val tablica = ZaslonPovecava(1.5f).apply { nastaviOsnovo(1920, 1200, 0, 60, 1920, 1080) }
    check(tablica.lahkoZapolni && blizu(tablica.meriloZapolni(), 1200f / 1080f))
    tablica.zapolni()
    check(blizu(tablica.vrh, 0f) && blizu(tablica.levo, (1920f - 1920f * 1200f / 1080f) / 2f)) { "tablica: ${tablica.levo}, ${tablica.vrh}" }
    // Brez znanih mer: nic.
    check(blizu(ZaslonPovecava(2f).meriloZapolni(), 1f) && !ZaslonPovecava(2f).lahkoZapolni)

    println("ZaslonPovecavaTest: OK")
}
