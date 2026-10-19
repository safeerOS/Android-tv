package si.safeer.tv.os

import android.app.Activity
import android.view.View
import android.view.ViewGroup

/**
 * TV (daljinec) za zaslone z XML menijem: kot stranske vrstice brskalnikov - ko je fokus v vsebini,
 * je meni skrcen na ikone (vec prostora), ob vstopu v meni se razsiri z imeni.
 */
class TvSkrcenjeVrstice(private val a: Activity, private val meni: ViewGroup) :
    android.view.ViewTreeObserver.OnGlobalFocusChangeListener {
    private val sirina = meni.layoutParams.width
    private val zacetni = intArrayOf(meni.paddingStart, meni.paddingTop, meni.paddingEnd, meni.paddingBottom)
    private val besedila = ArrayList<View>().also { zberi(meni, it) }
    private var skrcena = false

    init {
        meni.viewTreeObserver.addOnGlobalFocusChangeListener(this)
        // Fokus, ki ga zaslon dobi ze v onCreate, poslusalec ne vidi: meni je ostal razsirjen, ceprav je bil fokus v
        // vsebini, do prvega pritiska na daljinec (kot f5402c3a pri StranskaVrstica, TV Domov 19. 10. 2026).
        meni.post { a.currentFocus?.let { onGlobalFocusChanged(null, it) } }
    }

    private fun zberi(v: View, out: MutableList<View>) {
        if (v is android.widget.TextView && v.visibility == View.VISIBLE) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) zberi(v.getChildAt(i), out)
    }

    private fun jeV(v: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        if (v === meni) return true
        while (p != null) { if (p === meni) return true; p = p.parent }
        return false
    }

    override fun onGlobalFocusChanged(stari: View?, novi: View?) {
        if (novi == null) return
        val skrci = !jeV(novi)
        if (skrci == skrcena) return
        skrcena = skrci
        val d = a.resources.displayMetrics.density
        meni.layoutParams = meni.layoutParams.apply { width = if (skrci) (68 * d).toInt() else sirina }
        if (skrci) meni.setPaddingRelative((10 * d).toInt(), zacetni[1], (10 * d).toInt(), zacetni[3])
        else meni.setPaddingRelative(zacetni[0], zacetni[1], zacetni[2], zacetni[3])
        besedila.forEach { it.visibility = if (skrci) View.GONE else View.VISIBLE }
    }
}
