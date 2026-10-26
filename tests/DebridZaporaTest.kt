package si.safeer.tv.os

// Pregled 104, S1/S2: zavrnjen ali zaklenjen kljuc (prejem z druge naprave, ponoven vpis) dobi zaporo - brez novih klicev.

private var napak = 0

private fun preveriEnako(ime: String, pricakovano: Any?, dejansko: Any?) {
    if (pricakovano == dejansko) println("OK     $ime")
    else { println("NAPAKA $ime: pricakovano <$pricakovano>, dobljeno <$dejansko>"); napak++ }
}

fun main() {
    preveriEnako("ne velja -> zapora ne velja", DebridPravila.StanjeKljuca.NE_VELJA,
        DebridPravila.zaporaPoZavrnitvi(DebridPravila.Preverba.NE_VELJA))
    preveriEnako("zaklenjen -> zapora zaklenjen", DebridPravila.StanjeKljuca.ZAKLENJEN,
        DebridPravila.zaporaPoZavrnitvi(DebridPravila.Preverba.ZAKLENJEN))
    for (p in listOf(DebridPravila.Preverba.VELJA, DebridPravila.Preverba.NI_ODGOVORA, DebridPravila.Preverba.MEJA, DebridPravila.Preverba.OBLIKA))
        preveriEnako("$p -> brez zapore", null, DebridPravila.zaporaPoZavrnitvi(p))
    // Zapora po kljucu res ustavi klice (stanjeZZaporo + smeKlicati), tudi za kljuc, ki tu ni shranjen.
    for (p in listOf(DebridPravila.Preverba.NE_VELJA, DebridPravila.Preverba.ZAKLENJEN))
        preveriEnako("po zapori $p storitve ne klicemo", false,
            DebridPravila.smeKlicati(DebridPravila.stanjeZZaporo(null, null, DebridPravila.zaporaPoZavrnitvi(p))))
    // S2: ponoven vpis shranjenega veljavnega kljuca, ki ga storitev zavrne - stanjePoVpisu ga ne shrani (null), zato
    // mora zaporo dati zaporaPoZavrnitvi; po zapisu zapore ni vec v ozjem krogu.
    preveriEnako("vpis: velja -> ne velja ni za shranjevanje", null,
        DebridPravila.stanjePoVpisu(DebridPravila.StanjeKljuca.VELJA, DebridPravila.Preverba.NE_VELJA))
    preveriEnako("vpis: zapora ni za ozji krog", false,
        DebridPravila.zaUskladitev(DebridPravila.zaporaPoZavrnitvi(DebridPravila.Preverba.NE_VELJA)))
    // Velja iz vpisa zaporo se vedno odpravi (zZaporo) - uporabnik lahko kljuc vpise znova.
    val o = DebridPravila.odtisStanja("Ab3dE5gH7jK9mN1pQ3sT5vW7yZ9bC1eF3hJ5lM7oP9rS1")
    val z = DebridPravila.zZaporo(null, o, DebridPravila.zaporaPoZavrnitvi(DebridPravila.Preverba.NE_VELJA)!!)
    preveriEnako("velja odpravi zaporo", emptyMap<String, DebridPravila.StanjeKljuca>(),
        DebridPravila.zaporeIzZapisa(DebridPravila.zZaporo(z, o, DebridPravila.StanjeKljuca.VELJA)))
    if (napak == 0) println("DebridZaporaTest: VSE OK")
    else { println("DebridZaporaTest: NAPAK $napak"); kotlin.system.exitProcess(1) }
}
