package si.safeer.tv.cast

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Pregled 12. 11. 2026, najdba S1: clan z "dodano" v prihodnosti ne sme preprecit svojega umika. */
private var preverb = 0

private fun preveri(opis: String, pogoj: Boolean) {
    check(pogoj) { "NAPAKA: $opis" }
    preverb++
}

private fun javniKljuc(): String {
    val p = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    return Base64.getEncoder().encodeToString(p.public.encoded)
}

private fun vnos(id: String, kljuc: String, dodano: Double): String {
    val z = JsonLahki.Zapis().niz("kljuc", kljuc).niz("ime", "Naprava").niz("platforma", "linux").stevilo("dodano", dodano).niz("dodal", "hub")
    return JsonLahki.Zapis().stevilo("v", 1.0).surovo("clani", JsonLahki.Zapis().surovo(id, z.toString()).toString())
        .surovo("umiki", "{}").toString()
}

fun main() {
    val krog = KrogZaupanja()
    val kljuc = javniKljuc()
    val id = KrogZaupanja.idIzKljuca(kljuc) + "-control"
    val zdaj = KrogZaupanja.zdaj()
    preveri("clan pride v krog", krog.zdruzi(vnos(id, kljuc, zdaj - 100.0), varujUmike = true))
    // Napadalec (clan) predpripravi vnos s casom daleč v prihodnosti, preden pride umik.
    preveri("vnos z dodano v prihodnosti se zavrne", !krog.zdruzi(vnos(id, kljuc, 9e15), varujUmike = true))
    preveri("tudi brez varovala (sosed)", !krog.zdruzi(vnos(id, kljuc, zdaj + 86400.0)))
    preveri("umik uspe", krog.umakni(id, "hub"))
    preveri("umaknjen clan je umaknjen", krog.jeUmaknjen(id))
    println("KrogPrihodnjeDodanoTest: OK ($preverb preverb)")
}
