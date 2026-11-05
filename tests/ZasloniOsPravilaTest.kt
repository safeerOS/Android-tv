package si.safeer.tv.os

import java.io.File

/**
 * Pregled zaslonov 5. 11. 2026 (os/ZasloniOsPravila): fokus po izrisu (Sporocila, Zapiski), drsenje sporocil, vrstica
 * zaslona v Napravah, spremenjen zapisek, prikazanih od vseh. Argument: mapa os/ (preverimo, da zasloni klicejo pravila).
 */
fun main(args: Array<String>) {
    // Fokus ostane na isti postavki, ce je se tam (tudi na drugem mestu).
    check(ZasloniOsPravila.fokusPoOznaki(listOf("a", "b", "c"), "b", listOf("c", "b", "a")) == "b")
    // Postavka je izginila (izbris): soseda na istem mestu, na koncu zadnja.
    check(ZasloniOsPravila.fokusPoOznaki(listOf("a", "b", "c"), "b", listOf("a", "c")) == "c")
    check(ZasloniOsPravila.fokusPoOznaki(listOf("a", "b", "c"), "c", listOf("a", "b")) == "b")
    check(ZasloniOsPravila.fokusPoOznaki(listOf("a"), "a", emptyList()) == null)
    // Fokusa ni bilo v seznamu: nic ne premikamo.
    check(ZasloniOsPravila.fokusPoOznaki(listOf("a", "b"), null, listOf("a", "b")) == null)
    check(ZasloniOsPravila.fokusPoOznaki(listOf("a", "b"), "x", listOf("a", "b")) == null)

    // Sporocila: zadetek iskanja pred vsem, fokus v sporocilih ne skoci na dno, sicer dno.
    check(ZasloniOsPravila.poIzrisuSporocil(zadetek = true, fokusVSeznamu = true) == ZasloniOsPravila.Drsenje.ZADETEK)
    check(ZasloniOsPravila.poIzrisuSporocil(zadetek = false, fokusVSeznamu = true) == ZasloniOsPravila.Drsenje.OHRANI)
    check(ZasloniOsPravila.poIzrisuSporocil(zadetek = false, fokusVSeznamu = false) == ZasloniOsPravila.Drsenje.DNO)
    check(ZasloniOsPravila.ZAMIK_ISKANJA_MS in 150L..300L)

    // Naprave: vrstica zaslona preimenuje napravo.
    check(ZasloniOsPravila.napravaVrstice("abc123#zaslon") == "abc123")
    check(ZasloniOsPravila.napravaVrstice("abc123") == "abc123")
    check(ZasloniOsPravila.napravaVrstice("") == "")

    // Zapiski: presledek na robu naslova ni sprememba (shrani ga obreze), vsaka sprememba vsebine je.
    check(!ZasloniOsPravila.zapisekSpremenjen("Nakup", "mleko", "Nakup ", "mleko"))
    check(ZasloniOsPravila.zapisekSpremenjen("Nakup", "mleko", "Nakup", "mleko, kruh"))
    check(ZasloniOsPravila.zapisekSpremenjen("", "", "Nov", ""))
    check(!ZasloniOsPravila.zapisekSpremenjen("", "", "", ""))

    // Krajevne datoteke: vec kot meja - povemo, koliko je prikazanih.
    check(ZasloniOsPravila.prikazanih(1200, 500) == 500)
    check(ZasloniOsPravila.prikazanih(500, 500) == null)
    check(ZasloniOsPravila.prikazanih(0, 500) == null)

    // Zasloni res uporabljajo pravila (brez tega bi preizkus zelenel ob stari kodi).
    val os = File(args.firstOrNull() ?: error("manjka pot do os/"))
    fun vir(ime: String) = File(os, ime).readText()
    val sporocila = vir("SporocilaActivity.kt")
    check(sporocila.contains("ZasloniOsPravila.poIzrisuSporocil(")) { "Sporocila: drsenje brez pravila" }
    check(sporocila.contains("tag = \"spor|\" + s.id")) { "Sporocila: oblacek brez oznake" }
    check(sporocila.contains("postDelayed(iskanjeZamik, ZasloniOsPravila.ZAMIK_ISKANJA_MS)")) { "Sporocila: iskanje brez zamika" }
    check(sporocila.contains("bralec.execute")) { "Sporocila: seznam se bere na glavni niti" }
    check(!sporocila.contains("override fun afterTextChanged(p0: android.text.Editable?) { narisiSeznam() }")) { "Sporocila: iskanje ob vsakem znaku" }
    val zapiski = vir("ZapiskiActivity.kt")
    check(zapiski.contains("ZasloniOsPravila.zapisekSpremenjen(")) { "Zapiski: Nazaj brez vprasanja" }
    check(zapiski.contains("ZasloniOsPravila.fokusPoOznaki(")) { "Zapiski: fokus po izrisu" }
    check(zapiski.contains("iskanje.showSoftInputOnFocus = false")) { "Zapiski: tipkovnica ob fokusu iskanja" }
    check(vir("NapraveActivity.kt").contains("ZasloniOsPravila.napravaVrstice(")) { "Naprave: preimenovanje vrstice zaslona" }
    check(vir("KrajevneDatoteke.kt").contains("ZasloniOsPravila.prikazanih(")) { "Krajevne: ni povedano, koliko je prikazanih" }
    // Drugi krog pregleda 5. 11. 2026: oznaka sporocila na kateremkoli otroku vrstice; okno epizod samo po samodejnem zaprtju.
    check(ZasloniOsPravila.oznakaSporocila(listOf(null, "spor|7")) == "spor|7")
    check(ZasloniOsPravila.oznakaSporocila(listOf("spor|1", "spor|2")) == "spor|1")
    check(ZasloniOsPravila.oznakaSporocila(listOf(null, 3, "drugo")) == null && ZasloniOsPravila.oznakaSporocila(emptyList()) == null)
    check(ZasloniOsPravila.poZaprtjuNapake(zaprtoSamodejno = true) && !ZasloniOsPravila.poZaprtjuNapake(zaprtoSamodejno = false))
    check(vir("SporocilaActivity.kt").let { "ZasloniOsPravila.oznakaSporocila(" in it && "?.getChildAt(0)?.tag" !in it }) { "Sporocila: oznaka samo s prvega otroka" }
    vir("PredvajanjeActivity.kt").let { v ->
        check("ZasloniOsPravila.poZaprtjuNapake(zaprtoSamodejno)" in v && "zaprtoSamodejno = true; finish()" in v) { "Predvajanje: okno epizod tudi po Nazaj" }
    }

    for (ime in listOf("AplikacijeTvActivity.kt", "AplikacijeHostaActivity.kt")) {
        val v = vir(ime)
        check(v.contains("Aplikacije.obSpremembiPaketov.add(obSpremembiPaketov)") && v.contains("Aplikacije.obSpremembiPaketov.remove(obSpremembiPaketov)")) { "$ime: brez prijave na spremembe paketov" }
    }
    val hosta = vir("AplikacijeHostaActivity.kt")
    check(!hosta.contains("parseColor(\"#")) { "AplikacijeHosta: trdo kodirana barva" }
    check(hosta.contains("if (vrsta.findFocus() != null) mreza.requestFocus()")) { "AplikacijeHosta: fokus na cipu, ki izgine" }
    val datoteke = vir("DatotekeActivity.kt")
    check(!datoteke.contains("Color.rgb(") && !datoteke.contains("Color.WHITE")) { "Datoteke: trdo kodirana barva" }
    check(!datoteke.contains("takePersistableUriPermission")) { "Datoteke: trajno dovoljenje za ISO" }
    check(vir("SpletniKlepetActivity.kt").contains("SporocilaShramba(a).use")) { "SpletniKlepet: shramba ni zaprta" }
    val aplikacije = vir("Aplikacije.kt")
    check(aplikacije.contains("@Synchronized private fun odstraniIzShranjenega(") && !aplikacije.contains("datoteka(app).writeText(")) { "Aplikacije: pisanje mimo kljucavnice" }
    println("ZasloniOsPravilaTest: OK")
}
