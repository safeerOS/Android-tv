package si.safeer.tv.os

import si.safeer.tv.R
import java.util.Locale

/**
 * Znani ponudniki e-poste: uporabnik izbere ponudnika s seznama, vpise le naslov in geslo; streznike in vrata
 * poznamo mi. Naslovi streznikov so javni podatki ponudnikov (preverjeni 30. 9. 2026: IMAP 993 TLS, SMTP
 * 465 TLS ali 587 STARTTLS). "geslo": navadno = obicajno geslo; aplikacije = geslo za aplikacije (ponudnik
 * zahteva 2FA + posebno geslo); oauth = ponudnik gesel ne dovoli vec (potrebna prijava OAuth, ki je se nimamo).
 */
object PonudnikiEposte {
    const val NAVADNO = "navadno"
    const val APLIKACIJE = "aplikacije"
    const val OAUTH = "oauth"
    const val DRUG = "drug"

    data class Ponudnik(val id: String, val ime: String, val domene: List<String>, val imap: String, val imapVrata: Int,
                        val smtp: String, val smtpVrata: Int, val geslo: String, val navodila: Int)

    val SEZNAM: List<Ponudnik> = listOf(
        Ponudnik("gmail", "Gmail", listOf("gmail.com", "googlemail.com"), "imap.gmail.com", 993, "smtp.gmail.com", 465, APLIKACIJE, R.string.os_spor_nav_gmail),
        Ponudnik("siol", "Siol (Telekom Slovenije)", listOf("siol.net", "siol.com"), "imap.siol.net", 993, "mail.siol.net", 465, NAVADNO, R.string.os_spor_nav_navadno),
        Ponudnik("t2", "T-2", listOf("t-2.net", "t-2.si"), "imap.t-2.net", 993, "smtp.t-2.net", 465, NAVADNO, R.string.os_spor_nav_navadno),
        Ponudnik("telemach", "Telemach", listOf("telemach.net", "telemach.si"), "imap.telemach.net", 993, "smtp.telemach.net", 587, NAVADNO, R.string.os_spor_nav_navadno),
        Ponudnik("amis", "Amis", listOf("amis.net"), "imap.amis.net", 993, "smtp.amis.net", 465, NAVADNO, R.string.os_spor_nav_navadno),
        Ponudnik("arnes", "Arnes", listOf("arnes.si", "guest.arnes.si"), "imap.arnes.si", 993, "mail.arnes.si", 465, NAVADNO, R.string.os_spor_nav_arnes),
        Ponudnik("yahoo", "Yahoo Mail", listOf("yahoo.com", "yahoo.co.uk", "yahoo.de", "ymail.com", "rocketmail.com"), "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465, APLIKACIJE, R.string.os_spor_nav_yahoo),
        Ponudnik("icloud", "iCloud Mail", listOf("icloud.com", "me.com", "mac.com"), "imap.mail.me.com", 993, "smtp.mail.me.com", 587, APLIKACIJE, R.string.os_spor_nav_icloud),
        Ponudnik("gmx", "GMX", listOf("gmx.net", "gmx.de", "gmx.at", "gmx.ch", "gmx.com"), "imap.gmx.net", 993, "mail.gmx.net", 465, NAVADNO, R.string.os_spor_nav_gmx),
        Ponudnik("mailcom", "mail.com", listOf("mail.com", "email.com", "usa.com"), "imap.mail.com", 993, "smtp.mail.com", 465, NAVADNO, R.string.os_spor_nav_gmx),
        Ponudnik("zoho", "Zoho Mail", listOf("zohomail.eu", "zohomail.com", "zoho.com", "zoho.eu"), "imap.zoho.eu", 993, "smtp.zoho.eu", 465, APLIKACIJE, R.string.os_spor_nav_zoho),
        Ponudnik("fastmail", "Fastmail", listOf("fastmail.com", "fastmail.fm"), "imap.fastmail.com", 993, "smtp.fastmail.com", 465, APLIKACIJE, R.string.os_spor_nav_fastmail),
        Ponudnik("posteo", "Posteo", listOf("posteo.de", "posteo.net", "posteo.eu"), "posteo.de", 993, "posteo.de", 465, NAVADNO, R.string.os_spor_nav_navadno),
        Ponudnik("seznam", "Seznam.cz", listOf("seznam.cz", "email.cz", "post.cz"), "imap.seznam.cz", 993, "smtp.seznam.cz", 465, NAVADNO, R.string.os_spor_nav_navadno),
        Ponudnik("outlook", "Outlook / Hotmail", listOf("outlook.com", "hotmail.com", "live.com", "msn.com", "outlook.de", "hotmail.de"), "outlook.office365.com", 993, "smtp.office365.com", 587, OAUTH, R.string.os_spor_nav_oauth),
        Ponudnik(DRUG, "", emptyList(), "", 993, "", 465, NAVADNO, R.string.os_spor_nav_drug),
    )

    fun po(id: String): Ponudnik = SEZNAM.first { it.id == id }

    /** Ponudnik po domeni naslova (za samodejno izbiro, ko uporabnik najprej vpise naslov). */
    fun izNaslova(naslov: String): Ponudnik? {
        val d = naslov.substringAfterLast('@').lowercase(Locale.ROOT).trim()
        return SEZNAM.firstOrNull { d in it.domene }
    }

    /** Sluzi tudi za "drug ponudnik": predlog imap.<domena> / smtp.<domena>. */
    fun predlog(naslov: String): Pair<String, String> {
        val d = naslov.substringAfterLast('@').lowercase(Locale.ROOT).trim()
        return "imap.$d" to "smtp.$d"
    }
}

/**
 * Ponudniki, ki IMAP/SMTP ne ponujajo (ali ne vec) in delujejo le prek lastne aplikacije, ter klepeti.
 * Ponudimo uradno aplikacijo iz trgovine (Google Play) - odprt je vedno uradni vnos, nic ne prenasamo sami.
 */
object AplikacijeSporocil {
    data class Aplikacija(val ime: String, val paket: String, val opis: Int, val vrsta: String)   // vrsta: posta | klepet

    val SEZNAM = listOf(
        Aplikacija("Outlook", "com.microsoft.office.outlook", R.string.os_spor_app_outlook, "posta"),
        Aplikacija("Proton Mail", "ch.protonmail.android", R.string.os_spor_app_proton, "posta"),
        Aplikacija("Tuta Mail", "de.tutao.tutanota", R.string.os_spor_app_tuta, "posta"),
        Aplikacija("Signal", "org.thoughtcrime.securesms", R.string.os_spor_app_signal, "klepet"),
        Aplikacija("Telegram", "org.telegram.messenger", R.string.os_spor_app_telegram, "klepet"),
        Aplikacija("WhatsApp", "com.whatsapp", R.string.os_spor_app_whatsapp, "klepet"),
        Aplikacija("Viber", "com.viber.voip", R.string.os_spor_app_viber, "klepet"),
        Aplikacija("Element (Matrix)", "im.vector.app", R.string.os_spor_app_element, "klepet"),
        Aplikacija("Messenger", "com.facebook.orca", R.string.os_spor_app_messenger, "klepet"),
        Aplikacija("Discord", "com.discord", R.string.os_spor_app_discord, "klepet"),
    )

    fun nameščena(c: android.content.Context, paket: String): Boolean =
        try { c.packageManager.getPackageInfo(paket, 0); true } catch (_: Throwable) { false }

    /** Odpre aplikacijo, ce je namescena, sicer njen uradni vnos v trgovini (Google Play; brez trgovine spletna stran Playa). */
    fun odpriAliNamesti(c: android.content.Context, paket: String) {
        if (nameščena(c, paket)) {
            c.packageManager.getLaunchIntentForPackage(paket)?.let { c.startActivity(it); return }
        }
        val trgovina = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$paket"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(trgovina) } catch (_: Throwable) {
            c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$paket"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
