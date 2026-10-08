package si.safeer.tv.os

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import si.safeer.tv.R

/**
 * Izbira vira slike (HDMI, SCART, TV ...). Namero com.android.tv.action.VIEW_INPUTS, ki jo ob
 * pritisku na tipko za vire poslje Philipsov sourceenabler, sicer zna odpreti samo Googlov
 * zaganjalnik; brez njega tipka ne naredi nicesar. Seznam vhodov dobimo od sistema (TvInputManager),
 * vir pa odpremo z navadno namero VIEW na passthrough naslov - to isto naredi Googlov zaganjalnik.
 */
class ViriActivity : android.app.Activity() {

    companion object {
        private const val ALIAS = "si.safeer.tv.os.ViriAlias"
        private const val GOOGLOV_ZAGANJALNIK = "com.google.android.tvlauncher"

        /** Ali Googlov zaganjalnik (ki sam odpre izbiro virov) obstaja in je omogocen. */
        fun googlovZaganjalnikDeluje(context: android.content.Context): Boolean = try {
            context.packageManager.getApplicationInfo(GOOGLOV_ZAGANJALNIK, 0).enabled
        } catch (_: Throwable) { false }

        /**
         * Safeer ponudi izbiro virov samo, kadar je Googlov zaganjalnik izklopljen; sicer bi se ob
         * tipki za vire odprl izbirnik z dvema programoma. Klicati ob vsakem odprtju domaceg zaslona.
         */
        fun uskladi(context: android.content.Context) {
            try {
                val zelen = !googlovZaganjalnikDeluje(context)
                val stanje = if (zelen) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                val c = android.content.ComponentName(context.packageName, ALIAS)
                if (context.packageManager.getComponentEnabledSetting(c) != stanje)
                    context.packageManager.setComponentEnabledSetting(c, stanje, android.content.pm.PackageManager.DONT_KILL_APP)
            } catch (e: Throwable) {
                android.util.Log.w("SafeerViri", "Usklajevanje ni uspelo: ${e.message}")
            }
        }
    }

    /** Jezik in gostota kot pri ostalih zaslonih Safeer OS (glej [OsActivity]). */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Naprava.zaslonTelevizorja(si.safeer.tv.JezikVmesnika.vKontekstu(newBase)))
    }

    /** Barve in kartice iz teme, ki jo je uporabnik izbral (okno samo ostane prosojno). */
    private val tema by lazy { android.view.ContextThemeWrapper(this, Tema.izbrana(this).stil) }
    private fun barva(id: Int) = tema.osBarva(id)

    /** Imena, ki jih je uporabnik sam dal vhodom (kljuc = id vhoda). Sistemskih imen ne spreminjamo. */
    private val imena by lazy { getSharedPreferences("safeer_os_viri", MODE_PRIVATE) }

    private data class Vhod(val info: TvInputInfo, val ime: String, val vrata: String?, val prikljucen: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        narisi(null)
    }

    private fun narisi(izbran: String?) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        // Plosca na desni, cez to, kar je bilo na zaslonu (kot sistemska izbira virov).
        val plosca = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(36), dp(28), dp(28))
            setBackgroundColor(barva(R.color.os_meni_ozadje))
        }
        plosca.addView(TextView(this).apply {
            text = getString(R.string.os_viri_naslov)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(barva(R.color.os_besedilo))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, dp(20))
        })

        val seznam = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val vhodi = vhodi()
        var fokus: View? = null
        if (vhodi.isEmpty()) {
            seznam.addView(TextView(this).apply {
                text = getString(R.string.os_viri_prazno)
                setTextColor(barva(R.color.os_umirjeno))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
        }
        for (v in vhodi) {
            val vrstica = vrstica(v, ::dp)
            seznam.addView(vrstica, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
            if (fokus == null && v.prikljucen) fokus = vrstica
        }
        if (izbran != null) fokus = seznam.findViewWithTag(izbran) ?: fokus
        if (fokus == null) fokus = seznam.getChildAt(0)?.takeIf { it.isFocusable }
        plosca.addView(ScrollView(this).apply { addView(seznam); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        if (vhodi.isNotEmpty()) plosca.addView(TextView(this).apply {
            text = getString(R.string.os_viri_namig)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(barva(R.color.os_umirjeno))
            setPadding(dp(8), dp(12), 0, 0)
        })

        val koren = android.widget.FrameLayout(this)
        // Dotik ali klik zunaj plosce zapre izbiro, kot pri sistemski.
        koren.setOnClickListener { finish() }
        plosca.isClickable = true
        koren.addView(plosca, android.widget.FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.36f).toInt().coerceAtLeast(dp(360)),
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
        setContentView(koren)
        fokus?.requestFocus()
    }

    private fun vrstica(v: Vhod, dp: (Int) -> Int): View {
        val vrstica = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = tema.getDrawable(R.drawable.os_kartica_steklo)
            isFocusable = true
            isClickable = true
            setOnClickListener { odpri(v.info) }
            isLongClickable = true
            setOnLongClickListener { preimenuj(v); true }
            alpha = if (v.prikljucen) 1f else 0.72f
            tag = v.info.id
        }
        val ikona = android.widget.ImageView(this).apply {
            setImageResource(if (v.info.type == TvInputInfo.TYPE_TUNER) R.drawable.os_ikona_tv else R.drawable.os_ikona_zaslon)
            setColorFilter(if (v.prikljucen) barva(R.color.os_mint) else barva(R.color.os_umirjeno))
        }
        vrstica.addView(ikona, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(16) })
        val besedila = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        besedila.addView(TextView(this).apply {
            text = v.ime
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(barva(R.color.os_besedilo))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val pod = listOfNotNull(v.vrata, if (v.prikljucen) getString(R.string.os_viri_prikljuceno) else null)
        if (pod.isNotEmpty()) besedila.addView(TextView(this).apply {
            text = pod.joinToString(" · ")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (v.prikljucen) barva(R.color.os_mint) else barva(R.color.os_umirjeno))
        })
        vrstica.addView(besedila, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return vrstica
    }

    /**
     * Vhodi, kot jih pozna sistem. Ime je uporabnikovo (npr. »Racunalnik«), ce ga je dal; vrata (»HDMI 4«)
     * pa vedno pokazemo zraven, da je jasno, kateri prikljucek je - uporabnikovo ime ostane tudi, ko
     * naprave ni vec. Prikljuceni so na vrhu, nato po vrsti vrat.
     */
    private fun vhodi(): List<Vhod> = try {
        val u = getSystemService(TV_INPUT_SERVICE) as TvInputManager
        u.tvInputList.filter { it.isPassthroughInput }.map { info ->
            val vrata = try { info.loadLabel(this)?.toString()?.trim() } catch (_: Throwable) { null }
                ?.takeIf { it.isNotEmpty() }
            val svoje = try { info.loadCustomLabel(this)?.toString()?.trim() } catch (_: Throwable) { null }
                ?.takeIf { it.isNotEmpty() }
            val stanje = try { u.getInputState(info.id) } catch (_: Throwable) { TvInputManager.INPUT_STATE_DISCONNECTED }
            android.util.Log.i("SafeerViri", "${info.id} vrata=$vrata svoje=$svoje stanje=$stanje")
            val moje = imena.getString(info.id, null)?.trim()?.takeIf { it.isNotEmpty() }
            val ime = moje ?: svoje ?: vrata ?: info.id.substringAfterLast('/')
            Vhod(info, ime, vrata?.takeIf { it != ime }, stanje == TvInputManager.INPUT_STATE_CONNECTED)
        }.sortedWith(compareBy<Vhod>({ !it.prikljucen }, { it.info.type != TvInputInfo.TYPE_HDMI },
            { it.vrata ?: it.ime }))
    } catch (_: Throwable) { emptyList() }

    /** Uporabnik da vhodu svoje ime; prazno ali »Privzeto« vrne sistemsko. */
    private fun preimenuj(v: Vhod) {
        val polje = android.widget.EditText(this).apply {
            setText(imena.getString(v.info.id, "") ?: "")
            hint = v.ime
            setSingleLine()
            selectAll()
        }
        val okvir = android.widget.FrameLayout(this).apply {
            val r = (24 * resources.displayMetrics.density).toInt()
            setPadding(r, r / 2, r, 0)
            addView(polje)
        }
        fun shrani(novo: String?) {
            val e = imena.edit()
            if (novo.isNullOrBlank()) e.remove(v.info.id) else e.putString(v.info.id, novo.trim().take(40))
            e.apply()
            narisi(v.info.id)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_viri_preimenuj) + (v.vrata?.let { " ($it)" } ?: ""))
            .setView(okvir)
            .setPositiveButton(R.string.os_viri_shrani) { _, _ -> shrani(polje.text?.toString()) }
            .setNeutralButton(R.string.os_viri_privzeto) { _, _ -> shrani(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        polje.requestFocus()
    }

    private fun odpri(v: TvInputInfo) {
        try {
            val uri = TvContract.buildChannelUriForPassthroughInput(v.id)
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        } catch (e: Throwable) {
            android.util.Log.w("SafeerViri", "Vira ${v.id} ni mogoce odpreti: ${e.message}")
            Toast.makeText(this, getString(R.string.os_viri_napaka), Toast.LENGTH_LONG).show()
        }
    }
}
