package android.content

/** Nadomestek za preizkus prehoda interneta na JVM (tests/PrehodInternetaTest.kt): nastavitve v pomnilniku. */
interface SharedPreferences {
    val all: Map<String, *>
    fun getBoolean(kljuc: String, privzeto: Boolean): Boolean
    fun getLong(kljuc: String, privzeto: Long): Long
    fun getString(kljuc: String, privzeto: String?): String?
    fun edit(): Editor
    interface Editor {
        fun remove(kljuc: String): Editor
        fun putString(kljuc: String, vrednost: String?): Editor
        fun putBoolean(kljuc: String, vrednost: Boolean): Editor
        fun putLong(kljuc: String, vrednost: Long): Editor
        fun apply()
    }
}

open class Context {
    private class Nastavitve : SharedPreferences {
        val v = java.util.concurrent.ConcurrentHashMap<String, Any>()
        override val all: Map<String, *> get() = HashMap(v)
        override fun getBoolean(kljuc: String, privzeto: Boolean): Boolean = v[kljuc] as? Boolean ?: privzeto
        override fun getLong(kljuc: String, privzeto: Long): Long = v[kljuc] as? Long ?: privzeto
        override fun getString(kljuc: String, privzeto: String?): String? = v[kljuc] as? String ?: privzeto
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun remove(kljuc: String) = apply { v.remove(kljuc) }
            override fun putString(kljuc: String, vrednost: String?) = apply { if (vrednost == null) v.remove(kljuc) else v[kljuc] = vrednost }
            override fun putBoolean(kljuc: String, vrednost: Boolean) = apply { v[kljuc] = vrednost }
            override fun putLong(kljuc: String, vrednost: Long) = apply { v[kljuc] = vrednost }
            override fun apply() { }
        }
    }

    private val nastavitve = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>()
    open val applicationContext: Context get() = this
    fun getSharedPreferences(ime: String, nacin: Int): SharedPreferences = nastavitve.getOrPut(ime) { Nastavitve() }

    companion object { const val MODE_PRIVATE = 0 }
}
