package com.example.gdbviewer.importer

import mil.nga.sf.GeometryType
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * Lector de Shapefile (.shp + .dbf [+ .prj]) en Kotlin puro, sin GDAL. Espera un .zip
 * con los archivos del shapefile (mismo patrón que usan QuickMapTools/MyGeodata),
 * porque el selector de archivos de Android solo permite elegir un archivo a la vez.
 *
 * Simplificaciones conocidas (documentadas para no generar falsas expectativas):
 * - Se ignoran los componentes Z/M de shapefiles 3D (PointZ, PolyLineZ, PolygonZ, etc.):
 *   se importa solo la geometría 2D (X, Y).
 * - En polígonos con varios anillos se asume que el primero es el exterior y el resto
 *   son huecos — no se analiza la orientación (horario/antihorario) para distinguir de
 *   forma 100% fiel múltiples polígonos con huecos según el estándar.
 * - Un PolyLine con varias partes solo importa la primera parte.
 * - No se soporta MultiPoint ni MultiPatch.
 * - No se usa el archivo .shx (se recorren los registros del .shp secuencialmente).
 * - Registros marcados como "eliminados" en el .dbf igual se importan.
 * - Si el .prj no está en WGS84, no se reproyecta automáticamente aquí (a diferencia
 *   del flujo GDB/GPKG) — conviene revisar el CRS de la capa importada manualmente.
 */
object ShapefileImporter {

    private val DBF_CHARSET: Charset = try {
        Charset.forName("Cp1252")
    } catch (e: Exception) {
        Charsets.ISO_8859_1
    }

    fun parseZip(input: InputStream): List<ImportedFeature> {
        var shpBytes: ByteArray? = null
        var dbfBytes: ByteArray? = null

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry?.name?.lowercase() ?: ""
                when {
                    name.endsWith(".shp") -> shpBytes = readAllBytes(zip)
                    name.endsWith(".dbf") -> dbfBytes = readAllBytes(zip)
                    // .prj se podría leer aquí con CrsResolver.fromPrjText si se quiere
                    // asignar el CRS automáticamente al crear la capa (mejora futura).
                }
                entry = zip.nextEntry
            }
        }

        val shp = shpBytes ?: return emptyList()
        val geometries = parseShp(shp)
        val attributesList = dbfBytes?.let { parseDbf(it) }

        return geometries.mapIndexed { i, geom ->
            val attrs = attributesList?.getOrNull(i) ?: emptyMap()
            geom.copy(attributes = attrs)
        }
    }

    private fun readAllBytes(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var len = zip.read(buffer)
        while (len > 0) {
            out.write(buffer, 0, len)
            len = zip.read(buffer)
        }
        return out.toByteArray()
    }

    // ---------- .shp ----------

    private fun parseShp(bytes: ByteArray): List<ImportedFeature> {
        val features = mutableListOf<ImportedFeature>()
        val buffer = ByteBuffer.wrap(bytes)

        var offset = 100 // encabezado fijo de 100 bytes
        while (offset + 8 <= bytes.size) {
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset)
            buffer.int // número de registro, no se usa
            val contentLengthWords = buffer.int
            val contentLengthBytes = contentLengthWords * 2
            val contentStart = offset + 8
            if (contentStart + 4 > bytes.size) break

            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(contentStart)
            val shapeType = buffer.int

            val feature = when (shapeType) {
                1, 11, 21 -> parsePointRecord(buffer)               // Point, PointZ, PointM
                3, 13, 23 -> parsePolyRecord(buffer, isPolygon = false) // PolyLine (+Z/M)
                5, 15, 25 -> parsePolyRecord(buffer, isPolygon = true)  // Polygon (+Z/M)
                else -> null // 0=Null, 8/18/28=MultiPoint, 31=MultiPatch: no soportados
            }
            if (feature != null) features.add(feature)

            offset = contentStart + contentLengthBytes
        }
        return features
    }

    private fun parsePointRecord(buffer: ByteBuffer): ImportedFeature {
        val x = buffer.double
        val y = buffer.double
        return ImportedFeature(GeometryType.POINT, points = listOf(x to y))
    }

    private fun parsePolyRecord(buffer: ByteBuffer, isPolygon: Boolean): ImportedFeature {
        buffer.position(buffer.position() + 32) // bounding box (4 doubles), no se usa
        val numParts = buffer.int
        val numPoints = buffer.int
        val partIndices = IntArray(numParts) { buffer.int }
        val allPoints = Array(numPoints) { 0.0 to 0.0 }
        for (i in 0 until numPoints) {
            allPoints[i] = buffer.double to buffer.double
        }

        val parts = mutableListOf<List<Pair<Double, Double>>>()
        for (p in partIndices.indices) {
            val start = partIndices[p]
            val end = if (p + 1 < partIndices.size) partIndices[p + 1] else numPoints
            parts.add(allPoints.slice(start until end))
        }

        return if (isPolygon) {
            ImportedFeature(GeometryType.POLYGON, rings = parts)
        } else {
            ImportedFeature(GeometryType.LINESTRING, points = parts.firstOrNull() ?: emptyList())
        }
    }

    // ---------- .dbf ----------

    private data class DbfField(val name: String, val length: Int)

    private fun parseDbf(bytes: ByteArray): List<Map<String, String>> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4)
        val numRecords = buffer.int
        val headerLength = buffer.short.toInt() and 0xFFFF
        val recordLength = buffer.short.toInt() and 0xFFFF

        val fields = mutableListOf<DbfField>()
        buffer.position(32)
        while (buffer.position() < headerLength - 1) {
            val nameBytes = ByteArray(11)
            buffer.get(nameBytes)
            val fieldName = String(nameBytes, DBF_CHARSET).trim { it == ' ' || it == '\u0000' }
            buffer.get() // tipo de campo (C/N/F/D/L/M), no distinguimos tipos: todo se lee como texto
            buffer.int   // dirección de datos, no se usa
            val length = buffer.get().toInt() and 0xFF
            buffer.get() // decimales, no se usa
            buffer.position(buffer.position() + 14) // reservado
            if (fieldName.isNotBlank()) fields.add(DbfField(fieldName, length))
        }

        val records = mutableListOf<Map<String, String>>()
        buffer.position(headerLength)
        for (r in 0 until numRecords) {
            if (buffer.position() + recordLength > bytes.size) break
            buffer.get() // marca de borrado (0x20 válido, 0x2A eliminado), no se distingue
            val row = mutableMapOf<String, String>()
            for (field in fields) {
                val fieldBytes = ByteArray(field.length)
                buffer.get(fieldBytes)
                row[field.name] = String(fieldBytes, DBF_CHARSET).trim()
            }
            records.add(row)
        }
        return records
    }
}
