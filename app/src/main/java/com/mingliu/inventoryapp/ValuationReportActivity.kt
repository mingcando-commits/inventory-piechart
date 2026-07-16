package com.mingliu.inventoryapp
import com.mingliu.inventoryapp.Product
import com.mingliu.inventoryapp.R
import com.mingliu.inventoryapp.RetrofitClient
import com.mingliu.inventoryapp.StockValuationResponse

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

private const val TAG = "ValuationReportActivity"

/** Parses a Retrofit "Any"-typed numeric field (server may send a JSON number or numeric string). */
private fun anyToDoubleOrNull(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
}

/** Formats a TWD amount in thousands with one decimal place, e.g. 35600.0 -> "35.6k". */
private fun formatMoneyK(value: Double): String = String.format(Locale.US, "%.1fk", value / 1000.0)

/** Formats a plain currency-style number with 2 decimal places, no thousands abbreviation. */
private fun formatPlain(value: Double): String = String.format(Locale.US, "%.2f", value)

/**
 * Admin-only detailed asset valuation report: current stock listed by
 * category (Main first, then Accessories), sorted within each category
 * using the same "主頁商品庫存展示排序" preference as the home screen, with
 * a subtotal per category and a grand total at the end.
 */
class ValuationReportActivity : AppCompatActivity() {

    private lateinit var llReportContent: LinearLayout
    private var sortCriteria: String = "ID_ASC"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_valuation_report)

        llReportContent = findViewById(R.id.llReportContent)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        sortCriteria = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getString("stock_sort_criteria", "ID_ASC") ?: "ID_ASC"

        loadValuation()
    }

    private fun loadValuation() {
        RetrofitClient.instance.getStockValuation().enqueue(object : Callback<StockValuationResponse> {
            override fun onResponse(call: Call<StockValuationResponse>, response: Response<StockValuationResponse>) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to load valuation: HTTP ${response.code()}")
                    runOnUiThread { Toast.makeText(this@ValuationReportActivity, "讀取估值資料失敗 (HTTP ${response.code()})", Toast.LENGTH_LONG).show() }
                    return
                }
                val body = response.body()
                val details = body?.details ?: emptyList()
                runOnUiThread { renderReport(details) }
            }

            override fun onFailure(call: Call<StockValuationResponse>, t: Throwable) {
                Log.e(TAG, "Network error loading valuation: ${t.localizedMessage}")
                runOnUiThread { Toast.makeText(this@ValuationReportActivity, "無法連線到伺服器：${t.localizedMessage}", Toast.LENGTH_LONG).show() }
            }
        })
    }

    /** Applies the same sort criteria used on the home screen (see HomeActivity.applySortingAndRenderWithList). */
    private fun sortItems(items: List<Product>): List<Product> = when (sortCriteria) {
        "NAME_ASC" -> items.sortedBy { it.item_name.lowercase() }
        "ID_ASC" -> items.sortedBy { it.item_id }
        "ID_DESC" -> items.sortedByDescending { it.item_id }
        "UPDATE_DESC" -> items.sortedByDescending { "${it.last_update_date ?: "1970-01-01"} ${it.last_update_time ?: "00:00:00"}" }
        else -> items
    }

    private fun renderReport(details: List<Product>) {
        llReportContent.removeAllViews()

        if (details.isEmpty()) {
            llReportContent.addView(TextView(this).apply {
                text = "目前尚無任何商品資料。"
                setTextColor(Color.parseColor("#ADB5BD"))
                textSize = 13f
                setPadding(16, 16, 16, 16)
            })
            return
        }

        var grandTotal = 0.0

        // Main first, then Accessories -- matches the category CHECK constraint's only two values.
        for (category in listOf("Main", "Accessories")) {
            val itemsInCategory = sortItems(details.filter { it.category == category })
            if (itemsInCategory.isEmpty()) continue

            llReportContent.addView(sectionHeader(if (category == "Main") "Main 主商品" else "Accessories 配件"))

            var categoryTotal = 0.0
            for (item in itemsInCategory) {
                val twdAmount = anyToDoubleOrNull(item.twd_amount) ?: 0.0
                categoryTotal += twdAmount
                llReportContent.addView(buildItemCard(item, twdAmount))
            }
            grandTotal += categoryTotal

            llReportContent.addView(totalRow(if (category == "Main") "Main 小計" else "Accessories 小計", categoryTotal, isGrandTotal = false))
        }

        llReportContent.addView(totalRow("總計 Total", grandTotal, isGrandTotal = true))
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#2A3B50"))
        setPadding(8, 12, 8, 8)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = 8
        layoutParams = params
        setBackgroundColor(Color.TRANSPARENT)
    }

    /** One card per item: quantity, purchase-currency price, rate/factor used (tagged if global), unit price, and stock value. */
    private fun buildItemCard(item: Product, twdAmount: Double): LinearLayout {
        val usdPrice = anyToDoubleOrNull(item.usd_price) ?: 0.0
        val rateUsed = anyToDoubleOrNull(item.exchange_rate_used) ?: 0.0
        val taxUsed = anyToDoubleOrNull(item.tax_coefficient_used) ?: 0.0
        val unitPriceTwd = anyToDoubleOrNull(item.unit_price_twd) ?: 0.0
        val isGlobalRate = item.is_global_rate ?: false
        val isGlobalTax = item.is_global_tax ?: false

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(24, 20, 24, 20)
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = 8
            layoutParams = params
        }

        card.addView(TextView(this).apply {
            text = item.item_name
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#212529"))
            setPadding(0, 0, 0, 6)
        })

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(detailRow("庫存數量: ${item.current_qty} 件", "採購幣別單價: \$${formatPlain(usdPrice)}"))
        grid.addView(detailRow(
            "使用匯率: ${formatPlain(rateUsed)}${if (isGlobalRate) " (全域)" else ""}",
            "使用 factor: ${formatPlain(taxUsed)}${if (isGlobalTax) " (全域)" else ""}"
        ))
        val valueRow = detailRow("本幣單價: NT$ ${formatPlain(unitPriceTwd)}", "")
        val valueText = TextView(this).apply {
            text = "庫存金額: NT$ ${formatMoneyK(twdAmount)}"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#212529"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        valueRow.addView(valueText)
        grid.addView(valueRow)

        card.addView(grid)
        return card
    }

    /** Two labels side by side, used to build the compact 2-column detail grid inside each item card. */
    private fun detailRow(leftText: String, rightText: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.topMargin = 2
            layoutParams = params
        }
        row.addView(TextView(this).apply {
            text = leftText
            textSize = 11f
            setTextColor(Color.parseColor("#6C757D"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (rightText.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = rightText
                textSize = 11f
                setTextColor(Color.parseColor("#6C757D"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        return row
    }

    private fun totalRow(label: String, amount: Double, isGrandTotal: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(if (isGrandTotal) Color.parseColor("#2A3B50") else Color.parseColor("#E9ECEF"))
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = if (isGrandTotal) 0 else 16
            layoutParams = params
        }
        val textColor = if (isGrandTotal) Color.WHITE else Color.parseColor("#2A3B50")

        row.addView(TextView(this).apply {
            text = label
            textSize = if (isGrandTotal) 15f else 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = "NT$ ${formatMoneyK(amount)}"
            textSize = if (isGrandTotal) 15f else 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
        })
        return row
    }
}

