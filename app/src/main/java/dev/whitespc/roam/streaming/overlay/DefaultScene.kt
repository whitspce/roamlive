package dev.whitespc.roam.streaming.overlay

/**
 * The default scene every install starts with. Just the locked Roam watermark in
 * the bottom-right corner — same visual as before the overlay system existed,
 * now driven through the new pipeline.
 */
fun defaultScene(): Scene = Scene(
    id = "default",
    name = "Main",
    items = listOf(
        OverlayItem(
            id = "watermark",
            source = OverlaySource.Watermark,
            // Centre coords roughly in the bottom-right quadrant. The renderer
            // maps these to TranslateTo.BOTTOM_RIGHT in v1 — a future editor
            // will use these as exact float positions instead.
            xPercent = 86f,
            yPercent = 96.5f,
            widthPercent = 28f,
            heightPercent = 7f,
            zOrder = 1000,  // Always on top so BRB / camera-off / future overlays
                            // can't accidentally hide the brand mark.
            locked = true,
        ),
    ),
)
