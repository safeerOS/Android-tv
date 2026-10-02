package si.safeer.tv

fun main() {
    check(SpletMostPravila.jeDovoljenIzvor("file:///android_asset/splet/splet.html?tv=1"))
    check(!SpletMostPravila.jeDovoljenIzvor("file:///android_asset/splet/drugo.html"))
    check(!SpletMostPravila.jeDovoljenIzvor("https://napadalec.test/splet/splet.html"))

    check(SpletMostPravila.razcleni("""{"action":"navigate","url":"https://safeer.si/pot"}""") ==
        SpletMostPravila.Ukaz.Navigacija("https://safeer.si/pot"))
    check(SpletMostPravila.razcleni("""{"action":"navigate","url":"javascript:alert(1)"}""") == null)
    check(SpletMostPravila.razcleni("""{"action":"open_sidebar","service":"add_portal"}""") ===
        SpletMostPravila.Ukaz.DodajBliznjico)
    check(SpletMostPravila.razcleni("""{"action":"open_sidebar","service":"devices"}""") == null)
    // Odstranjevanje bliznjic z zacetne strani (tudi vgrajenih) in obnova privzetih.
    check(SpletMostPravila.razcleni("""{"action":"remove_portal","url":"https://www.reddit.com"}""") ==
        SpletMostPravila.Ukaz.OdstraniBliznjico("https://www.reddit.com"))
    check(SpletMostPravila.razcleni("""{"action":"remove_portal","url":"javascript:alert(1)"}""") == null)
    check(SpletMostPravila.razcleni("""{"action":"reset_portals"}""") === SpletMostPravila.Ukaz.ObnoviBliznjice)
    check(SpletMostPravila.kljucBliznjice("https://www.Reddit.com/") == "reddit.com")
    check(SpletMostPravila.kljucBliznjice("http://365.rtvslo.si") == "365.rtvslo.si")
    check(SpletMostPravila.kljucBliznjice("https://mail.google.com/mail/") == "mail.google.com/mail")

    check(SpletMostPravila.izberiJezik("de", "sl-SI") == "de")
    check(SpletMostPravila.izberiJezik("auto", "sl-SI") == "sl")
    check(SpletMostPravila.izberiJezik(null, "pt-BR") == "en")
    println("SpletMostPravilaTest: OK")
}
