package com.hismith.session.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import com.hismith.session.MainActivity
import com.hismith.session.QuickLink
import com.hismith.session.R

/**
 * Browser extras: favourites and editable quick links, the ⋯ menu, private mode and video sync
 * (the session follows the video: pause, play, end).
 */
class BrowserExtras(
    private val a: MainActivity,
    private val webView: WebView,
    private val row: LinearLayout,
    private val favButton: Button,
    menuButton: Button,
    private val load: (String) -> Unit,
) {
    private val settings = a.settings
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var lastRaw = ""
    private var sameCount = 0
    private var reported = ""

    companion object {
        private const val POLL_MS = 1_000L

        /**
         * Finds the main video (the largest one that has loaded) and reports its state.
         * Runs in the page; nothing from the page can call back into the app.
         */
        private const val VIDEO_JS = """(function(){try{var vs=document.querySelectorAll('video');var best=null,area=0;
for(var i=0;i<vs.length;i++){var v=vs[i];var r=v.getBoundingClientRect();var a=r.width*r.height;
if(v.readyState>0&&a>area){area=a;best=v;}}
if(!best||area<20000)return 'none';if(best.ended)return 'ended';return best.paused?'paused':'playing';}catch(e){return 'none';}})()"""
    }

    init {
        favButton.setOnClickListener { toggleFavorite() }
        menuButton.setOnClickListener { showMenu() }
        rebuildQuickLinks()
    }

    // ------------------------------------------------------------------ quick links / favourites

    fun rebuildQuickLinks() {
        row.removeAllViews()
        val hidden = settings.hiddenQuickLinks
        val favs = settings.favorites
        val defaults = MainActivity.QUICK_LINKS.map { QuickLink(it.first, it.second) }.filter { it.url !in hidden }
        favs.forEach { addLink(it, favorite = true) }
        defaults.filter { d -> favs.none { it.url == d.url } }.forEach { addLink(it, favorite = false) }
    }

    private fun addLink(link: QuickLink, favorite: Boolean) {
        val b = Button(a, null, 0, R.style.Btn_Outline).apply {
            text = if (favorite) "★ ${link.label}" else link.label
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0)
            setOnClickListener { load(link.url) }
            setOnLongClickListener {
                Ui.dialog(a)
                    .setTitle(link.label)
                    .setMessage(link.url)
                    .setPositiveButton(if (favorite) "Ta bort favorit" else "Dölj snabblänk") { _, _ ->
                        if (favorite) settings.favorites = settings.favorites.filter { it.url != link.url }
                        else settings.hiddenQuickLinks = settings.hiddenQuickLinks + link.url
                        rebuildQuickLinks()
                        onPageStarted(webView.url)
                    }
                    .setNegativeButton("Avbryt", null)
                    .show()
                true
            }
        }
        row.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(a, 30)).apply { marginEnd = Ui.dp(a, 6) })
    }

    private fun isFavorite(url: String?): Boolean = url != null && settings.favorites.any { it.url == url }

    fun onPageStarted(url: String?) {
        favButton.text = if (isFavorite(url)) "★" else "☆"
        // A new page means a new video: start the debounce over.
        lastRaw = ""
        sameCount = 0
        reported = ""
    }

    private fun toggleFavorite() {
        val url = webView.url ?: return
        if (isFavorite(url)) {
            settings.favorites = settings.favorites.filter { it.url != url }
            Ui.toast(a, "Borttagen från favoriter")
        } else {
            val host = Uri.parse(url).host?.removePrefix("www.") ?: url
            val title = webView.title?.takeIf { it.isNotBlank() && it != url } ?: host
            settings.favorites = settings.favorites + QuickLink(title.take(22), url)
            Ui.toast(a, "Sparad som favorit – långtryck för att ta bort")
        }
        rebuildQuickLinks()
        onPageStarted(url)
    }

    // ------------------------------------------------------------------ menu

    private fun onOff(v: Boolean) = if (v) "PÅ" else "av"

    private fun showMenu() {
        val items = listOf(
            "Videosynk (pausa/fortsätt med videon): ${onOff(settings.videoSync)}" to {
                settings.videoSync = !settings.videoSync
                if (settings.videoSync) Ui.toast(a, "Pausar du videon pausas passet – spelar du igen fortsätter det")
            },
            "Final när videon tar slut: ${onOff(settings.videoEndFinal)}" to {
                settings.videoEndFinal = !settings.videoEndFinal
            },
            "Minipanel i webbläsaren: ${onOff(settings.miniPanel)}" to {
                settings.miniPanel = !settings.miniPanel
                a.refreshOverlaysFromExtras()
            },
            "Privat läge (rensa allt när appen stängs): ${onOff(settings.privateBrowsing)}" to {
                settings.privateBrowsing = !settings.privateBrowsing
            },
            "Rensa historik, cookies och webbdata nu" to {
                clearBrowsingData()
                Ui.toast(a, "Webbdata rensad")
            },
            "Visa dolda snabblänkar igen" to {
                settings.hiddenQuickLinks = emptySet()
                rebuildQuickLinks()
            },
        )
        Ui.dialog(a)
            .setTitle("Webbläsare")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Stäng", null)
            .show()
    }

    fun clearBrowsingData() {
        try {
            webView.clearHistory()
            webView.clearCache(true)
            webView.clearFormData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            settings.prefs.edit().remove("last_url").apply()
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ video sync

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            if (a.isBrowserVisible() && (settings.videoSync || settings.videoEndFinal)) {
                webView.evaluateJavascript(VIDEO_JS) { raw -> onVideoState(raw?.trim('"') ?: "none") }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun startVideoPoll() {
        if (polling) return
        polling = true
        handler.postDelayed(poll, POLL_MS)
    }

    fun stopVideoPoll() {
        polling = false
        handler.removeCallbacks(poll)
    }

    /** Debounced: a state must be seen twice in a row before the session reacts. */
    private fun onVideoState(state: String) {
        if (state == lastRaw) sameCount++ else { lastRaw = state; sameCount = 1 }
        if (sameCount < 2 || state == reported || state == "none") return
        reported = state
        a.service?.onVideoState(state)
    }
}
