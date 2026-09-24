package si.safeer.tv.os

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import si.safeer.tv.R
import si.safeer.tv.cast.HubDiscovery
import si.safeer.tv.cast.HubPairing

/**
 * Prijavno okno Safeer OS na televizorju (isto kot na racunalniku): poveži naprave s QR kodo, s
 * 6-mestno kodo tega televizorja, ali tako, da tukaj vpises kodo, ki jo kaze druga naprava --
 * televizor je lahko gostitelj ali odjemalec, izbira je uporabnikova (Safeer Link je decentraliziran).
 *
 * Ob prvem zagonu nadomesti vprasanje »Link / krajevno«; pozneje ga odpre »Poveži novo napravo« v
 * Napravah. »Nadaljuj brez povezave naprav« ni dokoncno: naprave se lahko povezejo kadarkoli.
 */
class PrijavaActivity : OsActivity(), LinkOdjemalec.Poslusalec {

    companion object {
        const val EXTRA_PRVI_ZAGON = "prvi_zagon"
        private const val OSVEZI_MS = 1_500L
        private const val PRAZNA_KODA = "··· ···"
    }

    private val glavna = Handler(Looper.getMainLooper())
    private val link by lazy { LinkUpravitelj.pridobi(this) }
    private var prviZagon = false
    private var qrId = ""
    private var odprtoOb = 0L
    private var zadnjaVidena = 0L
    private var konec = false
    /** Naprave v Linku, ko se je okno odprlo; nova med njimi = nekdo se je pravkar povezal (QR ali koda). */
    private var znane: Set<String>? = null

    private lateinit var koren: FrameLayout
    private lateinit var slikaQr: ImageView
    private lateinit var besediloQr: TextView
    private lateinit var stanjeQr: TextView
    private lateinit var kodaStevilke: TextView
    private lateinit var kodaZa: TextView
    private lateinit var vpisVnos: EditText
    private lateinit var vpisSporocilo: TextView
    private lateinit var vpisGumb: Button
    private lateinit var gumb: Button

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun besedilo(niz: String, velikost: Float, barva: Int, krepko: Boolean = false): TextView =
        TextView(this).apply {
            text = niz
            setTextSize(TypedValue.COMPLEX_UNIT_SP, velikost)
            setTextColor(barva)
            gravity = Gravity.CENTER
            if (krepko) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun kartica(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#111924")); cornerRadius = dp(18f).toFloat()
            setStroke(dp(1f), Color.parseColor("#29333D"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prviZagon = intent?.getBooleanExtra(EXTRA_PRVI_ZAGON, false) == true
        val bela = Color.parseColor("#F0F4F3")
        val medla = Color.parseColor("#B0BDC4")
        val zelena = Color.parseColor("#54D6A5")
        val rdeca = Color.parseColor("#E5766B")
        val sirinaKartice = dp(300f)

        koren = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#090D15")) }
        // ScrollView kot varovalka: ce vsebina (na katerem koli TV-ju, gostoti ali locljivosti) ne
        // gre v visino zaslona, se s puscicami navigira do nje namesto da bi bila obrezana.
        val drsnik = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val stolpec = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(14f), 0, dp(14f))
        }
        drsnik.addView(stolpec, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        koren.addView(drsnik, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Znak in naslov
        val znak = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        znak.addView(besedilo("S", 16f, Color.parseColor("#06231A"), true).apply {
            background = GradientDrawable().apply { setColor(zelena); cornerRadius = dp(9f).toFloat() }
        }, LinearLayout.LayoutParams(dp(28f), dp(28f)))
        znak.addView(besedilo("Safeer OS", 15f, bela, true).apply { setPadding(dp(8f), 0, 0, 0) })
        stolpec.addView(znak)
        stolpec.addView(besedilo(getString(R.string.os_prijava_naslov), 21f, bela, true).apply { setPadding(0, dp(8f), 0, dp(2f)) })
        stolpec.addView(besedilo(getString(R.string.os_prijava_podnaslov), 12.5f, medla).apply { setPadding(0, 0, 0, dp(10f)) })

        // Dve moznosti: skeniraj QR, ali koda tega televizorja -- pod njo, v isti kartici, se lahko
        // namesto tega vpise kodo, ki jo kaze druga naprava (televizor je lahko gostitelj ali odjemalec).
        val vrsta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }

        // 1) Skeniraj s telefonom (QR)
        val levo = kartica()
        slikaQr = ImageView(this).apply {
            setBackgroundColor(Color.WHITE); setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12f).toFloat() }
        }
        levo.addView(slikaQr, LinearLayout.LayoutParams(dp(140f), dp(140f)))
        levo.addView(besedilo(getString(R.string.os_prijava_qr_naslov), 15f, bela, true).apply { setPadding(0, dp(10f), 0, dp(3f)) })
        besediloQr = besedilo(getString(R.string.os_prijava_qr_opis), 11.5f, medla)
        levo.addView(besediloQr)
        stanjeQr = besedilo(getString(R.string.os_prijava_qr_pripravljam), 11.5f, zelena).apply { setPadding(0, dp(6f), 0, 0) }
        levo.addView(stanjeQr)
        vrsta.addView(levo, LinearLayout.LayoutParams(sirinaKartice, LinearLayout.LayoutParams.WRAP_CONTENT))

        vrsta.addView(besedilo(getString(R.string.os_prijava_ali).uppercase(), 12f, medla).apply { setPadding(dp(14f), 0, dp(14f), 0) })

        // 2) Koda tega televizorja (gostitelj) in pod njo vpis kode druge naprave (odjemalec) --
        // obe smeri seznanitve v isti kartici, izbira je uporabnikova.
        val desno = kartica()
        desno.addView(besedilo(getString(R.string.os_prijava_koda_naslov), 15f, bela, true).apply { setPadding(0, dp(2f), 0, dp(4f)) })
        desno.addView(besedilo(getString(R.string.os_prijava_koda_opis), 11.5f, medla))
        kodaStevilke = besedilo(PRAZNA_KODA, 30f, bela, true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.06f
            maxLines = 1
            setPadding(0, dp(10f), 0, dp(2f))
        }
        desno.addView(kodaStevilke)
        kodaZa = besedilo("", 11.5f, zelena)
        desno.addView(kodaZa)

        // Locilo znotraj kartice: nad njim prikaz kode (gostitelj), pod njim vpis kode druge naprave (odjemalec).
        val locilo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        locilo.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#29333D"))
        }, LinearLayout.LayoutParams(dp(40f), dp(1f)))
        locilo.addView(besedilo(getString(R.string.os_prijava_ali).uppercase(), 10.5f, medla).apply {
            setPadding(dp(8f), 0, dp(8f), 0)
        })
        locilo.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#29333D"))
        }, LinearLayout.LayoutParams(dp(40f), dp(1f)))
        desno.addView(locilo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10f); bottomMargin = dp(2f)
        })

        desno.addView(besedilo(getString(R.string.os_prijava_vpis_naslov), 13f, bela, true))
        vpisVnos = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            hint = getString(R.string.os_prijava_vpis_namig)
            setHintTextColor(Color.parseColor("#5A6771"))
            setTextColor(bela)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            // Vidna oznaka fokusa: na TV-ju (samo puscice, brez miske/dotika) mora biti vedno jasno,
            // ali je fokus na tem polju -- brez tega uporabnik ne vidi, kam bo pristala vtipkana koda.
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(Color.parseColor("#0D141C")); cornerRadius = dp(10f).toFloat(); setStroke(dp(2f), zelena)
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(Color.parseColor("#0D141C")); cornerRadius = dp(10f).toFloat(); setStroke(dp(1f), Color.parseColor("#33404B"))
                })
            }
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
        }
        desno.addView(vpisVnos, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
        })
        vpisSporocilo = besedilo("", 11f, medla).apply { setPadding(0, dp(4f), 0, 0) }
        desno.addView(vpisSporocilo)
        vpisGumb = Button(this).apply {
            text = getString(R.string.os_prijava_vpis_gumb)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(bela)
            setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(Color.parseColor("#1B2A33")); cornerRadius = dp(10f).toFloat(); setStroke(dp(2f), zelena)
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); cornerRadius = dp(10f).toFloat(); setStroke(dp(1f), Color.parseColor("#29333D"))
                })
            }
            setOnClickListener {
                val koda = vpisVnos.text?.toString()?.filter { it.isDigit() }.orEmpty()
                if (koda.length != 6) {
                    vpisSporocilo.setTextColor(rdeca)
                    vpisSporocilo.text = getString(R.string.os_naprave_koda_napacna_dolzina)
                    return@setOnClickListener
                }
                vpisPoveziSe(koda, rdeca, medla)
            }
        }
        desno.addView(vpisGumb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
        })
        vrsta.addView(desno, LinearLayout.LayoutParams(sirinaKartice, LinearLayout.LayoutParams.WRAP_CONTENT))

        stolpec.addView(vrsta)

        // Spodaj: nadaljuj brez povezave (prvi zagon) ali zapri
        gumb = Button(this).apply {
            text = getString(if (prviZagon) R.string.os_prijava_brez else R.string.os_prijava_zapri)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(bela)
            setPadding(dp(20f), dp(7f), dp(20f), dp(7f))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(Color.parseColor("#1B2A33")); cornerRadius = dp(12f).toFloat(); setStroke(dp(2f), zelena)
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); cornerRadius = dp(12f).toFloat(); setStroke(dp(1f), Color.parseColor("#29333D"))
                })
            }
            setOnClickListener { zapri(brezPovezave = prviZagon) }
        }
        stolpec.addView(gumb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14f); gravity = Gravity.CENTER_HORIZONTAL
        })

        setContentView(koren)
        gumb.requestFocus()
        odprtoOb = System.currentTimeMillis()
        // Koda potrebuje sredisce: uporabnik je okno odprl, da poveze naprave.
        if (!link.povezan && !Nacin.jeLink(this)) link.vklopiLink()
        novaKoda()
        glavna.post(osvezevanje)
    }

    override fun onStart() {
        super.onStart()
        Ozadje.uporabi(this, koren)
        link.dodaj(this)
        if (znane == null && link.povezan && link.naprave.isNotEmpty()) znane = link.naprave.map { it.id }.toSet()
    }

    override fun onStop() {
        link.odstrani(this)
        super.onStop()
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        val ids = naprave.map { it.id }.toSet()
        val prej = znane
        if (prej == null) { if (link.povezan) znane = ids; return }
        val nova = naprave.firstOrNull { it.id !in prej }
        znane = prej + ids
        if (nova != null) pokaziPovezano(DatotekeActivity.lepoIme(nova.ime).ifBlank { nova.ime.ifBlank { nova.id } })
    }

    override fun naStanje(povezan: Boolean, sporocilo: String) {}
    override fun naNaslov(url: String, naslov: String, od: String) {}
    override fun naBesedilo(besedilo: String, od: String) {}
    override fun naZavrnitev() {}

    /** Nekdo se je povezal (s QR, s kodo tega TV ali tako, da smo mi vpisali njegovo): pokazemo to. */
    private fun pokaziPovezano(ime: String) {
        if (konec) return
        stanjeQr.text = getString(R.string.os_prijava_povezano, ime.ifBlank { "Naprava" })
        if (!Nacin.jeLink(this)) link.vklopiLink()
        qrId = ""
        // Potrdilo ostane vidno nekaj sekund, medtem ko se pripravi nova koda za naslednjo napravo.
        potrdiloDo = System.currentTimeMillis() + 6_000
        if (prviZagon) glavna.postDelayed({ zapri(brezPovezave = false) }, 2_500) else novaKoda()
    }

    /** Do kdaj ostane na zaslonu »✓ … je povezan« (nova koda ga ne sme takoj prepisati). */
    private var potrdiloDo = 0L

    private fun novaKoda() {
        if (System.currentTimeMillis() > potrdiloDo) stanjeQr.text = getString(R.string.os_prijava_qr_pripravljam)
        PridruzitevKoda.nova(this, qrId) { b ->
            if (konec) { PridruzitevKoda.konec(this, b.getString("qr_id").orEmpty(), false); return@nova }
            val povezava = b.getString("povezava").orEmpty()
            if (povezava.isBlank()) {
                slikaQr.setImageDrawable(null)
                stanjeQr.text = getString(R.string.os_prijava_qr_napaka)
                glavna.postDelayed({ if (!konec) novaKoda() }, 5_000)
                return@nova
            }
            qrId = b.getString("qr_id").orEmpty()
            slikaQr.setImageBitmap(narisiQr(povezava, dp(128f)))
            val ostane = potrdiloDo - System.currentTimeMillis()
            if (ostane <= 0) stanjeQr.text = ""
            else glavna.postDelayed({
                if (!konec && System.currentTimeMillis() >= potrdiloDo) stanjeQr.text = ""
            }, ostane)
            // Nova koda, preden stara potece: uporabnik nikoli ne skenira mrtve kode.
            val velja = b.getLong("velja_ms", 300_000L)
            glavna.removeCallbacks(obnovi)
            glavna.postDelayed(obnovi, (velja - 30_000L).coerceAtLeast(30_000L))
        }
    }

    private val obnovi = Runnable { if (!konec) novaKoda() }

    /** Vsakih 1,5 s: ali se je kdo pridruzil s QR in ali caka koda za seznanitev s 6 stevilkami. */
    private val osvezevanje = object : Runnable {
        override fun run() {
            if (konec) return
            PridruzitevKoda.zadnja(this@PrijavaActivity) { z ->
                if (konec || z == null || z.first <= odprtoOb || z.first == zadnjaVidena) return@zadnja
                zadnjaVidena = z.first
                android.util.Log.i("SafeerOsPrijava", "Pridruzitev s QR: ${z.second}")
                pokaziPovezano(z.second)
            }
            KodaSeznanitve.poglej(this@PrijavaActivity) { prijave ->
                if (konec) return@poglej
                val p = prijave.firstOrNull()
                if (p == null) {
                    kodaStevilke.text = PRAZNA_KODA; kodaZa.text = ""
                } else {
                    kodaStevilke.text = p.pin.take(3) + " " + p.pin.drop(3)
                    kodaZa.text = getString(R.string.os_prijava_koda_za, p.ime)
                }
            }
            glavna.postDelayed(this, OSVEZI_MS)
        }
    }

    /**
     * Televizor kot odjemalec: uporabnik je ze vtipkal kodo, ki jo kaze druga naprava. Najprej
     * poiscemo tistega gostitelja po mDNS, nato zacnemo seznanitev in takoj, ko je gostitelj
     * pripravljen (koda ze tece), potrdimo z ze vtipkano kodo -- brez drugega okna.
     */
    private fun vpisPoveziSe(koda: String, rdeca: Int, medla: Int) {
        HubPairing.prekini()
        vpisGumb.isEnabled = false
        vpisSporocilo.setTextColor(medla)
        vpisSporocilo.text = getString(R.string.os_prijava_vpis_iskanje)
        HubDiscovery.poisciVse(this, 3500L) { hubi ->
            if (konec) return@poisciVse
            val kandidati = hubi.filter { it.id != Identiteta.id(this) }
            if (kandidati.isEmpty()) {
                vpisSporocilo.setTextColor(rdeca)
                vpisSporocilo.text = getString(R.string.os_prijava_vpis_ni_najdena)
                vpisGumb.isEnabled = true
                return@poisciVse
            }
            // Ce jih je vec, izberemo tistega z najvisjo prioriteto (glej IzvolitevHuba), ne kar
            // prvega -- sicer bi uporabnik lahko pomotoma poskusil seznaniti z napacno napravo.
            val najboljsi = kandidati.maxByOrNull { it.prioriteta } ?: kandidati.first()
            HubPairing.pair(this, najboljsi.naslov, Identiteta.id(this), "Safeer OS (" + android.os.Build.MODEL + ")",
                { _, _ ->
                    if (konec) return@pair
                    HubPairing.potrdiKodo(this, koda, Identiteta.id(this)) { uspelo, napaka ->
                        if (konec) return@potrdiKodo
                        val izid = HubPairing.zadnjaSeznanitev
                        if (uspelo && izid != null) {
                            Host.shrani(this, najboljsi.naslov, izid.zeton, izid.odtis, izid.hubId)
                            link.ponovnoPoveziSe()
                            vpisVnos.setText("")
                            vpisSporocilo.setTextColor(medla)
                            vpisSporocilo.text = ""
                            vpisGumb.isEnabled = true
                            pokaziPovezano(najboljsi.ime)
                        } else {
                            vpisSporocilo.setTextColor(rdeca)
                            vpisSporocilo.text = when (napaka) {
                                "napacna_koda" -> getString(R.string.os_host_napacna_koda)
                                "prevec_poskusov" -> getString(R.string.os_host_prevec_poskusov)
                                else -> getString(R.string.os_host_ni_odgovora)
                            }
                            vpisGumb.isEnabled = true
                        }
                    }
                },
                { uspelo ->
                    if (!uspelo && !konec) {
                        vpisSporocilo.setTextColor(rdeca)
                        vpisSporocilo.text = getString(R.string.os_host_ni_odgovora)
                        vpisGumb.isEnabled = true
                    }
                })
        }
    }

    private fun narisiQr(besedilo: String, velikost: Int): Bitmap {
        val matrika = QRCodeWriter().encode(besedilo, BarcodeFormat.QR_CODE, velikost, velikost,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val w = matrika.width
        val h = matrika.height
        val tocke = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) tocke[y * w + x] = if (matrika.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(tocke, w, h, Bitmap.Config.RGB_565)
    }

    private fun zapri(brezPovezave: Boolean) {
        if (konec) return
        konec = true
        glavna.removeCallbacksAndMessages(null)
        HubPairing.prekini()
        // Ce se je medtem kdo pridruzil, »brez povezave« ne velja vec: naprava je ze povezana.
        val brez = brezPovezave && zadnjaVidena == 0L
        if (brez) link.krajevniNacin()
        PridruzitevKoda.konec(this, qrId, brez)
        finish()
    }

    override fun onDestroy() {
        if (!konec) {
            konec = true
            glavna.removeCallbacksAndMessages(null)
            HubPairing.prekini()
            PridruzitevKoda.konec(this, qrId, false)
        }
        super.onDestroy()
    }
}
