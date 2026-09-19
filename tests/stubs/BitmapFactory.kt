package android.graphics

/**
 * Nadomestek Androidovega BitmapFactory za preizkus v JVM: prebere mere iz glave PNG (IHDR)
 * in si zapomni, kolikokrat je bilo zahtevano PRAVO dekodiranje in s katerim inSampleSize.
 * Pravo dekodiranje ogromne slike "porabi pomnilnik" - v preizkusu to vrze OutOfMemoryError,
 * tako kot bi na televizorju.
 */
class Bitmap(val width: Int, val height: Int)

object BitmapFactory {
    class Options {
        @JvmField var inJustDecodeBounds = false
        @JvmField var outWidth = -1
        @JvmField var outHeight = -1
        @JvmField var inSampleSize = 1
    }

    var polnihDekodiranj = 0
    var zadnjiVzorec = 0

    /** Kolikor pik bi televizor se prenesel, preden mu zmanjka pomnilnika (~200 MB pri 4 B/piko). */
    private const val NAJVEC_PIK = 50_000_000L

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun int(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    fun decodeByteArray(b: ByteArray, off: Int, len: Int, o: Options?): Bitmap? {
        val jePng = len >= 24 && (0 until 8).all { b[off + it] == PNG[it] } &&
            String(b, off + 12, 4, Charsets.US_ASCII) == "IHDR"
        val w = if (jePng) int(b, off + 16) else -1
        val h = if (jePng) int(b, off + 20) else -1
        if (o != null && o.inJustDecodeBounds) {
            o.outWidth = w; o.outHeight = h
            return null
        }
        if (!jePng || w <= 0 || h <= 0) return null
        val s = maxOf(1, o?.inSampleSize ?: 1)
        polnihDekodiranj++
        zadnjiVzorec = s
        val sirina = w / s
        val visina = h / s
        if (sirina.toLong() * visina > NAJVEC_PIK) throw OutOfMemoryError("slika $sirina x $visina")
        return Bitmap(sirina, visina)
    }

    fun decodeByteArray(b: ByteArray, off: Int, len: Int): Bitmap? = decodeByteArray(b, off, len, null)

    fun decodeFile(pot: String, o: Options?): Bitmap? {
        val b = try { java.io.File(pot).readBytes() } catch (_: Exception) { return null }
        return decodeByteArray(b, 0, b.size, o)
    }

    fun decodeFile(pot: String): Bitmap? = decodeFile(pot, null)
}
