package androidx.webkit

import android.content.Context
import android.content.pm.PackageInfo

open class WebViewCompat {
    companion object {
        @JvmStatic
        fun getCurrentWebViewPackage(context: Context): PackageInfo? = null
    }
}
