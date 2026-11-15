package si.safeer.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import kotlin.math.max
import kotlin.math.min

class VirtualPointerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var pointerX: Float = 0f
    var pointerY: Float = 0f
    var isPointerVisible: Boolean = false
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            // Skrit kazalec se ne giblje naprej (ob preklopu na puscice ali menjavi strani med drzanjem tipke).
            if (!value) gibanje = KazalecGibanje.ustavi(gibanje)
            invalidate()
        }

    /**
     * Vlecenje z daljincem (lastnik 30. 10. 2026: varnostne preverbe »povleci obliko«, drsniki).
     * Dolg pritisk OK zgrabi, puscice vlecejo, naslednji OK spusti. Kratek OK je klik ob spustu tipke.
     */
    var vlecem: Boolean = false
        private set
    var cakaKlik: Boolean = false
    /** OK je ze naredil svoje (zgrabil ali spustil): ponovitve drzane tipke do spusta nic ne naredijo. */
    var okPorabljen: Boolean = false
    private var vlecenjeOd: Long = 0L
    /** Pogled, v katerem se je vlecenje zacelo - spust gre vanj (tudi ce se je medtem zamenjal zavihek). */
    private var vlecenjePogled: java.lang.ref.WeakReference<WebView>? = null

    // Puscica (lastnik 15. 11. 2026): ena predpripravljena pot, konica v (0, 0) = tocka klika ([KazalecOblika]); bela polnitev in temen
    // obris brez sence in zameglitve (nobenega programskega sloja: senca je zahtevala LAYER_TYPE_SOFTWARE cez ves zaslon).
    private val puscicaPot = Path().apply {
        val t = KazalecOblika.tocke()
        moveTo(t[0], t[1])
        var i = 2
        while (i < t.size) { lineTo(t[i], t[i + 1]); i += 2 }
        close()
    }
    private val polnitevPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val obrisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }

    /** Konec kratkega povratnega signala ob kliku (uptime ms); do takrat je puscica manjsa in rumena. */
    private var klikDoMs: Long = 0L

    // Gibanje po slicicah ([KazalecGibanje]): prvi pritisk = natancen korak, drzanje = pospesek, drsenje samo s potiskom ob rob.
    private var gibanje = KazalecGibanje.Stanje(0f, 0f)
    /** Hitrost kazalca (stopnje 0..2 v [KazalecGibanje.hitrost]); privzeta stopnja je izmerjeni Hi Browser (3300 px/s). */
    var gibNastavitve: KazalecGibanje.Nastavitve = KazalecGibanje.hitrost(1)
    private var gibPogled: java.lang.ref.WeakReference<WebView>? = null
    private var tikNacrtan = false
    private val tikTeka = Runnable { tikSlicice() }
    private var zadnjeLebdenjeMs = 0L
    private var drsenjeOstankiX = 0f
    private var drsenjeOstankiY = 0f

    init {
        // Brez programskega sloja (prej LAYER_TYPE_SOFTWARE zaradi sence): navaden strojni izris ene poti.
        visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pointerX == 0f && pointerY == 0f) {
            pointerX = w / 2f
            pointerY = h / 2f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isPointerVisible) return
        val stanje = KazalecOblika.stanje(vlecem, klikDoMs, SystemClock.uptimeMillis())
        val izgled = KazalecOblika.izgled(stanje)
        val obrat = KazalecOblika.obrat(pointerX, pointerY, width, height, izgled.merilo)
        val (ox, oy) = KazalecOblika.slikaIzTocke(pointerX, pointerY, KazalecOblika.PRIVZETA)
        polnitevPaint.color = izgled.polnitev
        obrisPaint.color = izgled.obris
        canvas.save()
        canvas.translate(ox, oy)
        // Ob desnem in spodnjem robu se telo zrcali (konica ostane na mestu klika, telo ostane vidno).
        canvas.scale(if (obrat.vodoravno) -izgled.merilo else izgled.merilo, if (obrat.navpicno) -izgled.merilo else izgled.merilo)
        canvas.drawPath(puscicaPot, polnitevPaint)
        canvas.drawPath(puscicaPot, obrisPaint)
        canvas.restore()
    }

    /**
     * Smerna tipka dol (ACTION_DOWN): [smer] iz [KazalecGibanje.smerIzTipke], [ponovitev] = event.repeatCount > 0, [casMs] =
     * event.eventTime. Kratek pritisk je natancen korak takoj; zanko po slicicah vodi [tikSlicice], dokler je tipka drzana.
     */
    fun smerDol(smer: Int, ponovitev: Boolean, casMs: Long, wv: WebView?) {
        if (smer == 0 || width <= 0 || height <= 0) return
        gibPogled = java.lang.ref.WeakReference(wv)
        // Lega se lahko spremeni od zunaj (prva postavitev, vlecenje): modul dobi trenutno.
        gibanje = gibanje.copy(x = pointerX, y = pointerY)
        uporabiIzid(KazalecGibanje.pritisk(gibanje, smer, ponovitev, casMs, width, height, gibNastavitve), wv)
    }

    /** Smerna tipka gor (ACTION_UP): gibanje v tej smeri se ustavi v eni slicici. */
    fun smerGor(smer: Int, casMs: Long, wv: WebView?) {
        if (smer == 0 || width <= 0 || height <= 0) return
        gibanje = gibanje.copy(x = pointerX, y = pointerY)
        uporabiIzid(KazalecGibanje.spust(gibanje, smer, casMs, width, height, gibNastavitve), wv)
    }

    private fun uporabiIzid(izid: KazalecGibanje.Izid, wv: WebView?) {
        gibanje = izid.stanje
        pointerX = gibanje.x
        pointerY = gibanje.y
        if (wv != null) {
            if (vlecem) {
                // Med vlecenjem stran ne drsi (premik bi se izgubil), dobi le premik prsta.
                dotik(wv, MotionEvent.ACTION_MOVE)
            } else {
                drsi(wv, izid.drsiX, izid.drsiY)
                // Lebdenje (meniji, namigi kot z miško) najvec vsakih ~100 ms in enkrat ob ustavitvi.
                val zdaj = SystemClock.uptimeMillis()
                if (KazalecPravila.lebdenje() && (!izid.tece || zdaj - zadnjeLebdenjeMs >= KazalecPravila.LEBDENJE_MS)) {
                    zadnjeLebdenjeMs = zdaj
                    posljiLebdenje(wv)
                }
            }
        }
        invalidate()
        if (izid.tece && !tikNacrtan) { tikNacrtan = true; postOnAnimation(tikTeka) }
    }

    /** Ena slicica gibanja: samo dokler je smerna tipka drzana; v mirovanju ni zanke in ni casovnika. */
    private fun tikSlicice() {
        tikNacrtan = false
        if (!isPointerVisible || gibanje.smeri == 0 || width <= 0 || height <= 0) return
        uporabiIzid(KazalecGibanje.tik(gibanje, SystemClock.uptimeMillis(), width, height, gibNastavitve), gibPogled?.get())
    }

    /**
     * Drsenje pod kazalcem: kolesce miske na mestu kazalca (drsi najbolj notranji drsni element, kot z miško). [dx], [dy] v
     * tockah zaslona (pozitivno = desno / navzdol) iz [KazalecGibanje]; v zareze jih pretvori sistemski faktor drsenja
     * (ViewConfiguration), delne zareze se sestevajo. Ce pogled kolesca ne sprejme, drsi pogled sam (kot prej).
     */
    private fun drsi(wv: WebView, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        drsenjeOstankiX += dx
        drsenjeOstankiY += dy
        val vk = android.view.ViewConfiguration.get(context)
        val fx = vk.scaledHorizontalScrollFactor.takeIf { it > 0f } ?: KazalecPravila.PX_NA_ZAREZO
        val fy = vk.scaledVerticalScrollFactor.takeIf { it > 0f } ?: KazalecPravila.PX_NA_ZAREZO
        val zarezX = (drsenjeOstankiX / fx).toInt()
        val zarezY = (drsenjeOstankiY / fy).toInt()
        if (zarezX == 0 && zarezY == 0) return
        drsenjeOstankiX -= zarezX * fx
        drsenjeOstankiY -= zarezY * fy
        val sprejeto = try {
            val (x, y) = naPogledu(wv)
            val cas = SystemClock.uptimeMillis()
            val lastnosti = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
            val koordinate = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y
                // Android: pozitivna os VSCROLL drsi navzgor (vsebina gre dol); HSCROLL pozitivno = desno (NEPREVERJENO na TV).
                setAxisValue(MotionEvent.AXIS_VSCROLL, -zarezY.toFloat())
                setAxisValue(MotionEvent.AXIS_HSCROLL, zarezX.toFloat())
            })
            val dogodek = MotionEvent.obtain(cas, cas, MotionEvent.ACTION_SCROLL, 1, lastnosti, koordinate,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            val ok = wv.dispatchGenericMotionEvent(dogodek)
            dogodek.recycle()
            ok
        } catch (_: Throwable) { false }
        if (!sprejeto) wv.scrollBy((zarezX * fx).toInt(), (zarezY * fy).toInt())
    }

    /** Polozaj kazalca v koordinatah spletnega pogleda. */
    private fun naPogledu(wv: WebView): Pair<Float, Float> {
        val pogled = IntArray(2)
        val jaz = IntArray(2)
        wv.getLocationOnScreen(pogled)
        getLocationOnScreen(jaz)
        return Pair(pointerX + jaz[0] - pogled[0], pointerY + jaz[1] - pogled[1])
    }

    /** Premik miske (lebdenje): stran dobi mouseover/mousemove kot pri pravi miski. */
    private fun posljiLebdenje(wv: WebView) {
        try {
            val (x, y) = naPogledu(wv)
            val cas = SystemClock.uptimeMillis()
            val lastnosti = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
            val koordinate = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
            val dogodek = MotionEvent.obtain(cas, cas, MotionEvent.ACTION_HOVER_MOVE, 1, lastnosti, koordinate,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            wv.dispatchGenericMotionEvent(dogodek)
            dogodek.recycle()
        } catch (_: Throwable) { }
    }

    private fun dotik(wv: WebView, dejanje: Int) {
        try {
            val (x, y) = naPogledu(wv)
            val dogodek = MotionEvent.obtain(vlecenjeOd, SystemClock.uptimeMillis(), dejanje, x, y, 0)
            // Stran (captcha) mora videti pravi prst na zaslonu na dotik, ne neznanega vira.
            dogodek.source = InputDevice.SOURCE_TOUCHSCREEN
            wv.dispatchTouchEvent(dogodek)
            dogodek.recycle()
        } catch (_: Throwable) { }
    }

    /** Zgrabi na mestu kazalca (dotik dol). */
    fun zacniVlecenje(wv: WebView) {
        if (vlecem) return
        isPointerVisible = true
        vlecem = true
        cakaKlik = false
        okPorabljen = true
        vlecenjeOd = SystemClock.uptimeMillis()
        vlecenjePogled = java.lang.ref.WeakReference(wv)
        // TV 3. 11. 2026 (captcha na claude.ai): ploscica se je ob drzanju le oznacila, premika ni bilo - WebView je
        // drzan prst razumel kot dolg pritisk (izbira). Med vlecenjem dolg pritisk porabimo, da poteza ostane vlecenje.
        wv.setOnLongClickListener { vlecem }
        dotik(wv, MotionEvent.ACTION_DOWN)
        invalidate()
    }

    /** Spusti na mestu kazalca (dotik gor). */
    fun koncajVlecenje(wv: WebView?, preklic: Boolean = false) {
        if (!vlecem) return
        val cilj = vlecenjePogled?.get() ?: wv
        if (cilj != null) dotik(cilj, if (preklic || cilj !== wv) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)
        try { cilj?.setOnLongClickListener(null) } catch (_: Throwable) { }
        vlecenjePogled = null
        vlecem = false
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun performClickOnWebView(targetWebView: WebView) {
        if (!isPointerVisible) {
            isPointerVisible = true
            return
        }

        // Izračunaj relativne koordinate na WebViewu
        val (wvX, wvY) = naPogledu(targetWebView)

        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val downEvent = MotionEvent.obtain(
            downTime, eventTime,
            MotionEvent.ACTION_DOWN,
            wvX, wvY, 0
        )

        val upEvent = MotionEvent.obtain(
            downTime, eventTime + 100,
            MotionEvent.ACTION_UP,
            wvX, wvY, 0
        )

        targetWebView.dispatchTouchEvent(downEvent)
        targetWebView.dispatchTouchEvent(upEvent)

        downEvent.recycle()
        upEvent.recycle()

        // Kratek povratni signal klika: puscica je ~140 ms manjsa in rumena ([KazalecOblika.KLIK_MS]).
        klikDoMs = SystemClock.uptimeMillis() + KazalecOblika.KLIK_MS
        invalidate()
        postInvalidateDelayed(KazalecOblika.KLIK_MS + 20)
    }
}
