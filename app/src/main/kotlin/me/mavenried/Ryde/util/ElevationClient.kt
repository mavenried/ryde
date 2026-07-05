package me.mavenried.Ryde.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up ground elevation from a Digital Elevation Model (DEM) via the free Open-Topo-Data
 * public API (https://www.opentopodata.org), used to correct noisy raw GPS altitude after a ride.
 * Public API limits: 100 locations/request, 1 request/sec, ~1000 requests/day.
 */
object ElevationClient {
    private const val BASE_URL = "https://api.opentopodata.org/v1/aster30m"
    private const val MAX_LOCATIONS_PER_REQUEST = 100
    private const val REQUEST_INTERVAL_MS = 1_100L

    // Returns one elevation (meters) per input coordinate, in the same order.
    suspend fun fetchElevations(coords: List<Pair<Double, Double>>): Result<List<Double>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val elevations = mutableListOf<Double>()
                coords.chunked(MAX_LOCATIONS_PER_REQUEST).forEachIndexed { index, chunk ->
                    if (index > 0) delay(REQUEST_INTERVAL_MS)
                    elevations += fetchChunk(chunk)
                }
                elevations
            }
        }

    private fun fetchChunk(chunk: List<Pair<Double, Double>>): List<Double> {
        val locations = JSONArray()
        chunk.forEach { (lat, lng) ->
            locations.put(JSONObject().put("latitude", lat).put("longitude", lng))
        }
        val body = JSONObject().put("locations", locations).toString()

        val conn = URL(BASE_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        check(code == 200) { "HTTP $code" }

        val response = JSONObject(conn.inputStream.bufferedReader().readText())
        check(response.optString("status") == "OK") { "API status: ${response.optString("status")}" }

        val results = response.getJSONArray("results")
        return (0 until results.length()).map { results.getJSONObject(it).getDouble("elevation") }
    }
}
