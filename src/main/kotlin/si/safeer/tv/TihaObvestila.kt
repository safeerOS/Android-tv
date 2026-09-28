package si.safeer.tv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * En sam tih kanal za storitve, ki tecejo v ozadju (Cast sprejemnik, Safeer Link, Scit).
 * Android zanje zahteva obvestilo, uporabniku pa ne povedo nic novega: brez znacke na ikoni,
 * brez ikone v vrstici stanja, brez zvoka – zlozena med tiha obvestila.
 * Pomembna obvestila (sporocila, koda za novo napravo, dovoljenja) ostanejo na svojih kanalih.
 */
object TihaObvestila {
    const val KANAL = "safeer_ozadje"
    private val STARI_KANALI = listOf("safeer_cast_channel", "safeer_link_hub", "safeer_scit")

    fun kanal(context: Context): String {
        val upravitelj = context.getSystemService(NotificationManager::class.java) ?: return KANAL
        try {
            if (upravitelj.getNotificationChannel(KANAL) == null) {
                val kanal = NotificationChannel(KANAL, context.getString(R.string.obvestila_ozadje_kanal),
                    NotificationManager.IMPORTANCE_MIN)
                kanal.description = context.getString(R.string.obvestila_ozadje_opis)
                kanal.setShowBadge(false)
                kanal.setSound(null, null)
                kanal.enableVibration(false)
                upravitelj.createNotificationChannel(kanal)
            }
            // Stari kanali so kazali znacko »1« na ikoni; znacke obstojecemu kanalu ni mogoce izklopiti.
            for (stari in STARI_KANALI) {
                if (upravitelj.getNotificationChannel(stari) != null) upravitelj.deleteNotificationChannel(stari)
            }
        } catch (_: Throwable) { }
        return KANAL
    }
}
