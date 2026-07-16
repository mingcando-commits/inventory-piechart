package com.mingliu.inventoryapp

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.NumberFormat
import java.util.Locale

private const val TAG = "HomeActivity"
private const val ADMIN_MENU_MANAGE_OPERATORS = 0
private const val ADMIN_MENU_CHANGE_PASSWORD = 1
private const val ADMIN_MENU_SORT_PREFERENCE = 2
private const val ADMIN_MENU_GLOBAL_HISTORY = 3
private const val ADMIN_MENU_STOCK_CHART = 4
private const val ADMIN_MENU_GLOBAL_SETTINGS = 5
private const val ADMIN_MENU_VALUATION_REPORT = 6
private const val ADMIN_MENU_LOGOUT = 7
private const val RADIO_ID_STOCK_IN = 1001
private const val RADIO_ID_STOCK_OUT = 1002

/** Parses a Retrofit "Any"-typed numeric field (server may send a JSON number or numeric string). */
private fun anyToDoubleOrNull(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull()
    else -> null
}

/**
 * Main screen after login. Shows the stock list with live search and
 * sorting, and hosts every admin/operator dialog reachable from the
 * top bar: password changes, operator management, item creation, stock
 * transactions (in/out), and transaction history.
 *
 * The class is intentionally kept as a single Activity (rather than split
 * into multiple files) but is organized into clearly labeled sections
 * below, in the order a user would encounter them.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvTotalValuation: TextView
    private lateinit var fabAddItem: FloatingActionButton
    private lateinit var btnSetup: ImageView

    private lateinit var savedToken: String
    private var currentUserIsAdmin: Boolean = false
    private var currentOpName: String = ""

    private var currentRawProductList: List<Product> = emptyList()
    private var savedSortCriteria: String = "ID_ASC"

    // =====================================================================
    // Lifecycle & setup
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val sharedPreferences = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        savedToken = sharedPreferences.getString("auth_token", "") ?: ""
        currentUserIsAdmin = sharedPreferences.getBoolean("is_admin", false)
        currentOpName = sharedPreferences.getString("operator_name", "未知人員") ?: "未知人員"
        savedSortCriteria = sharedPreferences.getString("stock_sort_criteria", "ID_ASC") ?: "ID_ASC"

        if (savedToken.isEmpty()) {
            Toast.makeText(this, "請先重新登入", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindViews()
        setUpTopBarInsets()
        setUpSearchBar()

        btnSetup.setOnClickListener { showAdminMenuDialog() }
        fabAddItem.setOnClickListener { showAddItemDialog() }
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadStockData()

        // Only admins can create new items.
        fabAddItem.visibility = if (currentUserIsAdmin) View.VISIBLE else View.GONE

        Toast.makeText(this, "歡迎回來，$currentOpName ！", Toast.LENGTH_SHORT).show()
    }

    /** Finds and assigns the core views, falling back to throwaway instances if the layout lookup fails. */
    private fun bindViews() {
        val viewRv = findViewById<RecyclerView>(R.id.rvProducts)
        val viewTv = findViewById<TextView>(R.id.tvTotalValuation)
        val viewFab = findViewById<FloatingActionButton>(R.id.fabAddItem)
        val viewBtn = findViewById<ImageView>(R.id.btnSetup)

        if (viewRv == null || viewTv == null || viewFab == null || viewBtn == null) {
            Log.e(TAG, "Layout initialization failed: one or more core views are missing")
        }
        recyclerView = viewRv ?: RecyclerView(this)
        tvTotalValuation = viewTv ?: TextView(this)
        fabAddItem = viewFab ?: FloatingActionButton(this)
        btnSetup = viewBtn ?: ImageView(this)

        val titleId = resources.getIdentifier("tvTitle", "id", packageName)
        if (titleId != 0) {
            findViewById<TextView>(titleId)?.apply {
                val role = if (currentUserIsAdmin) "管理員 Admin" else "純檢視 Operator"
                setTextColor(Color.parseColor("#FFFFFF"))
                text = "雲端雲倉進銷存管理系統\n（當前登入者：$currentOpName / $role）"
                textSize = 13f
            }
        }
    }

    /** Pads the top bar by the status bar height so content isn't drawn under it. */
    private fun setUpTopBarInsets() {
        val topBar = findViewById<RelativeLayout>(R.id.topBar) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.topMargin = statusBarHeight
                view.layoutParams = params
            }
            insets
        }
    }

    /** Injects a search box above the product list that filters by partial, case-insensitive name match. */
    private fun setUpSearchBar() {
        val rootLayout = findViewById<LinearLayout>(resources.getIdentifier("rootContainer", "id", packageName))
            ?: recyclerView.parent as? LinearLayout
            ?: return

        val searchEditText = EditText(this).apply {
            hint = "輸入關鍵字模糊篩選商品名稱..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.BLACK)
            textSize = 14f
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(40, 30, 40, 30)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(40, 20, 40, 10)
            }
        }
        rootLayout.addView(searchEditText, 0)

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val keyword = s.toString().trim().lowercase()
                val filtered = if (keyword.isEmpty()) {
                    currentRawProductList
                } else {
                    currentRawProductList.filter { it.item_name.lowercase().contains(keyword) }
                }
                applySortingAndRenderWithList(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // =====================================================================
    // Data loading & sorting
    // =====================================================================

    /** Fetches the current stock valuation and refreshes the list + total. */
    private fun loadStockData() {
        RetrofitClient.instance.getStockValuation().enqueue(object : Callback<StockValuationResponse> {
            override fun onResponse(call: Call<StockValuationResponse>, response: Response<StockValuationResponse>) {
                if (response.isSuccessful) {
                    val responseBody = response.body()
                    currentRawProductList = responseBody?.details ?: emptyList()
                    val totalTwdAmount = responseBody?.total_twd_amount ?: 0.0

                    runOnUiThread {
                        val formatter = NumberFormat.getCurrencyInstance(Locale.US)
                        tvTotalValuation.text = formatter.format(totalTwdAmount).replace("$", "$ ")
                        applySortingAndRender()
                    }
                } else {
                    Log.e(TAG, "Failed to load stock valuation: HTTP ${response.code()}")
                }
            }

            override fun onFailure(call: Call<StockValuationResponse>, t: Throwable) {
                Log.e(TAG, "Network error while loading stock valuation: ${t.localizedMessage}")
            }
        })
    }

    /** Re-renders the list using the full, unfiltered product set. */
    private fun applySortingAndRender() {
        applySortingAndRenderWithList(currentRawProductList)
    }

    /**
     * Sorts [targetList] according to [savedSortCriteria] and rebinds the RecyclerView adapter.
     * Used both for the full list and for search-filtered subsets.
     */
    private fun applySortingAndRenderWithList(targetList: List<Product>) {
        if (targetList.isEmpty() && currentRawProductList.isNotEmpty()) {
            // Search yielded no matches: show an empty list instead of crashing on a null adapter.
            recyclerView.adapter = ProductAdapter(emptyList()) { _ -> }
            return
        }

        val sortedList = when (savedSortCriteria) {
            "NAME_ASC" -> targetList.sortedBy { it.item_name.lowercase() }
            "ID_ASC" -> targetList.sortedBy { it.item_id }
            "ID_DESC" -> targetList.sortedByDescending { it.item_id }
            "UPDATE_DESC" -> targetList.sortedByDescending {
                "${it.last_update_date ?: "1970-01-01"} ${it.last_update_time ?: "00:00:00"}"
            }
            else -> targetList
        }

        recyclerView.adapter = ProductAdapter(sortedList) { product -> showTransactionDialog(product) }
    }

    // =====================================================================
    // Admin menu & settings
    // =====================================================================

    /** Entry point for the gear-icon menu: operator management, password change, sort settings, history, chart, global settings, valuation report, logout. */
    private fun showAdminMenuDialog() {
        val options = if (currentUserIsAdmin) {
            arrayOf(
                "人員權限與名冊維護", "變更個人或人員密碼", "設定商品排序偏好", "時序交易查詢", "庫存趨勢圖表",
                "全域匯率及調整 factor 維護", "當前資產估值查詢", "登出系統，安全退出"
            )
        } else {
            arrayOf(
                "人員權限與名冊維護 (僅限管理員)", "變更個人或人員密碼", "設定商品排序偏好", "時序交易查詢", "庫存趨勢圖表 (僅限管理員)",
                "全域匯率及調整 factor 維護 (僅限管理員)", "當前資產估值查詢 (僅限管理員)", "登出系統，安全退出"
            )
        }

        AlertDialog.Builder(this)
            .setTitle("系統後台管理中心")
            .setItems(options) { dialog, which ->
                when (which) {
                    ADMIN_MENU_MANAGE_OPERATORS -> {
                        if (currentUserIsAdmin) showOperatorManagementDialog()
                        else Toast.makeText(this, "此功能已鎖定，僅限管理員操作", Toast.LENGTH_SHORT).show()
                    }
                    ADMIN_MENU_CHANGE_PASSWORD -> showChangePasswordDialog()
                    ADMIN_MENU_SORT_PREFERENCE -> showSortCriteriaDialog()
                    ADMIN_MENU_GLOBAL_HISTORY -> {
                        dialog.dismiss()
                        showGlobalHistoryDialog()
                    }
                    ADMIN_MENU_STOCK_CHART -> {
                        dialog.dismiss()
                        if (currentUserIsAdmin) {
                            startActivity(Intent(this, StockChartActivity::class.java))
                        } else {
                            Toast.makeText(this, "此功能已鎖定，僅限管理員操作", Toast.LENGTH_SHORT).show()
                        }
                    }
                    ADMIN_MENU_GLOBAL_SETTINGS -> {
                        dialog.dismiss()
                        if (currentUserIsAdmin) showGlobalSettingsDialog()
                        else Toast.makeText(this, "此功能已鎖定，僅限管理員操作", Toast.LENGTH_SHORT).show()
                    }
                    ADMIN_MENU_VALUATION_REPORT -> {
                        dialog.dismiss()
                        if (currentUserIsAdmin) {
                            startActivity(Intent(this, ValuationReportActivity::class.java))
                        } else {
                            Toast.makeText(this, "此功能已鎖定，僅限管理員操作", Toast.LENGTH_SHORT).show()
                        }
                    }
                    ADMIN_MENU_LOGOUT -> performCleanExit(this)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Lets the user choose how the stock list is sorted, and persists the choice. */
    private fun showSortCriteriaDialog() {
        val context = this
        val criteriaOptions = arrayOf(
            "依商品名稱 (A → Z)",
            "依建立時間 (舊到新)",
            "依逆向建立時間 (最新排最前)",
            "依最新庫存異動時間 (最新置頂)"
        )

        val checkedItem = when (savedSortCriteria) {
            "NAME_ASC" -> 0
            "ID_ASC" -> 1
            "ID_DESC" -> 2
            "UPDATE_DESC" -> 3
            else -> 1
        }

        AlertDialog.Builder(context)
            .setTitle("設定主頁商品庫存展示排序")
            .setSingleChoiceItems(criteriaOptions, checkedItem) { dialog, which ->
                val newCriteria = when (which) {
                    0 -> "NAME_ASC"
                    1 -> "ID_ASC"
                    2 -> "ID_DESC"
                    3 -> "UPDATE_DESC"
                    else -> "ID_ASC"
                }

                getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
                    .putString("stock_sort_criteria", newCriteria)
                    .apply()

                savedSortCriteria = newCriteria
                applySortingAndRender()

                Toast.makeText(context, "排序偏好已記錄，系統將永久維持此設定！", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // =====================================================================
    // Password management
    // =====================================================================

    /**
     * Resolves the target operator's ID and submits a password change.
     * Admins may target any operator (looked up by name via the operator list);
     * non-admins may only target themselves, using their cached operator ID.
     */
    private fun sendChangePasswordRequest(targetUserName: String, oldPass: String, newPass: String) {
        val request = PasswordChangeRequest(old_password = oldPass, new_password = newPass)

        if (currentUserIsAdmin) {
            RetrofitClient.instance.getAllOperators().enqueue(object : Callback<List<OperatorResponse>> {
                override fun onResponse(call: Call<List<OperatorResponse>>, response: Response<List<OperatorResponse>>) {
                    if (!response.isSuccessful) return
                    val opList = response.body() ?: emptyList()
                    val matchedOp = opList.find { it.operator_name == targetUserName }
                    val targetId = matchedOp?.operator_id ?: run {
                        if (targetUserName == "Admin" && currentOpName == "Admin") {
                            getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getInt("my_operator_id", -1)
                        } else {
                            -1
                        }
                    }

                    if (targetId == -1) {
                        runOnUiThread { Toast.makeText(this@HomeActivity, "系統錯誤：找不到人員精準編號！", Toast.LENGTH_LONG).show() }
                        return
                    }
                    executeChangePasswordApi(targetId, targetUserName, request)
                }

                override fun onFailure(call: Call<List<OperatorResponse>>, t: Throwable) {
                    Log.e(TAG, "Failed to load operator list: ${t.localizedMessage}")
                }
            })
        } else {
            val myRealId = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getInt("my_operator_id", -1)
            if (myRealId == -1) {
                Log.e(TAG, "Missing cached operator ID; blocking password change request")
                runOnUiThread {
                    AlertDialog.Builder(this@HomeActivity).apply {
                        setTitle("憑證讀取失敗")
                        setMessage("系統無法在本地硬碟安全查驗您的個人編號 (ID)。\n\n為了保障資安，本次變更請求已被強制攔截。請嘗試重新登入系統以重新初始化憑證！")
                        setCancelable(false)
                        setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        show()
                    }
                }
                return
            }
            executeChangePasswordApi(myRealId, targetUserName, request)
        }
    }

    /**
     * Password-change dialog. Admins get a dropdown of all operators; a regular
     * operator only changes their own password. Password fields are shown in
     * plain text deliberately, to reduce mis-typing on small screens.
     */
    private fun showChangePasswordDialog() {
        val context = this

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }

        val txtNotice = TextView(context).apply {
            text = "安全防呆提示：\n為避免小螢幕按錯字母，密碼欄位已解除星號隱藏，將以明文顯示。"
            textSize = 12f
            setTextColor(Color.parseColor("#D3D3D3"))
            setPadding(0, 0, 0, 20)
        }
        container.addView(txtNotice)

        var adminSpinner: Spinner? = null
        val spinnerOptionsList = ArrayList<String>()

        if (currentUserIsAdmin) {
            val txtSelectTitle = TextView(context).apply {
                text = "請選擇要變更密碼的人員："
                textSize = 13f
                setTextColor(Color.parseColor("#FFFFFF"))
                setPadding(0, 10, 0, 10)
            }
            container.addView(txtSelectTitle)

            adminSpinner = Spinner(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 30 }
            }
            container.addView(adminSpinner)

            RetrofitClient.instance.getAllOperators().enqueue(object : Callback<List<OperatorResponse>> {
                override fun onResponse(call: Call<List<OperatorResponse>>, response: Response<List<OperatorResponse>>) {
                    if (!response.isSuccessful) return
                    val opList = response.body() ?: emptyList()
                    spinnerOptionsList.clear()
                    if (opList.none { it.operator_name == "Admin" }) spinnerOptionsList.add("Admin")
                    spinnerOptionsList.addAll(opList.map { it.operator_name })

                    runOnUiThread {
                        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, spinnerOptionsList)
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        adminSpinner?.adapter = adapter
                    }
                }

                override fun onFailure(call: Call<List<OperatorResponse>>, t: Throwable) {
                    Log.e(TAG, "Failed to load operator list for password dialog: ${t.localizedMessage}")
                }
            })
        } else {
            val txtUserSelf = TextView(context).apply {
                text = "目前登入帳號：$currentOpName (普通操作員)\n"
                textSize = 14f
                setTextColor(Color.parseColor("#FFFFFF"))
                setPadding(0, 0, 0, 20)
            }
            container.addView(txtUserSelf)
        }

        fun plainTextField(hintText: String) = EditText(context).apply {
            hint = hintText
            setHintTextColor(Color.GRAY)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(30, 25, 30, 25)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val edtOldPassword = plainTextField("請輸入原來的舊密碼")
        container.addView(edtOldPassword)
        container.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 25) })

        val edtNewPassword = plainTextField("請輸入要變更的新密碼")
        container.addView(edtNewPassword)

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("變更個人或人員密碼")
            .setView(container)
            .setPositiveButton("確定變更", null) // overridden below so validation failures don't auto-dismiss
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val oldPwd = edtOldPassword.text.toString().trim()
            val newPwd = edtNewPassword.text.toString().trim()

            if (oldPwd.isEmpty() || newPwd.isEmpty()) {
                Toast.makeText(context, "舊密碼與新密碼皆欄位不得為空！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (newPwd.length < 4) {
                Toast.makeText(context, "為了安全起見，新密碼長度請至少輸入 4 位數以上！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val targetUserName = if (currentUserIsAdmin && adminSpinner != null) {
                adminSpinner.selectedItem?.toString() ?: "Admin"
            } else {
                currentOpName
            }

            sendChangePasswordRequest(targetUserName, oldPwd, newPwd)
            dialog.dismiss()
        }
    }

    /** Submits the password change and shows a blocking success/failure dialog (auto-logout if it was our own password). */
    private fun executeChangePasswordApi(id: Int, name: String, request: PasswordChangeRequest) {
        RetrofitClient.instance.changePassword(id, request).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        AlertDialog.Builder(this@HomeActivity).apply {
                            setTitle("密碼變更成功")
                            setMessage("人員 [$name] 的密碼已順利覆寫完成！\n\n點擊確定後，如果修改的是目前登入帳號，系統將自動登出以策安全。")
                            setCancelable(false)
                            setPositiveButton("OK") { dialog, _ ->
                                dialog.dismiss()
                                if (name == currentOpName) performCleanExit(this@HomeActivity)
                            }
                            show()
                        }
                    } else {
                        val errorCode = response.code()
                        val errorDetail = when (errorCode) {
                            400 -> "舊密碼驗證錯誤，拒絕變更！"
                            403 -> "權限不足：您無法變更他人的密碼！"
                            404 -> "系統錯誤：找不到該人員的資料庫主檔！"
                            500 -> "伺服器內部錯誤！"
                            else -> "未知的網路安全阻斷。"
                        }
                        AlertDialog.Builder(this@HomeActivity).apply {
                            setTitle("密碼變更失敗")
                            setMessage("無法變更人員 [$name] 的密碼。\n\n後端拒絕原因：$errorDetail\n(錯誤代碼: $errorCode)")
                            setCancelable(false)
                            setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            show()
                        }
                    }
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                runOnUiThread {
                    AlertDialog.Builder(this@HomeActivity).apply {
                        setTitle("網路連線失敗")
                        setMessage("網路郵包未能送達伺服器！\n\n請檢查您的 Wi-Fi 連線或後端 FastAPI 服務是否正常運作。\n原因: ${t.localizedMessage}")
                        setCancelable(false)
                        setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        show()
                    }
                }
            }
        })
    }

    /** Clears the stored session and returns to the login screen. */
    private fun performCleanExit(context: Context) {
        context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .remove("auth_token")
            .remove("is_admin")
            .remove("operator_name")
            .apply()
        Toast.makeText(context, "已安全登出系統", Toast.LENGTH_SHORT).show()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
        if (context is AppCompatActivity) context.finish()
    }

    // =====================================================================
    // Operator management (admin only)
    // =====================================================================

    /** Lists all operators with a delete action per non-admin account, plus a "create new operator" button. */
    private fun showOperatorManagementDialog() {
        val context = this

        if (!currentUserIsAdmin) {
            runOnUiThread {
                AlertDialog.Builder(context).apply {
                    setTitle("權限攔截")
                    setMessage("「人員權限與名冊維護」屬於高階管理員 (Admin) 核心主檔特權。\n\n普通操作人員 (Operator) 僅具備進出庫異動權限，無法存取此模組。")
                    setCancelable(false)
                    setPositiveButton("OK", null)
                    show()
                }
            }
            return
        }

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 40) }
        val btnAddNewOp = Button(context).apply {
            text = "建立全新 Operator 人員帳號"
            setBackgroundColor(Color.parseColor("#2A3B50"))
            setTextColor(Color.WHITE)
        }
        container.addView(btnAddNewOp)

        val txtTitle = TextView(context).apply {
            text = "\n現有人員名冊管理："
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 14f
            setTextColor(Color.parseColor("#FFFFFF"))
        }
        container.addView(txtTitle)

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400).apply { topMargin = 10 }
        }
        val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listContainer.addView(TextView(context).apply { text = "正在向伺服器索取人員名冊中..."; setTextColor(Color.parseColor("#FFFFFF")) })
        scrollView.addView(listContainer)
        container.addView(scrollView)

        // Theme forces a dark dialog regardless of the device's light/dark mode setting.
        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("人員權限控制台")
            .setView(container)
            .setNegativeButton("關閉退出", null)
            .create()

        btnAddNewOp.setOnClickListener { dialog.dismiss(); showCreateOperatorForm() }
        dialog.show()

        RetrofitClient.instance.getAllOperators().enqueue(object : Callback<List<OperatorResponse>> {
            override fun onResponse(call: Call<List<OperatorResponse>>, response: Response<List<OperatorResponse>>) {
                runOnUiThread {
                    if (!response.isSuccessful) {
                        dialog.dismiss()
                        AlertDialog.Builder(context).apply {
                            setTitle("索取名冊失敗")
                            setMessage("無法讀取人員名冊 (代碼: ${response.code()})。\n\n原因：伺服器拒絕了本次請求，請確認您具備管理員權限。")
                            setCancelable(false)
                            setPositiveButton("OK", null)
                            show()
                        }
                        return@runOnUiThread
                    }

                    val opList = response.body() ?: emptyList()
                    listContainer.removeAllViews()

                    if (opList.isEmpty()) {
                        listContainer.addView(TextView(context).apply { text = "目前系統無現有人員。"; setTextColor(Color.WHITE) })
                        return@runOnUiThread
                    }

                    for (op in opList) {
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, 15, 0, 15)
                            gravity = Gravity.CENTER_VERTICAL
                        }

                        val txtOpInfo = TextView(context).apply {
                            val role = if (op.is_admin) "Admin" else "Operator"
                            text = "• ${op.operator_name} ($role)"
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            setTextColor(Color.parseColor("#FFFFFF"))
                        }
                        row.addView(txtOpInfo)

                        if (!op.is_admin) {
                            val btnDelete = Button(context).apply {
                                text = "刪除"
                                setBackgroundColor(Color.parseColor("#DC3545"))
                                setTextColor(Color.WHITE)
                                textSize = 12f
                                setPadding(20, 0, 20, 0)
                                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                    leftMargin = 10
                                }
                            }
                            btnDelete.setOnClickListener {
                                AlertDialog.Builder(context)
                                    .setTitle("刪除確認")
                                    .setMessage("您確定要將人員 [${op.operator_name}] 從系統中徹底刪除嗎？")
                                    .setPositiveButton("確定刪除") { _, _ -> dialog.dismiss(); sendDeleteOperatorRequest(op.operator_id, op.operator_name) }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                            row.addView(btnDelete)
                        } else {
                            row.addView(TextView(context).apply {
                                text = "系統保護"
                                setTextColor(Color.parseColor("#D3D3D3"))
                                textSize = 12f
                                setPadding(15, 5, 15, 5)
                            })
                        }
                        listContainer.addView(row)
                    }
                }
            }

            override fun onFailure(call: Call<List<OperatorResponse>>, t: Throwable) {
                runOnUiThread {
                    dialog.dismiss()
                    AlertDialog.Builder(context).apply {
                        setTitle("網路連線失敗")
                        setMessage("無法連線至進銷存伺服器，名冊索取失敗！\n原因: ${t.localizedMessage}")
                        setCancelable(false)
                        setPositiveButton("OK", null)
                        show()
                    }
                }
            }
        })
    }

    private fun sendDeleteOperatorRequest(opId: Int, opName: String) {
        RetrofitClient.instance.deleteOperator(opId).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@HomeActivity, "成功刪除人員: [$opName]", Toast.LENGTH_SHORT).show()
                        showOperatorManagementDialog()
                    }
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e(TAG, "Failed to delete operator [$opName]: ${t.localizedMessage}")
            }
        })
    }

    private fun showCreateOperatorForm() {
        val context = this
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 20) }
        val edtName = EditText(context).apply { hint = "請輸入登入帳號" }
        val edtPassword = EditText(context).apply {
            hint = "請輸入登入密碼"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val cbIsAdmin = CheckBox(context).apply { text = "升級為管理員權限"; isChecked = false }
        layout.addView(edtName)
        layout.addView(edtPassword)
        layout.addView(cbIsAdmin)

        AlertDialog.Builder(context)
            .setTitle("建立人員帳號")
            .setView(layout)
            .setPositiveButton("確定建立") { _, _ ->
                val name = edtName.text.toString().trim()
                val pass = edtPassword.text.toString().trim()
                if (name.isNotEmpty() && pass.isNotEmpty()) {
                    sendCreateOperatorRequest(name, pass, cbIsAdmin.isChecked)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendCreateOperatorRequest(name: String, pass: String, isAdmin: Boolean) {
        RetrofitClient.instance.createOperator(OperatorCreateRequest(name, pass, isAdmin)).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@HomeActivity, "人員 [$name] 成功建立！", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e(TAG, "Failed to create operator [$name]: ${t.localizedMessage}")
            }
        })
    }

    // =====================================================================
    // Item management (admin only)
    // =====================================================================

    /** Views making up the create/edit item form, so both dialogs can share the same layout code. */
    private class ItemFormFields(
        val container: LinearLayout,
        val edtName: EditText,
        val spinnerCategory: Spinner,
        val edtUsdPrice: EditText,
        val edtRate: EditText,
        val edtTax: EditText
    )

    /**
     * Builds the item name/category/price/rate/factor form, used for both
     * "建立全新商品主檔" (create) and "編輯商品主檔" (edit). If [prefill] is
     * given, fields are populated from it; exchange rate / tax factor are
     * left blank when the item has no per-item override (i.e. it currently
     * uses the global fallback) rather than showing the resolved global value.
     */
    private fun buildItemFormFields(context: Context, prefill: Product?): ItemFormFields {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 20) }

        val edtName = EditText(context).apply {
            hint = "商品名稱"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(30, 25, 30, 25)
            prefill?.let { setText(it.item_name) }
        }
        val spinnerCategory = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, arrayOf("Main", "Accessories"))
            setPadding(0, 20, 0, 20)
            // Default new items to Accessories (index 1); when editing, match the item's actual category.
            setSelection(if (prefill == null || prefill.category == "Accessories") 1 else 0)
        }
        val edtUsdPrice = EditText(context).apply {
            hint = "採購幣別單價"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(30, 25, 30, 25)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            prefill?.let { anyToDoubleOrNull(it.usd_price)?.let { v -> setText(v.toString()) } }
        }
        val edtRate = EditText(context).apply {
            hint = "匯率 (留空 = 使用全域匯率)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(30, 25, 30, 25)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            prefill?.let { anyToDoubleOrNull(it.exchange_rate)?.let { v -> setText(v.toString()) } }
        }
        val edtTax = EditText(context).apply {
            hint = "調整 factor (留空 = 使用全域 factor)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(30, 25, 30, 25)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            prefill?.let { anyToDoubleOrNull(it.tax_coefficient)?.let { v -> setText(v.toString()) } }
        }

        container.addView(edtName)
        container.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 20) })
        container.addView(spinnerCategory)
        container.addView(edtUsdPrice)
        container.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 20) })
        container.addView(edtRate)
        container.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 20) })
        container.addView(edtTax)

        return ItemFormFields(container, edtName, spinnerCategory, edtUsdPrice, edtRate, edtTax)
    }

    private fun showAddItemDialog() {
        val context = this
        val fields = buildItemFormFields(context, prefill = null)

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("建立全新商品主檔")
            .setView(fields.container)
            .setPositiveButton("建立", null) // overridden below so validation failures don't auto-dismiss
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = fields.edtName.text.toString().trim()
            val usdText = fields.edtUsdPrice.text.toString().trim()

            if (name.isEmpty()) {
                showValidationAlert("請輸入商品名稱")
                return@setOnClickListener
            }
            val usd = usdText.toDoubleOrNull()
            if (usdText.isEmpty() || usd == null) {
                showValidationAlert("請輸入採購幣別單價")
                return@setOnClickListener
            }

            sendCreateItemRequest(
                name,
                fields.spinnerCategory.selectedItem.toString(),
                usd,
                fields.edtRate.text.toString().trim().toDoubleOrNull(),
                fields.edtTax.text.toString().trim().toDoubleOrNull()
            )
            dialog.dismiss()
        }
    }

    /** Blocking validation popup with a single OK button; the form dialog underneath stays open behind it. */
    private fun showValidationAlert(message: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setCancelable(false)
            .show()
    }

    private fun sendCreateItemRequest(name: String, cat: String, usd: Double, rate: Double?, tax: Double?) {
        RetrofitClient.instance.createItem(ItemCreateRequest(name, cat, usd, rate, tax)).enqueue(object : Callback<ItemCreateResponse> {
            override fun onResponse(call: Call<ItemCreateResponse>, response: Response<ItemCreateResponse>) {
                if (response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@HomeActivity, "商品建立成功！", Toast.LENGTH_SHORT).show()
                        loadStockData()
                    }
                }
            }

            override fun onFailure(call: Call<ItemCreateResponse>, t: Throwable) {
                Log.e(TAG, "Failed to create item [$name]: ${t.localizedMessage}")
            }
        })
    }

    /**
     * Entry point for the "⋯" button: fetches this item's transaction
     * history (to decide whether Delete is allowed) and a fresh copy of
     * the item's own data (name/category/price/rate/factor) -- both fired
     * at once rather than one after another, so the wait is however long
     * the slower of the two takes, not the sum of both. Item data is
     * always re-fetched from the server rather than reusing the [product]
     * passed in, since that may be a stale copy still held by the product
     * list/adapter from before a previous edit was saved.
     */
    private fun showItemEditMenuDialog(product: Product) {
        var hasTransactionsResult: Boolean? = null
        var freshProductResult: Product? = null

        // Retrofit's default Android callback executor delivers both callbacks on the
        // main thread, so no locking is needed even though the two requests overlap.
        fun tryShowFormOnceBothArrive() {
            val hasTransactions = hasTransactionsResult ?: return
            val fresh = freshProductResult ?: return
            showEditItemDialog(fresh, hasTransactions)
        }

        RetrofitClient.instance.getItemHistory(product.item_id).enqueue(object : Callback<List<TransactionHistoryItem>> {
            override fun onResponse(call: Call<List<TransactionHistoryItem>>, response: Response<List<TransactionHistoryItem>>) {
                hasTransactionsResult = if (response.isSuccessful) {
                    (response.body() ?: emptyList()).isNotEmpty()
                } else {
                    true // couldn't verify -> be conservative and disable Delete
                }
                tryShowFormOnceBothArrive()
            }

            override fun onFailure(call: Call<List<TransactionHistoryItem>>, t: Throwable) {
                Log.e(TAG, "Failed to check transaction history for item [${product.item_name}]: ${t.localizedMessage}")
                hasTransactionsResult = true
                tryShowFormOnceBothArrive()
            }
        })

        RetrofitClient.instance.getItem(product.item_id).enqueue(object : Callback<ItemDetailResponse> {
            override fun onResponse(call: Call<ItemDetailResponse>, response: Response<ItemDetailResponse>) {
                freshProductResult = response.body()?.let { mergeFreshItemIntoProduct(product, it) } ?: product
                tryShowFormOnceBothArrive()
            }

            override fun onFailure(call: Call<ItemDetailResponse>, t: Throwable) {
                Log.e(TAG, "Failed to refresh item data before editing [${product.item_name}]: ${t.localizedMessage}")
                freshProductResult = product
                tryShowFormOnceBothArrive()
            }
        })
    }

    /** Overlays a fresh single-item fetch onto a Product, keeping fields the lightweight endpoint doesn't return (e.g. current_qty) from [base]. */
    private fun mergeFreshItemIntoProduct(base: Product, fresh: ItemDetailResponse): Product = base.copy(
        item_name = fresh.item_name,
        category = fresh.category,
        usd_price = fresh.usd_price,
        exchange_rate = fresh.exchange_rate,
        tax_coefficient = fresh.tax_coefficient
    )

    /**
     * Edit form for an existing item: name, category, price, rate, factor
     * (never touches current_qty), plus a "刪除商品" action at the bottom --
     * grayed out with an explanation if the item has any transaction history.
     */
    private fun showEditItemDialog(product: Product, hasTransactions: Boolean) {
        val context = this
        val fields = buildItemFormFields(context, prefill = product)

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply { topMargin = 30; bottomMargin = 10 }
            setBackgroundColor(Color.parseColor("#495057"))
        }
        val rowDelete = TextView(context).apply {
            text = if (hasTransactions) "刪除商品\n(已有交易紀錄，無法刪除)" else "刪除商品"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (hasTransactions) Color.parseColor("#6C757D") else Color.parseColor("#FF6B6B"))
            setPadding(20, 20, 20, 20)
        }
        fields.container.addView(divider)
        fields.container.addView(rowDelete)

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("更新商品資料: ${product.item_name}")
            .setView(fields.container)
            .setPositiveButton("儲存變更", null) // overridden below so validation failures don't auto-dismiss
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = fields.edtName.text.toString().trim()
            val usdText = fields.edtUsdPrice.text.toString().trim()
            val usd = usdText.toDoubleOrNull()

            if (name.isEmpty() || usd == null) {
                Toast.makeText(context, "商品名稱及採購幣別單價為必填，且單價須為數字！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sendUpdateItemRequest(
                product.item_id,
                name,
                fields.spinnerCategory.selectedItem.toString(),
                usd,
                fields.edtRate.text.toString().trim().toDoubleOrNull(),
                fields.edtTax.text.toString().trim().toDoubleOrNull()
            )
            dialog.dismiss()
        }

        if (!hasTransactions) {
            rowDelete.setOnClickListener { dialog.dismiss(); confirmAndDeleteItem(product) }
        } // else: no click listener -> effectively disabled, matching the grayed-out styling above
    }

    private fun sendUpdateItemRequest(itemId: Int, name: String, cat: String, usd: Double, rate: Double?, tax: Double?) {
        RetrofitClient.instance.updateItem(itemId, ItemCreateRequest(name, cat, usd, rate, tax)).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@HomeActivity, "商品已更新！", Toast.LENGTH_SHORT).show()
                        loadStockData()
                    } else {
                        Toast.makeText(this@HomeActivity, "更新失敗 (HTTP ${response.code()})", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e(TAG, "Failed to update item [$name]: ${t.localizedMessage}")
                runOnUiThread { Toast.makeText(this@HomeActivity, "無法連線到伺服器：${t.localizedMessage}", Toast.LENGTH_LONG).show() }
            }
        })
    }

    private fun confirmAndDeleteItem(product: Product) {
        AlertDialog.Builder(this)
            .setTitle("刪除確認")
            .setMessage("您確定要刪除商品 [${product.item_name}] 嗎？此動作無法復原。")
            .setPositiveButton("確定刪除") { _, _ -> sendDeleteItemRequest(product) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendDeleteItemRequest(product: Product) {
        RetrofitClient.instance.deleteItem(product.item_id).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@HomeActivity, "商品 [${product.item_name}] 已刪除", Toast.LENGTH_SHORT).show()
                        loadStockData()
                    } else {
                        Toast.makeText(this@HomeActivity, "刪除失敗 (HTTP ${response.code()})：此項目可能已有交易紀錄", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e(TAG, "Failed to delete item [${product.item_name}]: ${t.localizedMessage}")
                runOnUiThread { Toast.makeText(this@HomeActivity, "無法連線到伺服器：${t.localizedMessage}", Toast.LENGTH_LONG).show() }
            }
        })
    }

    /** Admin-only: view/edit the global exchange rate & adjustment factor used by items without their own override. */
    private fun showGlobalSettingsDialog() {
        val context = this
        RetrofitClient.instance.getGlobalSettings().enqueue(object : Callback<GlobalSettingsResponse> {
            override fun onResponse(call: Call<GlobalSettingsResponse>, response: Response<GlobalSettingsResponse>) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to load global settings: HTTP ${response.code()}")
                    runOnUiThread { Toast.makeText(context, "讀取全域參數失敗 (HTTP ${response.code()})", Toast.LENGTH_LONG).show() }
                    return
                }
                val settings = response.body() ?: return
                runOnUiThread { renderGlobalSettingsDialog(settings) }
            }

            override fun onFailure(call: Call<GlobalSettingsResponse>, t: Throwable) {
                Log.e(TAG, "Network error loading global settings: ${t.localizedMessage}")
                runOnUiThread { Toast.makeText(context, "無法連線到伺服器：${t.localizedMessage}", Toast.LENGTH_LONG).show() }
            }
        })
    }

    private fun renderGlobalSettingsDialog(settings: GlobalSettingsResponse) {
        val context = this
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 20) }

        val txtNotice = TextView(context).apply {
            text = "未設定個別匯率/factor 的商品，估值時將套用以下全域參數。"
            textSize = 12f
            setTextColor(Color.parseColor("#D3D3D3"))
            setPadding(0, 0, 0, 20)
        }
        val edtRate = EditText(context).apply {
            hint = "全域匯率"
            setText(settings.global_exchange_rate.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val edtTax = EditText(context).apply {
            hint = "全域調整 factor"
            setText(settings.global_tax_coefficient.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(txtNotice)
        container.addView(edtRate)
        container.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 25) })
        container.addView(edtTax)

        val dialog = AlertDialog.Builder(context)
            .setTitle("全域匯率及調整 factor 維護")
            .setView(container)
            .setPositiveButton("儲存", null) // overridden below so validation failures don't auto-dismiss
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val rate = edtRate.text.toString().trim().toDoubleOrNull()
            val tax = edtTax.text.toString().trim().toDoubleOrNull()
            if (rate == null || tax == null) {
                Toast.makeText(context, "請輸入有效的數字！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendUpdateGlobalSettings(rate, tax)
            dialog.dismiss()
        }
    }

    private fun sendUpdateGlobalSettings(rate: Double, tax: Double) {
        RetrofitClient.instance.updateGlobalSettings(GlobalSettingsUpdateRequest(rate, tax)).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@HomeActivity, "全域參數已更新！", Toast.LENGTH_SHORT).show()
                        loadStockData()
                    } else {
                        Toast.makeText(this@HomeActivity, "更新失敗 (HTTP ${response.code()})", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Log.e(TAG, "Failed to update global settings: ${t.localizedMessage}")
                runOnUiThread { Toast.makeText(this@HomeActivity, "無法連線到伺服器：${t.localizedMessage}", Toast.LENGTH_LONG).show() }
            }
        })
    }

    // =====================================================================
    // Stock transactions (in / out) & item history
    // =====================================================================

    /** Stock-in/stock-out dialog for a single product, with a client-side guard against over-drawing stock. */
    private fun showTransactionDialog(product: Product) {
        val context = this
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 20) }

        // Custom title bar: item name on the left, a "..." menu button (Edit/Delete) on the right.
        val titleView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 30, 30, 10)
        }
        val titleText = TextView(context).apply {
            text = "庫存異動調整: ${product.item_name}"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnEditMenu = TextView(context).apply {
            text = "⋯"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#ADB5BD"))
            setPadding(20, 0, 10, 0)
            isClickable = true
            isFocusable = true
        }
        titleView.addView(titleText)
        titleView.addView(btnEditMenu)

        val radioGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL; setPadding(0, 0, 0, 30) }
        val radioIn = RadioButton(context).apply { text = "入庫"; id = RADIO_ID_STOCK_IN; isChecked = true; setTextColor(Color.WHITE) }
        val radioOut = RadioButton(context).apply { text = "出庫"; id = RADIO_ID_STOCK_OUT; setTextColor(Color.WHITE) }
        radioGroup.addView(radioIn)
        radioGroup.addView(radioOut)

        val edtQty = EditText(context).apply {
            hint = "請輸入異動數量"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(30, 25, 30, 25)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val edtRemark = EditText(context).apply {
            hint = "請輸入備註說明"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(30, 25, 30, 25)
        }
        container.addView(radioGroup)
        container.addView(edtQty)
        container.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 20) })
        container.addView(edtRemark)

        val btnHistory = Button(context).apply {
            text = "檢視此品項歷史交易日誌"
            setBackgroundColor(Color.parseColor("#495057"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 40 }
        }
        container.addView(btnHistory)

        val alertDialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setCustomTitle(titleView)
            .setView(container)
            .setPositiveButton("確定送出", null) // overridden below so validation failures don't auto-dismiss
            .setNegativeButton("取消", null)
            .create()

        btnHistory.setOnClickListener { alertDialog.dismiss(); showHistoryDialog(product) }
        btnEditMenu.setOnClickListener {
            if (currentUserIsAdmin) {
                alertDialog.dismiss()
                showItemEditMenuDialog(product)
            } else {
                Toast.makeText(context, "此功能已鎖定，僅限管理員操作", Toast.LENGTH_SHORT).show()
            }
        }
        alertDialog.show()


        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val qty = edtQty.text.toString().trim().toIntOrNull() ?: 0
            val remark = edtRemark.text.toString().trim()
            val actionType = if (radioGroup.checkedRadioButtonId == RADIO_ID_STOCK_IN) "IN" else "OUT"

            if (qty <= 0) {
                Toast.makeText(context, "請輸入大於零的有效數量！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Client-side guard: mirrors the server-side check but avoids a wasted round-trip.
            if (actionType == "OUT" && qty > product.current_qty) {
                AlertDialog.Builder(context).apply {
                    setTitle("庫存餘額不足")
                    setMessage("無法執行出貨！\n\n目前品項 [${product.item_name}] 倉庫僅剩 ${product.current_qty} 件，而您企圖出貨 $qty 件。\n\n請重新確認實體盤點數量！")
                    setCancelable(false)
                    setPositiveButton("OK", null)
                    show()
                }
                return@setOnClickListener
            }

            sendTransaction(product.item_id.toString(), actionType, qty, remark)
            alertDialog.dismiss()
        }
    }

    /** Shows the transaction history for a single product in a dark-themed scrollable dialog. */
    private fun showHistoryDialog(product: Product) {
        val context = this

        val outerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
            setBackgroundColor(Color.parseColor("#000000"))
        }
        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val txtHistoryContent = TextView(context).apply {
            text = "正在向資料庫索取日誌中..."
            textSize = 14f
            setTextColor(Color.parseColor("#FFFFFF"))
            setLineSpacing(10f, 1f)
        }
        scrollView.addView(txtHistoryContent)
        outerLayout.addView(scrollView)

        val btnClose = Button(context).apply {
            text = "關閉退出"
            setBackgroundColor(Color.parseColor("#DC3545"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 30 }
        }
        outerLayout.addView(btnClose)

        val historyDialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${product.item_name} 歷史交易清單")
            .setView(outerLayout)
            .create()
        btnClose.setOnClickListener { historyDialog.dismiss() }
        historyDialog.show()

        RetrofitClient.instance.getItemHistory(product.item_id).enqueue(object : Callback<List<TransactionHistoryItem>> {
            override fun onResponse(call: Call<List<TransactionHistoryItem>>, response: Response<List<TransactionHistoryItem>>) {
                when {
                    response.isSuccessful -> {
                        val historyList = response.body() ?: emptyList()
                        if (historyList.isEmpty()) {
                            runOnUiThread {
                                txtHistoryContent.text = "此品項目前尚無交易紀錄。"
                                txtHistoryContent.setTextColor(Color.WHITE)
                            }
                            return
                        }

                        val sb = StringBuilder()
                        for (item in historyList) {
                            sb.append("日期: ${item.transaction_date} ${item.transaction_time}\n")
                            sb.append("動作: ${if (item.io_type == "IN") "[入庫]" else "[出庫]"} 數量: ${item.qty} 件 → 結餘: ${item.post_balance_qty} 件\n")
                            sb.append("經辦人: ${item.operator_name}\n")
                            sb.append("備註說明: ${if (item.remark.isNullOrBlank()) "無" else item.remark}\n")
                            sb.append("----------------------------------\n")
                        }
                        runOnUiThread {
                            txtHistoryContent.text = sb.toString()
                            txtHistoryContent.setTextColor(Color.WHITE)
                        }
                    }

                    response.code() == 401 -> {
                        runOnUiThread {
                            txtHistoryContent.text = "登入已逾時，請重新登入。"
                            Toast.makeText(this@HomeActivity, "登入已逾時，請重新登入。", Toast.LENGTH_LONG).show()
                        }
                    }

                    else -> {
                        runOnUiThread { txtHistoryContent.text = "讀取歷史失敗 (HTTP ${response.code()})" }
                    }
                }
            }

            override fun onFailure(call: Call<List<TransactionHistoryItem>>, t: Throwable) {
                runOnUiThread {
                    txtHistoryContent.text = "索取歷史日誌失敗: ${t.localizedMessage}"
                    txtHistoryContent.setTextColor(Color.WHITE)
                }
            }
        })
    }

    /** Submits a stock-in/stock-out transaction and refreshes the list on success. */
    private fun sendTransaction(itemId: String, ioType: String, qty: Int, remark: String) {
        val request = TransactionRequest(itemId.toInt(), ioType, qty, remark)

        RetrofitClient.instance.createTransaction(request).enqueue(object : Callback<TransactionResponse> {
            override fun onResponse(call: Call<TransactionResponse>, response: Response<TransactionResponse>) {
                when {
                    response.isSuccessful -> runOnUiThread {
                        Toast.makeText(this@HomeActivity, "交易成功！", Toast.LENGTH_SHORT).show()
                        loadStockData()
                    }
                    response.code() == 401 -> runOnUiThread {
                        Toast.makeText(this@HomeActivity, "登入已逾時，請重新登入。", Toast.LENGTH_LONG).show()
                    }
                    else -> runOnUiThread {
                        Toast.makeText(this@HomeActivity, "交易失敗 (HTTP ${response.code()})", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onFailure(call: Call<TransactionResponse>, t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this@HomeActivity, "無法連線到伺服器：${t.localizedMessage}", Toast.LENGTH_LONG).show()
                }
                Log.e(TAG, "Transaction request failed", t)
            }
        })
    }

    // =====================================================================
    // Global (cross-item) transaction history
    // =====================================================================

    /** Shows the full, cross-item transaction log, newest first. */
    private fun showGlobalHistoryDialog() {
        val context = this

        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 40) }
        val txtTitle = TextView(context).apply {
            text = "全庫房歷史異動總帳流水線：\n(由近往遠，最新異動自動置頂)"
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 15f
            setTextColor(Color.parseColor("#FFFFFF"))
        }
        container.addView(txtTitle)

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1000).apply { topMargin = 10 }
        }
        val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listContainer.addView(TextView(context).apply { text = "正在讀取全域流水帳中..."; setTextColor(Color.parseColor("#FFFFFF")) })
        scrollView.addView(listContainer)
        container.addView(scrollView)

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("時序交易查詢面板")
            .setView(container)
            .setNegativeButton("關閉退出", null)
            .create()
        dialog.show()

        RetrofitClient.instance.getGlobalHistory().enqueue(object : Callback<List<GlobalLogResponse>> {
            override fun onResponse(call: Call<List<GlobalLogResponse>>, response: Response<List<GlobalLogResponse>>) {
                runOnUiThread {
                    when {
                        response.isSuccessful -> renderGlobalHistory(context, listContainer, response.body() ?: emptyList())
                        response.code() == 401 -> {
                            listContainer.removeAllViews()
                            listContainer.addView(TextView(context).apply { text = "登入已逾時，請重新登入。"; setTextColor(Color.YELLOW) })
                            Toast.makeText(context, "登入已逾時，請重新登入。", Toast.LENGTH_LONG).show()
                        }
                        else -> {
                            listContainer.removeAllViews()
                            listContainer.addView(TextView(context).apply { text = "讀取流水帳失敗 (HTTP ${response.code()})"; setTextColor(Color.RED) })
                        }
                    }
                }
            }

            override fun onFailure(call: Call<List<GlobalLogResponse>>, t: Throwable) {
                runOnUiThread {
                    listContainer.removeAllViews()
                    listContainer.addView(TextView(context).apply { text = "無法連線到伺服器：${t.localizedMessage}"; setTextColor(Color.RED) })
                }
                Log.e(TAG, "Failed to load global history", t)
            }
        })
    }

    /** Renders each global history entry as a text row with a divider. */
    private fun renderGlobalHistory(context: Context, listContainer: LinearLayout, logs: List<GlobalLogResponse>) {
        listContainer.removeAllViews()

        if (logs.isEmpty()) {
            listContainer.addView(TextView(context).apply { text = "目前資料庫尚無任何庫存進出交易紀錄。"; setTextColor(Color.WHITE) })
            return
        }

        for (log in logs) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 20, 0, 20) }
            val badge = if (log.io_type == "IN") "【入庫】" else "【出庫】"
            val remarkStr = if (log.remark.isNullOrBlank()) "無" else log.remark
            val displayOperator = if (log.operator_name.isNullOrBlank()) "已刪除人員" else log.operator_name

            val txtLogInfo = TextView(context).apply {
                text = "時間: ${log.transaction_date} ${log.transaction_time}\n" +
                        "$badge\n" +
                        "品項: ${log.item_name} (ID: ${log.item_id})\n" +
                        "數量: 實動 ${log.transaction_qty} 件 → 異動後庫存剩餘: ${log.post_balance_qty} 件\n" +
                        "異動人員: $displayOperator\n" +
                        "備註: $remarkStr"
                textSize = 13f
                setTextColor(Color.WHITE)
                setLineSpacing(0f, 1.15f)
            }
            row.addView(txtLogInfo)

            row.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply { topMargin = 15 }
                setBackgroundColor(Color.parseColor("#555555"))
            })

            listContainer.addView(row)
        }
    }
}