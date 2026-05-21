package dev.whitespc.roam.streaming.overlay

/**
 * A saved overlay configuration. v1 ships with a single always-active scene; the
 * data model supports multiple named scenes so v2 can add scene switching at
 * runtime ("Going Live", "Driving", "BRB", "Outro", etc.) without a migration.
 */
data class Scene(
    val id: String,
    val name: String,
    val items: List<OverlayItem>,
)

/**
 * A single overlay on top of the broadcast. Position and size are in percent of
 * the encoder frame so they scale across resolutions. [zOrder] determines render
 * order — higher draws on top. [locked] items are mandatory (e.g., watermark)
 * and the editor refuses to delete or move them.
 */
data class OverlayItem(
    val id: String,
    val source: OverlaySource,
    /** User-given name shown in the editor list. Blank falls back to a label
     *  derived from the source type. */
    val name: String = "",
    val xPercent: Float = 50f,
    val yPercent: Float = 50f,
    val widthPercent: Float = 30f,
    val heightPercent: Float = 10f,
    val zOrder: Int = 0,
    val visible: Boolean = true,
    val locked: Boolean = false,
)

/**
 * What an overlay actually shows. Sealed so the renderer can pattern-match on
 * known types; new source types (HTML, live widgets, camera PiP) are added by
 * extending this hierarchy and updating the renderer + serialiser.
 */
sealed interface OverlaySource {
    data class Text(
        val text: String,
        val fontSizeSp: Float = 24f,
        val colorArgb: Int = -0x1,  // white
    ) : OverlaySource

    /** A user-imported image. [path] is in app-private storage; [aspectRatio] is
     *  the source image's width/height, kept so the overlay's height can be
     *  derived from its width without re-decoding the file. */
    data class Image(
        val path: String,
        val aspectRatio: Float = 1f,
    ) : OverlaySource

    /** The Roam Live watermark. Renderer loads the bundled drawable; no user-
     *  configurable content. */
    data object Watermark : OverlaySource
}
