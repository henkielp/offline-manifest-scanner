package com.example.manifestscanner

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.manifestscanner.R
import com.example.manifestscanner.databinding.ItemManifestBinding
import com.example.manifestscanner.viewmodel.ManifestItem

/**
 * Displays [ManifestItem] rows in a RecyclerView.
 *
 * Used in two contexts:
 *   1. ManifestReady screen: shows all parsed items (scannedCases will be 0).
 *   2. Missing Items tab in Reporting: shows items where scannedCases < expectedCases.
 *
 * The progress text and color adapt automatically based on the scan state:
 *   - All scanned: green text, e.g. "2 of 2"
 *   - Partially scanned: orange text, e.g. "1 of 2"
 *   - Not scanned at all: red text, e.g. "0 of 2"
 */
class ManifestItemAdapter :
    ListAdapter<ManifestItem, ManifestItemAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemManifestBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemManifestBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ManifestItem) {
            val context = binding.root.context

            binding.txtItemDescription.text = item.description
            binding.txtItemUpc.text = item.upc

            val progressText = context.getString(
                R.string.missing_detail,
                item.scannedCases,
                item.expectedCases
            )
            binding.txtItemProgress.text = progressText

            // Color-code based on scan completion.
            val color = when {
                item.scannedCases >= item.expectedCases ->
                    ContextCompat.getColor(context, R.color.status_complete)
                item.scannedCases > 0 ->
                    ContextCompat.getColor(context, R.color.status_extra)
                else ->
                    ContextCompat.getColor(context, R.color.status_missing)
            }
            binding.txtItemProgress.setTextColor(color)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ManifestItem>() {
            override fun areItemsTheSame(old: ManifestItem, new: ManifestItem): Boolean =
                old.upc == new.upc

            override fun areContentsTheSame(old: ManifestItem, new: ManifestItem): Boolean =
                old == new
        }
    }
}
