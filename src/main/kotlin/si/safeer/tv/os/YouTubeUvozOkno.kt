package si.safeer.tv.os

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Pogovorno okno za prijavo v YouTube in samodejni uvoz playlist v Mojih virih.
 */
object YouTubeUvozOkno {

    fun pokazi(a: Activity, potem: () -> Unit) {
        val jePrijavljen = YouTubeUvoz.jePrijavljen(a)
        val d = a.resources.displayMetrics.density
        val rob = (20 * d).toInt()

        val vsebina = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(rob, rob, rob, rob)
        }

        val naslovStanja = TextView(a).apply {
            text = if (jePrijavljen) "✓ Povezan YouTube račun" else "YouTube račun ni povezan"
            textSize = 17f
            setTextColor(if (jePrijavljen) android.graphics.Color.parseColor("#2DD4BF") else android.graphics.Color.WHITE)
            setPadding(0, 0, 0, (12 * d).toInt())
        }
        vsebina.addView(naslovStanja)

        val opis = TextView(a).apply {
            text = if (jePrijavljen) {
                "Vaš račun je povezan. Lahko sinhronizirate svoje sezname predvajanja ali se odjavite."
            } else {
                "S prijavo v Google/YouTube račun se bodo vaši seznami predvajanja (vključno z všečki) samodejno uvozili v vaš predvajalnik."
            }
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            setPadding(0, 0, 0, (18 * d).toInt())
        }
        vsebina.addView(opis)

        val napredekVrsta = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, 0, 0, (12 * d).toInt())
        }
        val vrtavka = ProgressBar(a).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt())
        }
        val napredekBesedilo = TextView(a).apply {
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#2DD4BF"))
            setPadding((12 * d).toInt(), 0, 0, 0)
        }
        napredekVrsta.addView(vrtavka)
        napredekVrsta.addView(napredekBesedilo)
        vsebina.addView(napredekVrsta)

        val okno = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("YouTube račun in seznami")
            .setView(ScrollView(a).apply { addView(vsebina) })
            .setPositiveButton(if (jePrijavljen) "Sinhroniziraj sezname" else "Prijava v YouTube", null)
            .setNegativeButton(if (jePrijavljen) "Odjava" else "Zapri", null)
            .create()

        okno.setOnShowListener {
            val gumbGlavni = okno.getButton(AlertDialog.BUTTON_POSITIVE)
            val gumbDrugi = okno.getButton(AlertDialog.BUTTON_NEGATIVE)

            gumbGlavni.setOnClickListener {
                if (!YouTubeUvoz.jePrijavljen(a)) {
                    okno.dismiss()
                    odpriPrijavnoOkno(a) {
                        pokazi(a, potem)
                    }
                } else {
                    gumbGlavni.isEnabled = false
                    napredekVrsta.visibility = View.VISIBLE
                    napredekBesedilo.text = "Začenjam uvoz..."
                    YouTubeUvoz.uvoziVseSezname(
                        a,
                        onNapredek = { sporocilo -> napredekBesedilo.text = sporocilo },
                        onKoncano = { uspeh, sporocilo ->
                            napredekVrsta.visibility = View.GONE
                            gumbGlavni.isEnabled = true
                            Toast.makeText(a, sporocilo, Toast.LENGTH_LONG).show()
                            potem()
                            if (uspeh) okno.dismiss()
                        }
                    )
                }
            }

            gumbDrugi.setOnClickListener {
                if (YouTubeUvoz.jePrijavljen(a)) {
                    YouTubeUvoz.odjavi(a)
                    Toast.makeText(a, "Odjavljeni iz YouTube računa", Toast.LENGTH_SHORT).show()
                    okno.dismiss()
                    potem()
                } else {
                    okno.dismiss()
                }
            }
        }

        okno.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun odpriPrijavnoOkno(a: Activity, obPrijavi: () -> Unit) {
        val webView = WebView(a).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 11; SafeerTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
            isFocusable = true
            isFocusableInTouchMode = true
        }

        var zaprto = false
        val okno = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Prijava v YouTube")
            .setView(webView)
            .setNegativeButton("Prekliči") { _, _ -> zaprto = true; webView.destroy() }
            .create()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (zaprto || url == null) return
                // Preveri ali so piškotki prijave prisotni
                val cm = CookieManager.getInstance()
                val cookies = cm.getCookie("https://www.youtube.com").orEmpty()
                if (cookies.contains("LOGIN_INFO") || cookies.contains("SAPISID") || cookies.contains("SSID")) {
                    zaprto = true
                    YouTubeUvoz.zabeleziPrijavo(a)
                    Toast.makeText(a, "Prijava uspešna! Sinhroniziram sezname...", Toast.LENGTH_SHORT).show()
                    okno.dismiss()
                    webView.destroy()
                    YouTubeUvoz.uvoziVseSezname(a, onKoncano = { _, sporocilo ->
                        Toast.makeText(a, sporocilo, Toast.LENGTH_LONG).show()
                        obPrijavi()
                    })
                }
            }
        }
        webView.webChromeClient = WebChromeClient()

        // Prijavna stran za YouTube z Google računom
        webView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://m.youtube.com")
        okno.show()
        okno.window?.apply {
            setLayout((a.resources.displayMetrics.widthPixels * 0.85).toInt(), (a.resources.displayMetrics.heightPixels * 0.88).toInt())
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        okno.setOnShowListener {
            webView.requestFocus()
        }
    }
}
