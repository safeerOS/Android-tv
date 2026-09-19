package si.safeer.tv

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.StringRes
import java.util.Locale

@SuppressLint("StaticFieldLeak")
object UiText {
    @Volatile
    private var appContext: Context? = null

    val language: String
        get() {
            val ctx = appContext
            return if (ctx != null) {
                val locales = ctx.resources.configuration.locales
                if (!locales.isEmpty) locales[0].language else Locale.getDefault().language
            } else {
                Locale.getDefault().language
            }
        }

    /**
     * Kontekst za nize. Klicatelj poda kontekst, izpeljan iz aplikacijskega
     * (glej [JezikVmesnika.vKontekstu]), da nizi sledijo izbranemu jeziku.
     */
    fun init(context: Context) {
        appContext = context
    }

    fun get(@StringRes resId: Int): String {
        return appContext?.getString(resId) ?: ""
    }

    fun get(@StringRes resId: Int, vararg formatArgs: Any?): String {
        val ctx = appContext ?: return ""
        return try {
            ctx.getString(resId, *formatArgs)
        } catch (_: Exception) {
            ""
        }
    }
}
