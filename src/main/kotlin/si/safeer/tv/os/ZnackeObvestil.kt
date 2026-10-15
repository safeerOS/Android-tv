package si.safeer.tv.os

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Znacke obvestil na ikonah zaganjalnika - samo za obvestila, ki potrebujejo uporabnika (sporocilo, klic,
 * e-posta, opomnik), ne za storitve v ozadju, predvajanje ali prenose (lastnik, 28. 9. 2026: te znacke ga
 * motijo). Kaj steje, odloci [ZaganjalnikPravila.potrebujeUporabnika].
 *
 * Do obvestil drugih aplikacij pride zaganjalnik samo z dovoljenjem »Dostop do obvestil«, ki ga uporabnik
 * vklopi sam v sistemskih nastavitvah ([odpriNastavitve]); brez njega znack preprosto ni. Shranimo samo
 * stevilo po paketu - nobenega besedila obvestila, nic ne zapustimo naprave.
 */
class ZnackeObvestil : NotificationListenerService() {

    override fun onListenerConnected() {
        povezan = true
        prestej()
    }

    override fun onListenerDisconnected() {
        povezan = false
        objavi(emptyMap())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = prestej()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = prestej()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = prestej()

    private fun prestej() {
        val vsa = try { activeNotifications } catch (e: Throwable) {
            Log.w(TAG, "Obvestil ni bilo mogoce prebrati: ${e.message}"); return
        } ?: return
        val razvrstitev = try { currentRanking } catch (_: Throwable) { null }
        val stevci = HashMap<String, Int>()
        val r = Ranking()
        for (sbn in vsa) {
            val n = sbn.notification ?: continue
            if (sbn.packageName == packageName) continue
            val pomembnost = if (Build.VERSION.SDK_INT >= 26 && razvrstitev?.getRanking(sbn.key, r) == true) r.importance else 0
            val dovoli = if (Build.VERSION.SDK_INT >= 26 && razvrstitev?.getRanking(sbn.key, r) == true) r.canShowBadge() else true
            val potrebuje = ZaganjalnikPravila.potrebujeUporabnika(
                trajno = sbn.isOngoing || n.flags and Notification.FLAG_ONGOING_EVENT != 0,
                storitevVOspredju = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
                povzetekSkupine = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                kategorija = n.category,
                pomembnost = if (pomembnost == NotificationManager.IMPORTANCE_UNSPECIFIED) 0 else pomembnost,
                dovoliZnacko = dovoli,
                napredek = (n.extras?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0) > 0 ||
                    n.extras?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) == true,
            )
            if (potrebuje) stevci[sbn.packageName] = (stevci[sbn.packageName] ?: 0) + 1
        }
        objavi(stevci)
    }

    companion object {
        private const val TAG = "SafeerZnacke"
        @Volatile var povezan = false
            private set
        /** Paket -> stevilo obvestil, ki cakajo uporabnika. Bere ga glavna nit. */
        @Volatile var stevci: Map<String, Int> = emptyMap()
            private set
        /** Zaganjalnik in Programi se prijavijo, da znacke osvezijo, ko se stevilo spremeni. */
        val poslusalci = mutableSetOf<() -> Unit>()
        private val glavna = Handler(Looper.getMainLooper())

        private fun objavi(novi: Map<String, Int>) {
            glavna.post {
                if (novi == stevci) return@post
                stevci = novi
                poslusalci.toList().forEach { try { it() } catch (_: Throwable) { } }
            }
        }

        fun koliko(paket: String): Int = stevci[paket] ?: 0

        /** Ali je uporabnik zaganjalniku dovolil dostop do obvestil. */
        fun dovoljeno(c: Context): Boolean = try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 27) nm.isNotificationListenerAccessGranted(ComponentName(c, ZnackeObvestil::class.java))
            else (Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: "")
                .contains(ComponentName(c, ZnackeObvestil::class.java).flattenToString())
        } catch (_: Throwable) { false }

        /** Sistemska stran, kjer uporabnik dostop vklopi (Android 11+: naravnost stran te storitve). */
        fun odpriNastavitve(c: Context): Boolean {
            val komponenta = ComponentName(c, ZnackeObvestil::class.java).flattenToString()
            val poskusi = ArrayList<Intent>()
            if (Build.VERSION.SDK_INT >= 30) poskusi.add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, komponenta))
            poskusi.add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            for (i in poskusi) try {
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
            } catch (_: Throwable) { }
            return false
        }
    }
}
