package com.mingliu.inventoryapp

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.github.mikephil.charting.utils.ColorTemplate
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

private const val TAG = "StockChartActivity"

// Indices of the 3 grouped BarDataSets passed to BarData(...), in that fixed order.
private const val CATEGORY_CURRENT = 0
private const val CATEGORY_INCOMING = 1
private const val CATEGORY_OUTGOING = 2

// How many month-groups are visible on screen at once before the user has to drag.
// More visible groups means each bar renders narrower on screen (they share the
// same screen width), so this is a balance between "fatter bars" and "see more
// months at once" -- 3 keeps bars reasonably chunky while showing a full quarter.
private const val MAX_VISIBLE_MONTHS = 3f

// Bar sizing chosen so (BAR_WIDTH + BAR_SPACE) * 3 + GROUP_SPACE == 1.0,
// i.e. exactly one month-group per x-axis unit (see MPAndroidChart's groupBars docs).
private const val BAR_WIDTH = 0.26f
private const val BAR_SPACE = 0.02f
private const val GROUP_SPACE = 0.16f

/** Formats a TWD amount in thousands with one decimal place, e.g. 35600.0 -> "35.6k". */
private fun formatMoneyK(value: Double): String = String.format(Locale.US, "%.1fk", value / 1000.0)

/** Y-axis formatter: money mode shows "k" (thousands, 1 decimal); count mode shows whole numbers. */
private class StockAxisValueFormatter(private val showValue: Boolean) : ValueFormatter() {
    override fun getFormattedValue(value: Float): String {
        return if (showValue) formatMoneyK(value.toDouble()) else value.toInt().toString()
    }
}

/**
 * Admin-only screen showing monthly stock movement as a grouped + stacked bar chart.
 *
 * Each month is a group of 3 bars: current (end-of-month) inventory, incoming
 * stock, and outgoing stock. Each bar is itself stacked by product, one color
 * per product, consistent across all 3 categories. A toggle switches between
 * item counts and TWD value.
 *
 * Each bar (column) is one clickable unit: tapping anywhere on it -- any
 * product's segment -- shows every product's figure for that month/category
 * in a titled, horizontally scrollable strip at the top of the screen. The
 * product color legend at the bottom is also horizontally scrollable, so
 * neither panel grows unboundedly as more products are added.
 */
class StockChartActivity : AppCompatActivity() {

    private lateinit var barChart: BarChart
    private lateinit var toggleSwitch: Switch
    private lateinit var tvSwitchLabel: TextView
    private lateinit var tvDetailTitle: TextView
    private lateinit var llDetailChips: LinearLayout
    private lateinit var llLegend: LinearLayout

    private var summary: MonthlySummaryResponse? = null
    private var itemColors: Map<Int, Int> = emptyMap()
    private var showValue = false // false = item count, true = TWD value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_chart)

        barChart = findViewById(R.id.barChartMonthly)
        toggleSwitch = findViewById(R.id.switchValueToggle)
        tvSwitchLabel = findViewById(R.id.tvSwitchLabel)
        tvDetailTitle = findViewById(R.id.tvDetailTitle)
        llDetailChips = findViewById(R.id.llDetailChips)
        llLegend = findViewById(R.id.llLegend)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        setUpChartAppearance()

        toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            showValue = isChecked
            tvSwitchLabel.text = if (showValue) "顯示：金額 (TWD, 以千元 k 為單位)" else "顯示：件數"
            renderChart()
            clearSelectionDetail()
        }

        loadMonthlySummary()
    }

    private fun setUpChartAppearance() {
        barChart.apply {
            description.isEnabled = false
            legend.isEnabled = false // using our own scrollable legend row instead
            setDragEnabled(true)
            setScaleEnabled(false)
            setPinchZoom(false)
            setFitBars(false)
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.setDrawGridLines(false)
            setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry?, h: Highlight?) {
                    if (e is BarEntry && h != null) showSelectionDetail(e, h)
                }
                override fun onNothingSelected() {}
            })
        }
    }

    private fun loadMonthlySummary() {
        RetrofitClient.instance.getMonthlySummary().enqueue(object : Callback<MonthlySummaryResponse> {
            override fun onResponse(call: Call<MonthlySummaryResponse>, response: Response<MonthlySummaryResponse>) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to load monthly summary: HTTP ${response.code()}")
                    runOnUiThread {
                        Toast.makeText(this@StockChartActivity, "讀取月度資料失敗 (HTTP ${response.code()})", Toast.LENGTH_LONG).show()
                    }
                    return
                }

                val body = response.body()
                if (body == null || body.months.isEmpty()) {
                    runOnUiThread { tvDetailTitle.text = "目前尚無足夠的交易紀錄可供繪製圖表。" }
                    return
                }

                summary = body
                itemColors = buildItemColors(body.items)
                runOnUiThread {
                    setUpLegend(body.items)
                    renderChart()
                }
            }

            override fun onFailure(call: Call<MonthlySummaryResponse>, t: Throwable) {
                Log.e(TAG, "Network error loading monthly summary: ${t.localizedMessage}")
                runOnUiThread {
                    Toast.makeText(this@StockChartActivity, "無法連線到伺服器：${t.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    /** Assigns each product a stable, distinct color, in a fixed item order shared with the legend and chart stacks. */
    private fun buildItemColors(items: List<MonthlySummaryItemInfo>): Map<Int, Int> {
        val palette = ArrayList<Int>()
        palette.addAll(ColorTemplate.MATERIAL_COLORS.toList())
        palette.addAll(ColorTemplate.VORDIPLOM_COLORS.toList())
        palette.addAll(ColorTemplate.JOYFUL_COLORS.toList())
        palette.addAll(ColorTemplate.COLORFUL_COLORS.toList())
        return items.mapIndexed { index, item -> item.item_id to palette[index % palette.size] }.toMap()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** A small rounded, bordered rect used as the background for both legend and detail chips. */
    private fun chipBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#F8F9FA"))
        setStroke(dp(1), Color.parseColor("#E1E0D9"))
        cornerRadius = dp(6).toFloat()
    }

    /** Populates the horizontally scrollable product legend row at the bottom of the screen. */
    private fun setUpLegend(items: List<MonthlySummaryItemInfo>) {
        llLegend.removeAllViews()
        for (item in items) {
            val color = itemColors[item.item_id] ?: Color.GRAY

            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(4); gravity = Gravity.CENTER_VERTICAL }
                setBackgroundColor(color)
            }
            val label = TextView(this).apply {
                text = item.item_name
                textSize = 12f
                setTextColor(Color.parseColor("#495057"))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(14)
                }
                addView(dot)
                addView(label)
            }
            llLegend.addView(row)
        }
    }

    /** Clears the tap-detail panel back to its placeholder state (used when the display mode changes). */
    private fun clearSelectionDetail() {
        tvDetailTitle.text = "點擊任一長條圖，查看詳細數字"
        llDetailChips.removeAllViews()
    }

    /** Rebuilds the grouped + stacked BarData from [summary] using the current [showValue] mode. */
    private fun renderChart() {
        val data = summary ?: return
        val items = data.items
        if (items.isEmpty() || data.months.isEmpty()) {
            barChart.clear()
            barChart.invalidate()
            return
        }

        val currentEntries = ArrayList<BarEntry>()
        val incomingEntries = ArrayList<BarEntry>()
        val outgoingEntries = ArrayList<BarEntry>()

        for ((monthIndex, monthData) in data.data.withIndex()) {
            val byItemId = monthData.items.associateBy { it.item_id }

            // Keep values in the same fixed item order as `items`/`itemColors`
            // so stack segments line up with the same color across all 3 bars.
            val currentVals = FloatArray(items.size)
            val inVals = FloatArray(items.size)
            val outVals = FloatArray(items.size)

            items.forEachIndexed { i, item ->
                val entry = byItemId[item.item_id] ?: return@forEachIndexed
                currentVals[i] = (if (showValue) entry.ending_value else entry.ending_qty.toDouble()).toFloat()
                inVals[i] = (if (showValue) entry.incoming_value else entry.incoming_qty.toDouble()).toFloat()
                outVals[i] = (if (showValue) entry.outgoing_value else entry.outgoing_qty.toDouble()).toFloat()
            }

            currentEntries.add(BarEntry(monthIndex.toFloat(), currentVals))
            incomingEntries.add(BarEntry(monthIndex.toFloat(), inVals))
            outgoingEntries.add(BarEntry(monthIndex.toFloat(), outVals))
        }

        val colors = items.map { itemColors[it.item_id] ?: Color.GRAY }

        val dsCurrent = BarDataSet(currentEntries, "現有庫存").apply { setColors(colors); setDrawValues(false) }
        val dsIncoming = BarDataSet(incomingEntries, "入庫").apply { setColors(colors); setDrawValues(false) }
        val dsOutgoing = BarDataSet(outgoingEntries, "出庫").apply { setColors(colors); setDrawValues(false) }

        val barData = BarData(dsCurrent, dsIncoming, dsOutgoing).apply { barWidth = BAR_WIDTH }

        barChart.data = barData
        barChart.axisLeft.valueFormatter = StockAxisValueFormatter(showValue)
        barChart.xAxis.valueFormatter = IndexAxisValueFormatter(data.months.toTypedArray())
        barChart.xAxis.axisMinimum = 0f
        barChart.xAxis.axisMaximum = data.months.size.toFloat()
        barChart.groupBars(0f, GROUP_SPACE, BAR_SPACE)
        barChart.setVisibleXRangeMaximum(minOf(MAX_VISIBLE_MONTHS, data.months.size.toFloat()))
        // Default view: jump to the most recent months; the user drags left for history.
        barChart.moveViewToX(data.months.size.toFloat())
        barChart.invalidate()
    }

    /**
     * Populates the top detail panel for a tapped column: a clear "<month> <category>"
     * title, followed by every product's figure for that column as horizontally
     * scrollable chips. The whole bar is treated as one clickable unit -- which
     * specific product segment was tapped doesn't narrow the results.
     */
    private fun showSelectionDetail(entry: BarEntry, highlight: Highlight) {
        val data = summary ?: return
        val monthIndex = entry.x.toInt()
        val monthData = data.data.getOrNull(monthIndex) ?: return
        val month = data.months.getOrNull(monthIndex) ?: return

        val categoryLabel = when (highlight.dataSetIndex) {
            CATEGORY_CURRENT -> "現有庫存"
            CATEGORY_INCOMING -> "入庫"
            CATEGORY_OUTGOING -> "出庫"
            else -> ""
        }

        val categoryTotalValue = monthData.items.sumOf { itemEntry ->
            when (highlight.dataSetIndex) {
                CATEGORY_INCOMING -> itemEntry.incoming_value
                CATEGORY_OUTGOING -> itemEntry.outgoing_value
                else -> itemEntry.ending_value
            }
        }

        tvDetailTitle.text = if (showValue) {
            "$month　$categoryLabel NT$ ${formatMoneyK(categoryTotalValue)}"
        } else {
            "$month　$categoryLabel"
        }

        val byItemId = monthData.items.associateBy { it.item_id }
        llDetailChips.removeAllViews()

        var shownCount = 0
        for (item in data.items) {
            val itemEntry = byItemId[item.item_id] ?: continue
            val qty = when (highlight.dataSetIndex) {
                CATEGORY_INCOMING -> itemEntry.incoming_qty
                CATEGORY_OUTGOING -> itemEntry.outgoing_qty
                else -> itemEntry.ending_qty
            }
            if (qty == 0) continue // no activity/stock for this product in this category -> skip it

            val value = when (highlight.dataSetIndex) {
                CATEGORY_INCOMING -> itemEntry.incoming_value
                CATEGORY_OUTGOING -> itemEntry.outgoing_value
                else -> itemEntry.ending_value
            }
            val figure = if (showValue) "NT$ ${formatMoneyK(value)}" else "$qty 件"
            llDetailChips.addView(buildDetailChip(item.item_name, itemColors[item.item_id] ?: Color.GRAY, figure))
            shownCount++
        }

        if (shownCount == 0) {
            llDetailChips.addView(TextView(this).apply {
                text = "本項無資料"
                textSize = 12f
                setTextColor(Color.parseColor("#ADB5BD"))
            })
        }
    }

    /** Builds one small card showing a product's color dot, name, and figure, used in the detail panel. */
    private fun buildDetailChip(name: String, color: Int, figure: String): LinearLayout {
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(View(this@StockChartActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(4) }
                setBackgroundColor(color)
            })
            addView(TextView(this@StockChartActivity).apply {
                text = name
                textSize = 11f
                setTextColor(Color.parseColor("#495057"))
                maxLines = 1
            })
        }
        val figureText = TextView(this).apply {
            text = figure
            textSize = 13f
            setTextColor(Color.parseColor("#2A3B50"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = chipBackground()
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            }
            addView(nameRow)
            addView(figureText)
        }
    }
}