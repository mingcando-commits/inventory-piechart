package com.mingliu.inventoryapp

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TAG = "PeriodSummaryActivity"

/**
 * 庫存期間出庫量統計查詢 (Inventory Period Stock-Out Quantity Statistics Query).
 *
 * Lets the user pick a start date and an end date (default: today), then
 * shows, per item, the stock balance as of the end date, the total OUT
 * quantity, and the total IN quantity within that date range -- so they can
 * judge which items need reordering. Results can also be exported as a CSV
 * file with two extra blank columns ("建議進貨數量"/"備註") to fill in after
 * review, matching how 存貨開帳 CSV import already round-trips through a
 * spreadsheet.
 *
 * Sort order matches ValuationReportActivity/CsvImportActivity's convention:
 * "Main" items first, then "Accessories", and within each category by
 * item_id ascending (creation order -- the backend already returns rows in
 * that order; grouping by category is done here on the client).
 */
class PeriodSummaryActivity : AppCompatActivity() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private lateinit var btnStartDate: MaterialButton
    private lateinit var btnEndDate: MaterialButton
    private lateinit var btnQuery: MaterialButton
    private lateinit var btnExportCsv: MaterialButton
    private lateinit var llContent: LinearLayout

    private val startCalendar: Calendar = Calendar.getInstance()
    private val endCalendar: Calendar = Calendar.getInstance()

    /** The last successful query result, kept around so "匯出 CSV" doesn't need a re-query. */
    private var lastResult: PeriodSummaryResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_period_summary)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        btnStartDate = findViewById(R.id.btnStartDate)
        btnEndDate = findViewById(R.id.btnEndDate)
        btnQuery = findViewById(R.id.btnQuery)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        llContent = findViewById(R.id.llPeriodSummaryContent)

        // Default: end date = today, start date = 30 days before that. Both
        // are freely editable via the date pickers below.
        endCalendar.time = java.util.Date()
        startCalendar.time = java.util.Date()
        startCalendar.add(Calendar.DAY_OF_MONTH, -30)

        updateDateButtonLabels()

        btnStartDate.setOnClickListener { showDatePicker(startCalendar) { updateDateButtonLabels() } }
        btnEndDate.setOnClickListener { showDatePicker(endCalendar) { updateDateButtonLabels() } }
        btnQuery.setOnClickListener { runQuery() }
        btnExportCsv.setOnClickListener { exportCsv() }

        runQuery()
    }

    private fun showDatePicker(calendar: Calendar, onPicked: () -> Unit) {
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                onPicked()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateDateButtonLabels() {
        btnStartDate.text = "起始：${dateFormat.format(startCalendar.time)}"
        btnEndDate.text = "結束：${dateFormat.format(endCalendar.time)}"
    }

    private fun runQuery() {
        if (startCalendar.time.after(endCalendar.time)) {
            Toast.makeText(this, "統計起始日期不可晚於統計結束日期", Toast.LENGTH_SHORT).show()
            return
        }

        val startStr = dateFormat.format(startCalendar.time)
        val endStr = dateFormat.format(endCalendar.time)

        btnQuery.isEnabled = false
        btnExportCsv.isEnabled = false
        showMessage("正在查詢統計資料，請稍候...", isError = false)

        RetrofitClient.instance.getPeriodSummary(startStr, endStr).enqueue(object : Callback<PeriodSummaryResponse> {
            override fun onResponse(call: Call<PeriodSummaryResponse>, response: Response<PeriodSummaryResponse>) {
                runOnUiThread { btnQuery.isEnabled = true }
                if (!response.isSuccessful) {
                    val detail = extractErrorDetail(response)
                    Log.e(TAG, "Period summary query failed: $detail")
                    runOnUiThread { showMessage("查詢失敗：$detail", isError = true) }
                    return
                }
                val body = response.body() ?: return
                lastResult = body
                runOnUiThread {
                    renderResult(body)
                    btnExportCsv.isEnabled = body.rows.isNotEmpty()
                }
            }

            override fun onFailure(call: Call<PeriodSummaryResponse>, t: Throwable) {
                Log.e(TAG, "Network error querying period summary: ${t.localizedMessage}")
                runOnUiThread {
                    btnQuery.isEnabled = true
                    showMessage("無法連線到伺服器：${t.localizedMessage}", isError = true)
                }
            }
        })
    }

    /** FastAPI's HTTPException responses look like {"detail": "..."}. */
    private fun extractErrorDetail(response: Response<*>): String {
        return try {
            val errorBodyText = response.errorBody()?.string()
            if (errorBodyText.isNullOrBlank()) "HTTP ${response.code()}"
            else org.json.JSONObject(errorBodyText).optString("detail", errorBodyText)
        } catch (e: Exception) {
            "HTTP ${response.code()}"
        }
    }

    private fun showMessage(message: String, isError: Boolean) {
        llContent.removeAllViews()
        llContent.addView(TextView(this).apply {
            text = message
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(0, 120, 0, 0)
            setTextColor(ContextCompat.getColor(this@PeriodSummaryActivity, if (isError) R.color.color_danger else R.color.color_on_surface_variant))
        })
    }

    private fun renderResult(body: PeriodSummaryResponse) {
        llContent.removeAllViews()
        llContent.addView(headerCard(body))

        if (body.rows.isEmpty()) {
            llContent.addView(TextView(this).apply {
                text = "目前尚無任何商品資料。"
                setTextColor(ContextCompat.getColor(this@PeriodSummaryActivity, R.color.color_on_surface_variant))
                setPadding(16, 16, 16, 16)
            })
            return
        }

        // Main first, then Accessories -- same convention as ValuationReportActivity
        // and the CSV-import preview screen. Within each category, rows already
        // arrive sorted by item_id ASC (creation order) from the backend.
        for (category in listOf("Main", "Accessories")) {
            val rowsInCategory = body.rows.filter { it.category == category }
            if (rowsInCategory.isEmpty()) continue

            llContent.addView(sectionHeader(if (category == "Main") "Main 主商品" else "Accessories 配件"))
            for (row in rowsInCategory) {
                llContent.addView(buildRowCard(row))
            }
        }
    }

    private fun headerCard(body: PeriodSummaryResponse): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }

        content.addView(TextView(this).apply {
            text = "統計起始日期：${body.start_date}\n統計結束日期：${body.end_date}"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(6f, 1f)
            setTextColor(ContextCompat.getColor(this@PeriodSummaryActivity, R.color.color_on_surface))
        })

        card.addView(content)
        return card
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@PeriodSummaryActivity, R.color.color_on_surface))
        setPadding(8, 12, 8, 8)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = 8
        layoutParams = params
    }

    private fun buildRowCard(row: PeriodSummaryRow): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 24) }

        content.addView(TextView(this).apply {
            text = row.item_name
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@PeriodSummaryActivity, R.color.color_on_surface))
        })

        content.addView(TextView(this).apply {
            text = "統計結束日期庫存量：${row.end_date_qty} 件"
            textSize = 12f
            setPadding(0, 8, 0, 0)
            setTextColor(ContextCompat.getColor(this@PeriodSummaryActivity, R.color.color_on_surface_variant))
        })

        val flowRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.topMargin = 4
            layoutParams = params
        }
        flowRow.addView(TextView(this).apply {
            text = "統計期間總出庫量：${row.period_out_qty} 件"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@PeriodSummaryActivity, R.color.color_danger))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        flowRow.addView(TextView(this).apply {
            text = "期間入庫量：${row.period_in_qty} 件"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@PeriodSummaryActivity, R.color.color_success))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        content.addView(flowRow)

        card.addView(content)
        return card
    }

    /**
     * Writes the last query result to a CSV file (Big5-unfriendly UTF-8 with
     * BOM, so it opens correctly in Excel on Windows) and hands it to the
     * system share sheet. Two extra blank columns -- 建議進貨數量 (suggested
     * reorder qty) and 備註 (remarks) -- are included so the reviewer can
     * fill them in directly in Excel/Sheets after judging the numbers.
     */
    private fun exportCsv() {
        val result = lastResult
        if (result == null || result.rows.isEmpty()) {
            Toast.makeText(this, "沒有可匯出的資料，請先查詢。", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val reportsDir = File(cacheDir, "reports").apply { mkdirs() }
            val fileName = "庫存期間出庫量統計_${result.start_date}_至_${result.end_date}.csv"
            val file = File(reportsDir, fileName)

            FileWriter(file).use { writer ->
                writer.write("﻿") // UTF-8 BOM so Excel doesn't mangle Chinese text
                writer.append("統計起始日期,").append(result.start_date).append("\n")
                writer.append("統計結束日期,").append(result.end_date).append("\n")
                writer.append("\n")
                writer.append("品項,分類,統計結束日期庫存量,統計期間總出庫量,期間入庫量,建議進貨數量,備註\n")

                for (category in listOf("Main", "Accessories")) {
                    for (row in result.rows.filter { it.category == category }) {
                        writer.append(csvEscape(row.item_name)).append(",")
                        writer.append(csvEscape(row.category)).append(",")
                        writer.append(row.end_date_qty.toString()).append(",")
                        writer.append(row.period_out_qty.toString()).append(",")
                        writer.append(row.period_in_qty.toString()).append(",")
                        writer.append(",") // 建議進貨數量 -- left blank for the reviewer to fill in
                        writer.append("\n")  // 備註 -- left blank for the reviewer to fill in
                    }
                }
            }

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "匯出統計報表"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export CSV: ${e.localizedMessage}")
            Toast.makeText(this, "匯出失敗：${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /** Quotes a CSV field if it contains a comma, quote, or newline. */
    private fun csvEscape(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
