package si.safeer.tv.os

import si.safeer.tv.os.UskladitevKljucevPravila.Meta
import si.safeer.tv.os.UskladitevKljucevPravila.Odziv
import si.safeer.tv.os.UskladitevKljucevPravila.Stanje
import si.safeer.tv.os.UskladitevKljucevPravila.Zapis

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private val PONUDNIKI = setOf("torbox", "realdebrid", "alldebrid")
private val VSE: (String, String) -> Boolean = { _, v -> v != "slab" }

/** Simulacija naprave: stanje (meta + vrednosti) in prejem po pravilih ([UskladitevKljucevPravila]). */
private class Naprava(val id: String) {
    val meta = HashMap<String, Meta>()
    val vrednosti = HashMap<String, String>()
    private fun odtis(v: String) = "o" + v.hashCode()
    fun spremeni(k: String, v: String?, ura: Long) {
        if (v == null) vrednosti.remove(k) else vrednosti[k] = v
        meta.putAll(UskladitevKljucevPravila.lokalneSpremembe(meta, vrednosti.mapValues { odtis(it.value) }, emptySet(), id, ura, false))
    }
    fun stanje() = UskladitevKljucevPravila.zaPoslati(meta, vrednosti)
    fun prejmi(z: List<Zapis>, ura: Long): Int {
        val u = UskladitevKljucevPravila.zaUporabo(meta, z, ura, PONUDNIKI, VSE)
        for (x in u) {
            if (x.izbrisan) vrednosti.remove(x.kljuc) else vrednosti[x.kljuc] = x.vrednost
            meta[x.kljuc] = Meta(x.cas, x.izvor, x.izbrisan, if (x.izbrisan) "" else odtis(x.vrednost))
        }
        // po prejemu ni lastnih sprememb (prejeto ni »moja sprememba«)
        val lastne = UskladitevKljucevPravila.lokalneSpremembe(meta, vrednosti.mapValues { odtis(it.value) }, emptySet(), id, ura, false)
        if (lastne.isNotEmpty()) error("prejem je ustvaril lastno spremembo: $lastne")
        return u.size
    }
}

fun main() {
    println("Preizkus usklajevanja kljucev in dodatkov v ozjem krogu")
    val P = UskladitevKljucevPravila
    val tb = P.kljucDebrid("torbox")
    val dod = P.kljucDodatka("0123456789abcdef")
    preveriEnako("veljaven kljuc debrid", true, P.veljavenKljuc(tb, PONUDNIKI))
    preveriEnako("neznan ponudnik", false, P.veljavenKljuc(P.kljucDebrid("x"), PONUDNIKI))
    preveriEnako("veljaven kljuc dodatka", true, P.veljavenKljuc(dod, PONUDNIKI))
    preveriEnako("kljuc dodatka brez odtisa", false, P.veljavenKljuc("dodatek:abc", PONUDNIKI))
    preveriEnako("usenet", true, P.veljavenKljuc(P.KLJUC_USENET, PONUDNIKI))
    preveriEnako("neznan kljuc", false, P.veljavenKljuc("geslo", PONUDNIKI))

    // novejsi: cas, nato izvor, nato grob
    preveriEnako("novejsi po casu", true, P.novejsi(5, "a", false, Meta(4, "z", false, "")))
    preveriEnako("starejsi po casu", false, P.novejsi(3, "z", false, Meta(4, "a", false, "")))
    preveriEnako("enak cas: vecji izvor", true, P.novejsi(4, "b", false, Meta(4, "a", false, "")))
    preveriEnako("enak cas in izvor: grob zmaga", true, P.novejsi(4, "a", true, Meta(4, "a", false, "")))
    preveriEnako("isti zapis ni novejsi (idempotentno)", false, P.novejsi(4, "a", false, Meta(4, "a", false, "x")))
    preveriEnako("nov kljuc", true, P.novejsi(1, "a", false, null))

    // lokalne spremembe
    val m0 = P.lokalneSpremembe(emptyMap(), mapOf(tb to "h1"), emptySet(), "tel", 1000, uvoz = true)
    preveriEnako("uvoz: obstojeca vrednost dobi CAS_UVOZA", Meta(P.CAS_UVOZA, "tel", false, "h1"), m0[tb])
    val m1 = P.lokalneSpremembe(m0, mapOf(tb to "h2"), emptySet(), "tel", 2000, false)
    preveriEnako("sprememba vrednosti: nov cas", Meta(2000, "tel", false, "h2"), m1[tb])
    val m2 = P.lokalneSpremembe(mapOf(tb to Meta(5000, "tv", false, "h2")), emptyMap(), emptySet(), "tel", 3000, false)
    preveriEnako("izbris: grob, cas nikoli starejsi od prejsnjega", Meta(5001, "tel", true, ""), m2[tb])
    preveriEnako("neberljiv kljuc ni izbris", emptyMap<String, Meta>(), P.lokalneSpremembe(mapOf(tb to Meta(5, "a", false, "h")), emptyMap(), setOf(tb), "tel", 9, false))
    preveriEnako("brez spremembe nic", emptyMap<String, Meta>(), P.lokalneSpremembe(mapOf(tb to Meta(5, "a", false, "h")), mapOf(tb to "h"), emptySet(), "tel", 9, false))
    preveriEnako("ponoven vnos po izbrisu", Meta(10, "tel", false, "h"), P.lokalneSpremembe(mapOf(tb to Meta(9, "a", true, "")), mapOf(tb to "h"), emptySet(), "tel", 10, false)[tb])

    // veljavnost prejetih
    val ura = 1_000_000L
    preveriEnako("grob z vrednostjo je neveljaven", false, P.veljavenZapis(Zapis(tb, 5, "a", true, "x"), ura, PONUDNIKI, VSE))
    preveriEnako("prazna vrednost je neveljavna", false, P.veljavenZapis(Zapis(tb, 5, "a", false, ""), ura, PONUDNIKI, VSE))
    preveriEnako("slaba vrednost (preverba) je neveljavna", false, P.veljavenZapis(Zapis(tb, 5, "a", false, "slab"), ura, PONUDNIKI, VSE))
    preveriEnako("cas iz dalnje prihodnosti", false, P.veljavenZapis(Zapis(tb, ura + P.NAJVEC_NAPREJ_MS + 1, "a", false, "k"), ura, PONUDNIKI, VSE))
    preveriEnako("cas 0", false, P.veljavenZapis(Zapis(tb, 0, "a", false, "k"), ura, PONUDNIKI, VSE))
    preveriEnako("kontrolni znaki v izvoru", false, P.veljavenZapis(Zapis(tb, 5, "a\u0001", false, "k"), ura, PONUDNIKI, VSE))
    preveriEnako("za uporabo: najnovejsi zapis istega kljuca", listOf(Zapis(tb, 7, "b", false, "k7")),
        P.zaUporabo(emptyMap(), listOf(Zapis(tb, 5, "a", false, "k5"), Zapis(tb, 7, "b", false, "k7"), Zapis(tb, 6, "c", false, "k6")), ura, PONUDNIKI, VSE))
    preveriEnako("za uporabo: najvec 64", 64, P.zaUporabo(emptyMap(), (1..100).map { Zapis(P.kljucDodatka("%016x".format(it)), 5, "a", false, "v") }, ura, PONUDNIKI, VSE).size)

    // tri naprave: telefon vpise, TV in tablica dobita; izbris; hkratni spremembi; ponovljeno sporocilo
    val tel = Naprava("telefon"); val tv = Naprava("tv"); val tab = Naprava("tablica")
    tel.spremeni(tb, "KLJUC1", 100)
    tel.spremeni(dod, "https://webtor.io/token/z1/stremio/manifest.json", 101)
    preveriEnako("TV dobi oba", 2, tv.prejmi(tel.stanje(), ura))
    preveriEnako("ponovljeno sporocilo ne spremeni nicesar", 0, tv.prejmi(tel.stanje(), ura))
    preveriEnako("tablica dobi od TV (naprej po krogu)", 2, tab.prejmi(tv.stanje(), ura))
    preveriEnako("TV ima kljuc", "KLJUC1", tv.vrednosti[tb])
    tel.spremeni(dod, null, 200)
    tv.prejmi(tel.stanje(), ura); tab.prejmi(tv.stanje(), ura)
    preveriEnako("izbris dodatka se prenese (grob)", null, tab.vrednosti[dod])
    preveriEnako("grob ostane v stanju", true, tab.meta[dod]?.izbrisan)
    preveriEnako("star zapis ne obudi izbrisanega", 0, tab.prejmi(listOf(Zapis(dod, 101, "telefon", false, "https://webtor.io/token/z1/stremio/manifest.json")), ura))
    tab.spremeni(dod, "https://webtor.io/token/z2/stremio/manifest.json", 300)
    tel.prejmi(tab.stanje(), ura)
    preveriEnako("kasnejsi vnos preglasi grob", "https://webtor.io/token/z2/stremio/manifest.json", tel.vrednosti[dod])
    // hkratni spremembi istega kljuca na dveh napravah: zmaga kasnejsa, obe napravi prideta do istega
    tel.spremeni(tb, "KLJUC_TEL", 400); tv.spremeni(tb, "KLJUC_TV", 401)
    val zTel = tel.stanje(); val zTv = tv.stanje()
    tel.prejmi(zTv, ura); tv.prejmi(zTel, ura)
    preveriEnako("hkratni: obe napravi enako", tel.vrednosti[tb], tv.vrednosti[tb])
    preveriEnako("hkratni: zmaga kasnejsi cas", "KLJUC_TV", tel.vrednosti[tb])
    // enak cas: zmaga vecji izvor (»tv« > »telefon«), na obeh enako
    tel.spremeni(tb, "A", 500); tv.spremeni(tb, "B", 500)
    val a1 = tel.stanje(); val b1 = tv.stanje()
    tel.prejmi(b1, ura); tv.prejmi(a1, ura)
    preveriEnako("enak cas: obe napravi enako", tel.vrednosti[tb], tv.vrednosti[tb])
    preveriEnako("enak cas: odloci izvor", "B", tv.vrednosti[tb])
    // uvoz: obstojeci kljuc z uvozom ne preglasi kasnejse spremembe druge naprave
    val star = Naprava("stara")
    star.vrednosti[tb] = "STAR"
    star.meta.putAll(P.lokalneSpremembe(star.meta, mapOf(tb to "o" + "STAR".hashCode()), emptySet(), "stara", 9_000, uvoz = true))
    star.prejmi(tel.stanje(), ura)
    preveriEnako("uvozena vrednost ne preglasi spremembe", tel.vrednosti[tb], star.vrednosti[tb])

    // naprave in ozji krog
    preveriEnako("sprejem: potrjena", Odziv.SPREJMI, P.sprejem(true, true, Stanje.POTRJENA))
    preveriEnako("sprejem: povabljena (poslali smo ji prvi)", Odziv.SPREJMI, P.sprejem(true, true, Stanje.POVABLJENA))
    preveriEnako("sprejem: nova naprava - vprasaj enkrat", Odziv.VPRASAJ, P.sprejem(true, true, null))
    preveriEnako("sprejem: caka", Odziv.VPRASAJ, P.sprejem(true, true, Stanje.CAKA))
    preveriEnako("sprejem: zavrnjena", Odziv.ZAVRNI, P.sprejem(true, true, Stanje.ZAVRNJENA))
    preveriEnako("sprejem: zunaj ozjega kroga nikoli", Odziv.ZAVRNI, P.sprejem(true, false, Stanje.POTRJENA))
    preveriEnako("sprejem: izklopljeno", Odziv.ZAVRNI, P.sprejem(false, true, Stanje.POTRJENA))
    preveriEnako("poslji: potrjena v krogu", true, P.smePoslati(true, true, true, true, true, Stanje.POTRJENA))
    preveriEnako("poslji: nova v krogu (povabilo)", true, P.smePoslati(true, true, true, true, true, null))
    preveriEnako("poslji: odstranjena iz kroga - nic vec", false, P.smePoslati(true, false, true, true, true, Stanje.POTRJENA))
    preveriEnako("poslji: brez zascite od naprave do naprave", false, P.smePoslati(true, true, false, true, true, Stanje.POTRJENA))
    preveriEnako("poslji: stara razlicica", false, P.smePoslati(true, true, true, true, false, Stanje.POTRJENA))
    preveriEnako("poslji: zavrnjena", false, P.smePoslati(true, true, true, true, true, Stanje.ZAVRNJENA))
    preveriEnako("poslji: caka na potrditev tu", false, P.smePoslati(true, true, true, true, true, Stanje.CAKA))
    preveriEnako("poslji: izklopljeno", false, P.smePoslati(false, true, true, true, true, Stanje.POTRJENA))
    preveriEnako("poslji: brez povezave", false, P.smePoslati(true, true, true, false, true, Stanje.POTRJENA))
    preveriEnako("za poslati: neberljiva vrednost izpade, grob ostane",
        listOf(Zapis("debrid:alldebrid", 3, "a", true, "")),
        P.zaPoslati(mapOf(tb to Meta(2, "a", false, "h"), "debrid:alldebrid" to Meta(3, "a", true, "")), emptyMap()))

    if (napak == 0) println("UskladitevKljucevPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
