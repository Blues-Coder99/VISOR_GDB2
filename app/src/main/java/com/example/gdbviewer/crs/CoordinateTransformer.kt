package com.example.gdbviewer.crs

import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransform
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate

/**
 * Reproyecta coordenadas desde un CRS de origen (ej. CTM12) a WGS84 (lat/lon),
 * que es lo que necesita osmdroid para dibujar sobre el mapa.
 * Si el CRS de origen ya es WGS84, [toWgs84] devuelve las coordenadas sin cambios.
 */
class CoordinateTransformer private constructor(
    private val transform: CoordinateTransform?
) {
    private val srcPt = ProjCoordinate()
    private val dstPt = ProjCoordinate()

    /** Devuelve [lat, lon]. Si no hay transformación (ya es WGS84), retorna (y, x) tal cual. */
    fun toWgs84(x: Double, y: Double): DoubleArray {
        if (transform == null) return doubleArrayOf(y, x) // ya en lat/lon
        srcPt.x = x
        srcPt.y = y
        transform.transform(srcPt, dstPt)
        return doubleArrayOf(dstPt.y, dstPt.x) // (lat, lon)
    }

    companion object {
        private val crsFactory = CRSFactory()
        private val ctFactory = CoordinateTransformFactory()

        /** @param epsg código tipo "EPSG:9377", o null/"EPSG:4326" si ya está en WGS84. */
        fun forEpsg(epsg: String?): CoordinateTransformer {
            if (epsg == null || epsg.equals("EPSG:4326", ignoreCase = true)) {
                return CoordinateTransformer(null)
            }
            return try {
                val source = CrsCatalog.getCrs(crsFactory, epsg) ?: return CoordinateTransformer(null)
                val target = crsFactory.createFromName("EPSG:4326")
                CoordinateTransformer(ctFactory.createTransform(source, target))
            } catch (e: Exception) {
                CoordinateTransformer(null)
            }
        }
    }
}
