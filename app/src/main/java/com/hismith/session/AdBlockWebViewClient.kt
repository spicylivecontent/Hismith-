package com.hismith.session

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight but effective ad/tracker blocker for WebView.
 * Uses host + path pattern matching against common ad/tracker domains
 * and EasyList-style rules. Not as complete as Brave, but blocks the
 * majority of ads on Pornhub, XVideos, XNXX, Reddit, etc.
 */
class AdBlockWebViewClient(
    private val onPageStarted: ((String?) -> Unit)? = null,
    private val onPageFinished: ((String?) -> Unit)? = null
) : WebViewClient() {

    companion object {
        // Core ad / tracker / analytics hosts (lowercase)
        private val BLOCKED_HOSTS = setOf(
            // Generic ad networks
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "googletagmanager.com", "google-analytics.com", "adservice.google.com",
            "pagead2.googlesyndication.com", "tpc.googlesyndication.com",
            "partner.googleadservices.com", "ad.doubleclick.net",
            "static.doubleclick.net", "cm.g.doubleclick.net",
            "fundingchoicesmessages.google.com",

            // Popular ad / tracking networks
            "adnxs.com", "adsrvr.org", "adform.net", "advertising.com",
            "amazon-adsystem.com", "aax.amazon-adsystem.com",
            "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com",
            "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
            "mgid.com", "revcontent.com", "zergnet.com",
            "moatads.com", "moatpixel.com", "scorecardresearch.com",
            "quantserve.com", "quantcast.com", "bluekai.com",
            "exelator.com", "krxd.net", "demdex.net", "everesttech.net",
            "agkn.com", "rlcdn.com", "mathtag.com", "turn.com",
            "bidswitch.net", "smartadserver.com", "contextweb.com",
            "adtechus.com", "adtech.de", "yieldmo.com", "sharethrough.com",
            "indexww.com", "districtm.io", "appnexus.com", "3lift.com",
            "triplelift.com", "sovrn.com", "lijit.com", "gumgum.com",
            "teads.tv", "outbrainimg.com", "taboola.com",
            "ads-twitter.com", "ads.linkedin.com", "facebook.com/tr",
            "connect.facebook.net", "pixel.facebook.com",
            "analytics.twitter.com", "static.ads-twitter.com",

            // Adult-site common ad networks / popunder / redirectors
            "exoclick.com", "exosrv.com", "realsrv.com", "juicyads.com",
            "trafficjunky.net", "trafficjunky.com", "ad.trafficjunky.net",
            "ads.trafficjunky.net", "ptwmstcmu.com", "tsyndicate.com",
            "clickadu.com", "propellerads.com", "propellerclick.com",
            "adsterra.com", "adserver.adsterra.com", "cdn.adsterra.com",
            "hilltopads.com", "clickaine.com", "popads.net", "popcash.net",
            "popunder.net", "richpush.co", "pushwoosh.com", "onesignal.com",
            "pushengage.com", "pushnami.com", "aimtell.com",
            "stripchat.com", "go.stripchat.com", "static.stripchat.com",
            "chaturbate.com/in", "livejasmin.com", "jasmin.com",
            "crakrevenue.com", "crakbanner.com", "ad.crakrevenue.com",
            "trafficfactory.biz", "a.trafficfactory.biz",
            "adxpansion.com", "plugrush.com", "bidvertiser.com",
            "clickbank.net", "hop.clickbank.net",

            // Analytics / fingerprint / anti-adblock helpers often abused
            "hotjar.com", "clarity.ms", "mouseflow.com", "fullstory.com",
            "loggly.com", "sentry.io", "bugsnag.com",
            "newrelic.com", "nr-data.net", "segment.io", "segment.com",
            "mixpanel.com", "amplitude.com", "heap.io",

            // Common CDN ad paths / known bad
            "cdn.adsafeprotected.com", "pixel.adsafeprotected.com",
            "fwmrm.net", "imrworldwide.com", "nielsen.com"
        )

        // Path / query patterns that almost always mean ads
        private val BLOCKED_PATH_PATTERNS = listOf(
            "/ads/", "/ad/", "/advert", "/banner", "/popunder",
            "/pop/", "/popup", "/track", "/tracking", "/pixel",
            "/beacon", "/collect", "/analytics", "/gtm.js",
            "/ads.js", "/ad.js", "/sponsor", "/promo/",
            "doubleclick", "googlesyndication", "pagead",
            "adservice", "adserver", "adform", "adnxs",
            "exoclick", "trafficjunky", "juicyads", "realsrv",
            "tsyndicate", "popads", "popcash", "propeller"
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

        // Simple in-memory cache of decisions for speed
        private val hostCache = ConcurrentHashMap<String, Boolean>()

        // Extra cosmetic filter for common ad containers
        private const val HIDE_ADS_JS = """
            (function() {
              try {
                var style = document.getElementById('hismith-adblock-style');
                if (!style) {
                  style = document.createElement('style');
                  style.id = 'hismith-adblock-style';
                  style.textContent = `
                    [id*="ad-"], [id*="ads-"], [class*="ad-"], [class*="ads-"],
                    [id*="banner"], [class*="banner"], [id*="sponsor"],
                    [class*="sponsor"], [data-ad], [data-ads],
                    iframe[src*="doubleclick"], iframe[src*="exoclick"],
                    iframe[src*="trafficjunky"], iframe[src*="juicyads"],
                    iframe[src*="realsrv"], iframe[src*="tsyndicate"],
                    .adsbygoogle, #ads, .ad-container, .adcontainer,
                    .video-ads, .ad-overlay, .adblock-modal, .pop-up-ad {
                      display: none !important;
                      visibility: hidden !important;
                      height: 0 !important;
                      width: 0 !important;
                      opacity: 0 !important;
                      pointer-events: none !important;
                    }
                  `;
                  (document.head || document.documentElement).appendChild(style);
                }
              } catch(e) {}
            })();
        """
    }

    private fun shouldBlock(url: String): Boolean {
        val lower = url.lowercase()
        // Fast path patterns
        for (p in BLOCKED_PATH_PATTERNS) {
            if (lower.contains(p)) return true
        }

        try {
            val host = java.net.URI(url).host?.lowercase() ?: return false
            hostCache[host]?.let { return it }

            val blocked = BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
            hostCache[host] = blocked
            return blocked
        } catch (_: Exception) {
            return false
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
        if (shouldBlock(url)) {
            return emptyResponse()
        }
        return super.shouldInterceptRequest(view, request)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        if (url != null && shouldBlock(url)) return emptyResponse()
        return super.shouldInterceptRequest(view, url)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted?.invoke(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished?.invoke(url)
        // Inject minimal CSS to hide leftover ad containers on common adult sites
        view?.evaluateJavascript(HIDE_ADS_JS, null)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        // Stay inside our WebView
        return false
    }
}
