package si.safeer.tv.os

fun main() {
    var cas = 100L
    val shramba = ZapiskiShramba(zdaj = { cas++ })

    val prvi = shramba.shrani(naslov = "Nakup", vsebina = "Mleko\nKruh")
    val drugi = shramba.shrani(naslov = "Film", vsebina = "Poišči slovenske podnapise")
    check(shramba.seznam().map { it.id } == listOf(drugi.id, prvi.id))
    check(shramba.seznam("mleko").single().id == prvi.id)
    check(shramba.seznam("SLOVENSKE").single().id == drugi.id)

    val urejen = shramba.shrani(prvi.id, "Nakup za vikend", "Mleko\tKruh\nSadje")
    check(urejen.id == prvi.id)
    check(shramba.najdi(prvi.id)?.naslov == "Nakup za vikend")

    val obnovljena = ZapiskiShramba(shramba.kodirano(), zdaj = { 500L })
    check(obnovljena.seznam().size == 2)
    check(obnovljena.najdi(prvi.id)?.vsebina == "Mleko\tKruh\nSadje")
    check(obnovljena.izbrisi(drugi.id))
    check(!obnovljena.izbrisi(drugi.id))
    check(obnovljena.seznam().single().id == prvi.id)

    val sPokvarjenoVrstico = ZapiskiShramba(shramba.kodirano() + "\nni-veljavno")
    check(sPokvarjenoVrstico.seznam().size == 2)
    val prazna = ZapiskiShramba(zdaj = { 900L }).apply { shrani(naslov = "", vsebina = "") }
    check(ZapiskiShramba(prazna.kodirano()).seznam().single().naslov.isEmpty())
    println("ZapiskiShrambaTest: OK")
}
