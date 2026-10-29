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
            invalidate()
        }

    /**
     * Nacin miske za spletne aplikacije, ki niso narejene za daljinec: kazalec se ne skrije sam
     * in strani sporoca premike kot prava miska (lebdenje), da se meniji in namigi vedejo kot na racunalniku.
     */
    var trajen: Boolean = false
        set(value) {
            field = value
            removeCallbacks(hideRunnable)
        }

    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
        setShadowLayer(12f, 0f, 0f, Color.parseColor("#8000E5FF"))
    }

    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val hideRunnable = Runnable {
        isPointerVisible = false
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
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

        // 1. Zunanji krog z žarenjem (Glow Ring)
        canvas.drawCircle(pointerX, pointerY, 18f, ringPaint)

        // 2. Glavna pikica kazalca (Cyan Circle)
        canvas.drawCircle(pointerX, pointerY, 12f, pointerPaint)

        // 3. Notranja bela točka (Center Specular Dot)
        canvas.drawCircle(pointerX, pointerY, 4f, innerPaint)
    }

    fun movePointer(dx: Float, dy: Float, targetWebView: WebView?): Boolean {
        isPointerVisible = true
        removeCallbacks(hideRunnable)
        if (!trajen) postDelayed(hideRunnable, 30000)

        val newX = max(10f, min(width - 10f, pointerX + dx))
        val newY = max(10f, min(height - 10f, pointerY + dy))

        pointerX = newX
        pointerY = newY
        invalidate()

        // Samodejno pomikanje (Auto-scroll) ko se kazalec približa vrhu ali dnu spletne strani
        if (targetWebView != null) {
            val topThreshold = 140f
            val bottomThreshold = height - 120f

            if (pointerY < topThreshold) {
                targetWebView.scrollBy(0, -120)
            } else if (pointerY > bottomThreshold) {
                targetWebView.scrollBy(0, 120)
            }
            if (trajen) posljiLebdenje(targetWebView)
        }
        return true
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

        // Kratka vizualna animacija klika (Pulse)
        invalidate()
    }
}
