package si.safeer.tv

import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

fun main() {
    testSeznamInHashi()
    testPrepoznavaRealnihPotrdil()
    testPreverjanjeVerige()
    testPreverjanjeIzdajateljaInDName()
    testZastavicaOpozorila()
    testPrikazniNaslov()
    testNazajNaVarno()
    testOpozorilnaStran()
    println("PASS: eMudhra revocations (TV): 16 certs verified, SHA-256/SPKI match, chain rejection, flag, back-to-safety, warning page")
}

private fun testSeznamInHashi() {
    check(EmudhraRevocations.VSI_KOMPROMITIRANI.size == 16) { "Mora biti točno 16 kompromitiranih certifikatov" }
    check(EmudhraRevocations.KOMPROMITIRANI_SHA256.size == 16) { "Mora biti 16 unikatnih SHA-256 odtisov" }
    check(EmudhraRevocations.KOMPROMITIRANI_SPKI_SHA256.size == 8) { "Mora biti 8 unikatnih javnih ključev (SPKI)" }
    check(EmudhraRevocations.KOMPROMITIRANI_SUBJECT_PRINCIPALS.size == 8) { "Mora biti 8 unikatnih subjektov" }
    check(EmudhraRevocations.KOMPROMITIRANI_CNS.size == 8) { "Mora biti 8 unikatnih Common Names (CN)" }

    for (c in EmudhraRevocations.VSI_KOMPROMITIRANI) {
        check(EmudhraRevocations.jeKompromitiranSha256(c.sha256)) { "SHA256 ni prepoznan: ${c.sha256}" }
        check(EmudhraRevocations.jeKompromitiranSha256(c.sha256.uppercase())) { "SHA256 z velikimi črkami mora delovati" }
        check(EmudhraRevocations.jeKompromitiranSpkiSha256(c.spkiSha256)) { "SPKI ni prepoznan: ${c.spkiSha256}" }
        check(EmudhraRevocations.jeKompromitiranSubject(X500Principal(c.subject))) { "Subjekt ni prepoznan: ${c.subject}" }
        check(EmudhraRevocations.jeKompromitiranIzdajatelj(X500Principal(c.subject))) { "Izdajatelj ni prepoznan: ${c.subject}" }
    }
}

private fun testPrepoznavaRealnihPotrdil() {
    val cf = CertificateFactory.getInstance("X.509")

    // 1. Pristno kompromitirano vmesno potrdilo: EM DV TLS CA - G3B (eMudhra root)
    val pemG3bEmudhra = """
        -----BEGIN CERTIFICATE-----
        MIIDaDCCAu6gAwIBAgIQAKSu5njyM2K0wORgt3LyFDAKBggqhkjOPQQDAzBWMQsw
        CQYDVQQGEwJJTjElMCMGA1UECgwcZU11ZGhyYSBUZWNobm9sb2dpZXMgTGltaXRl
        ZDEgMB4GA1UEAwwXZW1TaWduIFJvb3QgVExTIENBIC0gRzMwHhcNMjUwNDMwMTU1
        NDA4WhcNNDAwNDI4MTgzMDAwWjA/MQswCQYDVQQGEwJOTDETMBEGA1UECgwKZU11
        ZGhyYSBCVjEbMBkGA1UEAwwSRU0gRFYgVExTIENBIC0gRzNCMHYwEAYHKoZIzj0C
        AQYFK4EEACIDYgAE557ZwEqXc8RzXlERHahawr1eZKs3S4DhLLwgOOweTw47gS/J
        hZ4eZ6+wTi+LyX90hbplqpEe6KTkW2LVzAPVa+Z4s8OMtNyTfKJGDiOEU8iSk10p
        1E+R4VYGtjzO2rRoo4IBljCCAZIwHwYDVR0jBBgwFoAU4TqrIXzx401UrtRyt/2L
        WUIgkFQwHQYDVR0OBBYEFOIeL+ccGTkOVUtiwSAkVzUkiiUUMB0GA1UdJQQWMBQG
        CCsGAQUFBwMBBggrBgEFBQcDAjBdBgNVHSAEVjBUMDkGCysGAQQBg44hAQABMCow
        KAYIKwYBBQUHAgEWHGh0dHA6Ly9yZXBvc2l0b3J5LmVtc2lnbi5jb20wDQYLKwYB
        BAGDjiEBAmQwCAYGZ4EMAQIBMHUGCCsGAQUFBwEBBGkwZzAkBggrBgEFBQcwAYYY
        aHR0cDovL29jc3AtYi5lbXNpZ24uY29tMD8GCCsGAQUFBzAChjNodHRwOi8vcmVw
        b3NpdG9yeS5lbXNpZ24uY29tL2NlcnRzL0VNRFZUTFNDQUczQi5wN2MwNwYDVR0f
        BDAwLjAsoCqgKIYmaHR0cDovL2NybC5lbXNpZ24uY29tLz9yb290dGxzY2FnMy5j
        cmwwDgYDVR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQAwCgYIKoZIzj0E
        AwMDaAAwZQIwLmsGXi6oFMJ2klkcEMXMug6gvxQWZWkewiDWVzQyl2L6z6DuQfPO
        Ibh1grvdULDCAjEAxZvLlxUavG69J6wnSjC8XBbqNIQsJ6CE2QdNOe84Ptxb6VG8
        gt2TlfJbHii+KNvK
        -----END CERTIFICATE-----
    """.trimIndent()
    val certG3bEmudhra = cf.generateCertificate(ByteArrayInputStream(pemG3bEmudhra.toByteArray())) as X509Certificate
    check(EmudhraRevocations.jeKompromitiranoPotrdilo(certG3bEmudhra)) { "certG3bEmudhra mora biti označen kot kompromitiran" }
    check(EmudhraRevocations.sha256Hex(certG3bEmudhra.encoded) == "42c4accd12c4f59597925e1a08a7a90102608543809308d50ec427e118ba1493")

    // 2. Pristno kompromitirano vmesno potrdilo: EM DV TLS CA - G3B (Sectigo cross-sign)
    val pemG3bSectigo = """
        -----BEGIN CERTIFICATE-----
        MIIDzjCCAragAwIBAgIRAKAk/fS6Zlx4XOSgIEg4WLcwDQYJKoZIhvcNAQELBQAw
        ezELMAkGA1UEBhMCR0IxGzAZBgNVBAgMEkdyZWF0ZXIgTWFuY2hlc3RlcjEQMA4G
        A1UEBwwHU2FsZm9yZDEaMBgGA1UECgwRQ29tb2RvIENBIExpbWl0ZWQxITAfBgNV
        BAMMGEFBQSBDZXJ0aWZpY2F0ZSBTZXJ2aWNlczAeFw0yNTA1MTQwMDAwMDBaFw0y
        ODEyMzEyMzU5NTlaMD8xCzAJBgNVBAYTAk5MMRMwEQYDVQQKDAplTXVkaHJhIEJW
        MRswGQYDVQQDDBJFTSBEViBUTFMgQ0EgLSBHM0IwdjAQBgcqhkjOPQIBBgUrgQQA
        IgNiAATnntnASpdzxHNeUREdqFrCvV5kqzdLgOEsvCA47B5PDjuBL8mFnh5nr7BO
        L4vJf3SFumWqkR7opORbYtXMA9Vr5nizw4y03JN8okYOI4RTyJKTXSnUT5HhVga2
        PM7atGijggE1MIIBMTAfBgNVHSMEGDAWgBSgEQojPpbxB+zirynvgqV/0DCktDAd
        BgNVHQ4EFgQU4h4v5xwZOQ5VS2LBICRXNSSKJRQwDgYDVR0PAQH/BAQDAgEGMBIG
        A1UdEwEB/wQIMAYBAf8CAQAwHQYDVR0lBBYwFAYIKwYBBQUHAwEGCCsGAQUFBwMC
        MDEGA1UdIAQqMCgwCAYGZ4EMAQIBMA0GCysGAQQBg44hAQABMA0GCysGAQQBg44h
        AQJkMEMGA1UdHwQ8MDowOKA2oDSGMmh0dHA6Ly9jcmwuY29tb2RvY2EuY29tL0FB
        QUNlcnRpZmljYXRlU2VydmljZXMuY3JsMDQGCCsGAQUFBwEBBCgwJjAkBggrBgEF
        BQcwAYYYaHR0cDovL29jc3AuY29tb2RvY2EuY29tMA0GCSqGSIb3DQEBCwUAA4IB
        AQBzQ1IoqpdotM6gFrR4Q3YrKifOLeaj5XXunldpVE/vZn2TxC2y0s8oYiLR5ICE
        wqVXSB7N6BM1Mp+Nif7Jbhb2IswAfSR4Iik8+B0iRmi7lYoNlycCgHjmsjOmZaxM
        QkPmjXAJbG5f4V41OsvJZmS7lI3r0IuvEDVX3GAZ13on6IImSs9ybNpHQ7NCqkMQ
        7wH4uAO1dtpFycgJSdHaCOhHjrox2BTYGLdpUrYwY3EOAjlZ8UGgsxs7mG+xquMl
        bN1LFURwfnkVMP3T/DBIZ/ys2+lNFyxK3FlArrV4/000uTnVB1lO8oGORYdXoT5V
        GUyRCEIDhF5TBUuKF4E3kf1h
        -----END CERTIFICATE-----
    """.trimIndent()
    val certG3bSectigo = cf.generateCertificate(ByteArrayInputStream(pemG3bSectigo.toByteArray())) as X509Certificate
    check(EmudhraRevocations.jeKompromitiranoPotrdilo(certG3bSectigo)) { "certG3bSectigo mora biti označen kot kompromitiran" }
    check(EmudhraRevocations.sha256Hex(certG3bSectigo.encoded) == "45a883f349aedfd5f3a649b5498ffb121712ba8b448f3f2a91dbc5a766fcddbe")

    // 3. Pristno NEPOŠKODOVANO krovno potrdilo: AAA Certificate Services (Sectigo Root CA)
    // To potrdilo je korenski izdajatelj (ni vmesni CA) in NE SME biti blokirano!
    val pemSectigoRoot = """
        -----BEGIN CERTIFICATE-----
        MIIEMjCCAxqgAwIBAgIBATANBgkqhkiG9w0BAQUFADB7MQswCQYDVQQGEwJHQjEb
        MBkGA1UECAwSR3JlYXRlciBNYW5jaGVzdGVyMRAwDgYDVQQHDAdTYWxmb3JkMRow
        GAYDVQQKDBFDb21vZG8gQ0EgTGltaXRlZDEhMB8GA1UEAwwYQUFBIENlcnRpZmlj
        YXRlIFNlcnZpY2VzMB4XDTA0MDEwMTAwMDAwMFoXDTI4MTIzMTIzNTk1OVowezEL
        MAkGA1UEBhMCR0IxGzAZBgNVBAgMEkdyZWF0ZXIgTWFuY2hlc3RlcjEQMA4GA1UE
        BwwHU2FsZm9yZDEaMBgGA1UECgwRQ29tb2RvIENBIExpbWl0ZWQxITAfBgNVBAMM
        GEFBQSBDZXJ0aWZpY2F0ZSBTZXJ2aWNlczCCASIwDQYJKoZIhvcNAQEBBQADggEP
        ADCCAQoCggEBAL5AnfRu4ep2hxxNRUSOvkbIgwadwSr+GB+O5AL686tdUIoWMQua
        BtDFcCLNSS1UY8y2bmhGC1Pqy0wkwLxyTurxFa70VJoSCsN6sjNg4tqJVfMiWPPe
        3M/vg4aijJRPn2jymJBGhCfHdr/jzDUsi14HZGWCwEiwqJH5YZ92IFCokcdmtet4
        YgNW8IoaE+oxox6gmf049vYnMlhvB/VruPsUK6+3qszWY19zjNoFmag4qMsXeDZR
        rOme9Hg6jc8P2ULimAyrL58OAd7vn5lJ8S3frHRNG5i1R8XlKdH5kBjHYpy+g8cm
        ez6KJcfA3Z3mNWgQIJ2P2N7Sw4ScDV7oL8kCAwEAAaOBwDCBvTAdBgNVHQ4EFgQU
        oBEKIz6W8Qfs4q8p74Klf9AwpLQwDgYDVR0PAQH/BAQDAgEGMA8GA1UdEwEB/wQF
        MAMBAf8wewYDVR0fBHQwcjA4oDagNIYyaHR0cDovL2NybC5jb21vZG9jYS5jb20v
        QUFBQ2VydGlmaWNhdGVTZXJ2aWNlcy5jcmwwNqA0oDKGMGh0dHA6Ly9jcmwuY29t
        b2RvLm5ldC9BQUFDZXJ0aWZpY2F0ZVNlcnZpY2VzLmNybDANBgkqhkiG9w0BAQUF
        AAOCAQEACFb8AvCb6P+k+tZ7xkSAzk/ExfYAWMymtrwUSWgEdujm7l3sAg9g1o1Q
        GE8mTgHj5rCl7r+8dFRBv/38ErjHT1r0iWAFf2C3BUrz9vHCv8S5dIa2LX1rzNLz
        Rt0vxuBqw8M0Ayx9lt1awg6nCpnBBYurDC/zXDrPbDdVCYfeU0BsWO/8tqtlbgT2
        G9w84FoVxp7Z8VlIMCFlA2zs6SFz7JsDoeA3raAVGI/6ugLOpyypEBMs1OUIJqsi
        l2D4kF501KKaU73yqWjgom7C12yxow+ev+to51byrvLjKzg6CYG1a4XXvi3tPxq3
        smPi9WIsgtRqAEFQ8TmDn5XpNpaYbg==
        -----END CERTIFICATE-----
    """.trimIndent()
    val certSectigoRoot = cf.generateCertificate(ByteArrayInputStream(pemSectigoRoot.toByteArray())) as X509Certificate
    check(!EmudhraRevocations.jeKompromitiranoPotrdilo(certSectigoRoot)) { "Sectigo korensko potrdilo NE SME biti blokirano" }
    check(!EmudhraRevocations.jeKompromitiranIzdajatelj(certSectigoRoot.issuerX500Principal)) { "Sectigo korenski izdajatelj NE SME biti blokiran" }
}

private fun testPreverjanjeVerige() {
    val cf = CertificateFactory.getInstance("X.509")

    val pemG3b = """
        -----BEGIN CERTIFICATE-----
        MIIDaDCCAu6gAwIBAgIQAKSu5njyM2K0wORgt3LyFDAKBggqhkjOPQQDAzBWMQsw
        CQYDVQQGEwJJTjElMCMGA1UECgwcZU11ZGhyYSBUZWNobm9sb2dpZXMgTGltaXRl
        ZDEgMB4GA1UEAwwXZW1TaWduIFJvb3QgVExTIENBIC0gRzMwHhcNMjUwNDMwMTU1
        NDA4WhcNNDAwNDI4MTgzMDAwWjA/MQswCQYDVQQGEwJOTDETMBEGA1UECgwKZU11
        ZGhyYSBCVjEbMBkGA1UEAwwSRU0gRFYgVExTIENBIC0gRzNCMHYwEAYHKoZIzj0C
        AQYFK4EEACIDYgAE557ZwEqXc8RzXlERHahawr1eZKs3S4DhLLwgOOweTw47gS/J
        hZ4eZ6+wTi+LyX90hbplqpEe6KTkW2LVzAPVa+Z4s8OMtNyTfKJGDiOEU8iSk10p
        1E+R4VYGtjzO2rRoo4IBljCCAZIwHwYDVR0jBBgwFoAU4TqrIXzx401UrtRyt/2L
        WUIgkFQwHQYDVR0OBBYEFOIeL+ccGTkOVUtiwSAkVzUkiiUUMB0GA1UdJQQWMBQG
        CCsGAQUFBwMBBggrBgEFBQcDAjBdBgNVHSAEVjBUMDkGCysGAQQBg44hAQABMCow
        KAYIKwYBBQUHAgEWHGh0dHA6Ly9yZXBvc2l0b3J5LmVtc2lnbi5jb20wDQYLKwYB
        BAGDjiEBAmQwCAYGZ4EMAQIBMHUGCCsGAQUFBwEBBGkwZzAkBggrBgEFBQcwAYYY
        aHR0cDovL29jc3AtYi5lbXNpZ24uY29tMD8GCCsGAQUFBzAChjNodHRwOi8vcmVw
        b3NpdG9yeS5lbXNpZ24uY29tL2NlcnRzL0VNRFZUTFNDQUczQi5wN2MwNwYDVR0f
        BDAwLjAsoCqgKIYmaHR0cDovL2NybC5lbXNpZ24uY29tLz9yb290dGxzY2FnMy5j
        cmwwDgYDVR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQAwCgYIKoZIzj0E
        AwMDaAAwZQIwLmsGXi6oFMJ2klkcEMXMug6gvxQWZWkewiDWVzQyl2L6z6DuQfPO
        Ibh1grvdULDCAjEAxZvLlxUavG69J6wnSjC8XBbqNIQsJ6CE2QdNOe84Ptxb6VG8
        gt2TlfJbHii+KNvK
        -----END CERTIFICATE-----
    """.trimIndent()
    val certG3b = cf.generateCertificate(ByteArrayInputStream(pemG3b.toByteArray())) as X509Certificate

    val pemClean = """
        -----BEGIN CERTIFICATE-----
        MIIEMjCCAxqgAwIBAgIBATANBgkqhkiG9w0BAQUFADB7MQswCQYDVQQGEwJHQjEb
        MBkGA1UECAwSR3JlYXRlciBNYW5jaGVzdGVyMRAwDgYDVQQHDAdTYWxmb3JkMRow
        GAYDVQQKDBFDb21vZG8gQ0EgTGltaXRlZDEhMB8GA1UEAwwYQUFBIENlcnRpZmlj
        YXRlIFNlcnZpY2VzMB4XDTA0MDEwMTAwMDAwMFoXDTI4MTIzMTIzNTk1OVowezEL
        MAkGA1UEBhMCR0IxGzAZBgNVBAgMEkdyZWF0ZXIgTWFuY2hlc3RlcjEQMA4GA1UE
        BwwHU2FsZm9yZDEaMBgGA1UECgwRQ29tb2RvIENBIExpbWl0ZWQxITAfBgNVBAMM
        GEFBQSBDZXJ0aWZpY2F0ZSBTZXJ2aWNlczCCASIwDQYJKoZIhvcNAQEBBQADggEP
        ADCCAQoCggEBAL5AnfRu4ep2hxxNRUSOvkbIgwadwSr+GB+O5AL686tdUIoWMQua
        BtDFcCLNSS1UY8y2bmhGC1Pqy0wkwLxyTurxFa70VJoSCsN6sjNg4tqJVfMiWPPe
        3M/vg4aijJRPn2jymJBGhCfHdr/jzDUsi14HZGWCwEiwqJH5YZ92IFCokcdmtet4
        YgNW8IoaE+oxox6gmf049vYnMlhvB/VruPsUK6+3qszWY19zjNoFmag4qMsXeDZR
        rOme9Hg6jc8P2ULimAyrL58OAd7vn5lJ8S3frHRNG5i1R8XlKdH5kBjHYpy+g8cm
        ez6KJcfA3Z3mNWgQIJ2P2N7Sw4ScDV7oL8kCAwEAAaOBwDCBvTAdBgNVHQ4EFgQU
        oBEKIz6W8Qfs4q8p74Klf9AwpLQwDgYDVR0PAQH/BAQDAgEGMA8GA1UdEwEB/wQF
        MAMBAf8wewYDVR0fBHQwcjA4oDagNIYyaHR0cDovL2NybC5jb21vZG9jYS5jb20v
        QUFBQ2VydGlmaWNhdGVTZXJ2aWNlcy5jcmwwNqA0oDKGMGh0dHA6Ly9jcmwuY29t
        b2RvLm5ldC9BQUFDZXJ0aWZpY2F0ZVNlcnZpY2VzLmNybDANBgkqhkiG9w0BAQUF
        AAOCAQEACFb8AvCb6P+k+tZ7xkSAzk/ExfYAWMymtrwUSWgEdujm7l3sAg9g1o1Q
        GE8mTgHj5rCl7r+8dFRBv/38ErjHT1r0iWAFf2C3BUrz9vHCv8S5dIa2LX1rzNLz
        Rt0vxuBqw8M0Ayx9lt1awg6nCpnBBYurDC/zXDrPbDdVCYfeU0BsWO/8tqtlbgT2
        G9w84FoVxp7Z8VlIMCFlA2zs6SFz7JsDoeA3raAVGI/6ugLOpyypEBMs1OUIJqsi
        l2D4kF501KKaU73yqWjgom7C12yxow+ev+to51byrvLjKzg6CYG1a4XXvi3tPxq3
        smPi9WIsgtRqAEFQ8TmDn5XpNpaYbg==
        -----END CERTIFICATE-----
    """.trimIndent()
    val certClean = cf.generateCertificate(ByteArrayInputStream(pemClean.toByteArray())) as X509Certificate

    // 1. Veriga s kompromitiranim vmesnim CA mora vreči CertificateException
    var padel = false
    try {
        EmudhraRevocations.preveriVerigo(arrayOf(certG3b))
    } catch (e: CertificateException) {
        padel = true
        check(e.message?.contains("kompromitirano vmesno potrdilo") == true)
    }
    check(padel) { "preveriVerigo z vmesnim CA eMudhra mora pasti s CertificateException" }

    // 2. Čista veriga gre skozi brez izjeme
    EmudhraRevocations.preveriVerigo(arrayOf(certClean))
}

private fun testPreverjanjeIzdajateljaInDName() {
    // 1. Vseh 8 CN-jev mora biti prepoznanih
    val pricakovaniCn = listOf(
        "EM DV TLS CA - G3B",
        "EM OV TLS CA - G3B",
        "EM EV TLS CA - G3B",
        "EM OV CS CA - G3B",
        "EM DV TLS CA - G3C",
        "EM OV TLS CA - G3C",
        "EM EV TLS CA - G3C",
        "EM OV CS CA - G3C"
    )
    for (cn in pricakovaniCn) {
        check(EmudhraRevocations.jeKompromitiranIzdajatelj(cn, "CN=$cn, O=eMudhra BV, C=NL"))
        check(EmudhraRevocations.jeKompromitiranIzdajatelj(cn, null))
    }

    // 2. Preizkusna stran iz naloge (https://dv-g3b-emudhra.we-have-a-problem.com/)
    // Njen izdajatelj je natanko:
    val testnaStranIssuer = "CN=EM DV TLS CA - G3B,O=eMudhra BV,C=NL"
    check(EmudhraRevocations.jeKompromitiranIzdajatelj(X500Principal(testnaStranIssuer)))
    check(EmudhraRevocations.jeKompromitiranIzdajatelj("EM DV TLS CA - G3B", testnaStranIssuer))

    // 3. Neoporečni izdajatelji NE SMEJO biti blokirani
    val cistiIzdajatelji = listOf(
        "CN=Let's Encrypt Authority X3,O=Let's Encrypt,C=US",
        "CN=GTS CA 1C3,O=Google Trust Services LLC,C=US",
        "CN=DigiCert Global G2 TLS RSA SHA256 2020 CA1,O=DigiCert Inc,C=US",
        "CN=AAA Certificate Services,O=Comodo CA Limited,L=Salford,ST=Greater Manchester,C=GB"
    )
    for (iss in cistiIzdajatelji) {
        val p = X500Principal(iss)
        check(!EmudhraRevocations.jeKompromitiranIzdajatelj(p)) { "Čist izdajatelj ne sme biti kompromitiran: $iss" }
        check(!EmudhraRevocations.jeKompromitiranIzdajatelj(null, iss)) { "Čist DName ne sme biti kompromitiran: $iss" }
    }
}

fun testZastavicaOpozorila() {
    val z = EmudhraRevocations.ZastavicaOpozorila()
    z.novaNavigacija()
    check(z.prviZaNavigacijo("https://a.example/")) { "Prvi klic za navigacijo mora pokazati opozorilo" }
    // onPageCommitVisible, onPageFinished, doUpdateVisitedHistory za isto navigacijo
    repeat(3) { check(!z.prviZaNavigacijo("https://a.example/")) { "Isto navigacijo je treba obravnavati le enkrat" } }
    // ponovno nalaganje istega URL je nova navigacija
    z.novaNavigacija()
    check(z.prviZaNavigacijo("https://a.example/")) { "Nova navigacija mora znova pokazati opozorilo" }
    // drugi URL v isti navigaciji (preusmeritev)
    check(z.prviZaNavigacijo("https://b.example/"))
}

fun testPrikazniNaslov() {
    val z = EmudhraRevocations.ZastavicaOpozorila()
    check(EmudhraRevocations.prikazniNaslov("https://a.example/", z) == "https://a.example/")
    check(EmudhraRevocations.prikazniNaslov("safeer://compromised-ca", z) == "safeer://compromised-ca") { "Brez zabeleženega URL ostane nespremenjen" }
    z.zabeleziBlokiraniUrl("https://slaba.example/pot")
    check(EmudhraRevocations.prikazniNaslov("safeer://compromised-ca", z) == "https://slaba.example/pot") { "Naslovna vrstica kaže izvirni URL" }
    check(EmudhraRevocations.prikazniNaslov("https://a.example/", z) == "https://a.example/") { "Druge strani niso prizadete" }
}

fun testNazajNaVarno() {
    EmudhraRevocations.blokirajGostitelja("Slaba.example")
    check(EmudhraRevocations.imaBlokiraneGostitelje())
    val urlji = listOf(
        "file:///android_asset/brave_home.html",
        "https://dobra.example/",
        "https://slaba.example/a",
        "https://slaba.example/b",
        "safeer://compromised-ca"
    )
    check(EmudhraRevocations.zamikNazajNaVarno(urlji, 4) == -3) { "Preskoči blokirane vnose in opozorilno stran" }
    check(EmudhraRevocations.zamikNazajNaVarno(urlji.drop(2), 2) == null) { "Brez varnega vnosa -> domača stran" }
    check(EmudhraRevocations.zamikNazajNaVarno(urlji, 0) == null)
}

fun testOpozorilnaStran() {
    val html = EmudhraRevocations.ustvariOpozorilnoStran(
        "Naslov <b>", "Prva vrstica\n\nDruga \"vrstica\"", "Znacka", "Nazaj na varno",
        "evil.example\"><script>alert(1)</script>", "https://evil.example/?a=<x>&b='1'", "sl"
    )
    check("<script>alert(1)</script>" !in html) { "Gostitelj mora biti izpisan kot besedilo" }
    check("&lt;script&gt;alert(1)&lt;/script&gt;" in html)
    check("<b>" !in html.substringAfter("<title>").substringBefore("</title>")) { "Naslov mora biti kodiran" }
    check("&lt;x&gt;&amp;b=&#39;1&#39;" in html) { "URL mora biti kodiran" }
    check("<br><br>" in html) { "Odstavka morata biti ločena" }
    // Daljinec: gumb ima fokus ob odprtju in vodi na isti naslov, ki ga obravnava shouldOverrideUrlLoading
    check("id=\"nazaj\" class=\"btn\" autofocus" in html) { "Gumb mora imeti autofocus" }
    check("getElementById('nazaj').focus()" in html) { "Gumb mora dobiti fokus tudi prek skripte" }
    check("location.href='${EmudhraRevocations.NAZAJ_NA_VARNO_URL}'" in html)
    check(">Nazaj na varno</button>" in html)
    check(EmudhraRevocations.jeOpozorilnaStran(EmudhraRevocations.OPOZORILNA_STRAN_URL))
    check(!EmudhraRevocations.jeOpozorilnaStran("https://evil.example/"))
}
