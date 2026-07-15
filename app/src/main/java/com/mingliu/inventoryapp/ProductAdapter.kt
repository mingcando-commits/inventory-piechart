package com.mingliu.inventoryapp

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.NumberFormat
import java.util.Locale

/** Stock level at or below this value is highlighted as low-stock. */
private const val LOW_STOCK_THRESHOLD = 5

/**
 * RecyclerView adapter for the product/stock list shown on the home screen.
 *
 * @param onItemClick invoked when a card is tapped, used to open the
 *   stock-in/stock-out dialog for that product.
 */
class ProductAdapter(
    private val productList: List<Product>,
    private val onItemClick: (Product) -> Unit
) : RecyclerView.Adapter<ProductAdapter.ProductViewHolder>() {

    class ProductViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvProductName: TextView = itemView.findViewById(R.id.tvItemName)
        val tvProductStock: TextView = itemView.findViewById(R.id.tvCurrentQty)
        val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
        val tvTwdAmount: TextView = itemView.findViewById(R.id.tvTwdAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product, parent, false)
        return ProductViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        val product = productList[position]

        holder.tvProductName.text = product.item_name
        holder.tvCategory.text = product.category

        // Highlight low stock in red so it's easy to spot at a glance.
        holder.tvProductStock.text = "庫存: ${product.current_qty} 件"
        holder.tvProductStock.setTextColor(
            if (product.current_qty <= LOW_STOCK_THRESHOLD) Color.parseColor("#DC3545") else Color.parseColor("#495057")
        )

        // twd_amount arrives as either a Number or a numeric String depending on the JSON source.
        val amountDouble = when (val amount = product.twd_amount) {
            is Number -> amount.toDouble()
            is String -> amount.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
        val formatter = NumberFormat.getCurrencyInstance(Locale.US)
        holder.tvTwdAmount.text = "${formatter.format(amountDouble).replace("$", "$ ")} TWD"

        holder.itemView.setOnClickListener {
            onItemClick(product)
        }
    }

    override fun getItemCount(): Int = productList.size
}
