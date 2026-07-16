package me.mavenried.Ryde.util

import android.content.Context
import android.net.Uri
import me.mavenried.Ryde.domain.model.LocationPoint
import me.mavenried.Ryde.domain.model.Route
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GpxExporter {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun exportToUri(context: Context, uri: Uri, route: Route, points: List<LocationPoint>): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    val name = escapeXml(route.name)
                    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                    writer.write("<gpx version=\"1.1\" creator=\"Ryde\"\n")
                    writer.write("     xmlns=\"http://www.topografix.com/GPX/1/1\">\n")

                    writer.write("  <metadata>\n")
                    writer.write("    <name>$name</name>\n")
                    writer.write("    <time>${isoFormat.format(Date(route.startTime))}</time>\n")
                    writer.write("  </metadata>\n")

                    writer.write("  <trk>\n")
                    writer.write("    <name>$name</name>\n")
                    writer.write("    <type>${route.activityType.name}</type>\n")
                    writer.write("    <trkseg>\n")
                    
                    points.forEach { point ->
                        writer.write("      <trkpt lat=\"${point.lat}\" lon=\"${point.lng}\">\n")
                        writer.write("        <ele>${point.altitude}</ele>\n")
                        writer.write("        <time>${isoFormat.format(Date(point.timestamp))}</time>\n")
                        writer.write("      </trkpt>\n")
                    }
                    
                    writer.write("    </trkseg>\n")
                    writer.write("  </trk>\n")
                    writer.write("</gpx>\n")
                }
            }
            true
        } catch (e: Exception) {
            FileLogger.logError(context, "Failed to export GPX", e)
            false
        }
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
