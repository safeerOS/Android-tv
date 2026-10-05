package si.safeer.tv.cast

/**
 * Obvestilo o deljenem zaslonu (CastReceiverService, id 4041): konec deljenja ga pospravi, obvestila za drugo
 * vsebino pa ne. Izmerjeno 5. 10. 2026 na telefonu (0.5.52): obvestilo »Zaslon: ...« je bilo dve uri po koncu
 * deljenja se aktivno.
 */
fun main() {
    // Konec deljenja, za katerega je obvestilo, ga pospravi - enkrat.
    var o = ObvestiloZaslona()
    o.zaZaslon("a1")
    check(o.obKoncu("a1")) { "konec deljenja mora pospraviti svoje obvestilo" }
    check(!o.obKoncu("a1")) { "drugi konec nima vec cesa pospraviti" }

    // Brez obvestila ni kaj pospraviti (gledalec se je odprl neposredno).
    o = ObvestiloZaslona()
    check(!o.obKoncu("a1")) { "brez obvestila" }

    // Konec DRUGEGA deljenja obvestila ne pospravi.
    o.zaZaslon("a1")
    check(!o.obKoncu("b2")) { "tuje deljenje" }
    check(o.obKoncu("a1")) { "svoje deljenje po tujem" }

    // Obvestilo za poslano stran ali video ostane.
    o.zaZaslon("a1")
    o.zaDrugo()
    check(!o.obKoncu("a1")) { "obvestilo je zdaj za drugo vsebino" }

    // Uporabnik je obvestilo odprl (vsebina je na zaslonu): konec nima cesa pospraviti.
    o.zaZaslon("a1")
    o.pospravljeno()
    check(!o.obKoncu("a1")) { "ze pospravljeno" }

    // Sredisce, ki id-ja ne pove (start ali stop brez id): obvestilo za zaslon vseeno pospravimo.
    o.zaZaslon("")
    check(o.obKoncu("x")) { "start brez id" }
    o.zaZaslon("a1")
    check(o.obKoncu("")) { "stop brez id" }

    // Novo deljenje zamenja staro obvestilo: konec starega novega ne pospravi.
    o.zaZaslon("a1")
    o.zaZaslon("b2")
    check(!o.obKoncu("a1")) { "konec starega deljenja" }
    check(o.obKoncu("b2")) { "konec novega deljenja" }
    println("ObvestiloZaslonaTest: OK")
}
