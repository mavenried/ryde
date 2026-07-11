package me.mavenried.Ryde.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import me.mavenried.Ryde.ui.theme.LocalIsDarkTheme
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch
import me.mavenried.Ryde.domain.model.ActivityType
import me.mavenried.Ryde.domain.model.LocationPoint
import me.mavenried.Ryde.ui.viewmodel.DestinationPoint

private fun speedColor(speedMs: Float, activityType: ActivityType): Color {
    val (slowMs, fastMs) = when (activityType) {
        ActivityType.RUNNING -> 2f to 4.5f
        ActivityType.CYCLING -> 3f to 9f
        ActivityType.WALKING -> 0.8f to 2f
    }
    val t = ((speedMs - slowMs) / (fastMs - slowMs)).coerceIn(0f, 1f)
    val r: Int; val g: Int; val b: Int
    if (t < 0.5f) {
        val s = t * 2f
        r = lerpInt(0x29, 0x00, s); g = lerpInt(0x79, 0xE6, s); b = lerpInt(0xFF, 0x76, s)
    } else {
        val s = (t - 0.5f) * 2f
        r = lerpInt(0x00, 0xFF, s); g = lerpInt(0xE6, 0x17, s); b = lerpInt(0x76, 0x44, s)
    }
    return Color(red = r / 255f, green = g / 255f, blue = b / 255f)
}

private fun lerpInt(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt().coerceIn(0, 255)

private fun lerp(a: Double, b: Double, t: Float) = a + (b - a) * t

private fun lerpColor(c1: Color, c2: Color, t: Float) = Color(
    red = c1.red + (c2.red - c1.red) * t,
    green = c1.green + (c2.green - c1.green) * t,
    blue = c1.blue + (c2.blue - c1.blue) * t
)

// Level-of-detail bucketed by zoom: at low zoom the route collapses to a few
// screen pixels, so both extra gradient sub-segments and every raw GPS vertex
// are imperceptible — they only add clutter and polyline-cap seam artifacts.
// simplifyEpsilonDeg is a Douglas-Peucker tolerance in degrees (~111km/degree).
private data class TrackingMapLod(val subdivisions: Int, val simplifyEpsilonDeg: Double)

private fun lodForZoom(zoom: Float): TrackingMapLod = when {
    zoom >= 17f -> TrackingMapLod(subdivisions = 4, simplifyEpsilonDeg = 0.0)
    zoom >= 15f -> TrackingMapLod(subdivisions = 2, simplifyEpsilonDeg = 0.00002)
    zoom >= 13f -> TrackingMapLod(subdivisions = 1, simplifyEpsilonDeg = 0.00008)
    zoom >= 11f -> TrackingMapLod(subdivisions = 1, simplifyEpsilonDeg = 0.0003)
    else -> TrackingMapLod(subdivisions = 1, simplifyEpsilonDeg = 0.001)
}

private fun perpendicularDistanceDeg(p: LocationPoint, a: LocationPoint, b: LocationPoint): Double {
    val dx = b.lat - a.lat
    val dy = b.lng - a.lng
    if (dx == 0.0 && dy == 0.0) return kotlin.math.hypot(p.lat - a.lat, p.lng - a.lng)
    val t = (((p.lat - a.lat) * dx) + ((p.lng - a.lng) * dy)) / (dx * dx + dy * dy)
    val tc = t.coerceIn(0.0, 1.0)
    return kotlin.math.hypot(p.lat - (a.lat + tc * dx), p.lng - (a.lng + tc * dy))
}

// Iterative Douglas-Peucker so very long routes can't blow the call stack.
private fun simplifyRoute(points: List<LocationPoint>, epsilonDeg: Double): List<LocationPoint> {
    if (epsilonDeg <= 0.0 || points.size < 3) return points
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.size - 1] = true
    val stack = ArrayDeque<IntRange>()
    stack.addLast(0..points.size - 1)
    while (stack.isNotEmpty()) {
        val range = stack.removeLast()
        val start = range.first
        val end = range.last
        if (end - start < 2) continue
        var maxDist = 0.0
        var index = -1
        val a = points[start]
        val b = points[end]
        for (i in start + 1 until end) {
            val d = perpendicularDistanceDeg(points[i], a, b)
            if (d > maxDist) { maxDist = d; index = i }
        }
        if (index != -1 && maxDist > epsilonDeg) {
            keep[index] = true
            stack.addLast(start..index)
            stack.addLast(index..end)
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

@Composable
fun TrackingMapView(
    points: List<LocationPoint>,
    activityType: ActivityType,
    modifier: Modifier = Modifier,
    recenterTrigger: Int = 0,
    overlayRoutes: List<List<LocationPoint>> = emptyList(),
    destination: DestinationPoint? = null,
    onMapClick: ((Double, Double) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val hasLocation = remember {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 17f)
    }

    // Before any tracking points arrive, center on last-known location
    LaunchedEffect(Unit) {
        if (hasLocation) {
            try {
                LocationServices.getFusedLocationProviderClient(context)
                    .lastLocation
                    .addOnSuccessListener { loc ->
                        loc?.let {
                            scope.launch {
                                cameraPositionState.animate(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(it.latitude, it.longitude), 17f
                                    )
                                )
                            }
                        }
                    }
            } catch (_: SecurityException) {}
        }
    }

    // Follow the latest tracking point with heading
    LaunchedEffect(points) {
        if (points.isNotEmpty()) {
            val last = points.last()
            val camPos = CameraPosition.Builder()
                .target(LatLng(last.lat, last.lng))
                .zoom(17f)
                .apply { if (last.bearing != 0f) bearing(last.bearing) }
                .build()
            cameraPositionState.animate(CameraUpdateFactory.newCameraPosition(camPos))
        }
    }

    // Recenter on demand, restoring heading
    LaunchedEffect(recenterTrigger) {
        if (recenterTrigger == 0) return@LaunchedEffect
        val target = points.lastOrNull()
        if (target != null) {
            val camPos = CameraPosition.Builder()
                .target(LatLng(target.lat, target.lng))
                .zoom(17f)
                .apply { if (target.bearing != 0f) bearing(target.bearing) }
                .build()
            cameraPositionState.animate(CameraUpdateFactory.newCameraPosition(camPos))
        } else if (hasLocation) {
            try {
                LocationServices.getFusedLocationProviderClient(context)
                    .lastLocation
                    .addOnSuccessListener { loc ->
                        loc?.let {
                            scope.launch {
                                cameraPositionState.animate(
                                    CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 17f)
                                )
                            }
                        }
                    }
            } catch (_: SecurityException) {}
        }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        onMapClick = { latLng -> onMapClick?.invoke(latLng.latitude, latLng.longitude) },
        mapColorScheme = if (LocalIsDarkTheme.current) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
        properties = MapProperties(
            isMyLocationEnabled = hasLocation
        ),
        uiSettings = MapUiSettings(
            myLocationButtonEnabled = false,
            zoomControlsEnabled = false,
            compassEnabled = false,
            mapToolbarEnabled = false
        )
    ) {
        val lod = lodForZoom(cameraPositionState.position.zoom)

        // Previous route overlays (semi-transparent gray)
        overlayRoutes.forEach { overlayPts ->
            val simplifiedOverlay = remember(overlayPts, lod.simplifyEpsilonDeg) {
                simplifyRoute(overlayPts, lod.simplifyEpsilonDeg)
            }
            for (i in 0 until simplifiedOverlay.size - 1) {
                val p1 = simplifiedOverlay[i]; val p2 = simplifiedOverlay[i + 1]
                Polyline(
                    points = listOf(LatLng(p1.lat, p1.lng), LatLng(p2.lat, p2.lng)),
                    color = Color(0x88888888.toInt()),
                    width = 8f
                )
            }
        }

        // Destination bearing line + marker
        destination?.let { dest ->
            val destLatLng = LatLng(dest.lat, dest.lng)
            if (points.isNotEmpty()) {
                val last = points.last()
                Polyline(
                    points = listOf(LatLng(last.lat, last.lng), destLatLng),
                    color = Color(0xCCFF6B35.toInt()),
                    width = 6f,
                    pattern = listOf(
                        com.google.android.gms.maps.model.Dash(20f),
                        com.google.android.gms.maps.model.Gap(12f)
                    )
                )
            }
            Marker(
                state = MarkerState(position = destLatLng),
                title = dest.label.ifBlank { "Destination" }
            )
        }

        // Active route polyline
        val renderPoints = remember(points, lod.simplifyEpsilonDeg) {
            simplifyRoute(points, lod.simplifyEpsilonDeg)
        }
        val subdivisions = lod.subdivisions
        for (i in 0 until renderPoints.size - 1) {
            val p1 = renderPoints[i]
            val p2 = renderPoints[i + 1]
            if (subdivisions <= 1) {
                Polyline(
                    points = listOf(LatLng(p1.lat, p1.lng), LatLng(p2.lat, p2.lng)),
                    color = speedColor(((p1.speed + p2.speed) / 2f), activityType),
                    width = 14f
                )
                continue
            }
            val color1 = speedColor(p1.speed, activityType)
            val color2 = speedColor(p2.speed, activityType)
            for (s in 0 until subdivisions) {
                val t0 = s / subdivisions.toFloat()
                val t1 = (s + 1) / subdivisions.toFloat()
                Polyline(
                    points = listOf(
                        LatLng(lerp(p1.lat, p2.lat, t0), lerp(p1.lng, p2.lng, t0)),
                        LatLng(lerp(p1.lat, p2.lat, t1), lerp(p1.lng, p2.lng, t1))
                    ),
                    color = lerpColor(color1, color2, (t0 + t1) / 2f),
                    width = 14f
                )
            }
        }
    }
}
