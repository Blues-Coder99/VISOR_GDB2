package com.example.gdbviewer

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gdbviewer.crs.CrsCatalog
import com.example.gdbviewer.crs.CrsResolver
import com.example.gdbviewer.databinding.ActivityMainBinding
import com.example.gdbviewer.digitize.DigitizingHelper
import com.example.gdbviewer.importer.GeoPackageFeatureWriter
import com.example.gdbviewer.importer.KmlImporter
import com.example.gdbviewer.importer.ShapefileImporter
import com.example.gdbviewer.style.DefaultPalette
import com.example.gdbviewer.style.LayerStyle
import mil.nga.geopackage.GeoPackageManager
import mil.nga.geopackage.GeoPackageFactory
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var geoPackageManager: GeoPackageManager
    private var currentGpkgPath: String? = null
    private var currentGpkgName: String = "capas"
    private var selectedLayers: List<String> = emptyList()

    // nombre de capa -> "EPSG:xxxx"
    private val layerCrs = mutableMapOf<String, String>()
    private var layerPendingPrj: String? = null
    private lateinit var layerAdapter: LayerAdapter

    // nombre de capa -> estilo (color, grosor, relleno, radio de punto)
    private val layerStyles = mutableMapOf<String, LayerStyle>()

    private val pickGpkgLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri: Uri? = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) handlePickedGpkg(uri)
    }

    private val pickPrjLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri: Uri? = result.data?.data
        val layer = layerPendingPrj
        if (result.resultCode == RESULT_OK && uri != null && layer != null) {
            handlePickedPrj(layer, uri)
        }
        layerPendingPrj = null
    }

    private val pickKmlLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri: Uri? = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) handlePickedKml(uri)
    }

    private val pickShapefileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri: Uri? = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) handlePickedShapefile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        geoPackageManager = GeoPackageFactory.getManager(this)

        binding.btnOpenGpkg.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            pickGpkgLauncher.launch(intent)
        }

        binding.btnImportKml.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            pickKmlLauncher.launch(intent)
        }

        binding.btnImportShapefile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            pickShapefileLauncher.launch(intent)
        }

        binding.btnViewMap.setOnClickListener {
            val path = currentGpkgPath ?: return@setOnClickListener
            if (selectedLayers.isEmpty()) {
                Toast.makeText(this, "Selecciona al menos una capa", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, MapActivity::class.java).apply {
                putExtra("gpkg_path", path)
                putStringArrayListExtra("layers", ArrayList(selectedLayers))
                putExtra("layer_crs", HashMap(layerCrs)) // Serializable
                putExtra("layer_styles", HashMap(layerStyles)) // Serializable
            }
            startActivity(intent)
        }
    }

    // ---------- Carga del GeoPackage ----------

    // ---------- Espacio de trabajo (crea un .gpkg vacío si aún no hay ninguno abierto) ----------

    /** Si no hay un .gpkg abierto todavía, crea uno vacío para poder importar datos en él. */
    private fun ensureWorkspaceGpkg(): String {
        currentGpkgPath?.let { return it }

        val workspaceName = "espacio_de_trabajo.gpkg"
        if (!geoPackageManager.exists(workspaceName)) {
            geoPackageManager.create(workspaceName)
        }
        currentGpkgName = workspaceName
        // Nota: la ruta exacta que usa la librería para bases de datos "creadas" (a
        // diferencia de las importadas por nosotros mismos) sigue el directorio estándar
        // de bases de datos de Android. Si Android Studio no encuentra getDatabasePath,
        // es el único punto de esta función que necesitaría revisión.
        val path = getDatabasePath(workspaceName).absolutePath
        currentGpkgPath = path
        binding.tvFileName.text = workspaceName
        return path
    }

    // ---------- Importar KML / KMZ ----------

    private fun handlePickedKml(uri: Uri) {
        val fileName = queryFileName(uri) ?: "importado.kml"
        try {
            val features = contentResolver.openInputStream(uri)?.use { stream ->
                KmlImporter.parseKmlOrKmz(stream, fileName)
            } ?: emptyList()

            if (features.isEmpty()) {
                Toast.makeText(this, "No se encontraron geometrías en el archivo", Toast.LENGTH_LONG).show()
                return
            }

            ensureWorkspaceGpkg()
            val baseTableName = fileName.substringBeforeLast(".").replace(Regex("[^A-Za-z0-9_]"), "_")
            val geoPackage = geoPackageManager.open(currentGpkgName.removeSuffix(".gpkg"))
            val createdTables = try {
                GeoPackageFeatureWriter.writeFeatures(geoPackage, baseTableName, features)
            } finally {
                geoPackage.close()
            }

            Toast.makeText(this, "Importado: ${createdTables.joinToString(", ")}", Toast.LENGTH_LONG).show()
            loadLayers(currentGpkgName.removeSuffix(".gpkg"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error importando KML/KMZ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Importar Shapefile (.zip) ----------

    private fun handlePickedShapefile(uri: Uri) {
        val fileName = queryFileName(uri) ?: "importado.zip"
        try {
            val features = contentResolver.openInputStream(uri)?.use { stream ->
                ShapefileImporter.parseZip(stream)
            } ?: emptyList()

            if (features.isEmpty()) {
                Toast.makeText(
                    this,
                    "No se encontraron geometrías. Verifica que el .zip contenga los archivos .shp y .dbf.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            ensureWorkspaceGpkg()
            val baseTableName = fileName.substringBeforeLast(".").replace(Regex("[^A-Za-z0-9_]"), "_")
            val geoPackage = geoPackageManager.open(currentGpkgName.removeSuffix(".gpkg"))
            val createdTables = try {
                GeoPackageFeatureWriter.writeFeatures(geoPackage, baseTableName, features)
            } finally {
                geoPackage.close()
            }

            Toast.makeText(this, "Importado: ${createdTables.joinToString(", ")}", Toast.LENGTH_LONG).show()
            loadLayers(currentGpkgName.removeSuffix(".gpkg"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error importando Shapefile: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handlePickedGpkg(uri: Uri) {
        val fileName = queryFileName(uri) ?: "importado.gpkg"
        currentGpkgName = fileName
        val destFile = File(filesDir, fileName)

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al leer el archivo: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        val gpkgName = fileName.removeSuffix(".gpkg")
        try {
            if (geoPackageManager.exists(gpkgName)) geoPackageManager.delete(gpkgName)
            geoPackageManager.importGeoPackage(gpkgName, destFile)
            currentGpkgPath = destFile.absolutePath
            binding.tvFileName.text = fileName
            loadLayers(gpkgName)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el GeoPackage: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadLayers(gpkgName: String) {
        val geoPackage = geoPackageManager.open(gpkgName)
        val featureTables = geoPackage.featureTables
        layerCrs.clear()

        // Paso 1: detectar automáticamente el CRS de cada capa desde los metadatos del gpkg.
        for ((index, table) in featureTables.withIndex()) {
            val dao = geoPackage.getFeatureDao(table)
            val epsg = CrsResolver.fromGeoPackageSrs(dao.srs)
            if (epsg != null) layerCrs[table] = epsg
            // Estilo por defecto: un color distinto por capa, para que se distingan sin
            // que el usuario tenga que configurar nada. Si la capa ya tenía un estilo
            // asignado (p. ej. tras una importación previa en esta misma sesión), se
            // conserva en vez de sobreescribirlo.
            layerStyles.getOrPut(table) { LayerStyle(color = DefaultPalette.colorForIndex(index)) }
        }
        geoPackage.close()

        layerAdapter = LayerAdapter(
            layerNames = featureTables,
            layerCrs = layerCrs,
            layerStyles = layerStyles,
            onSelectionChanged = { selection ->
                selectedLayers = selection
                binding.btnViewMap.isEnabled = selection.isNotEmpty()
            },
            onDefineCrs = { layerName -> showCrsDialog(layerName) },
            onDefineStyle = { layerName -> showStyleDialog(layerName) }
        )
        binding.rvLayers.layoutManager = LinearLayoutManager(this)
        binding.rvLayers.adapter = layerAdapter
    }

    // ---------- Asignación / corrección manual de CRS ----------

    private fun showCrsDialog(layerName: String) {
        val options = mutableListOf("Detección automática (gpkg)")
        options.addAll(CrsCatalog.KNOWN.map { "${it.label} (${it.epsg})" })
        options.add("Cargar archivo .PRJ…")

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)

        AlertDialog.Builder(this)
            .setTitle("Sistema de coordenadas: $layerName")
            .setMessage("Actual: ${layerCrs[layerName]?.let { CrsCatalog.labelFor(it) } ?: "no detectado"}")
            .setView(spinner)
            .setPositiveButton("Aplicar") { _, _ ->
                when (val index = spinner.selectedItemPosition) {
                    0 -> reDetectCrs(layerName)
                    options.size - 1 -> {
                        layerPendingPrj = layerName
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        pickPrjLauncher.launch(intent)
                    }
                    else -> {
                        val entry = CrsCatalog.KNOWN[index - 1]
                        layerCrs[layerName] = entry.epsg
                        layerAdapter.refreshCrsLabels()
                        Toast.makeText(this, "${entry.label} asignado a $layerName", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun reDetectCrs(layerName: String) {
        val gpkgName = currentGpkgName.removeSuffix(".gpkg")
        val geoPackage = geoPackageManager.open(gpkgName)
        val dao = geoPackage.getFeatureDao(layerName)
        val epsg = CrsResolver.fromGeoPackageSrs(dao.srs)
        geoPackage.close()
        if (epsg != null) {
            layerCrs[layerName] = epsg
            Toast.makeText(this, "Detectado: ${CrsCatalog.labelFor(epsg)}", Toast.LENGTH_SHORT).show()
        } else {
            layerCrs.remove(layerName)
            Toast.makeText(this, "No se pudo detectar automáticamente", Toast.LENGTH_SHORT).show()
        }
        layerAdapter.refreshCrsLabels()
    }

    private fun handlePickedPrj(layerName: String, uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            val epsg = CrsResolver.fromPrjText(text)
            if (epsg != null) {
                layerCrs[layerName] = epsg
                Toast.makeText(this, "CRS detectado en .prj: ${CrsCatalog.labelFor(epsg)}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "No se reconoció el sistema en el .prj. Selecciónalo manualmente en la lista.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error leyendo .prj: ${e.message}", Toast.LENGTH_LONG).show()
        }
        layerAdapter.refreshCrsLabels()
    }

    // ---------- Simbología ----------

    private fun showStyleDialog(layerName: String) {
        val style = layerStyles.getOrPut(layerName) {
            LayerStyle(color = DefaultPalette.colorForIndex(layerStyles.size))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        // Fila de swatches de color
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val palette = listOf(
            0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(),
            0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(), 0xFFFDD835.toInt(), 0xFF6D4C41.toInt(),
            0xFF000000.toInt()
        )
        var selectedColor = style.color
        val swatchViews = mutableListOf<View>()
        for (c in palette) {
            val swatch = View(this)
            val params = LinearLayout.LayoutParams(60, 60).apply { setMargins(6, 6, 6, 6) }
            swatch.layoutParams = params
            swatch.setBackgroundColor(c)
            swatch.setOnClickListener {
                selectedColor = c
                swatchViews.forEach { v -> v.alpha = if ((v.background as? android.graphics.drawable.ColorDrawable)?.color == c) 1f else 0.4f }
            }
            swatch.alpha = if (c == selectedColor) 1f else 0.4f
            swatchViews.add(swatch)
            colorRow.addView(swatch)
        }
        container.addView(TextView(this).apply { text = "Color" })
        container.addView(colorRow)

        // Grosor de línea / borde
        container.addView(TextView(this).apply { text = "Grosor de línea/borde"; setPadding(0, 24, 0, 0) })
        val strokeSeek = SeekBar(this).apply { max = 20; progress = style.strokeWidth.toInt() }
        container.addView(strokeSeek)

        // Opacidad de relleno (polígonos)
        container.addView(TextView(this).apply { text = "Opacidad de relleno (polígonos)"; setPadding(0, 16, 0, 0) })
        val fillSeek = SeekBar(this).apply { max = 255; progress = style.fillAlpha }
        container.addView(fillSeek)

        // Radio de punto (capas de puntos)
        container.addView(TextView(this).apply { text = "Tamaño de punto"; setPadding(0, 16, 0, 0) })
        val radiusSeek = SeekBar(this).apply { max = 25; progress = style.pointRadius.toInt() }
        container.addView(radiusSeek)

        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("Simbología: $layerName")
            .setView(scroll)
            .setPositiveButton("Aplicar") { _, _ ->
                style.color = selectedColor
                style.strokeWidth = strokeSeek.progress.coerceAtLeast(1).toFloat()
                style.fillAlpha = fillSeek.progress
                style.pointRadius = radiusSeek.progress.coerceAtLeast(2).toFloat()
                layerStyles[layerName] = style
                layerAdapter.refreshCrsLabels()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) name = cursor.getString(nameIndex)
        }
        return name
    }
}
