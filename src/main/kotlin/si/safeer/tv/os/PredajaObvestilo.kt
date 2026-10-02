package si.safeer.tv.os

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import si.safeer.tv.R
import java.util.Locale

/**
 * Tiho obvestilo »X ti posilja: Film (pri 12:34) – Sprejmi / Zavrni« na cilju (pravila 28. 9. 2026): brez zvoka, brez
 * celozaslonskega okna; dokler uporabnik ne sprejme, se tu nic ne ustavi in nic ne spremeni.
 *
 * Aplikacija v ospredju: majhna pasica na dnu zaslona (televizor nima vrstice z obvestili), ki se sama umakne po minuti.
 * Aplikacija v ozadju (telefon, tablica): obvestilo v vrstici z gumboma. Sprejmi odpre Medijski center, ki ponudbo
 * prevzame kot pri »Nadaljuj z druge naprave«.
 */
object PredajaObvestilo {
    private const val KANAL = "safeer_predaja"
    private const val ID = 7421
    const val DEJANJE_ZAVRNI = "si.safeer.tv.PREDAJA_ZAVRNI"
    /** Pritisk v prvih 700 ms po prikazu ne steje (odboj tipke, OK, ki je bil namenjen prejsnjemu elementu). */
    private const val ZASCITA_MS = 700L
    private const val PASICA_MS = 60_000L

    private var pasica: View? = null

    fun besedilo(ctx: Context, po: Predaja.Ponujeno): String =
        ctx.getString(R.string.os_predaja_ponudba, po.odIme, po.skladba.naslov, cas(po.polozajMs))

    /** Pasica v zaslonu [a]; Sprejmi preda ponudbo Medijskemu centru. */
    fun pasica(a: Activity, po: Predaja.Ponujeno) {
        umakniPasico()
        val koren = a.window?.decorView as? FrameLayout ?: run { obvesti(a, po); return }
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt() }
        val prikazano = SystemClock.uptimeMillis()
        fun gumb(napis: String, klik: () -> Unit) = Button(a).apply {
            text = napis; isAllCaps = false; textSize = 15f
            setTextColor(a.osBarva(R.color.os_besedilo))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(a.osBarva(R.color.os_kartica_dvignjena)); setStroke(dp(1), a.osBarva(R.color.os_kartica_obroba)) }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isFocusable = true
            setOnFocusChangeListener { v, f -> (v.background as GradientDrawable).setStroke(dp(if (f) 2 else 1), a.osBarva(if (f) R.color.os_mint else R.color.os_kartica_obroba)) }
            setOnClickListener { if (SystemClock.uptimeMillis() - prikazano > ZASCITA_MS) klik() }
            // Nazaj na pasici = Zavrni (gledalec filma se ne sme znajti zunaj predvajalnika); posiljatelj lahko poslje znova.
            setOnKeyListener { _, k, e ->
                if (k != android.view.KeyEvent.KEYCODE_BACK) false
                else { if (e.action == android.view.KeyEvent.ACTION_UP) { umakniPasico(); Predaja.zavrni(a) }; true }
            }
        }
        // Telefon (ozek zaslon): besedilo zgoraj, gumba v svoji vrsti; tablica in televizor: vse v eni vrsti.
        val sirok = a.resources.configuration.smallestScreenWidthDp >= 600
        val sprejmi = gumb(a.getString(R.string.os_predaja_sprejmi)) { umakniPasico(); sprejmi(a) }
        val zavrni = gumb(a.getString(R.string.os_predaja_zavrni)) { umakniPasico(); Predaja.zavrni(a) }
        val napis = TextView(a).apply { text = besedilo(a, po); textSize = 15f; setTextColor(a.osBarva(R.color.os_besedilo)); maxLines = 3 }
        val vsebina = LinearLayout(a).apply {
            orientation = if (sirok) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(a.osBarva(R.color.os_meni_ozadje)); setStroke(dp(1), a.osBarva(R.color.os_kartica_obroba)) }
            setPadding(dp(16), dp(10), dp(10), dp(10))
            elevation = dp(8).toFloat()
            if (sirok) {
                addView(napis, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
                addView(sprejmi, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
                addView(zavrni)
            } else {
                addView(napis, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                    addView(sprejmi, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
                    addView(zavrni)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            tag = sprejmi
        }
        val p = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        val rob = dp(if (sirok) 48 else 12)
        p.setMargins(rob, 0, rob, dp(if (sirok) 32 else 72))
        koren.addView(vsebina, p)
        pasica = vsebina
        // Brez dotika (televizor) OK sprejme, Nazaj ali Zavrni umakne; na telefonu se gumba tapneta.
        if (!a.packageManager.hasSystemFeature("android.hardware.touchscreen")) vsebina.post { (vsebina.tag as? View)?.requestFocus() }
        vsebina.postDelayed({ if (pasica === vsebina) umakniPasico() }, PASICA_MS)
    }

    fun umakniPasico() {
        val v = pasica ?: return
        pasica = null
        (v.parent as? FrameLayout)?.removeView(v)
    }

    /** Sprejmi: Medijski center ponudbo prevzame (nadaljuje pri isti sekundi, video odpre predvajalnik). */
    fun sprejmi(a: Activity) {
        if (a is GlasbaActivity) { a.sprejmiPonudbo(); return }
        a.startActivity(Intent(a, GlasbaActivity::class.java).putExtra(GlasbaActivity.EXTRA_PREDAJA_SPREJMI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Obvestilo v vrstici (aplikacija v ozadju): tiho (brez zvoka), z gumboma Sprejmi in Zavrni. */
    fun obvesti(ctx: Context, po: Predaja.Ponujeno) {
        try {
            val c = ctx.applicationContext
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(KANAL) == null) {
                nm.createNotificationChannel(NotificationChannel(KANAL, c.getString(R.string.os_predaja_poslji), NotificationManager.IMPORTANCE_LOW))
            }
            val odpri = Intent(c, GlasbaActivity::class.java).putExtra(GlasbaActivity.EXTRA_PREDAJA_SPREJMI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val piSprejmi = PendingIntent.getActivity(c, ID, odpri, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val piZavrni = PendingIntent.getBroadcast(c, ID + 1, Intent(c, Prejemnik::class.java).setAction(DEJANJE_ZAVRNI),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val g = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, KANAL) else android.app.Notification.Builder(c)
            val obvestilo = g.setSmallIcon(R.drawable.os_znak)
                .setContentTitle(c.getString(R.string.os_predaja_poslji))
                .setContentText(besedilo(c, po))
                .setStyle(android.app.Notification.BigTextStyle().bigText(besedilo(c, po)))
                .setContentIntent(piSprejmi).setAutoCancel(true)
                .setTimeoutAfter(Predaja.VELJA_MS)
                .addAction(android.app.Notification.Action.Builder(null, c.getString(R.string.os_predaja_sprejmi), piSprejmi).build())
                .addAction(android.app.Notification.Action.Builder(null, c.getString(R.string.os_predaja_zavrni), piZavrni).build())
                .build()
            nm.notify(ID, obvestilo)
        } catch (t: Throwable) {
            Predaja.log("obvestilo: $t")
        }
    }

    fun umakni(ctx: Context) {
        umakniPasico()
        try { ctx.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(ID) } catch (_: Throwable) { }
    }

    private fun cas(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** Zavrni iz obvestila: ponudba odpade, obvestilo se umakne; izvoru nicesar ne sporocimo (igra naprej). */
    class Prejemnik : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DEJANJE_ZAVRNI) Predaja.zavrni(context)
        }
    }
}
