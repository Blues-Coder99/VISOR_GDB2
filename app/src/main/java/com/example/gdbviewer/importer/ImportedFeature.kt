package com.example.gdbviewer.importer

import mil.nga.sf.GeometryType

/**
 * Representación intermedia de una geometría leída de KML/KMZ o Shapefile, antes de
 * insertarla en el GeoPackage. Las coordenadas siempre van como (lon, lat).
 */
data class ImportedFeature(
    val geometryType: GeometryType,
    val points: List<Pair<Double, Double>> = emptyList(),        // para POINT (1 elemento) y LINESTRING
    val rings: List<List<Pair<Double, Double>>> = emptyList(),   // para POLYGON (anillo exterior + huecos)
    val attributes: Map<String, String> = emptyMap()
)
