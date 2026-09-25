package si.safeer.tv.os

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import si.safeer.tv.R
import si.safeer.tv.cast.HubDiscovery
import si.safeer.tv.cast.HubKrmilnik
import si.safeer.tv.cast.HubPairing
import si.safeer.tv.cast.HubUsmerjevalnik

/**
 * Prijavno okno Safeer OS na televizorju (isto kot na racunalniku): poveži naprave s QR kodo ali s
 * 6-mestno kodo, ali nadaljuj brez povezave. Televizor je navadno sredisce Safeer Linka, zato se tu
 * telefon ali tablica PRIDRUZI televizorju: poskenira kodo s kamero in je povezan.
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
    private var trenutniPin = ""
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
    private lateinit var gumb: Button
    /** Kartica QR in »ALI«: skrijemo ju, kadar je sredisce Linka na drugi napravi (tam QR se ne gre). */
    private var karticaQr: View? = null
    private var aliOznaka: View? = null

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
        setPadding(dp(22f), dp(20f), dp(22f), dp(20f))
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

        koren = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#090D15")) }
        val stolpec = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        // Nizek zaslon (telefon lezece) drsi; ozek (telefon pokonci) ima kartici eno pod drugo.
        // Na TV in tablici vsebina pade v zaslon in je sredinsko poravnana kot prej.
        val ozek = resources.configuration.screenWidthDp < 720
        koren.addView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(stolpec, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val sirinaKartice = if (ozek) LinearLayout.LayoutParams.MATCH_PARENT else dp(330f)

        // Znak in naslov
        val znak = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        znak.addView(besedilo("S", 18f, Color.parseColor("#06231A"), true).apply {
            background = GradientDrawable().apply { setColor(zelena); cornerRadius = dp(10f).toFloat() }
        }, LinearLayout.LayoutParams(dp(34f), dp(34f)))
        znak.addView(besedilo("Safeer OS", 17f, bela, true).apply { setPadding(dp(10f), 0, 0, 0) })
        stolpec.addView(znak)
        stolpec.addView(besedilo(getString(R.string.os_prijava_naslov), 26f, bela, true).apply { setPadding(0, dp(12f), 0, dp(2f)) })
        stolpec.addView(besedilo(getString(R.string.os_prijava_podnaslov), 14f, medla).apply { setPadding(0, 0, 0, dp(18f)) })

        // Dve moznosti
        val vrsta = LinearLayout(this).apply {
            orientation = if (ozek) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        val levo = kartica()
        slikaQr = ImageView(this).apply {
            setBackgroundColor(Color.WHITE); setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12f).toFloat() }
        }
        levo.addView(slikaQr, LinearLayout.LayoutParams(dp(190f), dp(190f)))
        levo.addView(besedilo(getString(R.string.os_prijava_qr_naslov), 16f, bela, true).apply { setPadding(0, dp(12f), 0, dp(4f)) })
        besediloQr = besedilo(getString(R.string.os_prijava_qr_opis), 13f, medla)
        levo.addView(besediloQr)
        stanjeQr = besedilo(getString(R.string.os_prijava_qr_pripravljam), 13f, zelena).apply { setPadding(0, dp(8f), 0, 0) }
        levo.addView(stanjeQr)
        vrsta.addView(levo, LinearLayout.LayoutParams(sirinaKartice, LinearLayout.LayoutParams.WRAP_CONTENT))
        karticaQr = levo

        vrsta.addView(besedilo(getString(R.string.os_prijava_ali).uppercase(), 12f, medla).apply {
            if (ozek) setPadding(0, dp(12f), 0, dp(12f)) else setPadding(dp(18f), 0, dp(18f), 0)
            aliOznaka = this
        })

        val desno = kartica()
        desno.addView(besedilo(getString(R.string.os_prijava_koda_naslov), 16f, bela, true).apply { setPadding(0, dp(4f), 0, dp(6f)) })
        desno.addView(besedilo(getString(R.string.os_prijava_koda_opis), 13f, medla))
        kodaStevilke = besedilo(PRAZNA_KODA, 38f, bela, true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.08f
            maxLines = 1
            setPadding(0, dp(18f), 0, dp(4f))
        }
        desno.addView(kodaStevilke)
        kodaZa = besedilo("", 13f, zelena)
        desno.addView(kodaZa)
        val zePin = HubKrmilnik.aktivniPin()
        if (zePin != null && zePin.length == 6) {
            trenutniPin = zePin
            kodaStevilke.text = zePin.take(3) + " " + zePin.drop(3)
            kodaZa.text = getString(R.string.os_prijava_koda_velja)
        }

        // Fokusiran je samo gumb sam (ne se tudi kartica okoli njega): dve prekrivajoci se
        // fokusirani tarci z isto akcijo sta z daljinca zmedle iskanje fokusa (uporabnik je videl
        // gumb, a nanj ni mogel priti/klikniti). Kartica ostane le vizualni okvir.
        val gumbVpisi = Button(this).apply {
            text = getString(R.string.os_prijava_vpisi_gumb)
            isAllCaps = false
            isFocusable = true
            isFocusableInTouchMode = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(zelena)
            setPadding(dp(16f), dp(6f), dp(16f), dp(6f))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(Color.parseColor("#1B2A33")); cornerRadius = dp(10f).toFloat(); setStroke(dp(1.5f), zelena)
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(Color.parseColor("#15222E")); cornerRadius = dp(10f).toFloat(); setStroke(dp(1f), Color.parseColor("#29333D"))
                })
            }
            setOnClickListener { vnesi6MestnoKodo() }
        }
        desno.addView(gumbVpisi, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12f); gravity = Gravity.CENTER_HORIZONTAL
        })
        vrsta.addView(desno, LinearLayout.LayoutParams(sirinaKartice, LinearLayout.LayoutParams.WRAP_CONTENT))
        stolpec.addView(vrsta)

        // Spodaj: nadaljuj brez povezave (prvi zagon) ali zapri
        gumb = Button(this).apply {
            text = getString(if (prviZagon) R.string.os_prijava_brez else R.string.os_prijava_zapri)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(bela)
            setPadding(dp(22f), dp(8f), dp(22f), dp(8f))
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
            topMargin = dp(20f); gravity = Gravity.CENTER_HORIZONTAL
        })

        // Eksplicitna veriga fokusa za daljinec med gumbom »Vpiši kodo z druge naprave« in spodnjim
        // gumbom: privzeto (geometrijsko) iskanje fokusa v ScrollView z vecimi kandidati ni bilo
        // zanesljivo - uporabnik z daljincem ni mogel priti do gumba ali ga klikniti.
        // (nextFocusDown/nextFocusUp kot View-referenca v Kotlinu ne obstajata - potrebna sta ID-ja.)
        if (gumbVpisi.id == View.NO_ID) gumbVpisi.id = View.generateViewId()
        if (gumb.id == View.NO_ID) gumb.id = View.generateViewId()
        gumbVpisi.nextFocusDownId = gumb.id
        gumb.nextFocusUpId = gumbVpisi.id

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

    /** Nekdo se je povezal (s QR ali s 6-mestno kodo): pokazemo to in pripravimo novo kodo. */
    private fun pokaziPovezano(ime: String) {
        if (konec) return
        stanjeQr.text = getString(R.string.os_prijava_povezano, ime.ifBlank { "Naprava" })
        if (!Nacin.jeLink(this)) link.vklopiLink()
        qrId = ""
        trenutniPin = ""
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
            val pin = b.getString("pin")?.takeIf { it.isNotBlank() }
                ?: b.getString("code")?.takeIf { it.isNotBlank() }
                ?: HubKrmilnik.aktivniPin()
                ?: ""
            if (pin.length == 6) {
                trenutniPin = pin
                kodaStevilke.text = pin.take(3) + " " + pin.drop(3)
                kodaZa.text = getString(R.string.os_prijava_koda_velja)
            }
            val povezava = b.getString("povezava").orEmpty()
            if (b.getString("napaka") == "drugo_sredisce") {
                if (trenutniPin.length != 6) {
                    val u = HubKrmilnik.usmerjevalnik
                    val noviPin = u?.ustvariPridruzitev()?.pin
                    if (noviPin != null && noviPin.length == 6) {
                        trenutniPin = noviPin
                        kodaStevilke.text = noviPin.take(3) + " " + noviPin.drop(3)
                        kodaZa.text = getString(R.string.os_prijava_koda_velja)
                    }
                }
                karticaQr?.visibility = View.GONE
                aliOznaka?.visibility = View.GONE
                return@nova
            }
            if (povezava.isBlank()) {
                slikaQr.setImageDrawable(null)
                stanjeQr.text = getString(R.string.os_prijava_qr_napaka)
                glavna.postDelayed({ if (!konec) novaKoda() }, 5_000)
                return@nova
            }
            qrId = b.getString("qr_id").orEmpty()
            slikaQr.setImageBitmap(narisiQr(povezava, dp(178f)))
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
                    val pin = if (trenutniPin.length == 6) trenutniPin else HubKrmilnik.aktivniPin().orEmpty()
                    if (pin.length == 6) {
                        trenutniPin = pin
                        kodaStevilke.text = pin.take(3) + " " + pin.drop(3)
                        kodaZa.text = getString(R.string.os_prijava_koda_velja)
                    }
                } else {
                    kodaStevilke.text = p.pin.take(3) + " " + p.pin.drop(3)
                    kodaZa.text = getString(R.string.os_prijava_koda_za, p.ime)
                }
            }
            glavna.postDelayed(this, OSVEZI_MS)
        }
    }

    private fun vnesi6MestnoKodo() {
        val vnos = android.widget.EditText(this).apply {
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(7))
            hint = "123 456"
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(40, 30, 40, 30)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.os_naprave_vpisi_kodo))
            .setMessage(getString(R.string.os_naprave_vpisi_kodo_opis))
            .setView(vnos)
            .setPositiveButton(getString(R.string.os_host_poveziSe)) { _, _ ->
                val koda = vnos.text?.toString()?.filter { it.isDigit() }.orEmpty()
                if (koda.length != 6) {
                    Toast.makeText(this, getString(R.string.os_naprave_koda_napacna_dolzina), Toast.LENGTH_SHORT).show()
                    vnesi6MestnoKodo()
                    return@setPositiveButton
                }
                izvediPovezavoSKodo(koda)
            }
            .setNegativeButton(getString(R.string.os_preklici), null)
            .let { Kontroler.pokazi(it.show()) }
        // Na Android TV se ob fokusu z daljinca tipkovnica ne prikaže sama (za razliko od dotika na
        // telefonu/tablici) - brez tega uporabnik vidi fokusirano polje, a ne more nič vtipkati.
        vnos.requestFocus()
        vnos.post {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(vnos, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun izvediPovezavoSKodo(koda: String) {
        Toast.makeText(this, getString(R.string.os_naprave_iskanje_naprave), Toast.LENGTH_SHORT).show()
        HubDiscovery.poisciVse(this, 3500L) { hubi ->
            if (isFinishing) return@poisciVse
            // Primerjaj s HubKrmilnik.lastniId(), ne z Identiteta.id(this): ta doda "-os" priponko,
            // mDNS oglas pa nosi surov lastniId. Z narobe primerjavo se lastni hub ni nikoli izlocil,
            // zato je naprava, ki se ni imela s kom povezati, znala poskusiti seznanitev sama s sabo.
            val kandidati = hubi.filter { it.id != HubKrmilnik.lastniId() }
            if (kandidati.isEmpty()) {
                val znan = Host.naslov(this)
                if (!znan.isNullOrBlank()) {
                    poskusiPovezavoSKodo(znan, koda, "Safeer Hub")
                } else {
                    Toast.makeText(this, getString(R.string.os_naprave_naprava_ni_najdena), Toast.LENGTH_LONG).show()
                }
                return@poisciVse
            }
            poskusiPovezavoSKodo(kandidati.first().naslov, koda, kandidati.first().ime)
        }
    }

    private fun poskusiPovezavoSKodo(url: String, koda: String, imeHuba: String) {
        HubPairing.prekini()
        HubPairing.pair(this, url, Identiteta.id(this), "Safeer OS (" + android.os.Build.MODEL + ")",
            { _, _ ->
                if (isFinishing) return@pair
                HubPairing.potrdiKodo(this, koda, Identiteta.id(this)) { uspelo, napaka ->
                    if (isFinishing) return@potrdiKodo
                    val izid = HubPairing.zadnjaSeznanitev
                    if (uspelo && izid != null) {
                        Host.shrani(this, url, izid.zeton, izid.odtis, izid.hubId)
                        link.ponovnoPoveziSe()
                        pokaziPovezano(imeHuba)
                        Toast.makeText(this, getString(R.string.os_naprave_uspesno_povezano, imeHuba), Toast.LENGTH_LONG).show()
                        glavna.postDelayed({ zapri(brezPovezave = false) }, 1500)
                    } else {
                        val sporocilo = when (napaka) {
                            "napacna_koda" -> getString(R.string.os_host_napacna_koda)
                            "prevec_poskusov" -> getString(R.string.os_host_prevec_poskusov)
                            else -> getString(R.string.os_host_ni_odgovora)
                        }
                        Toast.makeText(this, sporocilo, Toast.LENGTH_LONG).show()
                    }
                }
            },
            { uspelo ->
                if (!uspelo && !isFinishing) {
                    Toast.makeText(this, getString(R.string.os_host_ni_odgovora), Toast.LENGTH_LONG).show()
                }
            })
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
            PridruzitevKoda.konec(this, qrId, false)
        }
        super.onDestroy()
    }
}
