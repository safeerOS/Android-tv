package si.safeer.tv.link

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import si.safeer.tv.R

/**
 * »Ali sme ta naprava uporabljati internet tega telefona?«
 *
 * Vprasanje sprozi druga naprava (racunalnik vklopi »Internet prek telefona«), odlocitev pa je
 * uporabnikova in na tem telefonu. Prehod tece v storitvi v ozadju, ta pa okna ne sme odpreti sama
 * (Android 10+), zato gre vprasanje v obvestilo z gumboma Dovoli in Zavrni; dotik obvestila odpre okno
 * z istim vprasanjem. Kadar je Safeer OS v ospredju, se okno odpre takoj.
 *
 * Obvestila za Safeer OS so lahko izklopljena - takrat uporabnik iz ozadja ne vidi nicesar. Zato se okno
 * odpre tudi, ko Safeer OS pride v ospredje in kaksna naprava se caka ([pokaziCakajoce]); racunalnik
 * uporabniku rece »na telefonu odpri Safeer OS«.
 *
 * Brez odlocitve ostane naprava »caka«: interneta ne dobi, vprasanje pa se ob naslednji prosnji
 * pokaze znova (najvec enkrat na minuto). Odlocitev se da kadarkoli spremeniti v Nastavitve ›
 * Internet prek Safeer Linka.
 */
object InternetVprasanje {
    private const val TAG = "SafeerGateway"
    private const val KANAL = "safeer_internet"
    private const val OSNOVA_ID = 4720
    const val DEJANJE_DOVOLI = "si.safeer.tv.link.INTERNET_DOVOLI"
    const val DEJANJE_ZAVRNI = "si.safeer.tv.link.INTERNET_ZAVRNI"
    const val KLJUC_NAPRAVA = "naprava"
    const val KLJUC_IME = "ime"

    private fun id(naprava: String) = OSNOVA_ID + (naprava.hashCode() and 0x3f)

    private fun okno(ctx: Context, naprava: String, ime: String) = Intent(ctx, InternetDovoljenjeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(KLJUC_NAPRAVA, naprava).putExtra(KLJUC_IME, ime)

    /** Pokaze vprasanje za [naprava] (fizicna naprava Linka) z imenom [ime]. */
    fun pokazi(ctx: Context, naprava: String, ime: String) {
        val c = ctx.applicationContext
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(KANAL) == null) {
                    nm.createNotificationChannel(NotificationChannel(KANAL, c.getString(R.string.os_ig_kanal), NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = c.getString(R.string.os_ig_kanal_opis) })
                }
                val zastavice = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val nid = id(naprava)
                val dotik = PendingIntent.getActivity(c, nid, okno(c, naprava, ime), zastavice)
                fun gumb(dejanje: String, zamik: Int) = PendingIntent.getBroadcast(c, nid * 4 + zamik,
                    Intent(c, Prejemnik::class.java).setAction(dejanje).putExtra(KLJUC_NAPRAVA, naprava).putExtra(KLJUC_IME, ime), zastavice)
                val besedilo = c.getString(R.string.os_ig_vprasanje, ime)
                @Suppress("DEPRECATION")
                val g = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, KANAL) else Notification.Builder(c)
                nm.notify(nid, g.setSmallIcon(R.drawable.os_znak)
                    .setContentTitle(c.getString(R.string.os_ig_vprasanje_kratko, ime))
                    .setContentText(besedilo)
                    .setStyle(Notification.BigTextStyle().bigText(besedilo))
                    .setContentIntent(dotik).setAutoCancel(true)
                    // Svoja skupina: sicer Android vprasanje zlozi pod stalno obvestilo »Safeer OS v ozadju« in gumba se skrijeta.
                    .setGroup("safeer_internet_" + naprava)
                    .addAction(Notification.Action.Builder(null, c.getString(R.string.os_ig_dovoli), gumb(DEJANJE_DOVOLI, 1)).build())
                    .addAction(Notification.Action.Builder(null, c.getString(R.string.os_ig_zavrni), gumb(DEJANJE_ZAVRNI, 2)).build())
                    .build())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "vprasanje (obvestilo): ${t.javaClass.simpleName}")
        }
        // Poskus neposrednega odprtja: uspe, kadar je Safeer OS v ospredju; sicer ga Android zavrne in ostane obvestilo.
        try { c.startActivity(okno(c, naprava, ime)) } catch (_: Throwable) { }
    }

    /** Cas prosnje, pri kateri je uporabnik vprasanje za napravo ze videl (odmaknjenega ne ponavljamo ob vsakem zaslonu). */
    private val pokazanoOb = HashMap<String, Long>()

    /** Okno z vprasanjem za [naprava] je na zaslonu. */
    fun zabeleziPokazano(ctx: Context, naprava: String) {
        val cas = try { AndroidApplicationGateway.dovoljenja(ctx).vnos(naprava)?.cas } catch (_: Throwable) { null } ?: return
        synchronized(pokazanoOb) { pokazanoOb[naprava] = cas }
    }

    /**
     * Zaslon Safeer OS je prisel v ospredje. Ce kaksna naprava caka na odlocitev in je prosila pred kratkim,
     * vprasanje pokazemo zdaj. Brez tega ostane nevidno, kadar so obvestila za Safeer OS izklopljena: okna iz
     * ozadja Android ne dovoli odpreti (izmerjeno 4. 10. 2026 na Androidu 16).
     */
    fun pokaziCakajoce(zaslon: Activity) {
        if (zaslon is InternetDovoljenjeActivity || zaslon.isFinishing) return
        try {
            if (!zaslon.getSharedPreferences(AndroidApplicationGateway.PREFS, Context.MODE_PRIVATE).getBoolean("gateway_enabled", false)) return
            val vnos = AndroidApplicationGateway.dovoljenja(zaslon).svezeCakajoce()
                .firstOrNull { v -> synchronized(pokazanoOb) { pokazanoOb[v.naprava] != v.cas } } ?: return
            synchronized(pokazanoOb) { pokazanoOb[vnos.naprava] = vnos.cas }
            zaslon.startActivity(okno(zaslon, vnos.naprava, vnos.ime))
        } catch (t: Throwable) {
            Log.w(TAG, "vprasanje (odprtje): ${t.javaClass.simpleName}")
        }
    }

    fun umakni(ctx: Context, naprava: String) {
        try { ctx.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(id(naprava)) } catch (_: Throwable) { }
    }

    /** Zapise odlocitev in jo pove prehodu (ta jo sporoci napravi, ki caka). */
    fun odloci(ctx: Context, naprava: String, ime: String, dovoli: Boolean) {
        val c = ctx.applicationContext
        if (naprava.isBlank()) return
        AndroidApplicationGateway.dovoljenja(c).odloci(naprava, dovoli, ime)
        umakni(c, naprava)
        try {
            c.startService(Intent(c, si.safeer.tv.cast.CastReceiverService::class.java)
                .setAction(si.safeer.tv.cast.CastReceiverService.ACTION_GATEWAY_PERMISSION))
        } catch (_: Throwable) { }
        try {
            Toast.makeText(c, c.getString(if (dovoli) R.string.os_ig_dovoljeno else R.string.os_ig_zavrnjeno, ime.ifBlank { naprava }), Toast.LENGTH_LONG).show()
        } catch (_: Throwable) { }
    }

    /** Gumba Dovoli / Zavrni v obvestilu (brez odpiranja aplikacije). */
    class Prejemnik : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val naprava = intent.getStringExtra(KLJUC_NAPRAVA).orEmpty()
            val ime = intent.getStringExtra(KLJUC_IME).orEmpty()
            when (intent.action) {
                DEJANJE_DOVOLI -> odloci(context, naprava, ime, true)
                DEJANJE_ZAVRNI -> odloci(context, naprava, ime, false)
            }
        }
    }
}

/** Okno z vprasanjem (dotik obvestila ali Safeer OS v ospredju). Brez svojega zaslona: samo pogovorno okno. */
class InternetDovoljenjeActivity : Activity() {
    private var okno: AlertDialog? = null

    override fun onCreate(shranjeno: Bundle?) {
        super.onCreate(shranjeno)
        pokazi(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pokazi(intent)
    }

    private fun pokazi(intent: Intent?) {
        val naprava = intent?.getStringExtra(InternetVprasanje.KLJUC_NAPRAVA).orEmpty()
        val ime = intent?.getStringExtra(InternetVprasanje.KLJUC_IME).orEmpty().ifBlank { naprava }
        if (naprava.isBlank()) { finish(); return }
        // Okno je odprto: obvestilo z istim vprasanjem ni vec potrebno (odlocitev ostane »caka«, ce uporabnik okno odmakne).
        InternetVprasanje.umakni(this, naprava)
        InternetVprasanje.zabeleziPokazano(this, naprava)
        okno?.setOnDismissListener(null)
        okno?.dismiss()
        okno = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_ig_naslov))
            .setMessage(getString(R.string.os_ig_vprasanje, ime))
            .setPositiveButton(getString(R.string.os_ig_dovoli)) { _, _ -> InternetVprasanje.odloci(this, naprava, ime, true) }
            .setNegativeButton(getString(R.string.os_ig_zavrni)) { _, _ -> InternetVprasanje.odloci(this, naprava, ime, false) }
            .setOnDismissListener { finish() }
            .show()
    }

    override fun onDestroy() {
        okno?.setOnDismissListener(null)
        try { okno?.dismiss() } catch (_: Throwable) { }
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
