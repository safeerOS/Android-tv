package si.safeer.tv.os

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Miskin kazalec racunalnika, ki ga narise naprava sama. Zajem zaslona Windows kazalca ne vsebuje; racunalnik zato
 * posilja samo polozaj in obliko, ko se spremenita (zamisel Stream Decka: odziv na napravi, ukaz racunalniku). Kazalec
 * se tako premakne takoj, ko pride drobno obvestilo - ne sele z naslednjo sliko (kodiranje + dekodiranje).
 *
 * Pogled je velik toliko kot kazalec; vrh puscice (vroca tocka) je v (ROB, ROB), sredina drugih oblik v sredini.
 */
class KazalecPogled(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    /** Velikost risbe kazalca v dp (puscica je visoka priblizno toliko). */
    private val velikost = 22f * d
    val rob = 3f * d
    val mera = (velikost + 2 * rob).toInt()

    var oblika: String = "puscica"
        set(v) { if (field != v) { field = v; invalidate() } }

    private val polnilo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val obroba = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val crta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4.2f * d; strokeCap = Paint.Cap.ROUND
    }
    private val crtaBela = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND
    }

    /** Kje v pogledu je vroca tocka kazalca (tja, kamor kaze na racunalniku). */
    fun vrocaX(): Float = if (vrhVKotu()) rob else mera / 2f
    fun vrocaY(): Float = if (vrhVKotu()) rob else mera / 2f
    private fun vrhVKotu() = oblika == "puscica" || oblika == "puscica_cakanje" || oblika == "roka" || oblika == "ne"

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(mera, mera)

    override fun onDraw(c: Canvas) {
        when (oblika) {
            "besedilo" -> crte(c, floatArrayOf(0f, -0.42f, 0f, 0.42f, -0.16f, -0.42f, 0.16f, -0.42f, -0.16f, 0.42f, 0.16f, 0.42f))
            "kriz" -> crte(c, floatArrayOf(0f, -0.42f, 0f, 0.42f, -0.42f, 0f, 0.42f, 0f))
            "velikost_we" -> dvojna(c, 0f)
            "velikost_ns" -> dvojna(c, 90f)
            "velikost_nwse" -> dvojna(c, 45f)
            "velikost_nesw" -> dvojna(c, -45f)
            "premik" -> { dvojna(c, 0f); dvojna(c, 90f) }
            "cakanje" -> {
                val r = velikost * 0.36f
                c.drawCircle(mera / 2f, mera / 2f, r, crta); c.drawCircle(mera / 2f, mera / 2f, r, crtaBela)
            }
            else -> {
                puscica(c)
                if (oblika == "puscica_cakanje") {
                    val r = velikost * 0.18f
                    c.drawCircle(rob + velikost * 0.72f, rob + velikost * 0.78f, r, crta)
                    c.drawCircle(rob + velikost * 0.72f, rob + velikost * 0.78f, r, crtaBela)
                }
            }
        }
    }

    /** Klasicna puscica: bela z crno obrobo, vrh v kotu (roka in »ne« prav tako - dovolj natancno, vroca tocka je enaka). */
    private fun puscica(c: Canvas) {
        val s = velikost
        val p = Path().apply {
            moveTo(rob, rob)
            lineTo(rob, rob + s)
            lineTo(rob + s * 0.27f, rob + s * 0.76f)
            lineTo(rob + s * 0.45f, rob + s * 1.0f)
            lineTo(rob + s * 0.58f, rob + s * 0.93f)
            lineTo(rob + s * 0.41f, rob + s * 0.69f)
            lineTo(rob + s * 0.72f, rob + s * 0.69f)
            close()
        }
        c.drawPath(p, polnilo)
        c.drawPath(p, obroba)
    }

    /** Crte (x1, y1, x2, y2 ...) v delezih velikosti okoli sredine: najprej crne, nato bele (vidno na vsaki podlagi). */
    private fun crte(c: Canvas, t: FloatArray) {
        val sx = mera / 2f; val sy = mera / 2f
        for (paint in arrayOf(crta, crtaBela)) {
            var i = 0
            while (i + 3 < t.size) {
                c.drawLine(sx + t[i] * velikost, sy + t[i + 1] * velikost, sx + t[i + 2] * velikost, sy + t[i + 3] * velikost, paint)
                i += 4
            }
        }
    }

    /** Dvojna puscica za spreminjanje velikosti, zasukana za kot (0 = vodoravno). */
    private fun dvojna(c: Canvas, kot: Float) {
        c.save()
        c.rotate(kot, mera / 2f, mera / 2f)
        crte(c, floatArrayOf(-0.42f, 0f, 0.42f, 0f, -0.42f, 0f, -0.26f, -0.14f, -0.42f, 0f, -0.26f, 0.14f,
            0.42f, 0f, 0.26f, -0.14f, 0.42f, 0f, 0.26f, 0.14f))
        c.restore()
    }
}
