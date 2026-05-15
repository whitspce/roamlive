package dev.whitespc.roam.ui.system

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

data class ThermalView(val label: String, val color: Color)

private val ThermalOk = ThermalView("OK", Color(0xFF53FC18))
private val ThermalWarm = ThermalView("WARM", Color(0xFFB4E83A))
private val ThermalHot = ThermalView("HOT", Color(0xFFE8B43A))
private val ThermalTooHot = ThermalView("TOO HOT", Color(0xFFE85A2C))
private val ThermalCritical = ThermalView("CRITICAL", Color(0xFFFF2D2D))

@Composable
fun rememberDeviceStatus(): DeviceStatusState {
    val context = LocalContext.current
    var battery by remember { mutableIntStateOf(readBattery(context)) }
    var thermal by remember { mutableStateOf(readThermal(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            battery = readBattery(context)
            thermal = readThermal(context)
            delay(15_000L)
        }
    }

    return DeviceStatusState(batteryPercent = battery, thermal = thermal)
}

data class DeviceStatusState(
    val batteryPercent: Int,
    val thermal: ThermalView,
)

private fun readBattery(context: Context): Int {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
    return runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
        .getOrDefault(-1)
}

private fun readThermal(context: Context): ThermalView {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalOk
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ?: return ThermalOk
    return when (pm.currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalOk
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalWarm
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalHot
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalTooHot
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalCritical
        else -> ThermalOk
    }
}
