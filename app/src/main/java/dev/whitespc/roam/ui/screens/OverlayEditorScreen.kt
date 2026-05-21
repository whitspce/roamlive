package dev.whitespc.roam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whitespc.roam.R
import dev.whitespc.roam.storage.Prefs
import dev.whitespc.roam.streaming.overlay.OverlayItem
import dev.whitespc.roam.streaming.overlay.OverlaySource
import dev.whitespc.roam.streaming.overlay.Scene
import dev.whitespc.roam.ui.theme.RoamLive
import java.util.UUID

// The nine anchor presets, as (label, xPercent, yPercent) centre coordinates.
// These values map cleanly through OverlayRenderer.positionToTranslate to the
// matching TranslateTo anchor, and the editor canvas can render an item centred
// at the same coordinates — so editor and broadcast agree.
private val ANCHORS = listOf(
    Triple("TL", 15f, 12f), Triple("T", 50f, 12f), Triple("TR", 85f, 12f),
    Triple("L", 15f, 50f), Triple("C", 50f, 50f), Triple("R", 85f, 50f),
    Triple("BL", 15f, 88f), Triple("B", 50f, 88f), Triple("BR", 85f, 88f),
)

@Composable
fun OverlayEditorScreen(onClose: () -> Unit) {
    val context = LocalContext.current

    // Edits happen on a draft; Save commits, back/Cancel discards.
    var draft by remember { mutableStateOf(Prefs.overlayScene(context)) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    val selected = draft.items.firstOrNull { it.id == selectedId }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            EditorCanvas(scene = draft, selectedId = selectedId)

            OverlayList(
                scene = draft,
                selectedId = selectedId,
                onSelect = { selectedId = it },
                onToggleVisible = { id ->
                    draft = draft.mapItem(id) { it.copy(visible = !it.visible) }
                },
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
            )

            if (selected != null && !selected.locked) {
                SelectedItemControls(
                    item = selected,
                    onChange = { updated -> draft = draft.mapItem(updated.id) { updated } },
                    onDelete = {
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
}

/** Updates the item with [id] via [transform], returning a new Scene. */
private fun Scene.mapItem(id: String, transform: (OverlayItem) -> OverlayItem): Scene =
    copy(items = items.map { if (it.id == id) transform(it) else it })

/** One above the current max zOrder, so a new overlay lands on top (but watermark
 *  stays above everything at zOrder 1000). */
private fun nextZOrder(scene: Scene): Int {
    val nonWatermarkMax = scene.items
        .filter { it.source != OverlaySource.Watermark }
        .maxOfOrNull { it.zOrder } ?: 0
    return (nonWatermarkMax + 1).coerceAtMost(999)
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

/** A 16:9 dark canvas showing where each visible overlay sits. Approximate — the
 *  real broadcast is rendered by OverlayRenderer — but close enough to position by. */
@Composable
private fun EditorCanvas(scene: Scene, selectedId: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            // Dark navy so the canvas (the broadcast frame) reads as distinct from
            // the near-black app background instead of blending into one big box.
            .background(Color(0xFF13243F))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
    ) {
        scene.items
            .filter { it.visible }
            .sortedBy { it.zOrder }
            .forEach { item ->
                val hBias = (item.xPercent / 50f) - 1f
                val vBias = (item.yPercent / 50f) - 1f
                Box(
                    modifier = Modifier
                        .align(BiasAlignment(hBias.coerceIn(-1f, 1f), vBias.coerceIn(-1f, 1f)))
                        .then(
                            if (item.id == selectedId) {
                                Modifier.border(1.5.dp, RoamLive, RoundedCornerShape(3.dp))
                            } else {
                                Modifier
                            },
                        )
                        .padding(2.dp),
                ) {
                    CanvasItem(item)
                }
            }
    }
}

@Composable
private fun CanvasItem(item: OverlayItem) {
    when (val s = item.source) {
        is OverlaySource.Text -> Text(
            text = s.text.ifBlank { "(empty)" },
            color = Color(s.colorArgb),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        OverlaySource.Watermark -> CanvasWatermark()
        is OverlaySource.Image -> Text(
            text = "[image]",
            color = Color.White,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun CanvasWatermark() {
    // Only the watermark uses a bundled drawable in this chunk; user images come later.
    androidx.compose.foundation.Image(
        painter = painterResource(R.drawable.watermark),
        contentDescription = null,
        modifier = Modifier.width(72.dp),
    )
}

@Composable
private fun OverlayList(
    scene: Scene,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onToggleVisible: (String) -> Unit,
    onAddText: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel("Overlays")
        scene.items.sortedByDescending { it.zOrder }.forEach { item ->
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
                    // Locked items (the watermark) can't be hidden — the checkbox
                    // is shown but disabled so it's always-on and non-interactive.
                    onCheckedChange = if (item.locked) null else { _ -> onToggleVisible(item.id) },
                    enabled = !item.locked,
                    colors = CheckboxDefaults.colors(checkedColor = RoamLive),
                )
                Text(
                    text = overlayLabel(item),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                if (item.locked) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Locked",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            onClick = onAddText,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Add text overlay",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

private fun overlayLabel(item: OverlayItem): String = when (val s = item.source) {
    is OverlaySource.Text -> "Text: ${s.text.take(20).ifBlank { "(empty)" }}"
    is OverlaySource.Image -> "Image"
    OverlaySource.Watermark -> "Watermark"
}

@Composable
private fun SelectedItemControls(
    item: OverlayItem,
    onChange: (OverlayItem) -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Selected overlay")

        if (item.source is OverlaySource.Text) {
            OutlinedTextField(
                value = item.source.text,
                onValueChange = { onChange(item.copy(source = item.source.copy(text = it))) },
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
        }

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

/** 3x3 grid of anchor buttons. Picking one snaps the item to that position. */
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
