package com.example.gdbviewer.crs

import mil.nga.geopackage.srs.SpatialReferenceSystem

/**
 * Intenta averiguar el código EPSG de una capa a partir de:
 * 1. Los metadatos que el propio GeoPackage guarda (ideal — ogr2ogr los preserva).
 * 2. El texto WKT de un archivo .PRJ, buscando primero una etiqueta AUTHORITY/ID EPSG,
 *    y si no existe (típico en .prj antiguos de Esri), por palabras clave conocidas.
 */
object CrsResolver {

    /** Paso 1: leer el SRS que trae el propio GeoPackage. */
    fun fromGeoPackageSrs(srs: SpatialReferenceSystem?): String? {
        if (srs == null) return null
        val org = srs.organization
        val code = srs.organizationCoordsysId
        if (org != null && org.equals("EPSG", ignoreCase = true) && code != null && code > 0) {
            return "EPSG:$code"
        }
        // Si la organización no es EPSG (raro, pero puede pasar), intentamos leer la
        // definición WKT que trae el registro.
        val definition = srs.definition ?: srs.definition_12_063
        return definition?.let { fromPrjText(it) }
    }

    /** Paso 2 (respaldo): analizar el texto crudo de un .PRJ. */
    fun fromPrjText(wkt: String): String? {
        // Caso ideal: el WKT trae explícitamente el código EPSG.
        val authorityRegex = Regex("""(?:AUTHORITY|ID)\s*\[\s*"EPSG"\s*,\s*"?(\d+)"?\s*\]""", RegexOption.IGNORE_CASE)
        authorityRegex.find(wkt)?.let { return "EPSG:${it.groupValues[1]}" }

        // Caso típico de .prj antiguos de Esri: solo el nombre, sin código EPSG.
        val text = wkt.uppercase()
        return when {
            text.contains("CTM12") || text.contains("ORIGEN_NACIONAL") || text.contains("ORIGEN-NACIONAL") ->
                "EPSG:9377"
            text.contains("MAGNA") && text.contains("BOGOTA") ->
                "EPSG:3116"
            text.contains("MAGNA") && text.contains("GEOGCS") && !text.contains("PROJCS") ->
                "EPSG:4686"
            text.contains("WGS_1984") || text.contains("WGS 84") ->
                "EPSG:4326"
            else -> null
        }
    }
}
