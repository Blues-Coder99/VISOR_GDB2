package com.example.gdbviewer.digitize

import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackage
import mil.nga.geopackage.db.GeoPackageDataType
import mil.nga.geopackage.db.TableColumnKey
import mil.nga.geopackage.features.columns.GeometryColumns
import mil.nga.geopackage.features.user.FeatureColumn
import mil.nga.geopackage.geom.GeoPackageGeometryData
import mil.nga.sf.GeometryType
import mil.nga.sf.LineString
import mil.nga.sf.Point
import mil.nga.sf.Polygon

/**
 * Crea capas nuevas (puntos, líneas o polígonos) dentro de un GeoPackage existente e
 * inserta geometrías — usadas tanto por la digitalización manual como por los
 * importadores de KML/KMZ y Shapefile.
 *
 * Nota honesta: crear tablas dentro de un GeoPackage usa una parte de la librería NGA
 * mucho menos documentada que la lectura. Las rutas de import (TableColumnKey,
 * GeoPackageDataType) corresponden a la versión 6.7.x según su documentación oficial;
 * si Android Studio marca alguna como no encontrada, su corrector de imports (Alt+Enter)
 * casi siempre resuelve la ubicación correcta en la versión que Gradle resuelva.
 *
 * Todo se guarda en WGS84 (EPSG:4326): es lo que entrega un tap sobre el mapa
 * directamente, y lo que exportan KML/GeoJSON siempre.
 */
object DigitizingHelper {

    private const val WGS84_EPSG = 4326L

    /** Crea la tabla si no existe, con las columnas de texto indicadas. */
    fun createLayer(
        geoPackage: GeoPackage,
        tableName: String,
        type: GeometryType,
        attributeColumns: List<String> = listOf("nombre")
    ): Boolean {
        if (geoPackage.isFeatureTable(tableName)) return false

        val srs = geoPackage.spatialReferenceSystemDao.getOrCreateCode("EPSG", WGS84_EPSG)

        val geometryColumns = GeometryColumns()
        geometryColumns.id = TableColumnKey(tableName, "geom")
        geometryColumns.geometryType = type
        geometryColumns.z = 0
        geometryColumns.m = 0

        val columns = attributeColumns.ifEmpty { listOf("nombre") }
            .map { FeatureColumn.createColumn(it, GeoPackageDataType.TEXT) }

        val worldBounds = BoundingBox(-180.0, -90.0, 180.0, 90.0)
        geoPackage.createFeatureTableWithMetadata(geometryColumns, columns, worldBounds, srs.id)
        return true
    }

    fun insertPoint(geoPackage: GeoPackage, tableName: String, lon: Double, lat: Double, attributes: Map<String, String>) {
        val featureDao = geoPackage.getFeatureDao(tableName)
        val row = featureDao.newRow()
        val geomData = GeoPackageGeometryData(WGS84_EPSG)
        geomData.geometry = Point(lon, lat)
        row.geometry = geomData
        applyAttributes(row, featureDao.columnNames.toSet(), attributes)
        featureDao.insert(row)
    }

    fun insertLineString(geoPackage: GeoPackage, tableName: String, vertices: List<Pair<Double, Double>>, attributes: Map<String, String>) {
        if (vertices.size < 2) return
        val featureDao = geoPackage.getFeatureDao(tableName)
        val row = featureDao.newRow()
        val line = LineString()
        for ((lon, lat) in vertices) line.addPoint(Point(lon, lat))
        val geomData = GeoPackageGeometryData(WGS84_EPSG)
        geomData.geometry = line
        row.geometry = geomData
        applyAttributes(row, featureDao.columnNames.toSet(), attributes)
        featureDao.insert(row)
    }

    /** [rings]: el primer anillo es el contorno exterior; los siguientes son huecos. */
    fun insertPolygon(geoPackage: GeoPackage, tableName: String, rings: List<List<Pair<Double, Double>>>, attributes: Map<String, String>) {
        if (rings.isEmpty() || rings[0].size < 3) return
        val featureDao = geoPackage.getFeatureDao(tableName)
        val row = featureDao.newRow()

        val polygon = Polygon()
        for (ringPoints in rings) {
            if (ringPoints.size < 3) continue
            val ring = LineString()
            for ((lon, lat) in ringPoints) ring.addPoint(Point(lon, lat))
            val first = ringPoints.first()
            val last = ringPoints.last()
            if (first != last) ring.addPoint(Point(first.first, first.second))
            polygon.addRing(ring)
        }

        val geomData = GeoPackageGeometryData(WGS84_EPSG)
        geomData.geometry = polygon
        row.geometry = geomData
        applyAttributes(row, featureDao.columnNames.toSet(), attributes)
        featureDao.insert(row)
    }

    private fun applyAttributes(row: mil.nga.geopackage.features.user.FeatureRow, existingColumns: Set<String>, attributes: Map<String, String>) {
        for ((key, value) in attributes) {
            if (existingColumns.contains(key)) row.setValue(key, value)
        }
    }
}
