package com.example.gdbviewer.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * Mapa base de imágenes satelitales gratuito (Esri World Imagery).
 * Gratuito para uso general/no comercial de bajo volumen bajo los términos de Esri;
 * revisa https://www.esri.com/en-us/legal/terms/full-master-agreement si vas a usarlo
 * en producción con muchos usuarios.
 *
 * osmdroid no trae esta fuente incluida (solo trae OSM Mapnik por defecto), y ArcGIS Online
 * usa el orden de tile z/y/x en vez del estándar z/x/y, por eso se sobreescribe la URL.
 */
object EsriWorldImagery : OnlineTileSourceBase(
    "EsriWorldImagery",
    0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}$zoom/$y/$x"
    }
}
