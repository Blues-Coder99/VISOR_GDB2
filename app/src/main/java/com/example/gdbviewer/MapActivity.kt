package com.example.gdbviewer

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.gdbviewer.crs.CoordinateTransformer
import com.example.gdbviewer.databinding.ActivityMapBinding
import com.example.gdbviewer.digitize.DigitizingHelper
import com.example.gdbviewer.map.EsriWorldImagery
import com.example.gdbviewer.style.DefaultPalette
import com.example.gdbviewer.style.LayerStyle
import com.example.gdbviewer.style.MarkerIconFactory
import mil.nga.geopackage.factory.GeoPackageFactory
import mil.nga.geopackage.features.user.FeatureCursor
import mil.nga.geopackage.features.user.FeatureDao
import mil.nga.sf.GeometryType
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var gpkgName: String
    private lateinit var layers: List<String>
    private lateinit var layerCrs: HashMap<String, String>
    private lateinit var layerStyles: HashMap<String, LayerStyle>

    // Mapa base
    private var usingImagery = false

    // GPS
    private var locationOverlay: MyLocationNewOverlay? = null
    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enableGps() else {
            Toast.makeText(this, "Se necesita permiso de ubicación para esta función", Toast.LENGTH_SHORT).show()
        }
    }

    // Medición de distancia
    private var measuring = false
    private val measurePoints = mutableListOf<GeoPoint>()
    private var measurePolyline: Polyline? = null
    private var measureEventsOverlay: MapEventsOverlay? = null

    // Digitalización (creación de capas nuevas de puntos/polígonos)
    private var digitizing = false
    private var digitizingType: GeometryType? = null
    private var digitizingTableName: String? = null
    private val digitizingPoints = mutableListOf<GeoPoint>()
    private var digitizingPreview: Polyline? = null
    private var digitizingEventsOverlay: MapEventsOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Configuration.getInstance().userAgentValue = packageName
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val gpkgPath = intent.getStringExtra("gpkg_path")!!
        gpkgName = File(gpkgPath).name.removeSuffix(".gpkg")
        layers = intent.getStringArrayListExtra("layers") ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        layerCrs = (intent.getSerializableExtra("layer_crs") as? HashMap<String, String>) ?: HashMap()
        @Suppress("UNCHECKED_CAST")
        layerStyles = (intent.getSerializableExtra("layer_styles") as? HashMap<String, LayerStyle>) ?: HashMap()

        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.controller.setZoom(12.0)
        binding.tvBasemapCredit.text = "© OpenStreetMap contributors"

        drawLayers(layers, null)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.map_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val editing = digitizing
        listOf(
            R.id.action_filter, R.id.action_basemap, R.id.action_gps,
            R.id.action_measure, R.id.action_table, R.id.action_create_layer
        ).forEach { menu.findItem(it)?.isVisible = !editing }
        menu.findItem(R.id.action_save_geometry)?.isVisible = editing && digitizingType == GeometryType.POLYGON
        menu.findItem(R.id.action_cancel_digitize)?.isVisible = editing
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_filter -> { showFilterDialog(); true }
            R.id.action_basemap -> { toggleBasemap(); true }
            R.id.action_gps -> { toggleGps(); true }
            R.id.action_measure -> { toggleMeasureMode(); true }
            R.id.action_table -> { openAttributeTable(); true }
            R.id.action_create_layer -> { showCreateLayerDialog(); true }
            R.id.action_save_geometry -> { finalizeDigitizedPolygon(); true }
            R.id.action_cancel_digitize -> { cancelDigitizing(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openAttributeTable() {
        if (layers.size == 1) {
            launchAttributeTable(layers[0])
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Elige una capa")
            .setItems(layers.toTypedArray()) { _, which -> launchAttributeTable(layers[which]) }
            .show()
    }

    private fun launchAttributeTable(layerName: String) {
        val gpkgPath = intent.getStringExtra("gpkg_path")!!
        val intent = android.content.Intent(this, AttributeTableActivity::class.java).apply {
            putExtra("gpkg_path", gpkgPath)
            putExtra("layer_name", layerName)
        }
        startActivity(intent)
    }

    // ---------- Mapa base ----------

    private fun toggleBasemap() {
        usingImagery = !usingImagery
        if (usingImagery) {
            binding.mapView.setTileSource(EsriWorldImagery)
            binding.tvBasemapCredit.text = "Imágenes: Esri, Maxar, Earthstar Geographics"
        } else {
            binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
            binding.tvBasemapCredit.text = "© OpenStreetMap contributors"
        }
        binding.mapView.invalidate()
    }

    // ---------- GPS ----------

    private fun toggleGps() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        val overlay = locationOverlay
        if (overlay != null && binding.mapView.overlays.contains(overlay)) {
            overlay.disableMyLocation()
            binding.mapView.overlays.remove(overlay)
            binding.mapView.invalidate()
        } else {
            enableGps()
        }
    }

    private fun enableGps() {
        val overlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView)
        overlay.enableMyLocation()
        overlay.enableFollowLocation()
        overlay.runOnFirstFix {
            runOnUiThread {
                overlay.myLocation?.let { binding.mapView.controller.animateTo(it) }
                binding.mapView.controller.setZoom(17.0)
            }
        }
        binding.mapView.overlays.add(overlay)
        locationOverlay = overlay
        Toast.makeText(this, "Buscando señal GPS…", Toast.LENGTH_SHORT).show()
    }

    // ---------- Medir distancia ----------

    private fun toggleMeasureMode() {
        measuring = !measuring
        if (measuring) {
            measurePoints.clear()
            val polyline = Polyline().apply {
                outlinePaint.color = Color.MAGENTA
                outlinePaint.strokeWidth = 6f
            }
            measurePolyline = polyline
            binding.mapView.overlays.add(polyline)

            val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    measurePoints.add(p)
                    polyline.setPoints(measurePoints)
                    updateMeasureLabel()
                    binding.mapView.invalidate()
                    return true
                }
                override fun longPressHelper(p: GeoPoint): Boolean = false
            })
            measureEventsOverlay = eventsOverlay
            // Se agrega al inicio de la lista para que capture el tap antes que otras capas.
            binding.mapView.overlays.add(0, eventsOverlay)

            binding.tvMeasure.visibility = View.VISIBLE
            binding.tvMeasure.text = "Distancia: 0 m — toca el mapa para medir"
            Toast.makeText(this, "Modo medición activado. Toca el mapa para trazar puntos.", Toast.LENGTH_LONG).show()
        } else {
            measureEventsOverlay?.let { binding.mapView.overlays.remove(it) }
            measurePolyline?.let { binding.mapView.overlays.remove(it) }
            measureEventsOverlay = null
            measurePolyline = null
            binding.tvMeasure.visibility = View.GONE
            binding.mapView.invalidate()
        }
    }

    private fun updateMeasureLabel() {
        var totalMeters = 0.0
        for (i in 1 until measurePoints.size) {
            totalMeters += measurePoints[i - 1].distanceToAsDouble(measurePoints[i])
        }
        val text = if (totalMeters >= 1000) {
            String.format("Distancia: %.2f km", totalMeters / 1000)
        } else {
            String.format("Distancia: %.1f m", totalMeters)
        }
        binding.tvMeasure.text = text
    }

    /**
     * Dibuja las capas indicadas. Si [whereClause] no es null, se aplica como filtro
     * SQL (ej: "poblacion > 1000") solo a la capa filtrada; el resto se dibuja completa.
     */
    private fun drawLayers(layerNames: List<String>, filtered: Pair<String, String>?) {
        binding.mapView.overlays.clear()
        val manager = GeoPackageFactory.getManager(this)
        val geoPackage = manager.open(gpkgName)
        var firstPoint: GeoPoint? = null

        for (layerName in layerNames) {
            val featureDao: FeatureDao = geoPackage.getFeatureDao(layerName)
            val cursor: FeatureCursor = if (filtered != null && filtered.first == layerName) {
                featureDao.queryForAll(filtered.second) // where clause cruda
            } else {
                featureDao.queryForAll()
            }

            // Transformador de coordenadas para esta capa (CTM12, MAGNA-SIRGAS, etc. -> WGS84).
            // Si no hay CRS asignado o ya es WGS84, actúa como identidad.
            val transformer = CoordinateTransformer.forEpsg(layerCrs[layerName])
            fun toGeoPoint(x: Double, y: Double): GeoPoint {
                val latLon = transformer.toWgs84(x, y) // [lat, lon]
                return GeoPoint(latLon[0], latLon[1])
            }

            val style = layerStyles[layerName] ?: LayerStyle(color = Color.RED)
            val pointIcon = MarkerIconFactory.circleIcon(this, style.color, style.pointRadius)

            cursor.use {
                while (it.moveToNext()) {
                    val row = it.row
                    val geomData = row.geometry ?: continue
                    val geometry = geomData.geometry ?: continue
                    val attributes = buildAttributeText(row)

                    when (geometry.geometryType) {
                        GeometryType.POINT -> {
                            val p = geometry as mil.nga.sf.Point
                            val geoPoint = toGeoPoint(p.x, p.y)
                            if (firstPoint == null) firstPoint = geoPoint
                            val marker = Marker(binding.mapView)
                            marker.position = geoPoint
                            marker.title = layerName
                            marker.snippet = attributes
                            marker.icon = pointIcon
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            marker.setOnMarkerClickListener { m, _ ->
                                showAttributesDialog(layerName, attributes)
                                true
                            }
                            binding.mapView.overlays.add(marker)
                        }
                        GeometryType.LINESTRING -> {
                            val line = geometry as mil.nga.sf.LineString
                            val polyline = Polyline()
                            polyline.setPoints(line.points.map { pt -> toGeoPoint(pt.x, pt.y) })
                            polyline.outlinePaint.color = style.color
                            polyline.outlinePaint.strokeWidth = style.strokeWidth
                            polyline.setOnClickListener { _, _, _ ->
                                showAttributesDialog(layerName, attributes)
                                true
                            }
                            if (firstPoint == null && line.points.isNotEmpty()) {
                                firstPoint = toGeoPoint(line.points[0].x, line.points[0].y)
                            }
                            binding.mapView.overlays.add(polyline)
                        }
                        GeometryType.POLYGON -> {
                            val poly = geometry as mil.nga.sf.Polygon
                            val ring = poly.rings.firstOrNull() ?: continue
                            val polygon = Polygon()
                            polygon.points = ring.points.map { pt -> toGeoPoint(pt.x, pt.y) }
                            polygon.fillColor = Color.argb(
                                style.fillAlpha, Color.red(style.color), Color.green(style.color), Color.blue(style.color)
                            )
                            polygon.strokeColor = style.color
                            polygon.outlinePaint.strokeWidth = style.strokeWidth
                            polygon.setOnClickListener { _, _, _ ->
                                showAttributesDialog(layerName, attributes)
                                true
                            }
                            if (firstPoint == null && ring.points.isNotEmpty()) {
                                firstPoint = toGeoPoint(ring.points[0].x, ring.points[0].y)
                            }
                            binding.mapView.overlays.add(polygon)
                        }
                        else -> {
                            // Tipos multi-geometría (MULTIPOINT, MULTIPOLYGON, etc.)
                            // se pueden manejar iterando geometry.geometries — omitido aquí por brevedad.
                        }
                    }
                }
            }
        }
        geoPackage.close()
        binding.mapView.invalidate()
        firstPoint?.let { binding.mapView.controller.setCenter(it) }
    }

    private fun buildAttributeText(row: mil.nga.geopackage.features.user.FeatureRow): String {
        val sb = StringBuilder()
        for (columnName in row.columnNames) {
            val value = row.getValue(columnName)
            sb.append(columnName).append(": ").append(value).append("\n")
        }
        return sb.toString()
    }

    private fun showAttributesDialog(layerName: String, attributes: String) {
        AlertDialog.Builder(this)
            .setTitle(layerName)
            .setMessage(attributes)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun showFilterDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val layerSpinner = Spinner(this)
        layerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, layers)
        container.addView(layerSpinner)

        val columnInput = EditText(this)
        columnInput.hint = "Columna (ej: nombre, poblacion)"
        container.addView(columnInput)

        val whereInput = EditText(this)
        whereInput.hint = "Condición (ej: = 'Valor' o > 1000)"
        container.addView(whereInput)

        AlertDialog.Builder(this)
            .setTitle("Filtrar por atributo")
            .setView(container)
            .setPositiveButton("Aplicar") { _, _ ->
                val layer = layerSpinner.selectedItem as String
                val column = columnInput.text.toString().trim()
                val condition = whereInput.text.toString().trim()
                if (column.isEmpty() || condition.isEmpty()) {
                    Toast.makeText(this, "Completa columna y condición", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val whereClause = "\"$column\" $condition"
                drawLayers(layers, Pair(layer, whereClause))
            }
            .setNeutralButton("Quitar filtro") { _, _ ->
                drawLayers(layers, null)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Digitalización (crear capas nuevas) ----------

    private fun showCreateLayerDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val nameInput = EditText(this).apply { hint = "Nombre de la capa (sin espacios)" }
        container.addView(nameInput)

        val typeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val radioPoint = RadioButton(this).apply { text = "Puntos"; isChecked = true }
        val radioPolygon = RadioButton(this).apply { text = "Polígonos" }
        typeGroup.addView(radioPoint)
        typeGroup.addView(radioPolygon)
        container.addView(typeGroup)

        AlertDialog.Builder(this)
            .setTitle("Nueva capa")
            .setView(container)
            .setPositiveButton("Crear") { _, _ ->
                val name = nameInput.text.toString().trim().replace(" ", "_")
                if (name.isEmpty()) {
                    Toast.makeText(this, "Escribe un nombre para la capa", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val type = if (radioPolygon.isChecked) GeometryType.POLYGON else GeometryType.POINT
                createLayerAndStartDigitizing(name, type)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun createLayerAndStartDigitizing(tableName: String, type: GeometryType) {
        val manager = GeoPackageFactory.getManager(this)
        val geoPackage = manager.open(gpkgName)
        val created = try {
            DigitizingHelper.createLayer(geoPackage, tableName, type)
        } catch (e: Exception) {
            Toast.makeText(this, "Error creando la capa: ${e.message}", Toast.LENGTH_LONG).show()
            geoPackage.close()
            return
        }
        geoPackage.close()

        if (!created) {
            Toast.makeText(this, "Ya existe una capa con ese nombre", Toast.LENGTH_SHORT).show()
            return
        }

        layers = layers + tableName
        layerCrs[tableName] = "EPSG:4326" // los taps del mapa ya vienen en WGS84
        layerStyles[tableName] = LayerStyle(color = DefaultPalette.colorForIndex(layerStyles.size))

        startDigitizing(tableName, type)
    }

    private fun startDigitizing(tableName: String, type: GeometryType) {
        digitizing = true
        digitizingType = type
        digitizingTableName = tableName
        digitizingPoints.clear()
        invalidateOptionsMenu()

        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onDigitizeTap(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
        digitizingEventsOverlay = eventsOverlay
        binding.mapView.overlays.add(0, eventsOverlay)

        val message = if (type == GeometryType.POINT)
            "Toca el mapa para agregar puntos a '$tableName'. Usa 'Cancelar digitalización' para terminar."
        else
            "Toca el mapa para agregar vértices del polígono. Usa 'Guardar geometría' cuando termines."
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun onDigitizeTap(p: GeoPoint) {
        when (digitizingType) {
            GeometryType.POINT -> promptNameAndInsertPoint(p)
            GeometryType.POLYGON -> {
                digitizingPoints.add(p)
                val preview = digitizingPreview ?: Polyline().also {
                    it.outlinePaint.color = Color.YELLOW
                    it.outlinePaint.strokeWidth = 6f
                    binding.mapView.overlays.add(it)
                    digitizingPreview = it
                }
                preview.setPoints(digitizingPoints)
                binding.mapView.invalidate()
            }
            else -> {}
        }
    }

    private fun promptNameAndInsertPoint(p: GeoPoint) {
        val input = EditText(this).apply { hint = "Nombre / etiqueta (opcional)" }
        AlertDialog.Builder(this)
            .setTitle("Nuevo punto")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ -> insertDigitizedPoint(p, input.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun finalizeDigitizedPolygon() {
        if (digitizingPoints.size < 3) {
            Toast.makeText(this, "Un polígono necesita al menos 3 puntos", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply { hint = "Nombre / etiqueta (opcional)" }
        AlertDialog.Builder(this)
            .setTitle("Guardar polígono")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val vertices = digitizingPoints.map { it.longitude to it.latitude }
                val tableName = digitizingTableName
                if (tableName != null) {
                    insertFeature(tableName) { geoPackage ->
                        DigitizingHelper.insertPolygon(geoPackage, tableName, listOf(vertices), mapOf("nombre" to input.text.toString()))
                    }
                }
                stopDigitizing()
                drawLayers(layers, null)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun insertDigitizedPoint(p: GeoPoint, nombre: String) {
        val tableName = digitizingTableName ?: return
        insertFeature(tableName) { geoPackage ->
            DigitizingHelper.insertPoint(geoPackage, tableName, p.longitude, p.latitude, mapOf("nombre" to nombre))
        }
        // Se redibuja todo (drawLayers limpia todos los overlays), así que hay que
        // reconectar el overlay de eventos para seguir digitalizando puntos.
        drawLayers(layers, null)
        digitizingEventsOverlay?.let {
            binding.mapView.overlays.remove(it)
            binding.mapView.overlays.add(0, it)
        }
    }

    /** Abre el GeoPackage, ejecuta [insert] dentro, y lo cierra — con manejo de errores común. */
    private fun insertFeature(tableName: String, insert: (geoPackage: mil.nga.geopackage.GeoPackage) -> Unit) {
        val manager = GeoPackageFactory.getManager(this)
        val geoPackage = manager.open(gpkgName)
        try {
            insert(geoPackage)
        } catch (e: Exception) {
            Toast.makeText(this, "Error guardando la geometría: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            geoPackage.close()
        }
    }

    private fun cancelDigitizing() {
        stopDigitizing()
        drawLayers(layers, null)
    }

    private fun stopDigitizing() {
        digitizing = false
        digitizingType = null
        digitizingTableName = null
        digitizingPoints.clear()
        digitizingEventsOverlay?.let { binding.mapView.overlays.remove(it) }
        digitizingPreview?.let { binding.mapView.overlays.remove(it) }
        digitizingEventsOverlay = null
        digitizingPreview = null
        invalidateOptionsMenu()
        binding.mapView.invalidate()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        locationOverlay?.disableMyLocation()
    }
}
