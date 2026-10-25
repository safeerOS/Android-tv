package si.safeer.tv.link

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import si.safeer.tv.R

/**
 * Dovoljenja Androida za datoteke te naprave v Safeer Linku. Odpre se ob privolitvi v Linku (druga naprava je tu na
 * novo dobila datoteke - [zacni]) ali ob dotiku obvestila, ko druga naprava prosi za vec datotek (`files.request_access`,
 * [obvesti]). Sistemsko okno se odpre samo po dotiku uporabnika, nikoli samo.
 *
 * Lastnik, 25. 10. 2026 10:49: naprave iz ozjega kroga dobijo dostop z eno privolitvijo v Linku, brez locenih stikal.
 * Zato najprej najboljse: vse datoteke (Android 11+ sistemska stran »dostop do vseh datotek« za Safeer, Android 9
 * dovoljenje za shrambo). Ce jih uporabnik ne odobri, se sam vklopi rezervni nacin - dovoljenje za medije (Android 14
 * pokaze »Izberi fotografije« / »Dovoli vse«). Kar je ze odobreno, ne vprasa znova. Prosojna dejavnost brez lastnega
 * vmesnika (kot PotrditevActivity).
 */
class DostopDatotekActivity : Activity() {

    companion object {
        private const val KANAL = "safeer_link_datoteke"
        private const val OBVESTILO = 7341
        private const val ZAHTEVA = 7342
        private const val ZAHTEVA_STRAN = 7343
        private const val VRSTA = "vrsta"
        private const val CAKA_STRAN = "caka_stran"
        private const val NAPRAVA = "naprava"

        /**
         * Privolitev v Linku: okno dovoljenja takoj (uporabnik je pravkar potrdil v Nastavitvah ali Safeer Linku).
         * False, ce ni cesa vprasati ali se okno ni odprlo.
         */
        fun zacni(context: Context, prosnja: LinkDatotekePravila.Prosnja): Boolean {
            if (prosnja == LinkDatotekePravila.Prosnja.NIC) return false
            return try {
                val namera = Intent(context, DostopDatotekActivity::class.java).putExtra(VRSTA, prosnja.name)
                if (context !is Activity) namera.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(namera)
                true
            } catch (_: Throwable) { false }
        }

        /**
         * Obvestilo o prosnji naprave [ime] ([idNaprave]); false, ce ga ni mogoce pokazati (obvestila izklopljena). Dotik
         * obvestila za vse datoteke je privolitev uporabnika prav za to napravo (pregled link-datoteke, tocka 2).
         */
        fun obvesti(context: Context, ime: String, prosnja: LinkDatotekePravila.Prosnja, idNaprave: String = ""): Boolean {
            val upravitelj = context.getSystemService(NotificationManager::class.java) ?: return false
            if (!upravitelj.areNotificationsEnabled()) return false
            return try {
                upravitelj.createNotificationChannel(NotificationChannel(KANAL, context.getString(R.string.link_datoteke_kanal),
                    NotificationManager.IMPORTANCE_DEFAULT))
                val namera = Intent(context, DostopDatotekActivity::class.java).putExtra(VRSTA, prosnja.name)
                    .putExtra(NAPRAVA, idNaprave).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val cakajoca = PendingIntent.getActivity(context, OBVESTILO, namera,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val besedilo = context.getString(if (prosnja == LinkDatotekePravila.Prosnja.VSE_DATOTEKE)
                    R.string.link_datoteke_prosnja_vse else R.string.link_datoteke_prosnja_fotografije, ime)
                val obvestilo = Notification.Builder(context, KANAL)
                    .setContentTitle(context.getString(R.string.link_datoteke_prosnja_naslov))
                    .setContentText(besedilo)
                    .setStyle(Notification.BigTextStyle().bigText(besedilo))
                    .setSmallIcon(android.R.drawable.ic_menu_gallery)
                    .setContentIntent(cakajoca)
                    .setAutoCancel(true)
                    .build()
                upravitelj.notify(OBVESTILO, obvestilo)
                true
            } catch (_: Throwable) { false }
        }
    }

    /** Sistemska stran »vse datoteke« je odprta; ob vrnitvi (onActivityResult) po potrebi se mediji. */
    private var cakaStran = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { getSystemService(NotificationManager::class.java)?.cancel(OBVESTILO) } catch (_: Throwable) { }
        // Po zasuku zaslona okno dovoljenja ali sistemska stran ze caka na odgovor.
        if (savedInstanceState != null) { cakaStran = savedInstanceState.getBoolean(CAKA_STRAN, false); return }
        // Dotik obvestila »naprava X prosi za vse datoteke«: privolitev za X (dejavnost ni izvozena - namero da le Safeer).
        if (intent?.getStringExtra(VRSTA) == LinkDatotekePravila.Prosnja.VSE_DATOTEKE.name)
            intent?.getStringExtra(NAPRAVA)?.takeIf { it.isNotBlank() }?.let {
                try { si.safeer.tv.cast.Dostop.zabeleziPrivolitevVsehDatotek(this, it) } catch (_: Throwable) { }
            }
        if (intent?.getStringExtra(VRSTA) == LinkDatotekePravila.Prosnja.VSE_DATOTEKE.name && !DatotekeStreznik.vseDatoteke(this)) {
            if (DatotekeStreznik.zahtevajVseDatoteke(this, ZAHTEVA, ZAHTEVA_STRAN)) {
                // Android 11+: odgovor pride v onActivityResult. Android 9: okno za shrambo (onRequestPermissionsResult),
                // ce dovoljenja se ni - sicer je izbira »vse datoteke« zapisana in konec.
                if (Build.VERSION.SDK_INT >= 30) { cakaStran = true; return }
                if (checkSelfPermission(LinkDatotekePravila.SHRAMBA) != PackageManager.PERMISSION_GRANTED) return
                finish()
                return
            }
            // Strani na tej napravi ni (ali se ni odprla): rezervni nacin takoj.
        }
        medijiAliKonec()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(CAKA_STRAN, cakaStran)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ZAHTEVA_STRAN) return
        cakaStran = false
        // Stran vrne RESULT_CANCELED tudi po vklopu: odloca stanje dostopa zdaj.
        medijiAliKonec()
    }

    /** Rezervni nacin: brez vseh datotek in brez vseh medijev vprasa za medije; sicer konec. */
    private fun medijiAliKonec() {
        if (DatotekeStreznik.dostop(this) == LinkDatotekePravila.Dostop.POLN) { finish(); return }
        try { requestPermissions(DatotekeStreznik.dovoljenja(), ZAHTEVA) } catch (_: Throwable) { finish() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}
