package dev.whitespc.roam.streaming.overlay

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Looper
import dev.whitespc.roam.diagnostics.RoamLog as Log
import androidx.core.content.ContextCompat
import dev.whitespc.roam.streaming.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "RoamTokenSource"

/** Re-geocode when we've moved further than this from the last lookup, otherwise
 *  reuse the cached city. Keeps the Geocoder traffic low for stationary streams. */
private const val GEOCODE_REFRESH_METRES = 500f

/**
 * Source for live overlay-token values. Cheap values (battery, clock-derived,
 * engine state) are read on demand; expensive ones (compass sensor, GPS) are
 * subscribed lazily and only when the active overlay scene actually uses them,
 * via [activate]. So a user with no compass / GPS overlays pays nothing for
 * those subsystems.
 *
 * GPS uses Android's [LocationManager] directly — not Google Play Services
 * (project rule: no GPS / Firebase). Reverse-geocoding for `{city}` uses
 * Android's stock [Geocoder], which on AOSP-only builds may not return results;
 * tokens silently fall back to the placeholder if so.
 */
class TokenSource(private val context: Context) {

    // --- Live sensor / location samples (volatile: read from the render tick) ---
    @Volatile private var compassDegrees: Float? = null
    @Volatile private var lastLocation: Location? = null
    @Volatile private var lastCity: String? = null

    /** Build a snapshot for the current tick from [state] and the latest
     *  subscription values. [streamUptimeSec] is computed by the engine from its
     *  live-start timestamp. */
    fun snapshot(state: StreamState, streamUptimeSec: Int?): TokenSnapshot {
        val bitrateKbps = (state as? StreamState.Live)
            ?.bitrateBps
            ?.takeIf { it >= 0 }
            ?.let { it / 1000L }
        val loc = lastLocation
        return TokenSnapshot(
            batteryPercent = readBatteryPercent(),
            bitrateKbps = bitrateKbps,
            streamUptimeSec = streamUptimeSec,
            compassDegrees = compassDegrees,
            gpsLat = loc?.latitude,
            gpsLng = loc?.longitude,
            gpsSpeedMs = loc?.takeIf { it.hasSpeed() }?.speed,
            gpsAltitudeM = loc?.takeIf { it.hasAltitude() }?.altitude?.toFloat(),
            gpsHeadingDeg = loc?.takeIf { it.hasBearing() }?.bearing,
            gpsCity = lastCity,
        )
    }

    /** Start/stop the compass and GPS subscriptions to match what the active
     *  scene actually needs. Called by the renderer right after [applyScene]. */
    fun activate(needsCompass: Boolean, needsGps: Boolean) {
        if (needsCompass) startCompass() else stopCompass()
        if (needsGps && hasLocationPermission()) startGps() else stopGps()
    }

    fun release() {
        stopCompass()
        stopGps()
        geocodeScope.cancel()
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    // --- Battery (read on demand; ~free) ---
    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private fun readBatteryPercent(): Int? = runCatching {
        val v = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (v == null || v < 0) null else v
    }.getOrNull()

    // --- Compass (rotation vector sensor; no permission) ---
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor by lazy {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    private var compassListener: SensorEventListener? = null

    private fun startCompass() {
        if (compassListener != null) return
        val sm = sensorManager ?: return
        val sensor = rotationSensor ?: return
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                // orientation[0] is azimuth in radians; map to 0..360°.
                var deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (deg < 0f) deg += 360f
                compassDegrees = deg
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // UI delay is plenty for a 1Hz overlay refresh and is gentle on battery.
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        compassListener = listener
        Log.d(TAG, "compass subscription started")
    }

    private fun stopCompass() {
        compassListener?.let { sensorManager?.unregisterListener(it) }
        compassListener = null
        compassDegrees = null
    }

    // --- GPS (stock LocationManager; permission-gated) ---
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var locationListener: LocationListener? = null

    private fun startGps() {
        if (locationListener != null) return
        val lm = locationManager ?: return
        val listener = LocationListener { loc ->
            lastLocation = loc
            maybeGeocode(loc)
        }
        runCatching {
            // 1Hz updates with no min distance — IRL streams want continuous
            // speed / altitude / position, not "moved 10 m" events.
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        }.onFailure { Log.w(TAG, "GPS start failed", it) }
        locationListener = listener
        Log.d(TAG, "GPS subscription started")
    }

    private fun stopGps() {
        locationListener?.let {
            runCatching { locationManager?.removeUpdates(it) }
        }
        locationListener = null
        lastLocation = null
        lastCity = null
        lastGeocodeLat = null
        lastGeocodeLng = null
    }

    // --- Reverse geocoding for {city} (online; may fail offline) ---
    private val geocodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var geocodeJob: Job? = null
    @Volatile private var lastGeocodeLat: Double? = null
    @Volatile private var lastGeocodeLng: Double? = null
    private val geocoder by lazy {
        runCatching { Geocoder(context, Locale.getDefault()) }.getOrNull()
    }

    private fun maybeGeocode(loc: Location) {
        val gc = geocoder ?: return
        // Skip if we already have a city for a nearby location, or a lookup is
        // already in flight.
        val prevLat = lastGeocodeLat
        val prevLng = lastGeocodeLng
        val moved = prevLat == null || prevLng == null ||
            distanceMeters(prevLat, prevLng, loc.latitude, loc.longitude) > GEOCODE_REFRESH_METRES
        if (!moved && lastCity != null) return
        if (geocodeJob?.isActive == true) return
        geocodeJob = geocodeScope.launch {
            runCatching {
                @Suppress("DEPRECATION") // Callback API is API 33+, sync still works.
                val results = gc.getFromLocation(loc.latitude, loc.longitude, 1)
                val city = results?.firstOrNull()?.let {
                    it.locality ?: it.subAdminArea ?: it.adminArea
                }
                if (city != null) {
                    lastCity = city
                    lastGeocodeLat = loc.latitude
                    lastGeocodeLng = loc.longitude
                }
            }.onFailure { Log.w(TAG, "geocode failed", it) }
        }
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, out)
        return out[0]
    }

    init {
        // Touch the scope so a brand-new instance has a live coroutine context.
        check(geocodeScope.coroutineContext[Job]?.isActive == true)
    }
}
