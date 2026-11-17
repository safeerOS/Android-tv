package si.safeer.tv.os

/** Preizkus [ZaslonKonecPravila]: odlocitev ob napaki zajema, dostopu, drugih koncih in izpadu ter varnost razloga. */
fun main() {
    var preverb = 0
    fun preveri(pogoj: Boolean, opis: String) {
        check(pogoj) { opis }
        preverb++
    }

    // Odpoved zajema na racunalniku: nikoli ponovitev, vedno KONCAJ_ZAJEM_PADA.
    preveri(
        ZaslonKonecPravila.odlocitev("zajem_pada", null, "izhod_234") == ZaslonKonecPravila.Odlocitev.KONCAJ_ZAJEM_PADA,
        "koda zajem_pada"
    )
    preveri(
        ZaslonKonecPravila.odlocitev(null, "zajem", "izhod_234") == ZaslonKonecPravila.Odlocitev.KONCAJ_ZAJEM_PADA,
        "sporocilo seje konec=zajem"
    )
    preveri(
        ZaslonKonecPravila.odlocitev("zajem_pada", "zajem", null) == ZaslonKonecPravila.Odlocitev.KONCAJ_ZAJEM_PADA,
        "koda in konec hkrati brez razloga"
    )
    preveri(
        ZaslonKonecPravila.odlocitev("", "zajem:izhod_234", null) == ZaslonKonecPravila.Odlocitev.KONCAJ_ZAJEM_PADA,
        "zapakirano stanje zajem:izhod_234"
    )
    preveri(
        ZaslonKonecPravila.odlocitev("  zajem_pada ", "", "<>&") == ZaslonKonecPravila.Odlocitev.KONCAJ_ZAJEM_PADA,
        "zajem_pada s presledki in nevarnim razlogom"
    )

    // Zavrnjen ali odvzet dostop: KONCAJ_DOSTOP.
    preveri(
        ZaslonKonecPravila.odlocitev("ni_dovoljeno", null, null) == ZaslonKonecPravila.Odlocitev.KONCAJ_DOSTOP,
        "screen.start ni_dovoljeno"
    )
    preveri(
        ZaslonKonecPravila.odlocitev(null, "dostop", null) == ZaslonKonecPravila.Odlocitev.KONCAJ_DOSTOP,
        "sporocilo seje konec=dostop"
    )

    // Zaslon racunalnika se je spremenil (pokrov zaprt): sejo zahtevamo znova, ne koncamo.
    preveri(
        ZaslonKonecPravila.odlocitev(null, "zaslon_spremenjen", null) == ZaslonKonecPravila.Odlocitev.PONOVI,
        "konec=zaslon_spremenjen -> ponovi"
    )
    preveri(
        ZaslonKonecPravila.odlocitev("ni_programa", "zaslon_spremenjen", null) == ZaslonKonecPravila.Odlocitev.KONCAJ_DRUGO,
        "koda napake ima prednost pred zaslon_spremenjen"
    )

    // Druge koncne napake ali konci seje: KONCAJ_DRUGO.
    preveri(
        ZaslonKonecPravila.odlocitev("ni_programa", null, null) == ZaslonKonecPravila.Odlocitev.KONCAJ_DRUGO,
        "koda ni_programa"
    )
    preveri(
        ZaslonKonecPravila.odlocitev("napaka", null, null) == ZaslonKonecPravila.Odlocitev.KONCAJ_DRUGO,
        "splosna koda napake screen.start"
    )
    preveri(
        ZaslonKonecPravila.odlocitev(null, "ni_okna", null) == ZaslonKonecPravila.Odlocitev.KONCAJ_DRUGO,
        "konec=ni_okna"
    )
    preveri(
        ZaslonKonecPravila.odlocitev(null, "program_zaprt", null) == ZaslonKonecPravila.Odlocitev.KONCAJ_DRUGO,
        "konec=program_zaprt"
    )
    preveri(
        ZaslonKonecPravila.odlocitev(null, "na_namizju", null) == ZaslonKonecPravila.Odlocitev.KONCAJ_DRUGO,
        "konec=na_namizju"
    )
    preveri(
        ZaslonKonecPravila.odlocitev(null, "prevzeto:naprava-2", null) == ZaslonKonecPravila.Odlocitev.KONCAJ_DRUGO,
        "konec=prevzeto"
    )

    // Obicajen izpad povezave brez konca seje in brez kode napake: PONOVI.
    preveri(
        ZaslonKonecPravila.odlocitev(null, null, null) == ZaslonKonecPravila.Odlocitev.PONOVI,
        "brez kode in konca -> PONOVI"
    )
    preveri(
        ZaslonKonecPravila.odlocitev("", "", "") == ZaslonKonecPravila.Odlocitev.PONOVI,
        "prazni nizi -> PONOVI"
    )
    preveri(
        ZaslonKonecPravila.odlocitev("   ", "  ", "izhod_234") == ZaslonKonecPravila.Odlocitev.PONOVI,
        "samo presledki -> PONOVI"
    )

    // Veljavni razlogi: samo izhod_<1-3 ASCII stevke>.
    preveri(ZaslonKonecPravila.varenRazlog("izhod_0") == "izhod_0", "1 stevka")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_42") == "izhod_42", "2 stevki")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_234") == "izhod_234", "3 stevke")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_999") == "izhod_999", "999")

    // Napadalne in neveljavne vrednosti razloga: ne smejo se prikazati.
    preveri(ZaslonKonecPravila.varenRazlog(null) == null, "null")
    preveri(ZaslonKonecPravila.varenRazlog("") == null, "prazen niz")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_") == null, "brez stevk: izhod_")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_1234") == null, "4 stevke: izhod_1234")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_-1") == null, "negativno stevilo")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_12a") == null, "crka v stevilki")
    preveri(ZaslonKonecPravila.varenRazlog("<>&") == null, "znaki <>&")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_<1>") == null, "znaki <> v izhod_")
    preveri(ZaslonKonecPravila.varenRazlog("<script>alert(1)</script>&\"'") == null, "HTML/JS vbrizg")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_\u0661\u0662\u0663") == null, "arabske unicode stevke")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_\uFF12\uFF13\uFF14") == null, "polnosirinske unicode stevke")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_\u0967") == null, "devanagari unicode stevka")
    preveri(ZaslonKonecPravila.varenRazlog("a".repeat(10_000)) == null, "dolg niz")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_" + "1".repeat(5_000)) == null, "dolg niz z izhod_")
    preveri(ZaslonKonecPravila.varenRazlog(" izhod_234") == null, "vodilni presledek")
    preveri(ZaslonKonecPravila.varenRazlog("izhod_234\n") == null, "nova vrstica")

    // Sestavljanje sporocila za uporabnika.
    val osnovno = "Racunalnik ne more zajeti zaslona. Preveri racunalnik in poskusi znova."
    preveri(
        ZaslonKonecPravila.sporocilo(osnovno, "izhod_234") ==
            "Racunalnik ne more zajeti zaslona. Preveri racunalnik in poskusi znova. (izhod_234)",
        "sporocilo z veljavnim razlogom"
    )
    preveri(
        ZaslonKonecPravila.sporocilo(osnovno, null) == osnovno,
        "sporocilo brez razloga"
    )
    preveri(
        ZaslonKonecPravila.sporocilo(osnovno, "izhod_1234") == osnovno,
        "sporocilo z neveljavnim razlogom izhod_1234"
    )
    preveri(
        ZaslonKonecPravila.sporocilo(osnovno, "<>&") == osnovno,
        "sporocilo z napadalnim razlogom <>&"
    )

    // Razbiranje razloga iz stanja seje.
    preveri(ZaslonKonecPravila.razlogIzStanja("zajem:izhod_234") == "izhod_234", "razlog iz stanja zajem:izhod_234")
    preveri(ZaslonKonecPravila.razlogIzStanja("zajem:") == "", "prazen razlog iz stanja zajem:")
    preveri(ZaslonKonecPravila.razlogIzStanja("zajem") == null, "stanje brez dvopicja")
    preveri(ZaslonKonecPravila.razlogIzStanja(null) == null, "null stanje")

    println("ZaslonKonecPravilaTest: OK ($preverb preverb)")
}
