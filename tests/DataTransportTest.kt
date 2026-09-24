package si.safeer.tv.cast

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.KeyAgreement
import kotlin.random.Random

private var napak = 0
private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun preveriNapako(opis: String, blok: () -> Unit) {
    try {
        blok()
        println("  NAPAKA $opis (pricakovana NapakaPrenosa, ni bilo nobene)")
        napak++
    } catch (e: DataTransport.NapakaPrenosa) {
        println("  OK   $opis (${e.message})")
    }
}

// ------------------------------------------------------------------ zacasna identiteta (samo za preizkus)

private fun novKljuc(): KeyPair {
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(ECGenParameterSpec("secp256r1"))
    return kpg.generateKeyPair()
}

private fun javniB64(kp: KeyPair): String = Base64.getEncoder().encodeToString(kp.public.encoded)

private fun podpisnikZa(kp: KeyPair): (ByteArray) -> String = { podatki ->
    val s = Signature.getInstance("SHA256withECDSA")
    s.initSign(kp.private)
    s.update(podatki)
    Base64.getEncoder().encodeToString(s.sign())
}

private fun ecdhZa(kp: KeyPair): (String) -> ByteArray = { tujKljucB64 ->
    val tujKljuc = KrogZaupanja.dekodirajKljuc(tujKljucB64) ?: throw DataTransport.NapakaPrenosa("neveljaven tuj kljuc")
    val ka = KeyAgreement.getInstance("ECDH")
    ka.init(kp.private)
    ka.doPhase(tujKljuc, true)
    ka.generateSecret()
}

fun main() {
    println("Safeer Data Transport (v0.26)")

    val kljucA = novKljuc()
    val kljucB = novKljuc()
    val pubA = javniB64(kljucA)
    val pubB = javniB64(kljucB)
    val idA = "n-aaaaaaaaaaaaaaaa"
    val idB = "n-bbbbbbbbbbbbbbbb"

    // ---- ECDH
    run {
        val sA = ecdhZa(kljucA)(pubB)
        val sB = ecdhZa(kljucB)(pubA)
        preveri("ECDH je simetricen", sA.contentEquals(sB))
        preveri("ECDH da 32 bajtov (P-256)", sA.size == 32)
    }

    // ---- HKDF / sejni kljuc
    run {
        val skrivnost = ByteArray(32) { 1 }
        val k1 = DataTransport.izpeljiSejniKljuc(skrivnost, "seja1", "bm9uY2Uy", "bm9uY2Uz")
        val k2 = DataTransport.izpeljiSejniKljuc(skrivnost, "seja1", "bm9uY2Uy", "bm9uY2Uz")
        preveri("HKDF je deterministicen (kljuc)", k1.kljuc.contentEquals(k2.kljuc))
        preveri("HKDF je deterministicen (predpona)", k1.predponaNonca.contentEquals(k2.predponaNonca))
        preveri("kljuc je 32 bajtov", k1.kljuc.size == 32)
        preveri("predpona nonca je 4 bajte", k1.predponaNonca.size == 4)
        val k3 = DataTransport.izpeljiSejniKljuc(skrivnost, "seja2", "bm9uY2Uy", "bm9uY2Uz")
        preveri("drug session_id da drug kljuc", !k1.kljuc.contentEquals(k3.kljuc))
        val n0 = k1.nonce(0)
        val n1 = k1.nonce(1)
        preveri("nonce je 12 bajtov", n0.size == 12)
        preveri("razlicna stevca dasta razlicen nonce", !n0.contentEquals(n1))
        preveri("nonce se zacne s predpono", n0.copyOfRange(0, 4).contentEquals(k1.predponaNonca))
    }

    // ---- Ponudba/odgovor
    run {
        val a = DataTransport(idA, podpisnikZa(kljucA), ecdhZa(kljucA))
        val b = DataTransport(idB, podpisnikZa(kljucB), ecdhZa(kljucB))

        val ponudba = a.sestaviPonudbo(idB, DataTransport.NAMEN_DATOTEKA)
        preveri("ponudba ima pravi tip", JsonLahki.objekt(ponudba.json)?.niz("type") == "data.offer")
        preveri("ponudba ima pravi cilj", JsonLahki.objekt(ponudba.json)?.niz("target") == idB)

        val izid = b.sprejmiPonudbo(ponudba.json, pubA)
        preveri("izid pozna pravega tujca", izid.tujId == idA)
        preveri("izid pozna pravi namen", izid.namen == DataTransport.NAMEN_DATOTEKA)
        preveri("odgovor ima pravi tip", JsonLahki.objekt(izid.odgovorJson)?.niz("type") == "data.answer")
        preveri("odgovor ima pravi cilj", JsonLahki.objekt(izid.odgovorJson)?.niz("target") == idA)

        val sejniA = a.dokoncajPonudbo(ponudba.json, izid.odgovorJson, pubB)
        preveri("obe strani dobita isti kljuc", sejniA.kljuc.contentEquals(izid.sejni.kljuc))
        preveri("obe strani dobita isto predpono nonca", sejniA.predponaNonca.contentEquals(izid.sejni.predponaNonca))

        preveriNapako("ponarejena ponudba (spremenjen session_id) pade") {
            val ponudba2 = a.sestaviPonudbo(idB, DataTransport.NAMEN_DATOTEKA)
            val ovoj = JsonLahki.objekt(ponudba2.json)!!
            val tovor = ovoj.objekt("payload")!!
            val ponarejen = JsonLahki.Zapis()
                .niz("session_id", "podtaknjen-id").niz("purpose", tovor.nizAli("purpose"))
                .niz("from", tovor.nizAli("from")).niz("to", tovor.nizAli("to"))
                .niz("nonce", tovor.nizAli("nonce")).stevilo("ts", tovor.stevilo("ts") ?: 0.0)
                .niz("sig", tovor.nizAli("sig"))
            val sporocilo = JsonLahki.Zapis().niz("type", "data.offer").niz("target", idB).surovo("payload", ponarejen.toString())
            b.sprejmiPonudbo(sporocilo.toString(), pubA)
        }

        preveriNapako("ponudba za drugo napravo pade") {
            val ponudbaZaTretjega = a.sestaviPonudbo("n-nekdo-drug", DataTransport.NAMEN_DATOTEKA)
            b.sprejmiPonudbo(ponudbaZaTretjega.json, pubA)
        }

        preveriNapako("napacen kljuc ponudnika pade") {
            val p3 = a.sestaviPonudbo(idB, DataTransport.NAMEN_DATOTEKA)
            b.sprejmiPonudbo(p3.json, pubB)
        }

        preveriNapako("odgovor za drugo ponudbo pade") {
            val p1 = a.sestaviPonudbo(idB, DataTransport.NAMEN_DATOTEKA)
            val p2 = a.sestaviPonudbo(idB, DataTransport.NAMEN_DATOTEKA)
            val izid1 = b.sprejmiPonudbo(p1.json, pubA)
            a.dokoncajPonudbo(p2.json, izid1.odgovorJson, pubB)
        }

        preveriNapako("posiljatelj iz huba se mora ujemati") {
            val p4 = a.sestaviPonudbo(idB, DataTransport.NAMEN_DATOTEKA)
            b.sprejmiPonudbo(p4.json, pubA, posiljateljIzHuba = "n-nekdo-tretji")
        }

        try {
            a.sestaviPonudbo(idB, "video-klic")
            println("  NAPAKA neznan namen bi moral pasti")
            napak++
        } catch (e: DataTransport.NapakaPrenosa) {
            println("  OK   neznan namen pade (${e.message})")
        }
    }

    // ---- AES-256-GCM kosi
    run {
        val sejni = DataTransport.izpeljiSejniKljuc(Random.nextBytes(32), "seja-x", "bm9uY2Uy", "bm9uY2Uz")

        val podatki = Random.nextBytes(3 * 1024 * 1024 + 137)
        val sifrirano = DataTransport.sifrirajBajte(sejni, "seja-x", podatki, 1024 * 1024)
        preveri("stevilo kosov je pravilno", sifrirano.size == 4)
        val nazaj = DataTransport.desifrirajBajte(sejni, "seja-x", sifrirano)
        preveri("sifriraj/desifriraj kroznica", nazaj.contentEquals(podatki))
        preveri("SHA-256 se ujema", DataTransport.sha256Hex(nazaj) == DataTransport.sha256Hex(podatki))

        val prazenSifr = DataTransport.sifrirajBajte(sejni, "seja-x", ByteArray(0))
        preveri("prazen prenos da en kos", prazenSifr.size == 1)
        preveri("prazen prenos se desifrira v prazno", DataTransport.desifrirajBajte(sejni, "seja-x", prazenSifr).isEmpty())

        preveriNapako("spremenjen bajt (tag) pade") {
            val s = DataTransport.sifrirajBajte(sejni, "seja-x", "varni podatki".toByteArray())
            val pokvarjen = s[0].copyOf()
            pokvarjen[pokvarjen.size - 1] = (pokvarjen[pokvarjen.size - 1].toInt() xor 0xFF).toByte()
            DataTransport.desifrirajBajte(sejni, "seja-x", listOf(pokvarjen))
        }

        preveriNapako("kos iz druge seje pade (AAD)") {
            val s = DataTransport.sifrirajBajte(sejni, "seja-x", "nekaj podatkov".toByteArray())
            DataTransport.desifrirajBajte(sejni, "druga-seja", s)
        }

        preveriNapako("premesani kosi padejo (AAD nosi indeks)") {
            val s = DataTransport.sifrirajBajte(sejni, "seja-x", Random.nextBytes(2 * 1024 * 1024), 1024 * 1024)
            DataTransport.desifrirajBajte(sejni, "seja-x", listOf(s[1], s[0]))
        }

        preveriNapako("drug kljuc ne desifrira") {
            val drugSejni = DataTransport.izpeljiSejniKljuc(Random.nextBytes(32), "seja-x", "bm9uY2Uy", "bm9uY2Uz")
            val s = DataTransport.sifrirajBajte(sejni, "seja-x", "tajno".toByteArray())
            DataTransport.desifrirajBajte(drugSejni, "seja-x", s)
        }
    }

    // ---- Od ponudbe do prenosa (polna pot)
    run {
        val a = DataTransport(idA, podpisnikZa(kljucA), ecdhZa(kljucA))
        val b = DataTransport(idB, podpisnikZa(kljucB), ecdhZa(kljucB))
        val ponudba = a.sestaviPonudbo(idB, DataTransport.NAMEN_DATOTEKA)
        val izid = b.sprejmiPonudbo(ponudba.json, pubA)
        val sejniA = a.dokoncajPonudbo(ponudba.json, izid.odgovorJson, pubB)

        val datoteka = Random.nextBytes(2 * 1024 * 1024 + 42)
        val sifrirano = DataTransport.sifrirajBajte(sejniA, izid.sessionId, datoteka)
        val prejeto = DataTransport.desifrirajBajte(izid.sejni, izid.sessionId, sifrirano)
        preveri("od ponudbe do prenosa: podatki se ujemajo", prejeto.contentEquals(datoteka))
        preveri("od ponudbe do prenosa: SHA-256 se ujema", DataTransport.sha256Hex(prejeto) == DataTransport.sha256Hex(datoteka))
    }

    println(if (napak == 0) "\nVse v redu." else "\nNapak: $napak")
    if (napak > 0) System.exit(1)
}
