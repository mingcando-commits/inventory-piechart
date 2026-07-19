package com.mingliu.inventoryapp

import android.content.Context
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

// ========================================================
// 1. API route definitions (all relative to RetrofitClient.BASE_URL)
// ========================================================
interface ApiService {

    /** Health check used to keep a free-tier host warm. */
    @GET("/api/ping")
    fun pingServer(): Call<ResponseBody>

    /** Lists all operators. Admin only (enforced server-side). */
    @GET("/api/operators")
    fun getAllOperators(): Call<List<OperatorResponse>>

    @FormUrlEncoded
    @POST("/api/login")
    fun login(
        @Field("username") username: String,
        @Field("password") password: String
    ): Call<LoginResponse>

    @GET("/api/stock/valuation")
    fun getStockValuation(): Call<StockValuationResponse>

    /** Transaction history for a single item, newest first. */
    @GET("/api/transactions/item/{item_id}")
    fun getItemHistory(
        @Path("item_id") itemId: Int
    ): Call<List<TransactionHistoryItem>>

    @POST("/api/transactions")
    fun createTransaction(
        @Body request: TransactionRequest
    ): Call<TransactionResponse>

    @POST("/api/items")
    fun createItem(
        @Body request: ItemCreateRequest
    ): Call<ItemCreateResponse>

    /** Lightweight single-item fetch (vs. the full valuation list) -- used to refresh the edit-item form. */
    @GET("/api/items/{item_id}")
    fun getItem(
        @Path("item_id") itemId: Int
    ): Call<ItemDetailResponse>

    /** Updates an existing item's master data (name, category, price, rate, factor). Never touches current_qty. */
    @PUT("/api/items/{item_id}")
    fun updateItem(
        @Path("item_id") itemId: Int,
        @Body request: ItemCreateRequest
    ): Call<ResponseBody>

    /** Deletes an item. Server rejects (HTTP 400) if the item has any transaction history. */
    @DELETE("/api/items/{item_id}")
    fun deleteItem(
        @Path("item_id") itemId: Int
    ): Call<ResponseBody>

    @POST("/api/operators")
    fun createOperator(
        @Body request: OperatorCreateRequest
    ): Call<ResponseBody>

    @DELETE("/api/operators/{operator_id}")
    fun deleteOperator(
        @Path("operator_id") operatorId: Int
    ): Call<ResponseBody>

    @POST("api/operators/{operator_id}/password")
    fun changePassword(
        @Path("operator_id") operatorId: Int,
        @Body request: PasswordChangeRequest
    ): Call<ResponseBody>

    /** Full stock transaction log across all items, newest first. */
    @GET("api/stock/global-history")
    fun getGlobalHistory(): Call<List<GlobalLogResponse>>

    /** Per-month, per-item stock movement summary, used by the trend chart screen. */
    @GET("api/stock/monthly-summary")
    fun getMonthlySummary(): Call<MonthlySummaryResponse>

    /** The global exchange rate / adjustment factor used by items that don't set their own. Admin only. */
    @GET("api/settings/global")
    fun getGlobalSettings(): Call<GlobalSettingsResponse>

    /** Updates the global exchange rate / adjustment factor. Admin only. */
    @PUT("api/settings/global")
    fun updateGlobalSettings(
        @Body request: GlobalSettingsUpdateRequest
    ): Call<ResponseBody>
}

// ========================================================
// 2. Data models (mirror the backend's Pydantic models/response shapes)
// ========================================================

data class LoginResponse(
    val access_token: String,
    val token_type: String,
    val is_admin: Boolean,
    val operator_id: Int
)

data class StockValuationResponse(
    val details: List<Product>,
    val total_twd_amount: Double
)

/**
 * A stock item as returned by /api/stock/valuation.
 *
 * exchange_rate/tax_coefficient are the item's *raw* per-item override (null
 * if this item uses the global fallback) -- used to correctly prefill the
 * edit-item form as blank vs. filled.
 * exchange_rate_used/tax_coefficient_used are the *resolved* values actually
 * applied (item override, or the global default) -- used for display.
 * is_global_rate/is_global_tax indicate which of the two was used.
 */
data class Product(
    @SerializedName("item_id", alternate = ["id", "id_item"])
    val item_id: Int,
    val item_name: String,
    val category: String,
    val current_qty: Int,
    val usd_price: Any,
    val twd_amount: Any,
    val last_update_date: String?,
    val last_update_time: String?,
    val exchange_rate: Any? = null,
    val tax_coefficient: Any? = null,
    val exchange_rate_used: Any? = null,
    val tax_coefficient_used: Any? = null,
    val is_global_rate: Boolean? = null,
    val is_global_tax: Boolean? = null,
    val unit_price_twd: Any? = null
)

data class TransactionRequest(
    val item_id: Int,
    val io_type: String, // "IN" or "OUT"
    val transaction_qty: Int,
    val remark: String
)

data class TransactionResponse(
    val message: String,
    val current_stock: Int
)

/** Used for both creating and updating an item -- null rate/factor means "use the global fallback". */
data class ItemCreateRequest(
    val item_name: String,
    val category: String, // "Main" or "Accessories"
    val usd_price: Double,
    val exchange_rate: Double?,
    val tax_coefficient: Double?
)

data class ItemCreateResponse(
    val message: String,
    val item_id: Int
)

/** Response for GET /api/items/{item_id} -- a lightweight single-item fetch used to refresh the edit form. */
data class ItemDetailResponse(
    val item_id: Int,
    val item_name: String,
    val category: String,
    val usd_price: Any,
    val exchange_rate: Any?,
    val tax_coefficient: Any?
)

data class TransactionHistoryItem(
    val transaction_date: String,
    val transaction_time: String,
    val io_type: String, // "IN" or "OUT"
    val qty: Int,        // sign already applied server-side (+ for IN, - for OUT)
    val post_balance_qty: Int,
    val remark: String?,
    val operator_name: String
)

data class OperatorCreateRequest(
    val operator_name: String,
    val password: String,
    val is_admin: Boolean = false
)

data class OperatorResponse(
    val operator_id: Int,
    val operator_name: String,
    val is_admin: Boolean
)

data class GlobalLogResponse(
    val tran_id: Int,
    val item_id: Int,
    val item_name: String,
    val io_type: String,
    val transaction_qty: Int,
    val transaction_date: String,
    val transaction_time: String,
    val remark: String?,
    val post_balance_qty: Int,
    val operator_name: String?
)

/** Response body for GET /api/stock/monthly-summary. */
data class MonthlySummaryResponse(
    val months: List<String>,                 // e.g. ["2025-01", "2025-02", ...], chronological
    val items: List<MonthlySummaryItemInfo>,   // fixed item order used for stacking + coloring
    val data: List<MonthlySummaryMonthData>
)

data class MonthlySummaryItemInfo(
    val item_id: Int,
    val item_name: String,
    val category: String
)

data class MonthlySummaryMonthData(
    val month: String,
    val items: List<MonthlySummaryItemEntry>
)

/**
 * One item's activity within a single month.
 * ending_* reflects the stock level at the end of that month (carried forward
 * from the prior month if there was no activity). Values are TWD, priced at
 * *today's* item price (there's no historical price table).
 */
data class MonthlySummaryItemEntry(
    val item_id: Int,
    val item_name: String,
    val category: String,
    val incoming_qty: Int,
    val incoming_value: Double,
    val outgoing_qty: Int,
    val outgoing_value: Double,
    val ending_qty: Int,
    val ending_value: Double
)

data class PasswordChangeRequest(
    val old_password: String, // required when a non-admin changes their own password
    val new_password: String
)

/** The global exchange rate / adjustment factor used by items that don't set their own. */
data class GlobalSettingsResponse(
    val global_exchange_rate: Double,
    val global_tax_coefficient: Double
)

data class GlobalSettingsUpdateRequest(
    val global_exchange_rate: Double,
    val global_tax_coefficient: Double
)

// ========================================================
// 3. Retrofit client (single source of truth for BASE_URL)
// ========================================================
object RetrofitClient {

    private const val BASE_URL = "https://inventoryapp-s3w2.onrender.com"
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(TokenInterceptor(appContext))
            .addInterceptor(AuthExpiredInterceptor(appContext))
            .build()
    }

    val instance: ApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(ApiService::class.java)
    }
}
