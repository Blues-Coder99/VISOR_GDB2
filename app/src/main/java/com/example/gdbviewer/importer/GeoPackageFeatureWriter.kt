package com.example.gdbviewer.importer

import com.example.gdbviewer.digitize.DigitizingHelper
import mil.nga.geopackage.GeoPackage
import mil.nga.sf.GeometryType

/**
 * KML y Shapefile pueden mezclar tipos de geometría en un mismo archivo, pero una tabla
 * de GeoPackage solo admite un tipo. Por eso se agrupan los features por tipo y se crea
 * una capa por cada grupo (ej. "miArchivo_puntos", "miArchivo_poligonos").
 */
object GeoPackageFeatureWriter {

    private val suffixFor = mapOf(
        GeometryType.POINT to "puntos",
        GeometryType.LINESTRING to "lineas",
        GeometryType.POLYGON to "poligonos"
    )

    /** Devuelve los nombres de las capas creadas. */
    fun writeFeatures(geoPackage: GeoPackage, baseTableName: String, features: List<ImportedFeature>): List<String> {
        val createdTables = mutableListOf<String>()
        val grouped = features.groupBy { it.geometryType }

        for ((geometryType, group) in grouped) {
            if (group.isEmpty()) continue
            val suffix = suffixFor[geometryType] ?: geometryType.name.lowercase()

            var tableName = "${baseTableName}_$suffix"
            var counter = 1
            while (geoPackage.isFeatureTable(tableName)) {
                tableName = "${baseTableName}_${suffix}_$counter"
                counter++
            }

            val columnNames = group.flatMap { it.attributes.keys }.toSortedSet().toList()
            DigitizingHelper.createLayer(geoPackage, tableName, geometryType, columnNames.ifEmpty { listOf("nombre") })

            for (feature in group) {
                when (geometryType) {
                    GeometryType.POINT -> {
                        val (lon, lat) = feature.points.firstOrNull() ?: continue
                        DigitizingHelper.insertPoint(geoPackage, tableName, lon, lat, feature.attributes)
                    }
                    GeometryType.LINESTRING ->
                        DigitizingHelper.insertLineString(geoPackage, tableName, feature.points, feature.attributes)
                    GeometryType.POLYGON ->
                        DigitizingHelper.insertPolygon(geoPackage, tableName, feature.rings, feature.attributes)
                    else -> {}
                }
            }
            createdTables.add(tableName)
        }
        return createdTables
    }
}
