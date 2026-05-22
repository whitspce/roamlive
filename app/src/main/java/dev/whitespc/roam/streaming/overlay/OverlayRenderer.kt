package dev.whitespc.roam.streaming.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.generic.GenericStream
import dev.whitespc.roam.R
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.streaming.WebOverlayController

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
 * Position handling: items store centre coords as percentages. The renderer maps
 * those to RootEncoder's [TranslateTo] enum (a 3×3 grid of anchor points). Web
 * overlays ignore position — they render full-frame.
 */
class OverlayRenderer(
    private val context: Context,
    private val stream: GenericStream,
) {
    private val activeFilters = LinkedHashMap<String, BaseFilterRender>()
    private val webOverlays = LinkedHashMap<String, WebOverlayController>()

    fun applyScene(scene: Scene) {
        clear()
        scene.items
            .filter { it.visible }
            .sortedBy { it.zOrder }
            .forEach { item ->
                val source = item.source
                if (source is OverlaySource.WebPage) {
                    // Skip blank-URL web overlays — no point spinning up a WebView
                    // for an overlay the user added but hasn't pointed anywhere yet.
                    if (source.url.isBlank()) return@forEach
                    val controller = WebOverlayController(context, stream)
                    controller.show(source.url)
                    webOverlays[item.id] = controller
                    return@forEach
                }
                val filter = createFilter(item) ?: return@forEach
                runCatching {
                    stream.getGlInterface().addFilter(filter)
                    activeFilters[item.id] = filter
                }.onFailure { Log.w(TAG, "addFilter failed for ${item.id}", it) }
            }
    }

    fun clear() {
        activeFilters.values.forEach { filter ->
            runCatching { stream.getGlInterface().removeFilter(filter) }
            runCatching { filter.release() }
        }
        activeFilters.clear()
        webOverlays.values.forEach { it.hide() }
        webOverlays.clear()
    }

    private fun createFilter(item: OverlayItem): BaseFilterRender? = when (val s = item.source) {
        is OverlaySource.Text -> createTextFilter(item, s)
        is OverlaySource.Image -> createImageFilter(item, s)
        // Web pages are handled by applyScene before reaching here.
        is OverlaySource.WebPage -> null
        OverlaySource.Watermark -> createWatermarkFilter(item)
    }

    private fun createWatermarkFilter(item: OverlayItem): ImageObjectFilterRender? = runCatching {
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.watermark)
            ?: return@runCatching null
        ImageObjectFilterRender().apply {
            setImage(bitmap)
            setScale(item.widthPercent, item.heightPercent)
            setPosition(positionToTranslate(item.xPercent, item.yPercent))
        }
    }.onFailure { Log.w(TAG, "watermark filter failed", it) }.getOrNull()

    private fun createTextFilter(item: OverlayItem, source: OverlaySource.Text): TextObjectFilterRender? =
        runCatching {
            TextObjectFilterRender().apply {
                setText(source.text, source.fontSizeSp, source.colorArgb)
                setDefaultScale(Prefs.videoWidth(context), Prefs.videoHeight(context))
                setPosition(positionToTranslate(item.xPercent, item.yPercent))
            }
        }.onFailure { Log.w(TAG, "text filter failed", it) }.getOrNull()

    private fun createImageFilter(item: OverlayItem, source: OverlaySource.Image): ImageObjectFilterRender? =
        runCatching {
            val bitmap = BitmapFactory.decodeFile(source.path) ?: return@runCatching null
            ImageObjectFilterRender().apply {
                setImage(bitmap)
                setScale(item.widthPercent, item.heightPercent)
                setPosition(positionToTranslate(item.xPercent, item.yPercent))
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
}
