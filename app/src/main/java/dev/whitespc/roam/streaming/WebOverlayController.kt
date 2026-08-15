package dev.whitespc.roam.streaming

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.generic.GenericStream
import dev.whitespc.roam.diagnostics.RoamLog as Log
import dev.whitespc.roam.storage.resolveArchiveFile
import dev.whitespc.roam.streaming.overlay.LocalWebOverlay
import dev.whitespc.roam.streaming.overlay.OverlayWebStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale

private const val TAG = "RoamWebOverlay"
private const val RENDER_WIDTH = 1920
private const val RENDER_HEIGHT = 1080
private const val LOCAL_HOST_SUFFIX = ".roam-overlay.invalid"

/** Renders one HTTPS page or app-managed HTML bundle into the GL filter chain. */
class WebOverlayController(
    private val context: Context,
    private val stream: GenericStream,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()

    @Volatile
    private var activeSession: Session? = null

    val isShowing: Boolean get() = activeSession != null

    fun show(content: String) {
        val source = resolveSource(content) ?: run {
            Log.w(TAG, "web overlay rejected: use HTTPS or an imported local bundle")
            return
        }
        synchronized(lifecycleLock) {
            if (activeSession != null) return
        }

        lateinit var session: Session
        val filter = SafeSurfaceFilter(
            object : SurfaceFilterRender.SurfaceReadyCallback {
                override fun surfaceReady(surfaceTexture: SurfaceTexture) {
                    onSurfaceReady(session, surfaceTexture)
                }
            },
        ).apply {
            setPosition(TranslateTo.CENTER)
            setScale(100f, 100f)
        }
        session = Session(filter, source)
        synchronized(lifecycleLock) {
            if (activeSession != null) {
                filter.release()
                return
            }
            activeSession = session
        }
        runCatching { stream.getGlInterface().addFilter(filter) }
            .onFailure {
                Log.w(TAG, "addFilter failed", it)
                close(session, removeFilter = false)
            }
    }

    private fun onSurfaceReady(session: Session, surfaceTexture: SurfaceTexture) {
        val surface = runCatching {
            surfaceTexture.setDefaultBufferSize(RENDER_WIDTH, RENDER_HEIGHT)
            Surface(surfaceTexture)
        }.getOrElse {
            Log.w(TAG, "surface setup failed", it)
            close(session)
            return
        }

        val attach = Runnable { attachWebView(session) }
        synchronized(lifecycleLock) {
            if (activeSession !== session || session.closed || session.surface != null) {
                surface.release()
                return
            }
            session.surface = surface
            session.attachRunnable = attach
        }
        if (!mainHandler.post(attach)) close(session)
    }

    private fun attachWebView(session: Session) {
        synchronized(lifecycleLock) {
            session.attachRunnable = null
            if (activeSession !== session || session.closed) return
        }

        runCatching {
            val surface = synchronized(lifecycleLock) {
                requireNotNull(session.surface) { "render surface disappeared" }
            }
            val displayManager =
                context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val virtualDisplay = requireNotNull(
                displayManager.createVirtualDisplay(
                    "RoamWebOverlay",
                    RENDER_WIDTH,
                    RENDER_HEIGHT,
                    DisplayMetrics.DENSITY_DEFAULT,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                ),
            ) { "could not create virtual display" }
            synchronized(lifecycleLock) { session.virtualDisplay = virtualDisplay }
            val display = requireNotNull(virtualDisplay.display) {
                "virtual display has no display"
            }
            val presentation = Presentation(context, display)
            synchronized(lifecycleLock) { session.presentation = presentation }
            presentation.window?.setBackgroundDrawableResource(android.R.color.transparent)
            val webView = buildWebView(presentation.context, session.source)
            synchronized(lifecycleLock) { session.webView = webView }
            presentation.setContentView(webView)
            presentation.show()

            synchronized(lifecycleLock) {
                if (activeSession !== session || session.closed) return@runCatching
            }
            when (val source = session.source) {
                is Source.Remote -> webView.loadUrl(source.url)
                is Source.Local -> webView.loadUrl(localEntryUrl(source))
            }
            Log.d(TAG, "web overlay attached")
        }.onFailure {
            Log.w(TAG, "web overlay attachment failed", it)
            close(session)
        }
        synchronized(lifecycleLock) {
            if (activeSession !== session || session.closed) {
                mainHandler.post { cleanUp(session) }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // Live browser overlays require JavaScript.
    private fun buildWebView(webContext: Context, source: Source): WebView =
        WebView(webContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false)
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = RestrictedWebViewClient(source)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

    fun hide() {
        activeSession?.let { close(it) }
    }

    private fun close(session: Session, removeFilter: Boolean = true) {
        val attachRunnable: Runnable?
        synchronized(lifecycleLock) {
            if (session.closed) return
            session.closed = true
            if (activeSession === session) activeSession = null
            attachRunnable = session.attachRunnable
            session.attachRunnable = null
        }
        attachRunnable?.let(mainHandler::removeCallbacks)
        if (removeFilter) {
            runCatching { stream.getGlInterface().removeFilter(session.filter) }
                .onFailure { Log.w(TAG, "removeFilter failed", it) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanUp(session)
        } else {
            mainHandler.post { cleanUp(session) }
        }
    }

    private fun cleanUp(session: Session) {
        val resources = synchronized(lifecycleLock) {
            if (session.cleaned) return
            session.cleaned = true
            Resources(
                webView = session.webView.also { session.webView = null },
                presentation = session.presentation.also { session.presentation = null },
                virtualDisplay = session.virtualDisplay.also { session.virtualDisplay = null },
                surface = session.surface.also { session.surface = null },
            )
        }
        resources.webView?.let { webView ->
            runCatching { webView.stopLoading() }
            runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
            runCatching { webView.removeAllViews() }
            runCatching { webView.destroy() }
        }
        runCatching { resources.presentation?.dismiss() }
        runCatching { resources.virtualDisplay?.release() }
        runCatching { resources.surface?.release() }
        runCatching { session.filter.release() }
    }

    private fun resolveSource(content: String): Source? {
        val value = content.trim()
        OverlayWebStore.resolveLocal(context, value)?.let { return Source.Local(it) }
        return value.takeIf(OverlayWebStore::isSafeHttpsUrl)?.let(Source::Remote)
    }

    private fun localEntryUrl(source: Source.Local): String {
        val id = source.overlay.relativePath.substringBefore('/')
        val relative = source.overlay.entry
            .relativeTo(source.overlay.root)
            .invariantSeparatorsPath
        return Uri.Builder()
            .scheme("https")
            .authority(id + LOCAL_HOST_SUFFIX)
            .apply { relative.split('/').forEach(::appendPath) }
            .build()
            .toString()
    }

    private inner class RestrictedWebViewClient(
        private val source: Source,
    ) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
            !isAllowedNavigation(request.url)

        @Deprecated("Called by older WebView implementations")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
            url == null || !isAllowedNavigation(Uri.parse(url))

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val local = source as? Source.Local ?: return null
            val expectedHost = local.overlay.relativePath.substringBefore('/') + LOCAL_HOST_SUFFIX
            if (!request.url.scheme.equals("https", true) ||
                !request.url.host.equals(expectedHost, true)
            ) return null
            return localResponse(local.overlay, request.url)
        }

        private fun isAllowedNavigation(uri: Uri): Boolean {
            val local = source as? Source.Local
            if (local != null) {
                val expectedHost =
                    local.overlay.relativePath.substringBefore('/') + LOCAL_HOST_SUFFIX
                if (uri.scheme.equals("https", true) && uri.host.equals(expectedHost, true)) {
                    return true
                }
            }
            return OverlayWebStore.isSafeHttpsUrl(uri.toString())
        }
    }

    private fun localResponse(overlay: LocalWebOverlay, uri: Uri): WebResourceResponse =
        runCatching {
            val relative = uri.path.orEmpty().removePrefix("/")
            require(relative.isNotBlank())
            val file = resolveArchiveFile(overlay.root, relative)
            require(file.isFile && file.path.startsWith(overlay.root.path + File.separator))
            val extension = file.extension.lowercase(Locale.ROOT)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
            val encoding = if (mimeType.startsWith("text/") ||
                mimeType in TEXT_APPLICATION_MIME_TYPES
            ) "utf-8" else null
            WebResourceResponse(mimeType, encoding, FileInputStream(file))
        }.getOrElse {
            WebResourceResponse(
                "text/plain",
                "utf-8",
                404,
                "Not Found",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(ByteArray(0)),
            )
        }

    private sealed interface Source {
        data class Remote(val url: String) : Source
        data class Local(val overlay: LocalWebOverlay) : Source
    }

    private class Session(
        val filter: SafeSurfaceFilter,
        val source: Source,
    ) {
        var closed = false
        var cleaned = false
        var attachRunnable: Runnable? = null
        var surface: Surface? = null
        var virtualDisplay: VirtualDisplay? = null
        var presentation: Presentation? = null
        var webView: WebView? = null
    }

    private data class Resources(
        val webView: WebView?,
        val presentation: Presentation?,
        val virtualDisplay: VirtualDisplay?,
        val surface: Surface?,
    )

    private companion object {
        val TEXT_APPLICATION_MIME_TYPES = setOf(
            "application/javascript",
            "application/json",
            "application/xml",
            "image/svg+xml",
        )
    }
}
