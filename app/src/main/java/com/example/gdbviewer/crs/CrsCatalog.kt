package com.example.gdbviewer.crs

import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateReferenceSystem

/**
 * Catálogo de sistemas de referencia comunes en Colombia.
 * proj4j no siempre trae definiciones actualizadas (ej. EPSG:9377 / CTM12 es de 2020),
 * así que las registramos manualmente con sus parámetros oficiales (fuente: IGAC).
 */
object CrsCatalog {

    data class CrsEntry(val epsg: String, val label: String, val proj4: String?)

    val KNOWN: List<CrsEntry> = listOf(
        CrsEntry(
            "EPSG:9377", "CTM12 / MAGNA-SIRGAS Origen-Nacional",
            "+proj=tmerc +lat_0=4.0 +lon_0=-73.0 +k=0.9992 +x_0=5000000 +y_0=2000000 +ellps=GRS80 +units=m +no_defs"
        ),
        CrsEntry(
            "EPSG:3116", "MAGNA-SIRGAS / Bogotá zone",
            "+proj=tmerc +lat_0=4.59620041666667 +lon_0=-74.0775079166667 +k=1 +x_0=1000000 +y_0=1000000 +ellps=GRS80 +units=m +no_defs"
        ),
        CrsEntry(
            "EPSG:4686", "MAGNA-SIRGAS geográfico (lat/lon)",
            "+proj=longlat +ellps=GRS80 +no_defs"
        ),
        CrsEntry(
            "EPSG:4326", "WGS84 geográfico (lat/lon)",
            "+proj=longlat +datum=WGS84 +no_defs"
        )
    )

    /** Crea o recupera el CRS de proj4j, primero probando nuestra definición manual,
     * y solo si no está en el catálogo, cae a la base de datos incluida en proj4j. */
    fun getCrs(factory: CRSFactory, epsg: String): CoordinateReferenceSystem? {
        val known = KNOWN.firstOrNull { it.epsg.equals(epsg, ignoreCase = true) }
        return try {
            if (known?.proj4 != null) {
                factory.createFromParameters(known.epsg, known.proj4)
            } else {
                factory.createFromName(epsg)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun labelFor(epsg: String): String =
        KNOWN.firstOrNull { it.epsg.equals(epsg, ignoreCase = true) }?.label ?: epsg
}
