package dev.whitespc.roam.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whitespc.roam.R
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.streaming.overlay.OverlayImageStore
import dev.whitespc.roam.streaming.overlay.OverlayItem
import dev.whitespc.roam.streaming.overlay.OverlaySource
import dev.whitespc.roam.streaming.overlay.Scene
import dev.whitespc.roam.ui.theme.RoamLive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// The nine anchor presets, as (label, xPercent, yPercent) centre coordinates.
private val ANCHORS = listOf(
    Triple("TL", 15f, 12f), Triple("T", 50f, 12f), Triple("TR", 85f, 12f),
    Triple("L", 15f, 50f), Triple("C", 50f, 50f), Triple("R", 85f, 50f),
    Triple("BL", 15f, 88f), Triple("B", 50f, 88f), Triple("BR", 85f, 88f),
)

private val CANVAS_NAVY = Color(0xFF13243F)

// Preset text colours (ARGB). A full colour picker is overkill for v1.
private val TEXT_COLOURS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFF2D2D, 0xFFFFD23A,
    0xFF53FC18, 0xFF3AC8FF, 0xFFFF7A3A, 0xFFB46BFF,
).map { it.toInt() }

private const val TEXT_SIZE_MIN = 12f
private const val TEXT_SIZE_MAX = 144f
private const val IMAGE_SIZE_MIN = 5f
private const val IMAGE_SIZE_MAX = 80f

@Composable
fun OverlayEditorScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf(Prefs.overlayScene(context)) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showWebWarning by remember { mutableStateOf(false) }
    val selected = draft.items.firstOrNull { it.id == selectedId }

    val frameAspect = remember {
        Prefs.videoWidth(context).toFloat() / Prefs.videoHeight(context).coerceAtLeast(1)
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) { OverlayImageStore.importImage(context, uri) }
                ?: return@launch
            val aspect = withContext(Dispatchers.IO) { OverlayImageStore.aspectRatio(path) }
            val width = 25f
            val item = OverlayItem(
                id = UUID.randomUUID().toString(),
                source = OverlaySource.Image(path, aspect),
                xPercent = 50f,
                yPercent = 50f,
                widthPercent = width,
                heightPercent = OverlayImageStore.imageHeightPercent(width, aspect, frameAspect),
                zOrder = nextZOrder(draft),
            )
            draft = draft.copy(items = draft.items + item)
            selectedId = item.id
        }
    }

    // Adds a blank-URL web overlay (full-frame) and selects it for editing.
    fun addWebOverlay() {
        val item = OverlayItem(
            id = UUID.randomUUID().toString(),
            source = OverlaySource.WebPage(""),
            xPercent = 50f,
            yPercent = 50f,
            widthPercent = 100f,
            heightPercent = 100f,
            zOrder = nextZOrder(draft),
        )
        draft = draft.copy(items = draft.items + item)
        selectedId = item.id
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        EditorTopBar(
            onCancel = onClose,
            onSave = {
                Prefs.setOverlayScene(context, draft)
                onClose()
            },
        )
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                EditorCanvas(scene = draft, selectedId = selectedId)
            }
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(end = 20.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OverlayList(
                    scene = draft,
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                    onToggleVisible = { id ->
                        draft = draft.mapItem(id) { it.copy(visible = !it.visible) }
                    },
                    onMove = { id, up -> draft = draft.moveItem(id, up) },
                    onAddText = {
                        val item = OverlayItem(
                            id = UUID.randomUUID().toString(),
                            source = OverlaySource.Text("New text"),
                            xPercent = 50f,
                            yPercent = 50f,
                            widthPercent = 40f,
                            heightPercent = 12f,
                            zOrder = nextZOrder(draft),
                        )
                        draft = draft.copy(items = draft.items + item)
                        selectedId = item.id
                    },
                    onAddImage = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onAddWeb = {
                        // Show the cost notice once, before the first web overlay.
                        if (Prefs.webOverlayWarningSeen(context)) {
                            addWebOverlay()
                        } else {
                            showWebWarning = true
                        }
                    },
                )
                if (selected != null && !selected.locked) {
                    SelectedItemControls(
                        item = selected,
                        frameAspect = frameAspect,
                        onChange = { updated -> draft = draft.mapItem(updated.id) { updated } },
                        onDelete = {
                            (selected.source as? OverlaySource.Image)?.let {
                                OverlayImageStore.deleteImage(it.path)
                            }
                            draft = draft.copy(items = draft.items.filter { it.id != selected.id })
                            selectedId = null
                        },
                    )
                } else if (selected != null && selected.locked) {
                    Text(
                        text = "The watermark is locked and can't be edited.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        if (showWebWarning) {
            WebOverlayWarningDialog(
                onConfirm = {
                    Prefs.setWebOverlayWarningSeen(context)
                    showWebWarning = false
                    addWebOverlay()
                },
                onDismiss = { showWebWarning = false },
            )
        }
    }
}

private fun Scene.mapItem(id: String, transform: (OverlayItem) -> OverlayItem): Scene =
    copy(items = items.map { if (it.id == id) transform(it) else it })

private fun nextZOrder(scene: Scene): Int {
    val nonWatermarkMax = scene.items
        .filter { it.source != OverlaySource.Watermark }
        .maxOfOrNull { it.zOrder } ?: 0
    return (nonWatermarkMax + 1).coerceAtMost(999)
}

/**
 * Swap an item's z-order with its neighbour, moving it [up] (toward the top
 * layer) or down. Only non-watermark items reorder — the watermark stays
 * pinned above everything. Returns the scene unchanged if the move isn't valid.
 */
private fun Scene.moveItem(id: String, up: Boolean): Scene {
    // List is shown top-layer-first, so "up" means a higher zOrder.
    val ordered = items
        .filter { it.source != OverlaySource.Watermark }
        .sortedByDescending { it.zOrder }
    val idx = ordered.indexOfFirst { it.id == id }
    if (idx < 0) return this
    val swapIdx = if (up) idx - 1 else idx + 1
    if (swapIdx !in ordered.indices) return this
    val a = ordered[idx]
    val b = ordered[swapIdx]
    return copy(
        items = items.map {
            when (it.id) {
                a.id -> it.copy(zOrder = b.zOrder)
                b.id -> it.copy(zOrder = a.zOrder)
                else -> it
            }
        },
    )
}

@Composable
private fun EditorTopBar(onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Cancel",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "Overlays",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Surface(
            onClick = onSave,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary,
        ) {
            Text(
                text = "Save",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun EditorCanvas(scene: Scene, selectedId: String?) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val containerRatio = maxWidth.value / maxHeight.value
        val canvasWidth: Dp
        val canvasHeight: Dp
        if (containerRatio > 16f / 9f) {
            canvasHeight = maxHeight
            canvasWidth = maxHeight * (16f / 9f)
        } else {
            canvasWidth = maxWidth
            canvasHeight = maxWidth * (9f / 16f)
        }
        Box(
            modifier = Modifier
                .size(canvasWidth, canvasHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(CANVAS_NAVY)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(6.dp),
        ) {
            scene.items
                .filter { it.visible }
                .sortedBy { it.zOrder }
                .forEach { item ->
                    Box(
                        modifier = Modifier
                            .align(positionToAlignment(item.xPercent, item.yPercent))
                            .then(
                                if (item.id == selectedId) {
                                    Modifier.border(1.5.dp, RoamLive, RoundedCornerShape(3.dp))
                                } else {
                                    Modifier
                                },
                            )
                            .padding(2.dp),
                    ) {
                        CanvasItem(item, canvasWidth, canvasHeight)
                    }
                }
        }
    }
}

/** Maps a centre-coord percent position to the Compose Alignment that mirrors
 *  RootEncoder's TranslateTo grid — keep in lockstep with the renderer. */
private fun positionToAlignment(x: Float, y: Float): Alignment {
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
        0 to 0 -> Alignment.TopStart
        0 to 1 -> Alignment.TopCenter
        0 to 2 -> Alignment.TopEnd
        1 to 0 -> Alignment.CenterStart
        1 to 1 -> Alignment.Center
        1 to 2 -> Alignment.CenterEnd
        2 to 0 -> Alignment.BottomStart
        2 to 1 -> Alignment.BottomCenter
        2 to 2 -> Alignment.BottomEnd
        else -> Alignment.Center
    }
}

@Composable
private fun CanvasItem(item: OverlayItem, canvasWidth: Dp, canvasHeight: Dp) {
    when (val s = item.source) {
        is OverlaySource.Text -> {
            // Scale the editor's text to the canvas the same way the broadcast
            // scales it to a 720-tall frame, so the preview is proportionate.
            val previewSp = (s.fontSizeSp * (canvasHeight.value / 720f)).coerceAtLeast(6f)
            Text(
                text = s.text.ifBlank { "(empty)" },
                color = Color(s.colorArgb),
                fontSize = previewSp.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        OverlaySource.Watermark -> CanvasWatermark(canvasWidth, item.widthPercent)
        is OverlaySource.Image -> CanvasImage(s.path, canvasWidth, item.widthPercent)
        is OverlaySource.WebPage -> CanvasWebPlaceholder(canvasWidth, canvasHeight)
    }
}

/** Web overlays render full-frame and the editor can't show the live page, so
 *  the canvas shows a labelled placeholder filling the frame. */
@Composable
private fun CanvasWebPlaceholder(canvasWidth: Dp, canvasHeight: Dp) {
    Box(
        modifier = Modifier
            .size(canvasWidth - 24.dp, canvasHeight - 24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(RoamLive.copy(alpha = 0.12f))
            .border(1.5.dp, RoamLive, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = null,
                tint = RoamLive,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "Web overlay", color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CanvasWatermark(canvasWidth: Dp, widthPercent: Float) {
    androidx.compose.foundation.Image(
        painter = painterResource(R.drawable.watermark),
        contentDescription = null,
        modifier = Modifier.width(canvasWidth * (widthPercent / 100f)),
    )
}

@Composable
private fun CanvasImage(path: String, canvasWidth: Dp, widthPercent: Float) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.width(canvasWidth * (widthPercent / 100f)),
        )
    } else {
        Text(text = "[image missing]", color = Color.White, fontSize = 10.sp)
    }
}

@Composable
private fun OverlayList(
    scene: Scene,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onToggleVisible: (String) -> Unit,
    onMove: (String, Boolean) -> Unit,
    onAddText: () -> Unit,
    onAddImage: () -> Unit,
    onAddWeb: () -> Unit,
) {
    val rows = scene.items.sortedByDescending { it.zOrder }
    // Reorderable subset, in the same top-first order as the visible list.
    val reorderable = rows.filter { it.source != OverlaySource.Watermark }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel("Overlays")
        rows.forEach { item ->
            val reorderIdx = reorderable.indexOfFirst { it.id == item.id }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (item.id == selectedId) {
                            Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(item.id) }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            ) {
                Checkbox(
                    checked = item.visible,
                    onCheckedChange = if (item.locked) null else { _ -> onToggleVisible(item.id) },
                    enabled = !item.locked,
                    colors = CheckboxDefaults.colors(checkedColor = RoamLive),
                )
                Text(
                    text = overlayLabel(item),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.locked) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Locked",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    ReorderArrow(
                        icon = Icons.Filled.KeyboardArrowUp,
                        description = "Move up a layer",
                        enabled = reorderIdx > 0,
                        onClick = { onMove(item.id, true) },
                    )
                    ReorderArrow(
                        icon = Icons.Filled.KeyboardArrowDown,
                        description = "Move down a layer",
                        enabled = reorderIdx < reorderable.lastIndex,
                        onClick = { onMove(item.id, false) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddButton(
                label = "Text",
                icon = Icons.Filled.TextFields,
                onClick = onAddText,
                modifier = Modifier.weight(1f),
            )
            AddButton(
                label = "Image",
                icon = Icons.Filled.Image,
                onClick = onAddImage,
                modifier = Modifier.weight(1f),
            )
            AddButton(
                label = "Web",
                icon = Icons.Filled.Language,
                onClick = onAddWeb,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReorderArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun AddButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
            )
        }
    }
}

private fun overlayLabel(item: OverlayItem): String {
    if (item.name.isNotBlank()) return item.name
    return when (val s = item.source) {
        is OverlaySource.Text -> "Text: ${s.text.take(18).ifBlank { "(empty)" }}"
        is OverlaySource.Image -> "Image"
        is OverlaySource.WebPage -> "Web: ${s.url.ifBlank { "(no URL)" }}"
        OverlaySource.Watermark -> "Watermark"
    }
}

@Composable
private fun SelectedItemControls(
    item: OverlayItem,
    frameAspect: Float,
    onChange: (OverlayItem) -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Selected overlay")

        OutlinedTextField(
            value = item.name,
            onValueChange = { onChange(item.copy(name = it)) },
            label = { Text("Name") },
            placeholder = {
                Text("Name (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        when (val s = item.source) {
            is OverlaySource.Text -> {
                OutlinedTextField(
                    value = s.text,
                    onValueChange = { onChange(item.copy(source = s.copy(text = it))) },
                    label = { Text("Text") },
                    placeholder = {
                        Text("Overlay text", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledSlider(
                    label = "Text size",
                    value = s.fontSizeSp,
                    range = TEXT_SIZE_MIN..TEXT_SIZE_MAX,
                    onChange = { onChange(item.copy(source = s.copy(fontSizeSp = it))) },
                )
                ColourPicker(
                    selected = s.colorArgb,
                    onPick = { onChange(item.copy(source = s.copy(colorArgb = it))) },
                )
            }
            is OverlaySource.Image -> {
                LabeledSlider(
                    label = "Size",
                    value = item.widthPercent,
                    range = IMAGE_SIZE_MIN..IMAGE_SIZE_MAX,
                    onChange = { newWidth ->
                        onChange(
                            item.copy(
                                widthPercent = newWidth,
                                heightPercent = OverlayImageStore.imageHeightPercent(
                                    newWidth, s.aspectRatio, frameAspect,
                                ),
                            ),
                        )
                    },
                )
            }
            is OverlaySource.WebPage -> {
                OutlinedTextField(
                    value = s.url,
                    onValueChange = { onChange(item.copy(source = s.copy(url = it))) },
                    label = { Text("URL") },
                    placeholder = {
                        Text("https://...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Web overlays fill the whole frame and run a live page. " +
                        "They use more battery than text or image overlays.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            OverlaySource.Watermark -> Unit
        }

        if (item.source !is OverlaySource.WebPage) {
            Text(
                text = "Position",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            AnchorGrid(
                current = item.xPercent to item.yPercent,
                onPick = { (x, y) -> onChange(item.copy(xPercent = x, yPercent = y)) },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            onClick = onDelete,
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(1.dp, RoamLive),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = RoamLive,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Delete overlay", color = RoamLive, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            text = "$label: ${value.toInt()}",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = RoamLive,
                activeTrackColor = RoamLive,
            ),
        )
    }
}

@Composable
private fun ColourPicker(selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Colour",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TEXT_COLOURS.forEach { argb ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (argb == selected) 2.5.dp else 1.dp,
                            color = if (argb == selected) RoamLive else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable { onPick(argb) },
                )
            }
        }
    }
}

@Composable
private fun AnchorGrid(
    current: Pair<Float, Float>,
    onPick: (Pair<Float, Float>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ANCHORS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (label, x, y) ->
                    val isCurrent = current.first == x && current.second == y
                    Surface(
                        onClick = { onPick(x to y) },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isCurrent) RoamLive else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(54.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = label,
                                color = if (isCurrent) Color.White else MaterialTheme.colorScheme.onBackground,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
    )
}

/** One-time notice shown before the user's first web overlay, so the battery
 *  and heat cost is an informed choice rather than a surprise. */
@Composable
private fun WebOverlayWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Web overlays use more power") },
        text = {
            Text(
                "A web overlay runs a live web page, so it uses more battery and " +
                    "heats your phone faster than text or image overlays.\n\n" +
                    "For a logo, frame, or any static graphic, use an Image overlay. " +
                    "For a title or fixed text, use a Text overlay. Both are far " +
                    "lighter.\n\n" +
                    "Add a web overlay only for what those can't do: alerts, chat, " +
                    "follower goals, or other live web content.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Add web overlay") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
