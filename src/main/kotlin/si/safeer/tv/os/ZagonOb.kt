package si.safeer.tv.os

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import si.safeer.tv.R

/**
 * "Zazeni ob vklopu televizorja".
 *
 * Tipke Domov nam nekateri televizorji ne dajo (proizvajalcev prestreznik), lahko pa je Safeer OS
 * **prvo, kar vidis**, ko televizor prizges: ob zagonu sistema se odpre cez ves zaslon. Privzeto je
 * izklopljeno in uporabnik to kadarkoli vklopi ali izklopi - televizorju nicesar ne spreminjamo in
 * njegov domaci zaslon ostane tam, kamor vodi tipka Domov.
 *
 * Android 10 in novejsi zagona dejavnosti iz ozadja ne dovoli kar tako; edina posteno predvidena
 * pot je obvestilo s celozaslonsko namero (isto, kar Safeer Link ze uporablja, ko naprava v hisi
 * odpre stran na televizorju).
 */
object ZagonOb {
    private const val TAG = "SafeerOsZagon"
    private const val PREFS = "safeer_os"
    private const val KLJUC = "zagon_ob_vklopu"
    private const val KANAL = "safeer_os_zagon"
    private const val OBVESTILO = 4051

    private fun p(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun jeVklopljen(context: Context): Boolean = p(context).getBoolean(KLJUC, false)

    fun nastavi(context: Context, vklopljen: Boolean) {
        p(context).edit().putBoolean(KLJUC, vklopljen).apply()
        Log.i(TAG, "Zagon ob vklopu televizorja: ${if (vklopljen) "vklopljen" else "izklopljen"}")
    }

    /** Safeer OS naj se pokaze. Iz ozadja gre to samo prek obvestila s celozaslonsko namero. */
    fun pokaziDomov(context: Context) {
        val app = context.applicationContext
        val namera = Intent(app, DomovActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { app.startActivity(namera) } catch (e: Throwable) {
            Log.i(TAG, "Neposredni zagon ni sel (pricakovano iz ozadja): ${e.message}")
        }
        prekOObvestila(app, namera)
    }

    /** Obvestilo pospravimo, ko je Safeer OS na zaslonu - uporabnik naj ga sploh ne vidi. */
    fun pospravi(context: Context) {
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(OBVESTILO)
        } catch (_: Throwable) { }
    }

    private fun prekOObvestila(app: Context, namera: Intent) {
        try {
            val upravitelj = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (upravitelj.getNotificationChannel(KANAL) == null) {
                val kanal = android.app.NotificationChannel(KANAL, app.getString(R.string.os_zagon_kanal),
                    android.app.NotificationManager.IMPORTANCE_HIGH)
                kanal.setShowBadge(false)
                upravitelj.createNotificationChannel(kanal)
            }
            val zastavice = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val cakajoca = PendingIntent.getActivity(app, OBVESTILO, namera, zastavice)
            val gradnik = android.app.Notification.Builder(app, KANAL)
            upravitelj.notify(OBVESTILO, gradnik
                .setContentTitle(app.getString(R.string.os_app_name))
                .setContentText(app.getString(R.string.os_zagon_obvestilo))
                .setSmallIcon(R.drawable.os_znak)
                .setContentIntent(cakajoca)
                .setFullScreenIntent(cakajoca, true)
                .setAutoCancel(true)
                .build())
        } catch (e: Throwable) {
            Log.w(TAG, "Obvestila za zagon ni bilo mogoce objaviti: ${e.message}")
        }
    }
}
