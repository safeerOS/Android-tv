package si.safeer.tv.os

import android.content.Context
import android.view.ViewGroup

/**
 * Predlogi iskanj v mrezi: [vrstic] vrstice po [stolpcev] predloga (lastnik 3. 11. 2026 18:30 in 18:32: »3 vrste in v
 * vsaki 2 predloga, skupno 6«). Vsak predlog je v eni vrstici brez loma besed (predolg dobi tri pike); kar je cez
 * mrezo, ni prikazano in ni dosegljivo s fokusom.
 */
class VrsticeCipov(c: Context, private val razmikPx: Int, private val vrstic: Int = 3, private val stolpcev: Int = 2) : ViewGroup(c) {
    val najvec: Int get() = vrstic * stolpcev

    override fun onMeasure(sirinaSpec: Int, visinaSpec: Int) {
        val sirina = (MeasureSpec.getSize(sirinaSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val stolpec = ((sirina - razmikPx * (stolpcev - 1)) / stolpcev).coerceAtLeast(0)
        var visinaVrstice = 0
        for (i in 0 until childCount) {
            val o = getChildAt(i)
            o.isFocusable = i < najvec
            if (i >= najvec) continue
            measureChild(o, MeasureSpec.makeMeasureSpec(stolpec, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            visinaVrstice = maxOf(visinaVrstice, o.measuredHeight)
        }
        val vrsticVidnih = (minOf(childCount, najvec) + stolpcev - 1) / stolpcev
        val visina = if (vrsticVidnih == 0) 0 else vrsticVidnih * visinaVrstice + (vrsticVidnih - 1) * razmikPx
        setMeasuredDimension(MeasureSpec.getSize(sirinaSpec), visina + paddingTop + paddingBottom)
    }

    override fun onLayout(spremenjeno: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val sirina = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)
        val stolpec = ((sirina - razmikPx * (stolpcev - 1)) / stolpcev).coerceAtLeast(0)
        for (i in 0 until childCount) {
            val o = getChildAt(i)
            if (i >= najvec) { o.layout(0, 0, 0, 0); continue }
            val x = paddingLeft + (i % stolpcev) * (stolpec + razmikPx)
            val y = paddingTop + (i / stolpcev) * (o.measuredHeight + razmikPx)
            o.layout(x, y, x + o.measuredWidth, y + o.measuredHeight)
        }
    }
}
