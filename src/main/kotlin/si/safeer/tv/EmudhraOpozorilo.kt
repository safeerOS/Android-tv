package si.safeer.tv

import android.content.Context
import android.net.http.SslCertificate
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Del zaščite eMudhra, ki potrebuje Android (WebView, SslCertificate); čista logika je v [EmudhraRevocations].
 * Preverba teče po prejemu strani (delna zaščita): ustavi nalaganje in naloži opozorilno stran brez obvoza.
 */
object EmudhraOpozorilo {

    /** Pretvori [SslCertificate] v [X509Certificate]: API 29+ neposredno, sicer prek saveState. */
    fun toX509Certificate(cert: SslCertificate): X509Certificate? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                cert.x509Certificate?.let { return it }
            } catch (_: Throwable) {}
        }
        return try {
            val bytes = SslCertificate.saveState(cert)?.getByteArray("x509-certificate") ?: return null
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
        } catch (_: Throwable) {
            null
        }
    }

    fun jeKompromitiranSslCertificate(cert: SslCertificate?): Boolean {
        if (cert == null) return false
        val x509 = toX509Certificate(cert)
        if (x509 != null) {
            if (EmudhraRevocations.jeKompromitiranoPotrdilo(x509)) return true
            if (EmudhraRevocations.jeKompromitiranIzdajatelj(x509.issuerX500Principal)) return true
        }
        val issuedBy = cert.issuedBy ?: return false
        return EmudhraRevocations.jeKompromitiranIzdajatelj(issuedBy.cName, issuedBy.dName)
    }

    /** Gumb »Nazaj na varno« in tipka Nazaj: nazaj mimo blokiranih gostiteljev, sicer domača stran. */
    fun nazajNaVarno(view: WebView, context: Context) {
        val seznam = view.copyBackForwardList()
        val urlji = (0 until seznam.size).map { seznam.getItemAtIndex(it).url ?: "" }
        val zamik = EmudhraRevocations.zamikNazajNaVarno(urlji, seznam.currentIndex)
        if (zamik != null) view.goBackOrForward(zamik) else view.loadUrl(SpletDomaca.naslov(context))
    }

    /**
     * Če je certifikat kompromitiran: ustavi nalaganje, naloži opozorilno stran in vrne true.
     * Opozorilo se za isto navigacijo zgodi le enkrat ([zastavica]). Fokus dobi gumb »Nazaj na varno«.
     */
    fun obravnavajKompromitiranCa(view: WebView, url: String?, zastavica: EmudhraRevocations.ZastavicaOpozorila): Boolean {
        val cert = view.certificate ?: return false
        if (!jeKompromitiranSslCertificate(cert)) return false

        val safeUrl = url ?: ""
        if (EmudhraRevocations.jeOpozorilnaStran(safeUrl)) return true
        if (!zastavica.prviZaNavigacijo(safeUrl)) return true

        zastavica.zabeleziBlokiraniUrl(safeUrl)
        android.util.Log.e("SafeerSecurity", "BLOKIRANO eMudhra: kompromitiran vmesni overitelj za $safeUrl")
        view.stopLoading()

        val host = try {
            java.net.URI(safeUrl).host?.ifEmpty { null } ?: safeUrl
        } catch (_: Throwable) {
            safeUrl
        }
        // Blokiran gostitelj: do ponovnega zagona brez predpomnilnika
        EmudhraRevocations.blokirajGostitelja(host)
        view.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        val html = EmudhraRevocations.ustvariOpozorilnoStran(
            UiText.get(R.string.ui_emudhra_title), UiText.get(R.string.ui_emudhra_msg),
            UiText.get(R.string.ui_emudhra_badge), UiText.get(R.string.ui_emudhra_back),
            host, safeUrl, UiText.language
        )
        view.loadDataWithBaseURL(
            EmudhraRevocations.OPOZORILNA_STRAN_URL, html, "text/html", "UTF-8", EmudhraRevocations.OPOZORILNA_STRAN_URL
        )
        view.requestFocus()
        return true
    }
}
