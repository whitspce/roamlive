package dev.whitespc.roam.streaming.overlay

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "RoamOverlayJson"
private const val SCHEMA_VERSION = 1

/**
 * Hand-rolled JSON for Scene persistence. Hand-rolled rather than pulling in
 * kotlinx.serialization because (a) we only have one set of types to serialise,
 * (b) we already have org.json on the Android platform, (c) avoids adding a
 * dependency for ~80 lines of glue.
 */
object OverlayJson {

    fun toJson(scene: Scene): String {
        val obj = JSONObject().apply {
            put("v", SCHEMA_VERSION)
            put("id", scene.id)
            put("name", scene.name)
            val arr = JSONArray()
            scene.items.forEach { arr.put(itemToJson(it)) }
            put("items", arr)
        }
        return obj.toString()
    }

    fun fromJson(json: String): Scene? = runCatching {
        val obj = JSONObject(json)
        val items = obj.optJSONArray("items") ?: JSONArray()
        Scene(
            id = obj.optString("id", "default"),
            name = obj.optString("name", "Main"),
            items = (0 until items.length()).mapNotNull { i ->
                itemFromJson(items.getJSONObject(i))
            },
        )
    }.onFailure { Log.w(TAG, "fromJson failed", it) }.getOrNull()

    private fun itemToJson(item: OverlayItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("source", sourceToJson(item.source))
        put("name", item.name)
        put("x", item.xPercent.toDouble())
        put("y", item.yPercent.toDouble())
        put("w", item.widthPercent.toDouble())
        put("h", item.heightPercent.toDouble())
        put("z", item.zOrder)
        put("visible", item.visible)
        put("locked", item.locked)
    }

    private fun itemFromJson(obj: JSONObject): OverlayItem? = runCatching {
        OverlayItem(
            id = obj.getString("id"),
            source = sourceFromJson(obj.getJSONObject("source")) ?: return null,
            name = obj.optString("name", ""),
            xPercent = obj.optDouble("x", 50.0).toFloat(),
            yPercent = obj.optDouble("y", 50.0).toFloat(),
            widthPercent = obj.optDouble("w", 30.0).toFloat(),
            heightPercent = obj.optDouble("h", 10.0).toFloat(),
            zOrder = obj.optInt("z", 0),
            visible = obj.optBoolean("visible", true),
            locked = obj.optBoolean("locked", false),
        )
    }.getOrNull()

    private fun sourceToJson(source: OverlaySource): JSONObject = when (source) {
        is OverlaySource.Text -> JSONObject().apply {
            put("type", "text")
            put("text", source.text)
            put("fontSize", source.fontSizeSp.toDouble())
            put("color", source.colorArgb)
        }
        is OverlaySource.Image -> JSONObject().apply {
            put("type", "image")
            put("path", source.path)
            put("aspectRatio", source.aspectRatio.toDouble())
        }
        OverlaySource.Watermark -> JSONObject().apply {
            put("type", "watermark")
        }
    }

    private fun sourceFromJson(obj: JSONObject): OverlaySource? = runCatching {
        when (obj.getString("type")) {
            "text" -> OverlaySource.Text(
                text = obj.optString("text", ""),
                fontSizeSp = obj.optDouble("fontSize", 24.0).toFloat(),
                colorArgb = obj.optInt("color", -0x1),
            )
            "image" -> OverlaySource.Image(
                path = obj.getString("path"),
                aspectRatio = obj.optDouble("aspectRatio", 1.0).toFloat(),
            )
            "watermark" -> OverlaySource.Watermark
            else -> null
        }
    }.getOrNull()
}
