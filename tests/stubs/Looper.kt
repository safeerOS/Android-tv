package android.os

/**
 * Nadomestek android.os.Looper za preizkuse v JVM: nobena nit ni glavna, razen tiste, ki jo preizkus izrecno oznaci
 * ([glavnaNit]) - tako preizkus preveri, da koda na glavni niti ne tece.
 */
class Looper private constructor() {
    companion object {
        private val glavni = Looper()

        /** Samo v preizkusih: id niti, ki se steje za glavno (-1 = nobena). */
        @Volatile var glavnaNit: Long = -1

        @JvmStatic fun myLooper(): Looper? = if (Thread.currentThread().id == glavnaNit) glavni else null
        @JvmStatic fun getMainLooper(): Looper = glavni
    }
}
