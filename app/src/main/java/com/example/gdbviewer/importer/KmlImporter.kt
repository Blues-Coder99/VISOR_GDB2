package com.example.gdbviewer.importer

import android.util.Xml
import mil.nga.sf.GeometryType
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Lector de KML/KMZ en Kotlin puro (sin GDAL) — KML es solo XML, y KMZ es un KML
 * comprimido en ZIP, así que se descomprime primero y se reutiliza el mismo parser.
 *
 * Simplificaciones conocidas:
 * - Los <MultiGeometry> se "aplanan": cada sub-geometría se importa como un feature
 *   separado (comparten los atributos de su Placemark), sin preservar el agrupamiento.
 * - No se importan estilos (colores, íconos) de KML — la simbología se asigna luego
 *   dentro de la app.
 */
object KmlImporter {

    fun parseKmlOrKmz(input: InputStream, fileName: String): List<ImportedFeature> {
        return if (fileName.lowercase().endsWith(".kmz")) {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entry?.name?.lowercase()?.endsWith(".kml") != true) {
                    entry = zip.nextEntry
                }
                if (entry == null) emptyList() else parseKml(zip)
            }
        } else {
            parseKml(input)
        }
    }

    private fun parseKml(input: InputStream): List<ImportedFeature> {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)

        val features = mutableListOf<ImportedFeature>()
        var eventType = parser.eventType

        var inPlacemark = false
        var name = ""
        var description = ""
        val extendedData = mutableMapOf<String, String>()
        var currentDataKey: String? = null

        var currentGeomType: String? = null
        var currentRingIsInner = false
        val outerRing = mutableListOf<Pair<Double, Double>>()
        val innerRings = mutableListOf<MutableList<Pair<Double, Double>>>()
        var lineOrPointCoords = mutableListOf<Pair<Double, Double>>()

        fun flushPlacemark() {
            val attrs = mutableMapOf<String, String>()
            if (name.isNotBlank()) attrs["nombre"] = name
            if (description.isNotBlank()) attrs["descripcion"] = description
            attrs.putAll(extendedData)

            when (currentGeomType) {
                "Point" -> if (lineOrPointCoords.isNotEmpty()) {
                    features.add(ImportedFeature(GeometryType.POINT, points = listOf(lineOrPointCoords.first()), attributes = attrs))
                }
                "LineString" -> if (lineOrPointCoords.size >= 2) {
                    features.add(ImportedFeature(GeometryType.LINESTRING, points = lineOrPointCoords.toList(), attributes = attrs))
                }
                "Polygon" -> if (outerRing.size >= 3) {
                    val rings = mutableListOf(outerRing.toList())
                    rings.addAll(innerRings.map { it.toList() })
                    features.add(ImportedFeature(GeometryType.POLYGON, rings = rings, attributes = attrs))
                }
            }
        }

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "Placemark" -> {
                            inPlacemark = true
                            name = ""; description = ""; extendedData.clear()
                        }
                        "name" -> if (inPlacemark) name = readText(parser)
                        "description" -> if (inPlacemark) description = readText(parser)
                        "Data" -> currentDataKey = parser.getAttributeValue(null, "name")
                        "value" -> if (currentDataKey != null) {
                            extendedData[currentDataKey!!] = readText(parser)
                            currentDataKey = null
                        }
                        "Point" -> { currentGeomType = "Point"; lineOrPointCoords = mutableListOf() }
                        "LineString" -> { currentGeomType = "LineString"; lineOrPointCoords = mutableListOf() }
                        "Polygon" -> { currentGeomType = "Polygon"; outerRing.clear(); innerRings.clear() }
                        "innerBoundaryIs" -> currentRingIsInner = true
                        "outerBoundaryIs" -> currentRingIsInner = false
                        "coordinates" -> {
                            val coords = parseCoordinates(readText(parser))
                            when (currentGeomType) {
                                "Point", "LineString" -> lineOrPointCoords.addAll(coords)
                                "Polygon" -> {
                                    if (currentRingIsInner) innerRings.add(coords.toMutableList())
                                    else outerRing.addAll(coords)
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "Placemark") {
                        flushPlacemark()
                        inPlacemark = false
                        currentGeomType = null
                    }
                }
            }
            eventType = parser.next()
        }
        return features
    }

    /** Acumula todo el texto (y CDATA) hasta el cierre de la etiqueta actual. */
    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var event = parser.next()
        while (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
            sb.append(parser.text)
            event = parser.next()
        }
        return sb.toString().trim()
    }

    private fun parseCoordinates(text: String): List<Pair<Double, Double>> {
        return text.trim().split(Regex("\\s+")).mapNotNull { token ->
            val parts = token.split(",")
            if (parts.size >= 2) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lon != null && lat != null) lon to lat else null
            } else null
        }
    }
}
