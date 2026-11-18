package cn.yuntong.companion.data

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.*
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.json.JSONTokener

/** Reads only the old local origin; it never loads a remote page or deletes old storage. */
object LegacyMigration {
    @SuppressLint("SetJavaScriptEnabled")
    fun read(activity: Activity, done: (JSONObject) -> Unit) {
        val web = WebView(activity)
        var finished = false
        fun finish(value: JSONObject) {
            if (finished) return
            finished = true
            done(value)
            web.destroy()
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    WebResourceResponse(
                        "text/html",
                        "UTF-8",
                        ByteArrayInputStream(
                            "<!doctype html><title>Local migration</title>".toByteArray()
                        ),
                    )

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(
                        "JSON.stringify({preferences:localStorage.getItem('yuntong.preferences'),tools:localStorage.getItem('yuntong.local-tools.v1')})"
                    ) { raw ->
                        val value =
                            runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }
                                .getOrNull()
                        if (value != null) finish(value)
                    }
                }
            }
        web.loadUrl("https://app.yuntong.local/migrate.html")
        web.postDelayed(
            {
                if (!finished) {
                    finished = true
                    web.destroy()
                }
            },
            8000,
        )
    }
}
