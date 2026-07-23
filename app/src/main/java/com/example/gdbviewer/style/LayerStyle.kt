package com.example.gdbviewer.style

import java.io.Serializable

/**
 * Estilo visual de una capa: color, grosor de línea, opacidad de relleno (polígonos)
 * y radio de los puntos (para capas de puntos).
 */
data class LayerStyle(
    var color: Int,
    var fillAlpha: Int = 60,     // 0-255, solo aplica a polígonos
    var strokeWidth: Float = 5f, // grosor de línea/borde
    var pointRadius: Float = 9f  // radio del círculo para capas de puntos, en dp
) : Serializable

/** Paleta por defecto para que cada capa se vea distinta aunque el usuario no la personalice. */
object DefaultPalette {
    private val colors = listOf(
        0xFFE53935.toInt(), // rojo
        0xFF1E88E5.toInt(), // azul
        0xFF43A047.toInt(), // verde
        0xFFFB8C00.toInt(), // naranja
        0xFF8E24AA.toInt(), // morado
        0xFF00ACC1.toInt(), // cian
        0xFFFDD835.toInt(), // amarillo
        0xFF6D4C41.toInt()  // marrón
    )

    fun colorForIndex(index: Int): Int = colors[index % colors.size]
}
