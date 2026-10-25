package si.safeer.tv.os

import si.safeer.tv.os.DovoljenjaPravila.Dejanje as D
import si.safeer.tv.os.DovoljenjaPravila.Stanje as S
import si.safeer.tv.os.DovoljenjaPravila.Vrsta as V

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Stran »Dovoljenja« ([DovoljenjaPravila]) - lastnik 25. 10. 2026. */
fun main() {
    println("Preizkus strani Dovoljenja")
    val P = DovoljenjaPravila
    // Kar deklarira skupni manifest (telefon, tablica, TV Safeer OS).
    val vsa = setOf(P.SAW, P.OBVESTILA, P.CELOZASLONSKA, P.NAMESCANJE, P.SLIKE, P.VIDEI, P.IZBRANE, P.ZVOK, P.SHRAMBA)

    // Postavke po napravi: samo deklarirano, samo kjer obstaja.
    preveriEnako("telefon Android 14 (WP28 S)", listOf(V.PRIKAZ, V.OBVESTILA, V.CELOZASLONSKA, V.SLIKE_VIDEI, V.GLASBA, V.VNOS, V.NAMESCANJE),
        P.postavke(34, tv = false, imaVnos = true, deklarirana = vsa))
    preveriEnako("TV Android 11: brez obvestil, celozaslonskih in Vnosa", listOf(V.PRIKAZ, V.SLIKE_VIDEI, V.GLASBA, V.NAMESCANJE),
        P.postavke(30, tv = true, imaVnos = false, deklarirana = vsa))
    preveriEnako("Android 13: celozaslonska se ni posebni dostop", false, V.CELOZASLONSKA in P.postavke(33, false, true, vsa))
    preveriEnako("Predvajalnik brez SYSTEM_ALERT_WINDOW: brez prikaza", false, V.PRIKAZ in P.postavke(34, false, false, vsa - P.SAW))
    preveriEnako("brez REQUEST_INSTALL_PACKAGES: brez namescanja", false, V.NAMESCANJE in P.postavke(34, false, false, vsa - P.NAMESCANJE))
    preveriEnako("Android 12: obvestila so stikalo (ostanejo)", true, V.OBVESTILA in P.postavke(31, false, false, vsa - P.OBVESTILA))
    preveriEnako("Android 13 brez READ_MEDIA_AUDIO: brez glasbe", false, V.GLASBA in P.postavke(33, false, false, vsa - P.ZVOK))

    // Dovoljenja, ki se vprasajo v aplikaciji.
    preveriEnako("fotografije 14+", listOf(P.SLIKE, P.VIDEI, P.IZBRANE), P.dovoljenja(V.SLIKE_VIDEI, 34))
    preveriEnako("fotografije 13", listOf(P.SLIKE, P.VIDEI), P.dovoljenja(V.SLIKE_VIDEI, 33))
    preveriEnako("fotografije 11", listOf(P.SHRAMBA), P.dovoljenja(V.SLIKE_VIDEI, 30))
    preveriEnako("glasba 13+", listOf(P.ZVOK), P.dovoljenja(V.GLASBA, 36))
    preveriEnako("obvestila 12: nic", emptyList<String>(), P.dovoljenja(V.OBVESTILA, 32))
    preveriEnako("prikaz ni v aplikaciji", emptyList<String>(), P.dovoljenja(V.PRIKAZ, 34))

    // Stanje: delni dostop do fotografij (Android 14+).
    preveriEnako("vse fotografije", S.DA, P.stanjeIzOdobrenih(V.SLIKE_VIDEI, 34, setOf(P.SLIKE, P.VIDEI, P.IZBRANE)))
    preveriEnako("samo izbrane", S.DELNO, P.stanjeIzOdobrenih(V.SLIKE_VIDEI, 34, setOf(P.IZBRANE)))
    preveriEnako("nic", S.NE, P.stanjeIzOdobrenih(V.SLIKE_VIDEI, 34, emptySet()))
    preveriEnako("13: obe", S.DA, P.stanjeIzOdobrenih(V.SLIKE_VIDEI, 33, setOf(P.SLIKE, P.VIDEI)))
    preveriEnako("13: samo slike ni dovolj", S.NE, P.stanjeIzOdobrenih(V.SLIKE_VIDEI, 33, setOf(P.SLIKE)))
    preveriEnako("11: shramba", S.DA, P.stanjeIzOdobrenih(V.GLASBA, 30, setOf(P.SHRAMBA)))

    // Ali sistem okno se pokaze.
    preveriEnako("nikoli vprasano", true, P.sistemVprasa(false, false))
    preveriEnako("zavrnjeno enkrat", true, P.sistemVprasa(true, true))
    preveriEnako("zavrnjeno dvakrat", false, P.sistemVprasa(true, false))

    // Gumb: v aplikaciji, ko gre; sicer tocno prava stran.
    preveriEnako("prikaz", D.STRAN_PRIKAZ, P.dejanje(V.PRIKAZ, 34, S.NE, true))
    preveriEnako("obvestila: vprasaj", D.VPRASAJ, P.dejanje(V.OBVESTILA, 34, S.NE, true))
    preveriEnako("obvestila: sistem ne vprasa vec", D.STRAN_OBVESTILA, P.dejanje(V.OBVESTILA, 34, S.NE, false))
    preveriEnako("obvestila 12: stikalo", D.STRAN_OBVESTILA, P.dejanje(V.OBVESTILA, 32, S.NE, true))
    preveriEnako("obvestila dovoljena: nastavitve", D.STRAN_OBVESTILA, P.dejanje(V.OBVESTILA, 34, S.DA, true))
    preveriEnako("fotografije delno: vprasaj (Dovoli vse)", D.VPRASAJ, P.dejanje(V.SLIKE_VIDEI, 34, S.DELNO, true))
    preveriEnako("fotografije trajno zavrnjene: podatki o aplikaciji", D.STRAN_APLIKACIJA, P.dejanje(V.SLIKE_VIDEI, 34, S.NE, false))
    preveriEnako("glasba dovoljena: podatki o aplikaciji", D.STRAN_APLIKACIJA, P.dejanje(V.GLASBA, 34, S.DA, true))
    preveriEnako("Vnos", D.STRAN_DOSTOPNOST, P.dejanje(V.VNOS, 34, S.NE, true))
    preveriEnako("celozaslonska", D.STRAN_CELOZASLONSKA, P.dejanje(V.CELOZASLONSKA, 34, S.NE, true))
    preveriEnako("namescanje", D.STRAN_NAMESCANJE, P.dejanje(V.NAMESCANJE, 30, S.NE, true))

    // Namig za omejene nastavitve: samo po vrnitvi brez dovoljenja; dostopnost od 13, prikaz od 15.
    preveriEnako("Vnos 14, vrnil se brez", true, P.namigOmejene(V.VNOS, 34, S.NE, 1))
    preveriEnako("Vnos 12: ni omejitve", false, P.namigOmejene(V.VNOS, 32, S.NE, 3))
    preveriEnako("Vnos, se ni poskusil", false, P.namigOmejene(V.VNOS, 34, S.NE, 0))
    preveriEnako("prikaz 15 (S25)", true, P.namigOmejene(V.PRIKAZ, 35, S.NE, 1))
    preveriEnako("prikaz 14: ni omejen", false, P.namigOmejene(V.PRIKAZ, 34, S.NE, 2))
    preveriEnako("prikaz dovoljen: brez namiga", false, P.namigOmejene(V.PRIKAZ, 36, S.DA, 2))
    preveriEnako("obvestila nikoli", false, P.namigOmejene(V.OBVESTILA, 36, S.NE, 2))

    // Ob zagonu: enkrat za vsako manjkajoco pomembno postavko.
    preveriEnako("telefon: manjka prikaz, se ni videl", true, P.pokaziObZagonu(setOf(V.PRIKAZ, V.GLASBA), emptySet(), false))
    preveriEnako("telefon: ze videl", false, P.pokaziObZagonu(setOf(V.PRIKAZ, V.OBVESTILA), setOf(V.PRIKAZ, V.OBVESTILA), false))
    preveriEnako("telefon: nova manjkajoca pomembna", true, P.pokaziObZagonu(setOf(V.PRIKAZ, V.OBVESTILA), setOf(V.PRIKAZ), false))
    preveriEnako("manjka samo nepomembno", false, P.pokaziObZagonu(setOf(V.GLASBA, V.NAMESCANJE), emptySet(), false))
    preveriEnako("TV: obvestila niso pomembna", false, P.pokaziObZagonu(setOf(V.OBVESTILA), emptySet(), true))
    preveriEnako("TV: prikaz je", true, P.pokaziObZagonu(setOf(V.PRIKAZ), emptySet(), true))
    preveriEnako("manjka", 2, P.manjka(mapOf(V.PRIKAZ to S.NE, V.SLIKE_VIDEI to S.DELNO, V.GLASBA to S.DA)))

    // Safeer Vnos v seznamu vklopljenih storitev dostopnosti (tablica: zapis iz Settings.Secure, 25. 10. 2026).
    val polno = "si.safeer.tablet/si.safeer.tv.link.VnosStoritev"
    val tablica = "com.microsoft.appmanager/com.microsoft.mmx.screenmirroringsrc.accessibility.ScreenMirroringAccessibilityService:$polno"
    preveriEnako("Vnos vklopljen (tablica)", true, P.vnosVklopljen(tablica, polno, "si.safeer.tablet/.x"))
    preveriEnako("Vnos izklopljen", false, P.vnosVklopljen("com.microsoft.appmanager/x.Y", polno, "si.safeer.tablet/.x"))
    preveriEnako("prazno (null)", false, P.vnosVklopljen(null, polno, "x"))
    preveriEnako("drug paket z istim razredom", false, P.vnosVklopljen("si.safeer.phone/si.safeer.tv.link.VnosStoritev", polno, "si.safeer.tablet/.y"))

    // Ime posebnega dostopa, kot ga vidi uporabnik.
    preveriEnako("Samsung", DovoljenjaPravila.ImePrikaza.SAMSUNG, P.imePrikaza("samsung"))
    preveriEnako("Samsung velike crke", DovoljenjaPravila.ImePrikaza.SAMSUNG, P.imePrikaza(" Samsung "))
    preveriEnako("OUKITEL (WP28 S)", DovoljenjaPravila.ImePrikaza.SPLOSNO, P.imePrikaza("OUKITEL"))
    preveriEnako("OnePlus (OxygenOS)", DovoljenjaPravila.ImePrikaza.SPLOSNO, P.imePrikaza("OnePlus"))
    preveriEnako("SAMSUNG (velike)", DovoljenjaPravila.ImePrikaza.SAMSUNG, P.imePrikaza("SAMSUNG"))
    preveriEnako("Xiaomi (ni preverjeno - splosno)", DovoljenjaPravila.ImePrikaza.SPLOSNO, P.imePrikaza("Xiaomi"))
    preveriEnako("neznan", DovoljenjaPravila.ImePrikaza.SPLOSNO, P.imePrikaza(null))

    // Zapis v nastavitve.
    preveriEnako("niz tja in nazaj", setOf(V.PRIKAZ, V.OBVESTILA), P.izNiza(P.vNiz(setOf(V.OBVESTILA, V.PRIKAZ))))
    preveriEnako("neznano ime izpade", setOf(V.PRIKAZ), P.izNiza("PRIKAZ,STARO"))
    preveriEnako("prazno", emptySet<DovoljenjaPravila.Vrsta>(), P.izNiza(null))

    println(if (napak == 0) "Dovoljenja: vse v redu" else "Dovoljenja: $napak napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
