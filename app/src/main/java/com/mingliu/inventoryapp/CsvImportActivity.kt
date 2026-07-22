package com.mingliu.inventoryapp

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "CsvImportActivity"
private const val EXTRA_FILE_URI = "file_uri"

/** Parses a Retrofit "Any"-typed numeric field (server may send a JSON number, numeric string, or null). */
private fun anyToDoubleOrNull(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
}

/**
 * FastAPI's HTTPException responses look like {"detail": "..."} -- surface
 * that actual message (e.g. "系統錯誤：找不到 Admin 帳號") instead of just a
 * generic HTTP status code, which is not enough to diagnose anything.
 */
private fun extractErrorDetail(response: Response<*>): String {
    return try {
        val errorBodyText = response.errorBody()?.string()
        if (errorBodyText.isNullOrBlank()) {
            "HTTP ${response.code()}"
        } else {
            org.json.JSONObject(errorBodyText).optString("detail", errorBodyText)
        }
    } catch (e: Exception) {
        "HTTP ${response.code()}"
    }
}

/**
 * Admin-only: preview then confirm a stock-in CSV batch import (存貨開帳-style
 * opening balance load). Reads the picked file, sends its raw content to the
 * backend for parsing/validation (no DB writes yet), shows every row's
 * outcome (new item / stock update / error, with reasons), and only writes
 * to the database after the admin explicitly confirms.
 */
class CsvImportActivity : AppCompatActivity() {

    companion object {
        fun newIntent(context: Context, fileUri: Uri): Intent {
            return Intent(context, CsvImportActivity::class.java).apply {
                putExtra(EXTRA_FILE_URI, fileUri.toString())
            }
        }
    }

    private lateinit var llContent: LinearLayout
    private lateinit var btnConfirm: MaterialButton
    private var previewRows: List<BulkImportRow> = emptyList()
    private var isSubmitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_csv_import)

        llContent = findViewById(R.id.llImportContent)
        btnConfirm = findViewById(R.id.btnConfirmImport)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnCancelImport).setOnClickListener { finish() }
        btnConfirm.setOnClickListener { confirmAndCommit() }
        btnConfirm.isEnabled = false

        val uriString = intent.getStringExtra(EXTRA_FILE_URI)
        if (uriString == null) {
            Toast.makeText(this, "找不到選取的檔案", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val csvContent = readCsvContent(Uri.parse(uriString))
        if (csvContent == null) {
            Toast.makeText(this, "讀取檔案失敗，請確認檔案格式為 CSV 純文字檔", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        loadPreview(csvContent)
    }

    /** Reads the picked file as UTF-8 text, stripping a leading BOM if present (common in Excel-exported CSVs). */
    private fun readCsvContent(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    var text = reader.readText()
                    if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
                    text
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read CSV file: ${e.localizedMessage}")
            null
        }
    }

    private fun loadPreview(csvContent: String) {
        showMessage("正在解析 CSV 檔案並比對現有商品，請稍候...", isError = false)

        RetrofitClient.instance.previewBulkImport(BulkImportPreviewRequest(csvContent)).enqueue(object : Callback<BulkImportPreviewResponse> {
            override fun onResponse(call: Call<BulkImportPreviewResponse>, response: Response<BulkImportPreviewResponse>) {
                if (!response.isSuccessful) {
                    val detail = extractErrorDetail(response)
                    Log.e(TAG, "Bulk import preview failed: $detail")
                    runOnUiThread { showMessage("預覽失敗：$detail", isError = true) }
                    return
                }
                val body = response.body() ?: return
                previewRows = body.rows
                runOnUiThread { renderPreview(body) }
            }

            override fun onFailure(call: Call<BulkImportPreviewResponse>, t: Throwable) {
                Log.e(TAG, "Failed to preview bulk import: ${t.localizedMessage}")
                runOnUiThread { showMessage("無法連線到伺服器：${t.localizedMessage}", isError = true) }
            }
        })
    }

    private fun showMessage(message: String, isError: Boolean) {
        llContent.removeAllViews()
        llContent.addView(TextView(this).apply {
            text = message
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(0, 120, 0, 0)
            setTextColor(ContextCompat.getColor(this@CsvImportActivity, if (isError) R.color.color_danger else R.color.color_on_surface_variant))
        })
    }

    private fun renderPreview(response: BulkImportPreviewResponse) {
        llContent.removeAllViews()
        llContent.addView(buildSummaryCard(response))

        if (response.rows.isEmpty()) {
            llContent.addView(TextView(this).apply {
                text = "CSV 檔案中沒有可處理的資料列。"
                setTextColor(ContextCompat.getColor(this@CsvImportActivity, R.color.color_on_surface_variant))
                setPadding(16, 16, 16, 16)
            })
        } else {
            for (row in response.rows) {
                llContent.addView(buildRowCard(row))
            }
        }

        btnConfirm.isEnabled = (response.new_count + response.update_count) > 0
    }

    private fun buildSummaryCard(response: BulkImportPreviewResponse): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }

        content.addView(TextView(this).apply {
            text = "批次處理預覽"
            setTypeface(typeface, Typeface.BOLD)
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@CsvImportActivity, R.color.color_on_surface))
        })
        content.addView(TextView(this).apply {
            text = "🆕 新增商品：${response.new_count} 項\n🔄 更新庫存：${response.update_count} 項\n⚠️ 錯誤略過：${response.error_count} 筆"
            setPadding(0, 16, 0, 0)
            textSize = 14f
            setLineSpacing(8f, 1f)
            setTextColor(ContextCompat.getColor(this@CsvImportActivity, R.color.color_on_surface))
        })
        if (response.new_count + response.update_count > 0) {
            content.addView(TextView(this).apply {
                text = "\n操作人員將記錄為 Admin，備註為「開帳」。"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@CsvImportActivity, R.color.color_on_surface_variant))
            })
        }

        card.addView(content)
        return card
    }

    private fun buildRowCard(row: BulkImportRow): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 24) }

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(TextView(this).apply {
            text = "#${row.row_number}  ${row.item_name}"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@CsvImportActivity, R.color.color_on_surface))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 2
        })

        val (badgeText, badgeTextColorRes, badgeBgColorRes) = when (row.action) {
            "create" -> Triple("新增", R.color.color_on_brand_navy, R.color.color_success)
            "update" -> Triple("更新", R.color.color_on_secondary_container, R.color.color_secondary_container)
            else -> Triple("略過", R.color.color_on_error, R.color.color_danger)
        }
        headerRow.addView(TextView(this).apply {
            text = badgeText
            setTextColor(ContextCompat.getColor(this@CsvImportActivity, badgeTextColorRes))
            setBackgroundColor(ContextCompat.getColor(this@CsvImportActivity, badgeBgColorRes))
            setPadding(16, 6, 16, 6)
            textSize = 11f
        })
        content.addView(headerRow)

        val detailText = if (row.action == "error") {
            "原因：${row.error_message ?: "未知錯誤"}"
        } else {
            val priceText = anyToDoubleOrNull(row.usd_price)?.let { "採購幣別單價: \$${"%.2f".format(it)}" }
                ?: "採購幣別單價: (沿用既有商品單價)"
            "類別: ${row.category}　入庫數量: ${row.quantity} 件\n$priceText"
        }
        content.addView(TextView(this).apply {
            text = detailText
            textSize = 12f
            setPadding(0, 8, 0, 0)
            setTextColor(ContextCompat.getColor(this@CsvImportActivity, R.color.color_on_surface_variant))
        })

        card.addView(content)
        return card
    }

    private fun confirmAndCommit() {
        if (isSubmitting) return
        val actionableRows = previewRows.filter { it.action == "create" || it.action == "update" }
        if (actionableRows.isEmpty()) {
            Toast.makeText(this, "沒有可處理的資料列。", Toast.LENGTH_SHORT).show()
            return
        }

        val createCount = actionableRows.count { it.action == "create" }
        val updateCount = actionableRows.count { it.action == "update" }

        MaterialAlertDialogBuilder(this)
            .setTitle("確認批次匯入")
            .setMessage("即將處理 $createCount 筆新增商品與 $updateCount 筆庫存更新。\n\n操作人員將記錄為 Admin，備註為「開帳」。\n\n此操作無法復原，確定要繼續嗎？")
            .setPositiveButton("確定匯入") { _, _ -> submitCommit(actionableRows) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun submitCommit(rows: List<BulkImportRow>) {
        isSubmitting = true
        btnConfirm.isEnabled = false
        showMessage("正在寫入資料庫，請稍候...", isError = false)

        RetrofitClient.instance.commitBulkImport(BulkImportCommitRequest(rows)).enqueue(object : Callback<BulkImportCommitResponse> {
            override fun onResponse(call: Call<BulkImportCommitResponse>, response: Response<BulkImportCommitResponse>) {
                runOnUiThread {
                    isSubmitting = false
                    if (response.isSuccessful) {
                        val body = response.body()
                        MaterialAlertDialogBuilder(this@CsvImportActivity)
                            .setTitle("匯入完成")
                            .setMessage(body?.message ?: "批次匯入已完成。")
                            .setCancelable(false)
                            .setPositiveButton("OK") { _, _ ->
                                setResult(RESULT_OK)
                                finish()
                            }
                            .show()
                    } else {
                        btnConfirm.isEnabled = true
                        val detail = extractErrorDetail(response)
                        Log.e(TAG, "Bulk import commit failed: $detail")
                        MaterialAlertDialogBuilder(this@CsvImportActivity)
                            .setTitle("匯入失敗")
                            .setMessage("$detail\n\n未寫入任何資料（已自動回滾）。")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }

            override fun onFailure(call: Call<BulkImportCommitResponse>, t: Throwable) {
                Log.e(TAG, "Failed to commit bulk import: ${t.localizedMessage}")
                runOnUiThread {
                    isSubmitting = false
                    btnConfirm.isEnabled = true
                    Toast.makeText(this@CsvImportActivity, "無法連線到伺服器：${t.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }
}
