package com.char_noir.ekd01

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Writes recorded routes as GPX 1.1 (one track per ride) and hands the file to the system share sheet. */
object GpxExport {

    fun build(creator: String, rides: List<RideTrack>): String {
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val label = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder(rides.sumOf { it.points.size } * 120 + 400)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"").append(escape(creator))
            .append("\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (ride in rides) {
            if (ride.points.isEmpty()) continue
            sb.append("  <trk>\n    <name>Ride ").append(label.format(Date(ride.session.connectedAt))).append("</name>\n    <trkseg>\n")
            for (p in ride.points) {
                sb.append("      <trkpt lat=\"").append("%.6f".format(Locale.US, p.lat))
                    .append("\" lon=\"").append("%.6f".format(Locale.US, p.lon)).append("\">")
                p.altM?.let { sb.append("<ele>").append("%.1f".format(Locale.US, it)).append("</ele>") }
                sb.append("<time>").append(time.format(Date(p.ts))).append("</time></trkpt>\n")
            }
            sb.append("    </trkseg>\n  </trk>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** Writes [xml] to the app's own files folder (exports/) and opens the share sheet. Call on the main thread. */
    fun share(activity: Activity, fileName: String, xml: String) {
        val dir = File(activity.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(xml)
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.gps_export_chooser)))
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
