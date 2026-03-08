package com.example.manifestscanner

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.manifestscanner.R
import com.example.manifestscanner.databinding.ItemExtraBinding
import com.example.manifestscanner.viewmodel.ExtraItem

/**
 * Displays [ExtraItem] rows in the "Extras" tab of the Discrepancy Report.
 *
 * Each row shows the raw barcode string and how many times it was scanned.
 */
class ExtraItemsAdapter :
    ListAdapter<ExtraItem, ExtraItemsAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemExtraBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemExtraBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ExtraItem) {
            binding.txtExtraBarcode.text = item.barcode
            binding.txtExtraScanCount.text = binding.root.context.getString(
                R.string.extra_detail,
                item.scanCount
            )
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ExtraItem>() {
            override fun areItemsTheSame(old: ExtraItem, new: ExtraItem): Boolean =
                old.barcode == new.barcode

            override fun areContentsTheSame(old: ExtraItem, new: ExtraItem): Boolean =
                old == new
        }
    }
}
