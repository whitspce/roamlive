package dev.whitespc.roam.streaming

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.generic.GenericStream

private const val TAG = "RoamWebOverlay"

// Internal render resolution for the WebView. Rendering at 1080p means overlay
// pages built for a 1920x1080 viewport (the near-universal design size) lay out
// correctly; SurfaceFilterRender downscales the result into the encoder frame.
private const val RENDER_WIDTH = 1920
private const val RENDER_HEIGHT = 1080

/**
 * Renders a web page into the broadcast as an overlay.
 *
 * The bridge, in order: a [SurfaceFilterRender] is added to the GL chain and
 * hands back a [SurfaceTexture]; that texture is wrapped in a [Surface] backing
 * a [VirtualDisplay]; a [Presentation] on that virtual display hosts a
 * [WebView]. The WebView renders, hardware-accelerated, straight into the
 * encoder pipeline. Transparency: the WebView and the Presentation window are
 * both transparent, so only the page's own graphics composite over the video.
 *
 * One controller drives one web overlay. [OverlayRenderer] creates one per
 * WebPage item in the scene. [SafeSurfaceFilter] provides the transient-
 * `updateTexImage` guard for the brief window before the WebView produces frames.
 */
class WebOverlayController(
    private val context: Context,
    private val stream: GenericStream,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var filter: SafeSurfaceFilter? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null

    val isShowing: Boolean get() = filter != null

    /** Show [content]: an http(s) URL, or raw HTML if it starts with '<'. */
    fun show(content: String) {
        if (filter != null) return
        val f = SafeSurfaceFilter(
            object : SurfaceFilterRender.SurfaceReadyCallback {
                // Fires on the GL render thread once the filter is live.
                override fun surfaceReady(surfaceTexture: SurfaceTexture) {
                    runCatching {
                        surfaceTexture.setDefaultBufferSize(RENDER_WIDTH, RENDER_HEIGHT)
                        val surface = Surface(surfaceTexture)
                        // WebView + Presentation must be built on the main thread.
                        mainHandler.post { attachWebView(surface, content) }
                    }.onFailure { Log.w(TAG, "surfaceReady failed", it) }
                }
            },
        )
        f.setPosition(TranslateTo.CENTER)
        f.setScale(100f, 100f)
        runCatching {
            stream.getGlInterface().addFilter(f)
            filter = f
        }.onFailure { Log.w(TAG, "addFilter failed", it) }
    }

    private fun attachWebView(surface: Surface, content: String) {
        runCatching {
            val displayManager =
                context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val vd = displayManager.createVirtualDisplay(
                "RoamWebOverlay",
                RENDER_WIDTH,
                RENDER_HEIGHT,
                DisplayMetrics.DENSITY_DEFAULT,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
            virtualDisplay = vd
            val display = vd?.display ?: run {
                Log.w(TAG, "virtual display created without a Display")
                return
            }

            val pres = Presentation(context, display)
            pres.window?.setBackgroundDrawableResource(android.R.color.transparent)
            val wv = WebView(pres.context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Let overlay pages auto-play audio (TTS, alert sounds) — an
                // overlay has no user gesture, and a WebView blocks autoplay
                // without one by default. This plays sound out the phone
                // speaker. Routing overlay audio into the broadcast is a
                // separate, permission-gated feature and is not built yet.
                settings.mediaPlaybackRequiresUserGesture = false
                // Needed for imported local overlays: load a file:// entry page
                // and its relative asset references (CSS, JS, images).
                settings.allowFileAccess = true
                webViewClient = WebViewClient()
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            pres.setContentView(wv)
            pres.show()

            if (content.trimStart().startsWith("<")) {
                wv.loadDataWithBaseURL(null, content, "text/html", "utf-8", null)
            } else {
                wv.loadUrl(content)
            }
            webView = wv
            presentation = pres
            Log.d(TAG, "web overlay attached")
        }.onFailure { Log.w(TAG, "attachWebView failed", it) }
    }

    fun hide() {
        val f = filter
        filter = null
        // removeFilter is async; SafePipFilter swallows the brief abandoned-texture
        // error if the GL thread draws one more frame before the queue drains.
        runCatching { f?.let { stream.getGlInterface().removeFilter(it) } }
        mainHandler.post {
            runCatching { webView?.destroy() }
            runCatching { presentation?.dismiss() }
            runCatching { virtualDisplay?.release() }
            runCatching { f?.release() }
            webView = null
            presentation = null
            virtualDisplay = null
        }
    }
}
