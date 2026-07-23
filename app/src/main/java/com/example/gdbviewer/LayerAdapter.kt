package com.example.gdbviewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.gdbviewer.crs.CrsCatalog
import com.example.gdbviewer.style.LayerStyle

/**
 * @param layerCrs mapa mutable (nombre de capa -> código EPSG), solo se lee para mostrar la etiqueta.
 * @param layerStyles mapa mutable (nombre de capa -> LayerStyle), solo se lee para pintar el swatch de color.
 */
class LayerAdapter(
    private val layerNames: List<String>,
    private val layerCrs: MutableMap<String, String>,
    private val layerStyles: MutableMap<String, LayerStyle>,
    private val onSelectionChanged: (List<String>) -> Unit,
    private val onDefineCrs: (String) -> Unit,
    private val onDefineStyle: (String) -> Unit
) : RecyclerView.Adapter<LayerAdapter.LayerViewHolder>() {

    private val selected = mutableSetOf<String>()

    inner class LayerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.cbLayer)
        val crsLabel: TextView = view.findViewById(R.id.tvLayerCrs)
        val crsButton: ImageButton = view.findViewById(R.id.btnLayerCrs)
        val styleButton: ImageButton = view.findViewById(R.id.btnLayerStyle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LayerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_layer, parent, false)
        return LayerViewHolder(view)
    }

    override fun onBindViewHolder(holder: LayerViewHolder, position: Int) {
        val name = layerNames[position]
        holder.checkBox.text = name
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = selected.contains(name)
        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selected.add(name) else selected.remove(name)
            onSelectionChanged(selected.toList())
        }

        val epsg = layerCrs[name]
        holder.crsLabel.text = if (epsg != null) CrsCatalog.labelFor(epsg).let {
            if (it.length > 18) epsg else it
        } else "CRS: ?"
        holder.crsButton.setOnClickListener { onDefineCrs(name) }

        layerStyles[name]?.let { style ->
            holder.styleButton.drawable?.setTint(style.color)
        }
        holder.styleButton.setOnClickListener { onDefineStyle(name) }
    }

    override fun getItemCount(): Int = layerNames.size

    fun refreshCrsLabels() = notifyDataSetChanged()

    fun getSelectedLayers(): List<String> = selected.toList()
}
