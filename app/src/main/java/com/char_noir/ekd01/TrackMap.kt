package com.char_noir.ekd01

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewGroup
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/** Draws recorded routes on an OpenStreetMap view (osmdroid). Only used when "Show map" is on. */
object TrackMap {

    /** One colour per ride, repeated when a day has more rides than colours. */
    val COLORS = intArrayOf(
        0xFFF57C00.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt(),
        0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(), 0xFF6D4C41.toInt(), 0xFFFDD835.toInt()
    )

    /** Tile source for a style from [GpsTracker.mapStyle]. Each has its own name, so the tile cache never mixes them. */
    private fun tileSource(style: Int): ITileSource = when (style) {
        1 -> XYTileSource(
            "CyclOSM", 0, 19, 256, ".png",
            arrayOf("https://a.tile-cyclosm.openstreetmap.fr/cyclosm/", "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/", "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/"),
            "© OpenStreetMap contributors, © CyclOSM"
        )
        2 -> XYTileSource(
            "CartoDarkMatter", 0, 19, 256, ".png",
            arrayOf("https://a.basemaps.cartocdn.com/dark_all/", "https://b.basemaps.cartocdn.com/dark_all/", "https://c.basemaps.cartocdn.com/dark_all/"),
            "© OpenStreetMap contributors, © CARTO"
        )
        else -> TileSourceFactory.MAPNIK
    }

    /** Call once at startup: tile cache inside the app's own cache folder (no storage permission needed). */
    fun configure(context: Context) {
        val base = File(context.cacheDir, "osmdroid").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = context.packageName // OSM's tile policy asks for an identifying user agent
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun createMap(context: Context, heightPx: Int): MapView = MapView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
        setTileSource(tileSource(GpsTracker.mapStyle(context)))
        overlays.add(CopyrightOverlay(context)) // provider credit, always visible
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        // The map sits inside a ScrollView: let the map take the drag instead of scrolling the page.
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    fun showRoutes(map: MapView, rides: List<RideTrack>, colors: List<Int>, startLabel: String, endLabel: String) {
        val all = ArrayList<GeoPoint>()
        rides.forEachIndexed { i, ride ->
            if (ride.points.isEmpty()) return@forEachIndexed
            val geo = ride.points.map { GeoPoint(it.lat, it.lon) }
            all += geo
            map.overlays.add(Polyline(map).apply {
                setPoints(geo)
                outlinePaint.color = colors.getOrElse(i) { COLORS[i % COLORS.size] }
                outlinePaint.strokeWidth = 9f
            })
            map.overlays.add(Marker(map).apply { position = geo.first(); title = startLabel; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
            if (geo.size > 1) map.overlays.add(Marker(map).apply { position = geo.last(); title = endLabel; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
        }
        if (all.isEmpty()) return
        val box = BoundingBox.fromGeoPointsSafe(all)
        // Zoom needs the view's size, so wait for the first layout.
        map.addOnFirstLayoutListener { _, _, _, _, _ ->
            if (all.size == 1 || box.latitudeSpan < 1e-5 && box.longitudeSpan < 1e-5) {
                map.controller.setZoom(17.0)
                map.controller.setCenter(all.first())
            } else {
                map.zoomToBoundingBox(box, false, 72)
            }
        }
    }
}
