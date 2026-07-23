package com.example.gdbviewer.style

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** Crea íconos de punto (círculo relleno con borde blanco) según el color/tamaño de la capa. */
object MarkerIconFactory {

    fun circleIcon(context: Context, color: Int, radiusDp: Float): Drawable {
        val density = context.resources.displayMetrics.density
        val radiusPx = radiusDp * density
        val strokePx = 2f * density
        val size = ((radiusPx + strokePx) * 2).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }

        canvas.drawCircle(center, center, radiusPx, fillPaint)
        canvas.drawCircle(center, center, radiusPx, strokePaint)

        return BitmapDrawable(context.resources, bitmap)
    }
}
