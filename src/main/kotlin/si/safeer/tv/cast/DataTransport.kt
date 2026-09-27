package si.safeer.tv.cast

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Safeer Data Transport (v0.26): sejni dogovor in sifriranje za prenos med seznanjenima napravama.
 * Kotlin stran istega protokola kot core/link_data_transport.py (safeer-lms); glej docs/P2P-NACRT.md
 * ("3. korak: Safeer Data Transport").
 *
 * Obe napravi sta ze v krogu zaupanja (KrogZaupanja), zato kljuca ne izmenjata na novo: sejni kljuc
 * izpeljeta iz ECDH med obstojecima kljucema P-256 + dvema nakljucnima nemenoma (HKDF-SHA256).
 * Sporocili data.offer/data.answer sta podpisani na enak nacin kot vsak drug vnos v krogu zaupanja
 * (glej KrogZaupanja.podatkiClana).
 *
 * Ta razred ne pozna Androida (isto nacelo kot KrogZaupanja) in tece tudi v preizkusih na JVM:
 * podpisovanje in ECDH dobi od klicatelja (lambdi) namesto da bi ju delal sam - na Androidu ju izvede
 * HubTls (AndroidKeyStore, isti kljuc kot za TLS potrdilo huba in za KrogNaprave.podpisnik), v
 * preizkusih pa navaden java.security kljuc. Hub sporocili data.offer/data.answer in nadaljnje
 * data.chunk/data.ack le posreduje po polju "target" (HubUsmerjevalnik, DATA_POSREDOVANJE) - isti
 * splosni vzorec kot control./internet./share.*.
 */
class DataTransport(
    private val mojId: String,
    /** Podpise podatke s kljucem TE naprave (HubTls.podpisi na Androidu, SHA256withECDSA, base64). */
    private val podpisnik: (ByteArray) -> String,
    /** ECDH P-256 med nasim zasebnim kljucem in tujim javnim kljucem (base64 SPKI DER); vrne surovo
     * skupno skrivnost. Zasebni kljuc nikoli ne zapusti te lambde. */
    private val ecdh: (tujKljucB64: String) -> ByteArray,
) {

    class NapakaPrenosa(sporocilo: String) : Exception(sporocilo)

    data class SejniKljuc(val kljuc: ByteArray, val predponaNonca: ByteArray) {
        /** 12-bajtni nonce za kos [stevec] (0-based). Predpona (iz HKDF, torej odvisna od seje) +
         * stevec je enolicna znotraj ene seje, ce noben stevec ni ponovljen. */
        fun nonce(stevec: Long): ByteArray {
            val b = java.nio.ByteBuffer.allocate(8).putLong(stevec).array()
            return predponaNonca + b
        }
    }

    data class Ponudba(val json: String, val sessionId: String, val tujId: String)

    data class IzidPonudbe(
        val sejni: SejniKljuc,
        val odgovorJson: String,
        val sessionId: String,
        val namen: String,
        val tujId: String,
    )

    /** Ustvari podpisano sporocilo data.offer (JSON), ki ga posiljatelj poslje prek huba
     * (target=[tujId]). */
    fun sestaviPonudbo(tujId: String, namen: String): Ponudba {
        if (namen != NAMEN_DATOTEKA && namen != NAMEN_ZASLON) throw NapakaPrenosa("neznan namen: $namen")
        val sessionId = novSessionId()
        val nonce = nakljucnoB64(16)
        val ts = zdajSekunde()
        val podpis = podpisnik(podatkiPonudbe(sessionId, namen, mojId, tujId, nonce, ts))
        val payload = JsonLahki.Zapis()
            .niz("session_id", sessionId).niz("purpose", namen).niz("from", mojId).niz("to", tujId)
            .niz("nonce", nonce).stevilo("ts", ts).niz("sig", podpis)
        val ovoj = JsonLahki.Zapis().niz("type", "data.offer").niz("target", tujId)
            .surovo("payload", payload.toString())
        return Ponudba(ovoj.toString(), sessionId, tujId)
    }

    /**
     * Preveri prejeto data.offer (podpis, cilj, svezost) in pripravi podpisan data.answer ter sejni
     * kljuc. Ne poslje nicesar - klicatelj odgovor poslje prek huba. [kljucPonudnikaB64] mora priti
     * iz kroga zaupanja (KrogZaupanja.clanZaId), NIKOLI iz samega sporocila.
     */
    fun sprejmiPonudbo(sporociloJson: String, kljucPonudnikaB64: String, posiljateljIzHuba: String? = null): IzidPonudbe {
        val ovoj = JsonLahki.objekt(sporociloJson) ?: throw NapakaPrenosa("neveljaven JSON")
        if (ovoj.niz("type") != "data.offer") throw NapakaPrenosa("ni data.offer")
        val t = ovoj.objekt("payload") ?: throw NapakaPrenosa("sporocilu manjka payload")
        val sessionId = t.nizAli("session_id")
        val namen = t.nizAli("purpose")
        val odId = t.nizAli("from")
        val doId = t.nizAli("to")
        val nonce = t.nizAli("nonce")
        val sig = t.nizAli("sig")
        val ts = t.stevilo("ts") ?: throw NapakaPrenosa("neveljaven ts v ponudbi")
        if (sessionId.isBlank() || (namen != NAMEN_DATOTEKA && namen != NAMEN_ZASLON) || odId.isBlank() || nonce.isBlank() || sig.isBlank()) {
            throw NapakaPrenosa("ponudbi manjka polje")
        }
        if (doId != mojId) throw NapakaPrenosa("ponudba ni namenjena tej napravi")
        if (posiljateljIzHuba != null && posiljateljIzHuba != odId) throw NapakaPrenosa("posiljatelj huba se ne ujema s podpisano ponudbo")
        if (Math.abs(zdajSekunde() - ts) > VELJAVNOST_PONUDBE_S) throw NapakaPrenosa("ponudba je potekla ali ima cas v prihodnosti")
        if (!KrogZaupanja.preveriPodpisSKljucem(kljucPonudnikaB64, podatkiPonudbe(sessionId, namen, odId, doId, nonce, ts), sig)) {
            throw NapakaPrenosa("podpis ponudbe se ne ujema")
        }

        val mojNonce = nakljucnoB64(16)
        val ts2 = zdajSekunde()
        val podpis2 = podpisnik(podatkiOdgovora(sessionId, odId, doId, nonce, mojNonce, ts2))
        val payload = JsonLahki.Zapis()
            .niz("session_id", sessionId).niz("from", mojId).niz("to", odId)
            .niz("offer_nonce", nonce).niz("nonce", mojNonce).stevilo("ts", ts2).niz("sig", podpis2)
        val odgovor = JsonLahki.Zapis().niz("type", "data.answer").niz("target", odId)
            .surovo("payload", payload.toString())

        val skrivnost = ecdh(kljucPonudnikaB64)
        val sejni = izpeljiSejniKljuc(skrivnost, sessionId, nonce, mojNonce)
        return IzidPonudbe(sejni, odgovor.toString(), sessionId, namen, odId)
    }

    /**
     * Stran ponudnika: iz prejetega data.answer (in svoje poslane ponudbe) izpelje isti sejni kljuc.
     * [kljucPrejemnikaB64] spet mora priti iz kroga zaupanja, ne iz sporocila.
     */
    fun dokoncajPonudbo(ponudbaJson: String, odgovorJson: String, kljucPrejemnikaB64: String, posiljateljIzHuba: String? = null): SejniKljuc {
        val ovojOdgovora = JsonLahki.objekt(odgovorJson) ?: throw NapakaPrenosa("neveljaven JSON")
        if (ovojOdgovora.niz("type") != "data.answer") throw NapakaPrenosa("ni data.answer")
        val tp = JsonLahki.objekt(ponudbaJson)?.objekt("payload") ?: throw NapakaPrenosa("neveljavna ponudba")
        val ta = ovojOdgovora.objekt("payload") ?: throw NapakaPrenosa("sporocilu manjka payload")

        val sessionId = tp.nizAli("session_id")
        val mojIdZPonudbe = tp.nizAli("from")
        val tujId = tp.nizAli("to")
        val noncePonudbe = tp.nizAli("nonce")

        if (ta.nizAli("session_id") != sessionId) throw NapakaPrenosa("odgovor se ne ujema s to ponudbo (session_id)")
        if (ta.nizAli("from") != tujId || ta.nizAli("to") != mojIdZPonudbe) throw NapakaPrenosa("odgovor ima napacnega posiljatelja ali prejemnika")
        if (posiljateljIzHuba != null && posiljateljIzHuba != tujId) throw NapakaPrenosa("posiljatelj huba se ne ujema s podpisanim odgovorom")
        if (ta.nizAli("offer_nonce") != noncePonudbe) throw NapakaPrenosa("odgovor ne veze pravega nonca ponudbe")
        val nonceOdgovora = ta.nizAli("nonce")
        val sig = ta.nizAli("sig")
        val ts2 = ta.stevilo("ts") ?: throw NapakaPrenosa("neveljaven ts v odgovoru")
        if (nonceOdgovora.isBlank() || sig.isBlank()) throw NapakaPrenosa("odgovoru manjka polje")
        if (Math.abs(zdajSekunde() - ts2) > VELJAVNOST_PONUDBE_S) throw NapakaPrenosa("odgovor je potekel ali ima cas v prihodnosti")
        if (!KrogZaupanja.preveriPodpisSKljucem(kljucPrejemnikaB64, podatkiOdgovora(sessionId, mojIdZPonudbe, tujId, noncePonudbe, nonceOdgovora, ts2), sig)) {
            throw NapakaPrenosa("podpis odgovora se ne ujema")
        }

        val skrivnost = ecdh(kljucPrejemnikaB64)
        return izpeljiSejniKljuc(skrivnost, sessionId, noncePonudbe, nonceOdgovora)
    }

    companion object {
        const val NAMEN_DATOTEKA = "file"
        const val NAMEN_ZASLON = "screen"
        const val DOLZINA_KOSA = 1024 * 1024 // 1 MiB, kot v P2P-NACRT.md
        /** Ponudba/odgovor s casovnim zigom starejsim od tega se zavrne (glej link_data_transport.py). */
        const val VELJAVNOST_PONUDBE_S = 300.0

        private val nakljucje = SecureRandom()

        fun zdajSekunde(): Double = System.currentTimeMillis() / 1000.0

        fun nakljucnoB64(n: Int): String {
            val b = ByteArray(n)
            nakljucje.nextBytes(b)
            return Base64.getEncoder().encodeToString(b)
        }

        fun novSessionId(): String {
            val b = ByteArray(16)
            nakljucje.nextBytes(b)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }

        /** Kar podpise ponudnik (data.offer); domensko loceno od drugih podpisov v krogu (glej
         * KrogZaupanja.podatkiClana - isti vzorec). Enak zapis kot link_data_transport.podatki_ponudbe. */
        fun podatkiPonudbe(sessionId: String, namen: String, odId: String, doId: String, nonceB64: String, ts: Double): ByteArray =
            "safeer-data-offer-v1\n$sessionId\n$namen\n$odId\n$doId\n$nonceB64\n${String.format(Locale.ROOT, "%.3f", ts)}"
                .toByteArray(Charsets.UTF_8)

        /** Kar podpise odgovarjajoci (data.answer); veze odgovor na TOCNO to ponudbo (oba nonca). */
        fun podatkiOdgovora(sessionId: String, odId: String, doId: String, noncePonudbeB64: String, nonceOdgovoraB64: String, ts: Double): ByteArray =
            "safeer-data-answer-v1\n$sessionId\n$odId\n$doId\n$noncePonudbeB64\n$nonceOdgovoraB64\n${String.format(Locale.ROOT, "%.3f", ts)}"
                .toByteArray(Charsets.UTF_8)

        /** HKDF-SHA256 (RFC 5869). */
        fun hkdf(ikm: ByteArray, sol: ByteArray, info: ByteArray, dolzina: Int): ByteArray {
            val solEfektivna = if (sol.isNotEmpty()) sol else ByteArray(32)
            val macIzvleci = Mac.getInstance("HmacSHA256")
            macIzvleci.init(SecretKeySpec(solEfektivna, "HmacSHA256"))
            val prk = macIzvleci.doFinal(ikm)
            val macRazsiri = Mac.getInstance("HmacSHA256")
            macRazsiri.init(SecretKeySpec(prk, "HmacSHA256"))
            var izhod = ByteArray(0)
            var blok = ByteArray(0)
            var i = 1
            while (izhod.size < dolzina) {
                macRazsiri.reset()
                macRazsiri.update(blok)
                macRazsiri.update(info)
                macRazsiri.update(i.toByte())
                blok = macRazsiri.doFinal()
                izhod += blok
                i++
            }
            return izhod.copyOf(dolzina)
        }

        /** Sejni kljuc iz ECDH skrivnosti + obeh enkratnih nemenov (ponudba + odgovor). Sol = SHA-256
         * session_id-ja, da razlicne seje med istima napravama nikoli ne delita kljuca. */
        fun izpeljiSejniKljuc(skupnaSkrivnost: ByteArray, sessionId: String, nonceOfferB64: String, nonceAnswerB64: String): SejniKljuc {
            val nonceA = try { Base64.getDecoder().decode(nonceOfferB64) } catch (e: Exception) { throw NapakaPrenosa("neveljaven nonce: ${e.message}") }
            val nonceB = try { Base64.getDecoder().decode(nonceAnswerB64) } catch (e: Exception) { throw NapakaPrenosa("neveljaven nonce: ${e.message}") }
            val sol = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
            val info = "safeer-data-transport-v1\n".toByteArray(Charsets.UTF_8) + nonceA + nonceB
            val material = hkdf(skupnaSkrivnost, sol, info, 36)
            return SejniKljuc(material.copyOfRange(0, 32), material.copyOfRange(32, 36))
        }

        /** Dodatni podpisani (a nesifrirani) podatki enega kosa: vezejo sifrobesedilo na TO sejo IN na
         * njegov polozaj/skupno stevilo kosov, da premikanje, podvajanje ali obrezovanje kosov med
         * prenosom pade pri desifriranju. */
        fun aadKosa(sessionId: String, stevec: Int, skupajKosov: Int, zadnji: Boolean): ByteArray =
            "safeer-data-chunk-v1\n$sessionId\n$stevec\n$skupajKosov\n${if (zadnji) 1 else 0}".toByteArray(Charsets.UTF_8)

        fun sifrirajKos(sejni: SejniKljuc, stevec: Long, cistopis: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sejni.kljuc, "AES"), GCMParameterSpec(128, sejni.nonce(stevec)))
            cipher.updateAAD(aad)
            return cipher.doFinal(cistopis)
        }

        fun desifrirajKos(sejni: SejniKljuc, stevec: Long, sifropis: ByteArray, aad: ByteArray): ByteArray {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sejni.kljuc, "AES"), GCMParameterSpec(128, sejni.nonce(stevec)))
                cipher.updateAAD(aad)
                return cipher.doFinal(sifropis)
            } catch (e: NapakaPrenosa) {
                throw e
            } catch (e: Exception) {
                throw NapakaPrenosa("kos $stevec se ni desifriral (pokvarjen ali podtaknjen): ${e.message}")
            }
        }

        private fun kosi(cistopis: ByteArray, dolzinaKosa: Int): List<ByteArray> {
            if (cistopis.isEmpty()) return listOf(ByteArray(0))
            val rezultat = ArrayList<ByteArray>((cistopis.size + dolzinaKosa - 1) / dolzinaKosa)
            var i = 0
            while (i < cistopis.size) {
                val konec = minOf(i + dolzinaKosa, cistopis.size)
                rezultat.add(cistopis.copyOfRange(i, konec))
                i = konec
            }
            return rezultat
        }

        /** Razdeli na kose po [dolzinaKosa] in vsak kos sifrira posebej (glej aadKosa). Prazen
         * cistopis da en prazen kos, da tudi prenos dolzine 0 preverimo enako kot vsakega drugega. */
        fun sifrirajBajte(sejni: SejniKljuc, sessionId: String, cistopis: ByteArray, dolzinaKosa: Int = DOLZINA_KOSA): List<ByteArray> {
            val deli = kosi(cistopis, dolzinaKosa)
            val skupaj = deli.size
            return deli.mapIndexed { i, kos -> sifrirajKos(sejni, i.toLong(), kos, aadKosa(sessionId, i, skupaj, i == skupaj - 1)) }
        }

        fun desifrirajBajte(sejni: SejniKljuc, sessionId: String, sifropisi: List<ByteArray>): ByteArray {
            val skupaj = sifropisi.size
            val izhod = ByteArrayOutputStream()
            sifropisi.forEachIndexed { i, s -> izhod.write(desifrirajKos(sejni, i.toLong(), s, aadKosa(sessionId, i, skupaj, i == skupaj - 1))) }
            return izhod.toByteArray()
        }

        /** Zgostitev celotne datoteke po zadnjem kosu (P2P-NACRT.md: "sele nato se datoteka
         * preimenuje v koncno ime"). */
        fun sha256Hex(podatki: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(podatki).joinToString("") { "%02x".format(it) }
    }
}
