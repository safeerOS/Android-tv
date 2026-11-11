package android.util

object Base64 {
    const val NO_WRAP = 2
    fun encodeToString(b: ByteArray, odmik: Int, dolzina: Int, zastavice: Int): String =
        java.util.Base64.getEncoder().encodeToString(b.copyOfRange(odmik, odmik + dolzina))
    fun decode(s: String, zastavice: Int): ByteArray = java.util.Base64.getDecoder().decode(s)
}
