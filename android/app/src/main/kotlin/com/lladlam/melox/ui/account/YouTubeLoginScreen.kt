package com.lladlam.melox.ui.account

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.lladlam.melox.core.provider.youtubemusic.YouTubeSession
import com.lladlam.melox.core.provider.youtubemusic.YouTubeSessionStore
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Square-compatible Google WebView login. MeloX stores only the resulting session context.
 *
 * Google hands out no token for YouTube Music, so the only session it recognises is the
 * one its own web player uses: a Google cookie plus the `VISITOR_DATA` and `DATASYNC_ID`
 * the page keeps in `window.yt.config_`. The web view loads Google's real sign-in page and
 * MeloX never sees the credentials, only the cookie that results.
 *
 * `SAPISID` is written for `youtube.com`, not always for `music.youtube.com`, and it can
 * land a moment after the page finishes. Reading a single host the instant the page loads
 * is how a finished login comes back with no cookie.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginScreen(
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var completing by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var visitorData by remember { mutableStateOf("") }
    var dataSyncId by remember { mutableStateOf("") }

    fun complete(abandonIfMissing: Boolean) {
        if (completing) return
        val cookie = youtubeSessionCookie()
        // A music.youtube.com page can finish before Google writes SAPISID.
        // Reading then stores a cookie InnerTube cannot authorize with.
        if (!cookie.contains("SAPISID=")) {
            if (abandonIfMissing) onDismiss()
            return
        }
        completing = true
        scope.launch {
            YouTube.cookie = cookie
            YouTube.visitorData = visitorData
            YouTube.dataSyncId = dataSyncId
            if (visitorData.isBlank()) {
                YouTube.visitorData().onSuccess { fresh ->
                    visitorData = fresh
                    YouTube.visitorData = fresh
                }
            }
            val resolvedVisitorData = visitorData
            val resolvedDataSyncId = dataSyncId
            // Cookie first. accountInfo() only supplies the display name; failing it
            // used to throw the SAPISID session away and look like login never happened.
            val name = YouTube.accountInfo().getOrNull()?.name.orEmpty()
            webView?.apply {
                stopLoading()
                clearHistory()
            }
            YouTubeSessionStore.write(
                context,
                YouTubeSession(
                    cookie = cookie,
                    visitorData = resolvedVisitorData,
                    dataSyncId = resolvedDataSyncId,
                    accountName = name,
                ),
            )
            onLoggedIn()
        }
    }

    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        while (isActive && !completing) {
            view.evaluateJavascript(CONFIG_SCRIPT) { value ->
                decodeJsPair(value)?.let { (visitor, sync) ->
                    if (visitor.isNotBlank()) visitorData = visitor
                    if (sync.isNotBlank()) dataSyncId = sync.substringBefore("||")
                }
            }
            if (youtubeSessionCookie().contains("SAPISID=")) {
                complete(abandonIfMissing = false)
            }
            delay(600)
        }
    }

    BackHandler {
        val view = webView
        // Google's flow is several pages deep, so back should walk it rather than
        // abandoning a sign-in halfway through.
        if (view?.canGoBack() == true) view.goBack() else complete(abandonIfMissing = true)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            // Same injection Square uses. evaluateJavascript cannot call
                            // the interface, and a music page can finish before SAPISID lands.
                            view.loadUrl(VISITOR_SCRIPT)
                            view.loadUrl(DATA_SYNC_SCRIPT)
                        }
                    }
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)
                    cookies.setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun visitorData(value: String?) {
                                if (!value.isNullOrBlank() && value != "null") visitorData = value
                            }

                            @JavascriptInterface
                            fun dataSyncId(value: String?) {
                                // Two ids separated by `||`; the first is this account's.
                                if (!value.isNullOrBlank() && value != "null") {
                                    dataSyncId = value.substringBefore("||")
                                }
                            }
                        },
                        "Square",
                    )
                    webView = this
                    loadUrl(SIGN_IN_URL)
                }
            },
        )
    }
}

/**
 * Google writes the session across several hosts. `getCookie("music.youtube.com")`
 * often returns the anonymous visitor cookie and omits `SAPISID`, which lives on
 * `youtube.com` / `.google.com`. Merge them the same way the web player sends one jar.
 */
internal fun youtubeSessionCookie(): String {
    val manager = CookieManager.getInstance()
    manager.flush()
    val values = linkedMapOf<String, String>()
    COOKIE_URLS.forEach { url ->
        manager.getCookie(url)?.split(';')?.forEach { item ->
            val parts = item.trim().split('=', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                values[parts[0]] = parts[1]
            }
        }
    }
    return values.entries.joinToString("; ") { (key, value) -> "$key=$value" }
}

private fun decodeJsPair(value: String?): Pair<String, String>? {
    val raw = value?.takeIf { it.isNotBlank() && it != "null" } ?: return null
    val decoded = runCatching { JSONArray("[$raw]").getString(0) }.getOrElse { raw.trim('"') }
    if (decoded.isBlank() || decoded == "null") return null
    val parts = decoded.split('\u001f', limit = 2)
    return parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
}

private val COOKIE_URLS = listOf(
    "https://music.youtube.com",
    "https://www.youtube.com",
    "https://youtube.com",
    "https://accounts.google.com",
    "https://google.com",
)

private const val VISITOR_SCRIPT =
    "javascript:Square.visitorData(window.yt && window.yt.config_ && window.yt.config_.VISITOR_DATA)"
private const val DATA_SYNC_SCRIPT =
    "javascript:Square.dataSyncId(window.yt && window.yt.config_ && window.yt.config_.DATASYNC_ID)"
private const val CONFIG_SCRIPT =
    "(function(){var c=window.yt&&window.yt.config_;if(!c)return '';return (c.VISITOR_DATA||'')+'\\u001f'+(c.DATASYNC_ID||'');})()"
private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
