package si.safeer.tv.os

import si.safeer.tv.R

import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Pripeto potrdilo sredisca Safeer Linka.
 *
 * Sredisce (Safeer Browser na tem televizorju) ima samopodpisano potrdilo; njegov odtis SHA-256
 * dobimo skupaj z zetonom. Zaupamo samo potrdilu s tem odtisom - nobeni izdajateljski verigi,
 * nobenemu imenu gostitelja (naslov je 127.0.0.1 ali naslov v domacem omrezju).
 */
object Pin {
    class Zaupnik(private val odtis: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw java.security.cert.CertificateException("Odjemalska potrdila niso v rabi.")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val list = chain.firstOrNull() ?: throw java.security.cert.CertificateException("Ni potrdila.")
            val dobljeni = sha256Hex(list.encoded)
            if (dobljeni != odtis) {
                throw java.security.cert.CertificateException("Potrdilo sredisca se ne ujema s pripetim odtisom.")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun tovarna(odtis: String): Pair<SSLSocketFactory, Zaupnik> {
        val zaupnik = Zaupnik(normaliziraj(odtis))
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(zaupnik), java.security.SecureRandom())
        return ctx.socketFactory to zaupnik
    }

    /** Ime gostitelja ne pove nicesar (IP v domacem omrezju); odtis je edina resnica. */
    val brezImena: HostnameVerifier = HostnameVerifier { _, _ -> true }

    fun normaliziraj(odtis: String): String = odtis.trim().lowercase().replace(":", "")

    fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
