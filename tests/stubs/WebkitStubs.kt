package android.webkit

import android.content.Context

open class CookieManager {
    open fun getCookie(url: String?): String? = null
    open fun setCookie(url: String?, value: String?) {}
    open fun flush() {}

    companion object {
        private val inst = CookieManager()
        @JvmStatic
        fun getInstance(): CookieManager = inst
    }
}

open class WebStorage {
    open fun deleteOrigin(origin: String?) {}

    companion object {
        private val inst = WebStorage()
        @JvmStatic
        fun getInstance(): WebStorage = inst
    }
}

open class WebView(context: Context) {
    open fun clearCache(includeDiskFiles: Boolean) {}
    open fun evaluateJavascript(script: String, resultCallback: ((String?) -> Unit)?) {}
}

open class WebSettings {
    companion object {
        @JvmStatic
        fun getDefaultUserAgent(context: Context): String =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
