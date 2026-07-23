package com.example.gdbviewer

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.gdbviewer.databinding.ActivityAttributeTableBinding
import mil.nga.geopackage.GeoPackageFactory
import java.io.File

/**
 * Muestra todos los atributos de una capa como tabla, con un buscador que filtra
 * filas cuyo contenido (en cualquier columna) contenga el texto ingresado.
 *
 * Nota de rendimiento: para capas con muchos miles de features, construir un TableLayout
 * completo puede ser lento; para ese caso convendría paginar o usar un RecyclerView virtualizado.
 */
class AttributeTableActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAttributeTableBinding
    private lateinit var columnNames: List<String>
    private lateinit var allRows: List<List<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttributeTableBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = intent.getStringExtra("layer_name") ?: "Atributos"

        loadData()
        renderTable(allRows)

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                val filtered = if (query.isEmpty()) {
                    allRows
                } else {
                    allRows.filter { row -> row.any { it.lowercase().contains(query) } }
                }
                renderTable(filtered)
            }
        })
    }

    private fun loadData() {
        val gpkgPath = intent.getStringExtra("gpkg_path")!!
        val layerName = intent.getStringExtra("layer_name")!!
        val gpkgName = File(gpkgPath).name.removeSuffix(".gpkg")

        val manager = GeoPackageFactory.getManager(this)
        val geoPackage = manager.open(gpkgName)
        val featureDao = geoPackage.getFeatureDao(layerName)

        // Excluimos la columna de geometría, ya que no aporta a la búsqueda/tabla de atributos.
        columnNames = featureDao.columnNames.filter { it != featureDao.geometryColumnName }

        val rows = mutableListOf<List<String>>()
        featureDao.queryForAll().use { cursor ->
            while (cursor.moveToNext()) {
                val row = cursor.row
                rows.add(columnNames.map { col -> row.getValue(col)?.toString() ?: "" })
            }
        }
        allRows = rows
        geoPackage.close()
    }

    private fun renderTable(rows: List<List<String>>) {
        binding.tvRowCount.text = "${rows.size} de ${allRows.size} registros"
        val table: TableLayout = binding.table
        table.removeAllViews()

        // Encabezado
        table.addView(buildRow(columnNames, isHeader = true))

        // Filas (para tablas muy grandes esto puede tardar; ver nota de rendimiento arriba)
        for (row in rows) {
            table.addView(buildRow(row, isHeader = false))
        }
    }

    private fun buildRow(cells: List<String>, isHeader: Boolean): TableRow {
        val tableRow = TableRow(this)
        for (cellText in cells) {
            val tv = TextView(this)
            tv.text = cellText
            tv.setPadding(20, 16, 20, 16)
            tv.gravity = Gravity.START
            tv.layoutParams = TableRow.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (isHeader) {
                tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
                tv.setBackgroundColor(Color.LTGRAY)
            }
            tableRow.addView(tv)
        }
        return tableRow
    }
}
