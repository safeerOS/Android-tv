package si.safeer.tv.cast

import org.json.JSONArray
import org.json.JSONObject
import si.safeer.tv.cast.DostopPravila.Zahteva
import si.safeer.tv.cast.DostopPravila.Zmoznost
import si.safeer.tv.cast.ProsnjeDostopaPravila.Stanje

private var napak = 0

private fun preveri(kaj: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $kaj") else { println("FAIL   $kaj"); napak++ }
}

fun main() {
    val p = ProsnjeDostopaPravila
    println("== prosnja za dostop (odobri samo ciljna naprava) ==")
    val mama = "n-0123456789abcdef"
    val tuj = "n-fedcba9876543210"
    // Krog TE naprave: kljuc -> jedro (lazna preslikava za preizkus).
    val kljuci = mapOf("KLJUC-MAMA" to mama, "KLJUC-TV" to "n-1111111111111111")
    val idIzKljuca: (String) -> String = { k -> kljuci[k] ?: error("neznan kljuc") }
    val clani = listOf(
        ProsnjeDostopaPravila.Clan("n-0123456789abcdef", "KLJUC-MAMA", "Telefon (staro ime)", 1.0),
        ProsnjeDostopaPravila.Clan("n-0123456789abcdef-os", "KLJUC-MAMA", "Mamin telefon", 5.0),
        ProsnjeDostopaPravila.Clan("n-1111111111111111", "KLJUC-TV", "Televizor", 0.0),
    )
    val t0 = 1_800_000_000_000L

    // Ime iz kroga te naprave - najnovejse poimenovanje; neznano jedro nima imena.
    val ime = p.imeIzKroga(clani, mama, idIzKljuca)
    preveri("ime pride iz kroga te naprave (najnovejse poimenovanje)", ime == "Mamin telefon")
    preveri("jedro, ki ni v krogu, nima imena (ni clan)", p.imeIzKroga(clani, tuj, idIzKljuca) == null)
    preveri("pokvarjeno jedro nima imena", p.imeIzKroga(clani, "mama", idIzKljuca) == null)

    // Ime ni iz sporocila: ce bi sporocilo nosilo »sender_name«, ga pravila sploh ne dobijo; ciscenje imena iz kroga.
    val grdo = "Ma‮min\u0000 \n\t telefon​" + "x".repeat(100)
    val cisto = p.cistoIme(grdo)
    preveri("ime: brez krmilnih, nevidnih in znakov za smer, en presledek", cisto.startsWith("Mamin telefonx") && cisto.none { it.code < 32 || it == '‮' || it == '​' })
    preveri("ime: najvec 60 znakov", cisto.length <= 60 && p.cistoIme("a".repeat(200)).length == 60)
    val krogZGrdimImenom = listOf(ProsnjeDostopaPravila.Clan(mama, "KLJUC-MAMA", "‮" + "y".repeat(80), 1.0))
    preveri("ime iz kroga je ocisceno in omejeno", p.imeIzKroga(krogZGrdimImenom, mama, idIzKljuca) == "y".repeat(60))

    // 1. Znan clan, manjka ena zmoznost: prosnja zapisana, vprasanje se pokaze.
    val s = ProsnjeDostopaPravila.Shramba()
    val o1 = p.obZavrnitvi(s, mama, ime, Zahteva.DATOTEKE, emptySet(), t0)
    preveri("znan clan: prosnja zapisana (pending) z vprasanjem", o1.stanje == Stanje.PENDING && o1.novaProsnja != null && o1.zmoznost == Zmoznost.DATOTEKE)
    preveri("prosnja: jedro, ime iz kroga, zmoznost, cas", s.cakajoce.size == 1 && s.cakajoce[0].jedro == mama &&
        s.cakajoce[0].ime == "Mamin telefon" && s.cakajoce[0].zmoznost == Zmoznost.DATOTEKE && s.cakajoce[0].cas == t0)

    // 2. Neznan posiljatelj, nepreverjeno sporocilo, VSE in prosto dejanje: brez vprasanja.
    val s2 = ProsnjeDostopaPravila.Shramba()
    preveri("neznan posiljatelj (ni v krogu): none, brez prosnje",
        p.obZavrnitvi(s2, tuj, null, Zahteva.DATOTEKE, emptySet(), t0).let { it.stanje == Stanje.NONE && it.novaProsnja == null } && s2.cakajoce.isEmpty())
    preveri("nepreverjen posiljatelj (brez jedra iz kljuca): none",
        p.obZavrnitvi(s2, "", "Mamin telefon", Zahteva.DATOTEKE, emptySet(), t0).stanje == Stanje.NONE && s2.cakajoce.isEmpty())
    val vse = p.obZavrnitvi(s2, mama, ime, Zahteva.VSE, emptySet(), t0)
    preveri("dejanje VSE (ozji krog): none, brez zmoznosti in prosnje", vse.stanje == Stanje.NONE && vse.zmoznost == null && s2.cakajoce.isEmpty())
    preveri("prosto dejanje: none", p.obZavrnitvi(s2, mama, ime, Zahteva.PROSTO, emptySet(), t0).stanje == Stanje.NONE && s2.cakajoce.isEmpty())
    preveri("zmoznost, ki jo naprava ze ima: none", p.obZavrnitvi(s2, mama, ime, Zahteva.ZASLON, setOf(Zmoznost.ZASLON), t0).stanje == Stanje.NONE)

    // 3. Omejitev: isto jedro + zmoznost najvec eno vprasanje na 10 minut.
    val o2 = p.obZavrnitvi(s, mama, ime, Zahteva.DATOTEKE, emptySet(), t0 + 60_000)
    preveri("v 10 minutah: pending, brez novega vprasanja", o2.stanje == Stanje.PENDING && o2.novaProsnja == null && s.cakajoce.size == 1)
    val o3 = p.obZavrnitvi(s, mama, ime, Zahteva.DATOTEKE, emptySet(), t0 + p.PONOVNO_MS)
    preveri("po 10 minutah: vprasanje znova (ista prosnja, nov cas)", o3.novaProsnja != null && s.cakajoce.size == 1 && s.cakajoce[0].cas == t0 + p.PONOVNO_MS)
    val o4 = p.obZavrnitvi(s, mama, ime, Zahteva.PROGRAMI, emptySet(), t0 + 60_000)
    preveri("druga zmoznost iste naprave je svoja prosnja", o4.novaProsnja != null && s.cakajoce.size == 2)

    // Najvec 5 cakajocih.
    val s5 = ProsnjeDostopaPravila.Shramba()
    var t = t0
    for (z in listOf(Zahteva.DATOTEKE, Zahteva.PROGRAMI, Zahteva.PREDVAJALNIK, Zahteva.ZASLON)) p.obZavrnitvi(s5, mama, ime, z, emptySet(), t++)
    p.obZavrnitvi(s5, "n-1111111111111111", "Televizor", Zahteva.DATOTEKE, emptySet(), t++)
    val sesta = p.obZavrnitvi(s5, "n-1111111111111111", "Televizor", Zahteva.PROGRAMI, emptySet(), t)
    preveri("najvec 5 cakajocih: sesta dobi none", s5.cakajoce.size == 5 && sesta.stanje == Stanje.NONE && sesta.novaProsnja == null)

    // 4. Zavrni: 24 ur nobenega vprasanja (declined), nato spet lahko.
    preveri("zavrni cakajoco prosnjo", p.zavrni(s, mama, Zmoznost.DATOTEKE, t0 + 700_000) && s.cakajoce.none { it.zmoznost == Zmoznost.DATOTEKE })
    val z1 = p.obZavrnitvi(s, mama, ime, Zahteva.DATOTEKE, emptySet(), t0 + 700_000 + p.PONOVNO_MS * 3)
    preveri("po zavrnitvi: declined, brez vprasanja", z1.stanje == Stanje.DECLINED && z1.novaProsnja == null)
    val z2 = p.obZavrnitvi(s, mama, ime, Zahteva.DATOTEKE, emptySet(), t0 + 700_000 + p.ZAVRNITEV_MS)
    preveri("po 24 urah: spet vprasanje", z2.stanje == Stanje.PENDING && z2.novaProsnja != null)
    preveri("zavrni brez cakajoce prosnje ne zapise nicesar", !p.zavrni(ProsnjeDostopaPravila.Shramba(), tuj, Zmoznost.ZASLON, t0))

    // 5. Dovoli doda samo to eno zmoznost (druge ostanejo).
    val sd = ProsnjeDostopaPravila.Shramba()
    p.obZavrnitvi(sd, mama, ime, Zahteva.PREDVAJALNIK, setOf(Zmoznost.DATOTEKE), t0)
    val nov = p.dovoli(sd, mama, Zmoznost.PREDVAJALNIK, setOf(Zmoznost.DATOTEKE))
    preveri("dovoli: obstojece + ena zmoznost", nov == setOf(Zmoznost.DATOTEKE, Zmoznost.PREDVAJALNIK))
    preveri("dovoli: prosnja ne caka vec", sd.cakajoce.isEmpty())
    preveri("dovoli brez cakajoce prosnje: nic (null)", p.dovoli(sd, mama, Zmoznost.ZASLON, setOf(Zmoznost.DATOTEKE)) == null)
    preveri("dovoli ne odpre zmoznosti, za katero ni prosil", p.dovoli(sd, mama, Zmoznost.PREDVAJALNIK, emptySet()) == null)

    // 6. Odgovor prosilcu: stara polja ostanejo, dodani access_request in capability.
    val seznam = JSONObject().put("ok", true).put("message", "Naprava datotek ne deli").put("data",
        JSONObject().put("folder", "").put("items", JSONArray()).put("shared", false).put("reason", "access")).put("action", "files.list")
    val prej = JSONObject(seznam.toString())
    p.dopolniOdgovor(seznam, o1)
    val stariVrh = prej.keys().asSequence().all { k -> seznam.get(k).toString() == prej.get(k).toString() || k == "data" }
    val d = seznam.getJSONObject("data")
    val stariData = prej.getJSONObject("data").let { pd -> pd.keys().asSequence().all { k -> d.get(k).toString() == pd.get(k).toString() } }
    preveri("odgovor s seznamom: stara polja nespremenjena (vrh in data)", stariVrh && stariData)
    preveri("odgovor s seznamom: access_request + capability na vrhu in v data",
        seznam.getString("access_request") == "pending" && seznam.getString("capability") == "d" &&
            d.getString("access_request") == "pending" && d.getString("capability") == "d")
    val kratka = JSONObject().put("ok", false).put("message", "Ta naprava ti tega ne dovoli.").put("code", "ni_dovoljeno").put("action", "screen.start")
    p.dopolniOdgovor(kratka, ProsnjeDostopaPravila.Odlocitev(Stanje.DECLINED, Zmoznost.ZASLON))
    preveri("kratka zavrnitev: code ostane, dodano declined + z", kratka.getString("code") == "ni_dovoljeno" && kratka.getBoolean("ok") == false &&
        kratka.getString("access_request") == "declined" && kratka.getString("capability") == "z" && kratka.length() == 6)
    val ack = JSONObject().put("type", "cast.ack").put("ref_id", "x").put("status", "error").put("error", "ni_dovoljeno")
    p.dopolniOdgovor(ack, vse)
    preveri("potrditev za VSE: none brez capability, stara polja ostanejo", ack.getString("access_request") == "none" && !ack.has("capability") &&
        ack.getString("error") == "ni_dovoljeno" && ack.getString("status") == "error")

    // 7. Prosilec: kaj pokazati.
    preveri("prosilec: pending", p.stanjeOdgovora(seznam) == Stanje.PENDING)
    preveri("prosilec: declined", p.stanjeOdgovora(kratka) == Stanje.DECLINED)
    preveri("prosilec: none ali staro sredisce brez polja -> dosedanje sporocilo", p.stanjeOdgovora(ack) == null && p.stanjeOdgovora(prej) == null && p.stanjeOdgovora(null) == null)
    preveri("prosilec: polje samo v data", p.stanjeOdgovora(JSONObject().put("data", JSONObject().put("access_request", "pending"))) == Stanje.PENDING)

    println(if (napak == 0) "VSE OK" else "NAPAK: $napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
