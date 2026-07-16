package me.mavenried.Ryde.util

import android.content.Context
import android.net.Uri
import me.mavenried.Ryde.domain.model.ActivityType
import me.mavenried.Ryde.domain.model.LocationPoint
import me.mavenried.Ryde.domain.model.Route
import java.io.ByteArrayOutputStream

object FitExporter {

    // Seconds between Unix epoch (1970-01-01) and FIT epoch (1989-12-31)
    private const val FIT_EPOCH = 631065600L

    // Base types
    private const val ENUM:   Byte = 0x00
    private const val UINT8:  Byte = 0x02
    private const val UINT16: Byte = 0x84.toByte()
    private const val SINT32: Byte = 0x85.toByte()
    private const val UINT32: Byte = 0x86.toByte()

    fun exportToUri(context: Context, uri: Uri, route: Route, points: List<LocationPoint>): Boolean =
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(buildFit(route, points))
            }
            true
        }.getOrDefault(false)

    fun buildFit(route: Route, points: List<LocationPoint>): ByteArray {
        val data = ByteArrayOutputStream()
        val t0 = (route.startTime / 1000L) - FIT_EPOCH
        val t1 = (route.endTime / 1000L) - FIT_EPOCH
        val elapsedMs = route.endTime - route.startTime
        val hasHr = points.any { it.heartRate != null }

        // Local 0 → Global 0: File ID
        data.def(0, 0,
            f(0, 1, ENUM),   // type
            f(1, 2, UINT16), // manufacturer
            f(2, 2, UINT16), // product
            f(3, 4, UINT32), // serial_number
            f(4, 4, UINT32), // time_created
        )
        data.dat(0) {
            u8(4)                          // type = activity
            u16(0xFF)                      // manufacturer = development
            u16(0)                         // product
            u32(route.id.hashCode())       // serial_number — unique per route
            u32(t0.toInt())
        }

        // Local 1 → Global 20: Record (one per GPS point)
        val recordFields = buildList {
            add(f(253, 4, UINT32)) // timestamp
            add(f(0,   4, SINT32)) // position_lat (semicircles)
            add(f(1,   4, SINT32)) // position_long (semicircles)
            add(f(6,   2, UINT16)) // speed (mm/s)
            add(f(2,   2, UINT16)) // altitude (scaled)
            if (hasHr) add(f(3, 1, UINT8)) // heart_rate (bpm)
        }
        data.def(1, 20, *recordFields.toTypedArray())

        for (pt in points) {
            val ts = (pt.timestamp / 1000L) - FIT_EPOCH
            data.dat(1) {
                u32(ts.toInt())
                u32(semicircles(pt.lat))
                u32(semicircles(pt.lng))
                u16((pt.speed * 1000f).toInt().coerceIn(0, 0xFFFF)) // m/s → mm/s
                u16(altFit(pt.altitude))
                if (hasHr) u8(pt.heartRate?.coerceIn(0, 254) ?: 0xFF)
            }
        }

        // Local 2 → Global 18: Session
        data.def(2, 18,
            f(253, 4, UINT32), // timestamp
            f(2,   4, UINT32), // start_time
            f(7,   4, UINT32), // total_elapsed_time (ms, scale=1000 → s)
            f(5,   4, UINT32), // total_distance (cm, scale=100 → m)
            f(6,   2, UINT16), // total_calories
            f(28,  1, ENUM),   // sport
        )
        data.dat(2) {
            u32(t1.toInt())
            u32(t0.toInt())
            u32(elapsedMs.toInt())
            u32((route.distanceKm * 100_000.0).toInt()) // km→cm
            u16(route.calories.toInt().coerceIn(0, 0xFFFF))
            u8(sport(route.activityType))
        }

        // Local 3 → Global 34: Activity
        data.def(3, 34,
            f(253, 4, UINT32), // timestamp
            f(1,   4, UINT32), // total_timer_time
            f(2,   2, UINT16), // num_sessions
            f(3,   1, ENUM),   // type
            f(4,   1, ENUM),   // event
            f(5,   1, ENUM),   // event_type
        )
        data.dat(3) {
            u32(t1.toInt())
            u32(elapsedMs.toInt())
            u16(1)  // num_sessions
            u8(0)   // type = manual
            u8(26)  // event = activity
            u8(1)   // event_type = stop
        }

        val dataBytes = data.toByteArray()

        val file = ByteArrayOutputStream()

        // 12-byte file header
        file.u8(12)   // header size
        file.u8(0x10) // protocol version 1.0
        file.u16(2132) // profile version 21.32
        file.u32(dataBytes.size)
        file.write(".FIT".toByteArray(Charsets.US_ASCII))
        file.write(dataBytes)

        // File CRC (over header + data)
        val crc = crc16(file.toByteArray())
        file.u16(crc)

        return file.toByteArray()
    }

    // --- helpers ---

    private data class FieldDef(val num: Int, val size: Int, val type: Byte)
    private fun f(num: Int, size: Int, type: Byte) = FieldDef(num, size, type)

    private fun ByteArrayOutputStream.def(local: Int, global: Int, vararg fields: FieldDef) {
        u8(0x40 or local) // definition record header
        u8(0)             // reserved
        u8(0)             // architecture: little-endian
        u16(global)
        u8(fields.size)
        for (fd in fields) {
            u8(fd.num)
            u8(fd.size)
            u8(fd.type.toInt() and 0xFF)
        }
    }

    private fun ByteArrayOutputStream.dat(local: Int, block: ByteArrayOutputStream.() -> Unit) {
        u8(local)
        block()
    }

    private fun ByteArrayOutputStream.u8(v: Int)  { write(v and 0xFF) }
    private fun ByteArrayOutputStream.u16(v: Int) { write(v and 0xFF); write((v ushr 8) and 0xFF) }
    private fun ByteArrayOutputStream.u32(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 24) and 0xFF)
    }

    private fun semicircles(degrees: Double): Int =
        (degrees * (Int.MAX_VALUE.toDouble() / 180.0)).toLong().toInt()

    private fun altFit(altM: Double): Int =
        ((altM + 500.0) * 5.0).toInt().coerceIn(0, 0xFFFE)

    private fun sport(type: ActivityType): Int = when (type) {
        ActivityType.RUNNING  -> 1
        ActivityType.CYCLING  -> 2
        ActivityType.WALKING  -> 11
    }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            val b = byte.toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[b and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(b ushr 4) and 0xF]
        }
        return crc
    }
}
