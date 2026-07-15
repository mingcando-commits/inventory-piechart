package com.mingliu.inventoryapp

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.LegendEntry
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
private const val MAX_VISIBLE_MONTHS = 6f

// Bar sizing chosen so (BAR_WIDTH + BAR_SPACE) * 3 + GROUP_SPACE == 1.0,
// i.e. exactly one month-group per x-axis unit (see MPAndroidChart's groupBars docs).
private const val BAR_WIDTH = 0.25f
private const val BAR_SPACE = 0.02f
private const val GROUP_SPACE = 0.19f

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
 * item counts and TWD value. Tapping any bar shows a numeric, per-product
 * breakdown at the top of the screen -- useful once the chart has enough
 * products/months to get visually crowded.
 */
class StockChartActivity : AppCompatActivity() {

    private lateinit var barChart: BarChart
    private lateinit var toggleSwitch: Switch
    private lateinit var tvSwitchLabel: TextView
    private lateinit var tvSelectionDetail: TextView

    private var summary: MonthlySummaryResponse? = null
    private var itemColors: Map<Int, Int> = emptyMap()
    private var showValue = false // false = item count, true = TWD value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_chart)

        barChart = findViewById(R.id.barChartMonthly)
        toggleSwitch = findViewById(R.id.switchValueToggle)
        tvSwitchLabel = findViewById(R.id.tvSwitchLabel)
        tvSelectionDetail = findViewById(R.id.tvSelectionDetail)

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        setUpChartAppearance()

        toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            showValue = isChecked
            tvSwitchLabel.text = if (showValue) "顯示：金額 (TWD)" else "顯示：件數"
            renderChart()
        }

        loadMonthlySummary()
    }

    private fun setUpChartAppearance() {
        barChart.apply {
            description.isEnabled = false
            setDragEnabled(true)
            setScaleEnabled(false)
            setPinchZoom(false)
            setFitBars(false)
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.setDrawGridLines(false)
            legend.textSize = 11f
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
                    runOnUiThread {
                        tvSelectionDetail.text = "目前尚無足夠的交易紀錄可供繪製圖表。"
                    }
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

    /** Builds a custom legend mapping each product's name to its chart color. */
    private fun setUpLegend(items: List<MonthlySummaryItemInfo>) {
        val entries = items.map { item ->
            LegendEntry().apply {
                label = item.item_name
                formColor = itemColors[item.item_id] ?: Color.GRAY
            }
        }
        barChart.legend.setCustom(entries)
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

    /** Populates the top detail panel with a per-product breakdown for the tapped bar. */
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

        val byItemId = monthData.items.associateBy { it.item_id }
        val stackIndex = highlight.stackIndex
        // A specific product segment was tapped -> show just that product; otherwise show all.
        val itemsToShow = if (stackIndex in data.items.indices) listOf(data.items[stackIndex]) else data.items

        val sb = StringBuilder("$month　$categoryLabel\n")
        for (item in itemsToShow) {
            val itemEntry = byItemId[item.item_id] ?: continue
            val qty = when (highlight.dataSetIndex) {
                CATEGORY_INCOMING -> itemEntry.incoming_qty
                CATEGORY_OUTGOING -> itemEntry.outgoing_qty
                else -> itemEntry.ending_qty
            }
            val value = when (highlight.dataSetIndex) {
                CATEGORY_INCOMING -> itemEntry.incoming_value
                CATEGORY_OUTGOING -> itemEntry.outgoing_value
                else -> itemEntry.ending_value
            }
            val figure = if (showValue) "NT$ ${formatMoneyK(value)}" else "$qty 件"
            sb.append("${item.item_name}: $figure\n")
        }

        tvSelectionDetail.text = sb.toString().trim()
    }
}
