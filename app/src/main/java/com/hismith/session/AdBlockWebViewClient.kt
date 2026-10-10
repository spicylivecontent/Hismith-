package com.hismith.session

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Ad / tracker blocker and navigation guard for the in-app WebView.
 *
 * Four layers:
 *  1. **Host rules** – whole ad-network domains (subdomains included). Applied to every request,
 *     including the main document.
 *  2. **Path rules** – ad-specific path *segments* (`/ads/`, `/adserver/`, `/popunder…`). Matched
 *     on the path only (never the query string or host) and only for sub-resources, so normal
 *     player/playlist URLs such as `/track/…` keep working.
 *  3. **Navigation guard** – a main-frame navigation to another site that was not caused by a
 *     recent user tap is cancelled. That is what popunders and "redirect to a fake update page"
 *     scripts rely on. Non-web schemes (`intent:`, `market:` …) are never followed.
 *  4. **Cosmetic filter** – hides ad containers that the network layers cannot see.
 */
class AdBlockWebViewClient(
    private val onPageStarted: ((String?) -> Unit)? = null,
    private val onPageFinished: ((String?) -> Unit)? = null,
    /** Called with a short reason whenever something was blocked (popup, redirect, host). */
    private val onBlocked: ((String) -> Unit)? = null,
    /** True when the user touched the screen very recently – i.e. a navigation is a real click. */
    private val hasRecentUserTouch: () -> Boolean = { false }
) : WebViewClient() {

    companion object {
        // Core ad / tracker / analytics hosts (lowercase). Subdomains are matched automatically.
        private val BLOCKED_HOSTS = setOf(
            // Generic ad networks
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "googletagmanager.com", "google-analytics.com", "adservice.google.com",
            "fundingchoicesmessages.google.com",

            // Popular ad / tracking networks
            "adnxs.com", "adsrvr.org", "adform.net", "advertising.com",
            "amazon-adsystem.com", "rubiconproject.com", "pubmatic.com", "openx.net",
            "casalemedia.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
            "outbrainimg.com", "mgid.com", "revcontent.com", "zergnet.com",
            "moatads.com", "moatpixel.com", "scorecardresearch.com",
            "quantserve.com", "quantcast.com", "bluekai.com", "exelator.com", "krxd.net",
            "demdex.net", "everesttech.net", "agkn.com", "rlcdn.com", "mathtag.com",
            "bidswitch.net", "smartadserver.com", "contextweb.com", "adtechus.com",
            "adtech.de", "yieldmo.com", "sharethrough.com", "indexww.com", "districtm.io",
            "appnexus.com", "3lift.com", "triplelift.com", "sovrn.com", "lijit.com",
            "gumgum.com", "teads.tv", "ads-twitter.com", "ads.linkedin.com",
            "connect.facebook.net", "pixel.facebook.com", "analytics.twitter.com",
            "static.ads-twitter.com", "cdn.adsafeprotected.com", "pixel.adsafeprotected.com",
            "fwmrm.net", "imrworldwide.com",

            // Adult-site ad networks / popunder / redirectors (the ones that actually show up
            // on the supported sites: TrafficJunky, ExoClick, JuicyAds, Adsterra, PopAds …)
            "exoclick.com", "exosrv.com", "exdynsrv.com", "ero-advertising.com",
            "realsrv.com", "juicyads.com",
            "trafficjunky.net", "trafficjunky.com", "ptwmstcmu.com", "tsyndicate.com",
            "trafficstars.com", "clickadu.com", "propellerads.com", "propellerclick.com",
            "adsterra.com", "hilltopads.com", "clickaine.com", "adcash.com", "ad-maven.com",
            "popads.net", "popcash.net", "popunder.net", "richpush.co", "pushwoosh.com",
            "onesignal.com", "pushengage.com", "pushnami.com", "aimtell.com",
            "go.stripchat.com", "crakrevenue.com", "crakbanner.com",
            "trafficfactory.biz", "adxpansion.com", "plugrush.com", "bidvertiser.com",
            "clickbank.net",

            // Analytics / session-replay / error-reporting services
            "hotjar.com", "clarity.ms", "mouseflow.com", "fullstory.com", "loggly.com",
            "sentry.io", "bugsnag.com", "newrelic.com", "nr-data.net", "segment.io",
            "segment.com", "mixpanel.com", "amplitude.com", "heap.io",
            "nielsen.com"
        )

        /** Path segments that only ever appear in ad URLs (compared case-insensitively). */
        private val BLOCKED_PATH_SEGMENTS = setOf(
            "ads", "ad", "adv", "advert", "adverts", "adserver", "adservice", "adsbygoogle",
            "popunder", "popunders", "popup", "popups", "pagead"
        )

        /** File-name prefixes for ad scripts/images, e.g. ads.js, ad.php, popunder.js. */
        private val BLOCKED_FILE_PREFIXES = listOf(
            "ads.", "ad.", "adsbygoogle", "popunder", "popup", "pop.", "pagead", "banner."
        )

        private const val EMPTY_BODY_MIME = "text/plain"

        /**
         * A fresh empty response per blocked request: [WebResourceResponse] hands out its
         * stream once, so sharing a single instance would leave later callers with an
         * already-consumed (undefined) body.
         */
        private fun emptyResponse() = WebResourceResponse(
            EMPTY_BODY_MIME, "utf-8", ByteArrayInputStream(ByteArray(0))
        )

        /** Host decisions cache. Capped so it cannot grow without bound while browsing. */
        private val hostCache = ConcurrentHashMap<String, Boolean>()
        private const val HOST_CACHE_MAX = 4000

        /**
         * Cosmetic filter. Deliberately narrow: an earlier version matched `[class*="ad-"]`,
         * which also hides `upload-`, `download-`, `preload-` and other legitimate UI.
         * Injected on page start and page finish; guarded so it only installs once per document.
         */
        private const val HIDE_ADS_JS = """
            (function() {
              try {
                if (window.__hismithCosmetic) return;
                var parent = document.head || document.documentElement;
                if (!parent) return;  // too early (onPageStarted) – onPageFinished will retry
                var css = [
                  '.adsbygoogle', 'ins.adsbygoogle', '[id^="google_ads"]', '[id^="div-gpt-ad"]',
                  '[id^="ad-"]', '[id^="ads-"]', '[class^="ad-"]', '[class^="ads-"]',
                  '[class*=" ad-slot"]', '[class*="adslot"]', '[class*="ad-banner"]',
                  '[class*="adbanner"]', '[class*="ad_banner"]', '[class*="ad-container"]',
                  '[class*="adcontainer"]', '[class*="ad-overlay"]', '[class*="sponsored-ad"]',
                  '[data-ad]', '[data-ads]', '[data-ad-slot]', '[data-ad-client]',
                  '[data-testid*="banner-ad"]',
                  'iframe[src*="doubleclick"]', 'iframe[src*="exoclick"]',
                  'iframe[src*="exdynsrv"]', 'iframe[src*="trafficjunky"]',
                  'iframe[src*="juicyads"]', 'iframe[src*="realsrv"]',
                  'iframe[src*="tsyndicate"]', 'iframe[src*="adsterra"]',
                  '#ads', '.ad-container', '.adblock-modal', '.pop-up-ad', '.popunder',
                  '.video-ads', '.ad-overlay'
                ].join(',');
                var style = document.createElement('style');
                style.id = 'hismith-adblock-style';
                style.textContent = css + ' { display: none !important; visibility: hidden !important; ' +
                  'height: 0 !important; width: 0 !important; opacity: 0 !important; ' +
                  'pointer-events: none !important; }';
                parent.appendChild(style);
                window.__hismithCosmetic = true;
              } catch (e) {}
            })();
        """
    }

    /** True if the host (or one of its parent domains) is on the ad-network list. */
    private fun isBlockedHost(host: String): Boolean {
        hostCache[host]?.let { return it }
        val blocked = BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
        if (hostCache.size > HOST_CACHE_MAX) hostCache.clear()
        hostCache[host] = blocked
        return blocked
    }

    /** True if the path contains an ad-specific segment or an ad-script file name. */
    private fun isAdPath(path: String): Boolean {
        val segments = path.lowercase().split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        if (segments.any { it in BLOCKED_PATH_SEGMENTS }) return true
        val file = segments.last()
        return BLOCKED_FILE_PREFIXES.any { file.startsWith(it) }
    }

    /** Registrable-domain approximation: `www.xvideos.com` → `xvideos.com`. */
    private fun siteOf(host: String): String {
        val parts = host.split('.')
        return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
    }

    /** Decision for a sub-resource (script, image, iframe, xhr …). */
    private fun shouldBlockSubresource(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (isBlockedHost(host)) return true
        return isAdPath(uri.encodedPath ?: "")
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val req = request ?: return null
        val blocked = if (req.isForMainFrame) {
            // Main document: only the host list. Path rules here could blank out a real page.
            req.url.host?.lowercase()?.let { isBlockedHost(it) } ?: false
        } else {
            shouldBlockSubresource(req.url)
        }
        if (blocked) return emptyResponse()
        return super.shouldInterceptRequest(view, request)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        if (url != null && shouldBlockSubresource(Uri.parse(url))) return emptyResponse()
        return super.shouldInterceptRequest(view, url)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val req = request ?: return false
        // Sub-frames are handled in shouldInterceptRequest; only top-level navigations matter here.
        if (!req.isForMainFrame) return false

        val target = req.url
        val scheme = target.scheme?.lowercase()
        // intent:// , market:// , custom app schemes are how popunders push people to app stores.
        if (scheme != "http" && scheme != "https" && scheme != "about") {
            onBlocked?.invoke("app-länk")
            return true
        }

        val targetHost = target.host?.lowercase() ?: return false
        if (isBlockedHost(targetHost)) {
            onBlocked?.invoke(targetHost)
            return true
        }

        val currentHost = view?.url?.let { Uri.parse(it).host?.lowercase() }
        val sameSite = currentHost == null || siteOf(currentHost) == siteOf(targetHost)
        if (!sameSite && !hasRecentUserTouch()) {
            // Not a click by the user → a script is sending us elsewhere (popunder / fake update page).
            onBlocked?.invoke("omdirigering till $targetHost")
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        view?.evaluateJavascript(HIDE_ADS_JS, null)
        onPageStarted?.invoke(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished?.invoke(url)
        // Second pass – some containers are only created after the page has loaded.
        view?.evaluateJavascript(HIDE_ADS_JS, null)
    }
}
