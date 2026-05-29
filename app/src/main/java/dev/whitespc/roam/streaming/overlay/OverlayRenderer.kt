package dev.whitespc.roam.streaming.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.generic.GenericStream
import dev.whitespc.roam.R
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.streaming.WebOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "RoamOverlayRenderer"

/**
 * Applies a [Scene] to the [GenericStream]'s GL filter chain. Created once per
 * engine, lives until the engine releases. Supports text, image, watermark, and
 * web-page sources; new source types plug in by extending [OverlaySource].
 *
 * Most sources become a single [BaseFilterRender]. Web pages are different —
 * each is a live WebView with its own lifecycle, so they're delegated to a
 * [WebOverlayController] and tracked separately from [activeFilters].
 *
 * Position handling: items store a 0–100 x/y position. User overlays (text,
 * image) are placed precisely via [applyPrecisePosition] (0 = flush start edge,
 * 50 = centred, 100 = flush end edge). The watermark stays on the legacy 3×3
 * anchor snap ([applyPosition]) so the branding can't drift. Web overlays ignore
 * position — they render full-frame.
 */
class OverlayRenderer(
    private val context: Context,
    private val stream: GenericStream,
) {
    private val activeFilters = LinkedHashMap<String, BaseFilterRender>()
    private val webOverlays = LinkedHashMap<String, WebOverlayController>()

    /** Text overlays whose text contains a live token ({time}/{date}), kept so
     *  [refreshLiveText] can re-render them on a timer. */
    private val liveText = LinkedHashMap<String, LiveText>()
    private val tickScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickJob: Job? = null

    fun applyScene(scene: Scene) {
        clear()
        scene.items
            .filter { it.visible }
            .sortedBy { it.zOrder }
            .forEach { item ->
                when (val source = item.source) {
                    is OverlaySource.WebPage -> {
                        // Skip blank-URL web overlays — no WebView for an overlay
                        // the user added but hasn't pointed anywhere yet.
                        if (source.url.isNotBlank()) {
                            val controller = WebOverlayController(context, stream)
                            controller.show(source.url)
                            webOverlays[item.id] = controller
                        }
                    }
                    // Text is configured after addFilter (see addTextFilter).
                    is OverlaySource.Text -> addTextFilter(item, source)
                    else -> {
                        val filter = createFilter(item) ?: return@forEach
                        runCatching {
                            stream.getGlInterface().addFilter(filter)
                            activeFilters[item.id] = filter
                        }.onFailure { Log.w(TAG, "addFilter failed for ${item.id}", it) }
                    }
                }
            }
        if (liveText.isNotEmpty()) startLiveTextTicking()
    }

    fun clear() {
        tickJob?.cancel()
        tickJob = null
        liveText.clear()
        activeFilters.values.forEach { filter ->
            runCatching { stream.getGlInterface().removeFilter(filter) }
            runCatching { filter.release() }
        }
        activeFilters.clear()
        webOverlays.values.forEach { it.hide() }
        webOverlays.clear()
    }

    private fun createFilter(item: OverlayItem): BaseFilterRender? = when (val s = item.source) {
        is OverlaySource.Image -> createImageFilter(item, s)
        // Text and web pages are handled directly by applyScene.
        is OverlaySource.Text -> null
        is OverlaySource.WebPage -> null
        OverlaySource.Watermark -> createWatermarkFilter(item)
    }

    private fun createWatermarkFilter(item: OverlayItem): ImageObjectFilterRender? = runCatching {
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.watermark)
            ?: return@runCatching null
        ImageObjectFilterRender().apply {
            setImage(bitmap)
            setScale(item.widthPercent, item.heightPercent)
            applyPosition(this, item.xPercent, item.yPercent)
        }
    }.onFailure { Log.w(TAG, "watermark filter failed", it) }.getOrNull()

    /**
     * Add a text overlay: create the filter, add it to the chain, THEN configure
     * it. A text object's scale is auto-derived from its rendered bitmap, so
     * [TextObjectFilterRender.setPosition] only computes the correct anchor once
     * the filter is live. Configuring before addFilter leaves the text slightly
     * mis-positioned (and inconsistent with a refreshed one).
     */
    private fun addTextFilter(item: OverlayItem, source: OverlaySource.Text) {
        val filter = TextObjectFilterRender()
        val added = runCatching { stream.getGlInterface().addFilter(filter) }
            .onFailure { Log.w(TAG, "addFilter failed for ${item.id}", it) }
            .isSuccess
        if (!added) return
        activeFilters[item.id] = filter
        applyTextFilter(filter, item, source)
        if (OverlayTokens.hasToken(source.text)) {
            liveText[item.id] = LiveText(filter, item)
        }
    }

    /**
     * Configure a (already-added) text filter: text, scale, position. Used for
     * the initial setup and for every live-token refresh, so both produce the
     * exact same result.
     */
    private fun applyTextFilter(
        filter: TextObjectFilterRender,
        item: OverlayItem,
        source: OverlaySource.Text,
    ) {
        runCatching {
            filter.setText(OverlayTokens.resolve(source.text), source.fontSizeSp, source.colorArgb)
            filter.setDefaultScale(Prefs.videoWidth(context), Prefs.videoHeight(context))
            applyPrecisePosition(filter, item.xPercent, item.yPercent)
        }.onFailure { Log.w(TAG, "text filter config failed", it) }
    }

    private fun createImageFilter(item: OverlayItem, source: OverlaySource.Image): ImageObjectFilterRender? =
        runCatching {
            val bitmap = BitmapFactory.decodeFile(source.path) ?: return@runCatching null
            ImageObjectFilterRender().apply {
                setImage(bitmap)
                setScale(item.widthPercent, item.heightPercent)
                applyPrecisePosition(this, item.xPercent, item.yPercent)
            }
        }.onFailure { Log.w(TAG, "image filter failed", it) }.getOrNull()

    /**
     * Maps a centre-coord percent position to RootEncoder's nearest anchor enum.
     * v1 snaps to a 3×3 grid; precise positioning will use setPosition(x, y) in
     * Phase B when the editor exposes drag/slider controls.
     */
    private fun positionToTranslate(x: Float, y: Float): TranslateTo {
        val col = when {
            x < 33f -> 0
            x > 67f -> 2
            else -> 1
        }
        val row = when {
            y < 33f -> 0
            y > 67f -> 2
            else -> 1
        }
        return when (row to col) {
            0 to 0 -> TranslateTo.TOP_LEFT
            0 to 1 -> TranslateTo.TOP
            0 to 2 -> TranslateTo.TOP_RIGHT
            1 to 0 -> TranslateTo.LEFT
            1 to 1 -> TranslateTo.CENTER
            1 to 2 -> TranslateTo.RIGHT
            2 to 0 -> TranslateTo.BOTTOM_LEFT
            2 to 1 -> TranslateTo.BOTTOM
            2 to 2 -> TranslateTo.BOTTOM_RIGHT
            else -> TranslateTo.CENTER
        }
    }

    /**
     * Position an object filter at the item's anchor.
     *
     * Works around a RootEncoder 2.5.5 bug: `Sprite.translate(CENTER)` sets the Y
     * coordinate from the object's WIDTH (scaleX) instead of its height, so a
     * wide centred object floats above true centre. For CENTER we compute the
     * position explicitly; the other eight anchors are correct in the library.
     */
    private fun applyPosition(filter: BaseObjectFilterRender, xPercent: Float, yPercent: Float) {
        val translate = positionToTranslate(xPercent, yPercent)
        if (translate == TranslateTo.CENTER) {
            val scale = filter.scale
            filter.setPosition(50f - scale.x / 2f, 50f - scale.y / 2f)
        } else {
            filter.setPosition(translate)
        }
    }

    /**
     * Precise free positioning for user overlays (text / image). x and y are a
     * 0–100 position: 0 = flush to the start edge, 50 = centred, 100 = flush to the
     * end edge. We map that onto the object's top-left so it stays fully on-frame at
     * both extremes: top-left = (x/100)·(100 − scaleWidth). Uses the explicit
     * setPosition(float, float), which sidesteps the Sprite.translate(CENTER) bug
     * (that lived only in the enum path). The editor canvas mirrors this exactly
     * with BiasAlignment, so preview and broadcast agree.
     */
    private fun applyPrecisePosition(filter: BaseObjectFilterRender, xPercent: Float, yPercent: Float) {
        runCatching {
            val scale = filter.scale
            val left = (xPercent / 100f) * (100f - scale.x)
            val top = (yPercent / 100f) * (100f - scale.y)
            filter.setPosition(left, top)
        }.onFailure { Log.w(TAG, "precise position failed", it) }
    }

    /** Re-render token-bearing text once a second so {time}/{date} stay current.
     *  [refreshLiveText] only calls setText when the resolved string actually
     *  changed, so a minute-resolution clock re-rasterises about once a minute. */
    private fun startLiveTextTicking() {
        tickJob?.cancel()
        tickJob = tickScope.launch {
            while (isActive) {
                delay(1000)
                refreshLiveText()
            }
        }
    }

    private fun refreshLiveText() {
        liveText.values.forEach { live ->
            val resolved = OverlayTokens.resolve(live.source.text)
            if (resolved != live.lastRendered) {
                // Same config path as the initial setup, so a refreshed overlay
                // lands in exactly the same place as a never-refreshed one.
                applyTextFilter(live.filter, live.item, live.source)
                live.lastRendered = resolved
            }
        }
    }

    /** A text filter carrying a live token. Holds the [item] so a refresh can
     *  re-apply position after setText, and the last string rendered into it so
     *  a tick that resolves to the same value skips the re-render. */
    private class LiveText(
        val filter: TextObjectFilterRender,
        val item: OverlayItem,
    ) {
        val source: OverlaySource.Text = item.source as OverlaySource.Text
        var lastRendered: String = OverlayTokens.resolve(source.text)
    }
}
