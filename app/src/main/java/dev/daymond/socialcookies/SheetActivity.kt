package dev.daymond.socialcookies

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Date
import dev.daymond.socialcookies.databinding.ActivitySheetBinding

class SheetActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySheetBinding
    private lateinit var adapter: SheetAdapter
    private lateinit var database: AppDatabase
    private var selectedExportFields = booleanArrayOf(true, true, true, true) // UID, Password, Cookies, Timestamp

    private var allCookies: List<CookieRecord> = emptyList()
    private var searchQuery: String = ""
    private var currentFilter: String = "All"

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri: Uri? ->
        uri?.let { exportToExcel(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySheetBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)
        setupRecyclerView()
        setupBottomNavigation()

        binding.btnSyncNow.setOnClickListener {
            syncData()
        }

        binding.btnSheetExport.setOnClickListener {
            showExportFieldDialog()
        }

        binding.btnClearDataSheet.setOnClickListener {
            showClearConfirmationDialog()
        }

        binding.btnCheckAccounts.setOnClickListener {
            checkAccounts()
        }

        setupSearchAndFilters()
        observeData()
        autoSyncData()
    }

    override fun onResume() {
        super.onResume()
        autoSyncData()
    }

    private fun getCurrentUserUid(): String {
        val authUid = FirebaseAuth.getInstance().currentUser?.uid
        if (!authUid.isNullOrEmpty()) {
            getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit().putString("ACTIVE_USER_UID", authUid).apply()
            return authUid
        }
        return getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("ACTIVE_USER_UID", "") ?: ""
    }

    private fun autoSyncData() {
        val userUid = getCurrentUserUid()
        if (userUid.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dao = database.cookieDao()
                dao.claimOrphanCookies(userUid)

                // 1. App -> Server: Push unsynced local cookies
                val unsynced = dao.getUnsyncedCookiesByUser(userUid)
                if (unsynced.isNotEmpty()) {
                    val pushResult = BackendApiManager.syncCookies(this@SheetActivity, unsynced)
                    if (pushResult.isSuccess) {
                        for (rec in unsynced) {
                            dao.update(rec.copy(isSynced = true))
                        }
                    }
                }

                // 2. Server -> App: Pull remote cookies for active user
                val pullResult = BackendApiManager.getCookies(this@SheetActivity, userUid)
                if (pullResult.isSuccess) {
                    val remoteCookies = pullResult.getOrDefault(emptyList())
                    for (remote in remoteCookies) {
                        val local = dao.getByUserAndUid(userUid, remote.uid)
                        if (local == null) {
                            dao.insert(remote.copy(id = 0, userUid = userUid, isSynced = true))
                        } else {
                            val shouldUpdate = remote.timestamp > local.timestamp ||
                                    remote.isLive != local.isLive ||
                                    (remote.cookies.isNotEmpty() && remote.cookies != local.cookies) ||
                                    (remote.password.isNotEmpty() && remote.password != local.password)
                            if (shouldUpdate) {
                                dao.update(local.copy(
                                    host = remote.host,
                                    cookies = if (remote.cookies.isNotEmpty()) remote.cookies else local.cookies,
                                    password = if (remote.password.isNotEmpty()) remote.password else local.password,
                                    timestamp = Math.max(local.timestamp, remote.timestamp),
                                    isLive = remote.isLive,
                                    isSynced = true
                                ))
                            } else if (!local.isSynced) {
                                dao.update(local.copy(isSynced = true))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SheetActivity", "AutoSync failed: ${e.message}")
            }
        }
    }

    private fun setupSearchAndFilters() {
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s.toString().trim()
                applyFilters()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.chipGroupFilter.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = group.findViewById<com.google.android.material.chip.Chip>(checkedIds.first())
                currentFilter = chip.text.toString()
                applyFilters()
            }
        }
    }

    private fun applyFilters() {
        lifecycleScope.launch {
            val (filteredList, liveCount, deadCount) = withContext(Dispatchers.Default) {
                var list = allCookies

                // Apply Filter Chip
                if (currentFilter == "Live") {
                    list = list.filter { it.isLive }
                } else if (currentFilter == "Dead") {
                    list = list.filter { !it.isLive }
                }

                // Apply Search
                if (searchQuery.isNotEmpty()) {
                    list = list.filter { it.uid.contains(searchQuery, ignoreCase = true) }
                }

                val live = allCookies.count { it.isLive }
                val dead = allCookies.count { !it.isLive }
                Triple(list, live, dead)
            }

            adapter.updateData(filteredList)

            binding.tvCountLive.text = "Live: $liveCount"
            binding.tvCountDead.text = "Dead: $deadCount"
            binding.layoutEmptyState.visibility = if (filteredList.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun showExportFieldDialog() {
        val fields = arrayOf(
            getString(R.string.uid),
            getString(R.string.pass),
            getString(R.string.cookies),
            getString(R.string.timestamp_field)
        )
        val tempChecked = selectedExportFields.clone()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_fields_to_export))
            .setMultiChoiceItems(fields, tempChecked) { _, which, isChecked ->
                tempChecked[which] = isChecked
            }
            .setPositiveButton("Save to Device") { _, _ ->
                if (!tempChecked.contains(true)) {
                    Toast.makeText(this, getString(R.string.select_at_least_one_field), Toast.LENGTH_SHORT).show()
                } else {
                    selectedExportFields = tempChecked
                    createDocumentLauncher.launch("cookies_sheet_export_${System.currentTimeMillis()}.xlsx")
                }
            }
            .setNeutralButton("Share") { _, _ ->
                if (!tempChecked.contains(true)) {
                    Toast.makeText(this, getString(R.string.select_at_least_one_field), Toast.LENGTH_SHORT).show()
                } else {
                    selectedExportFields = tempChecked
                    shareExcelFile()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun setupRecyclerView() {
        val rv = binding.rvSheetData
        rv.layoutManager = LinearLayoutManager(this)
        adapter = SheetAdapter(mutableListOf())
        adapter.onStatusToggle = { cookie ->
            lifecycleScope.launch(Dispatchers.IO) {
                val updated = cookie.copy(isLive = !cookie.isLive, isSynced = false)
                database.cookieDao().update(updated)
                val syncRes = BackendApiManager.syncCookies(this@SheetActivity, listOf(updated))
                if (syncRes.isSuccess) {
                    database.cookieDao().update(updated.copy(isSynced = true))
                }
            }
        }
        rv.adapter = adapter

        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean = false

            override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
                val itemView = viewHolder.itemView
                val icon = ContextCompat.getDrawable(this@SheetActivity, R.drawable.ic_delete)
                val background = ColorDrawable(Color.parseColor("#B00020"))
                
                val iconMargin = (itemView.height - (icon?.intrinsicHeight ?: 0)) / 2
                val iconTop = itemView.top + (itemView.height - (icon?.intrinsicHeight ?: 0)) / 2
                val iconBottom = iconTop + (icon?.intrinsicHeight ?: 0)

                if (dX > 0) { // Swiping Right
                    background.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                    icon?.setBounds(itemView.left + iconMargin, iconTop, itemView.left + iconMargin + (icon.intrinsicWidth), iconBottom)
                } else if (dX < 0) { // Swiping Left
                    background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                    icon?.setBounds(itemView.right - iconMargin - (icon.intrinsicWidth), iconTop, itemView.right - iconMargin, iconBottom)
                } else {
                    background.setBounds(0, 0, 0, 0)
                }
                
                background.draw(c)
                if (dX != 0f) icon?.draw(c)
                
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val cookieRecord = adapter.getCookieAt(position)
                
                AlertDialog.Builder(this@SheetActivity)
                    .setTitle("Delete Record?")
                    .setMessage("Are you sure you want to delete UID: ${cookieRecord.uid}?")
                    .setPositiveButton("Delete") { _, _ ->
                        deleteRecord(cookieRecord, position)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        adapter.notifyItemChanged(position)
                    }
                    .setOnCancelListener {
                        adapter.notifyItemChanged(position)
                    }
                    .show()
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(rv)
    }

    private fun observeData() {
        val userUid = getCurrentUserUid()
        lifecycleScope.launch {
            val flow = if (userUid.isNotEmpty()) {
                database.cookieDao().getCookiesByUserFlow(userUid)
            } else {
                database.cookieDao().getAllCookiesFlow()
            }
            flow.collectLatest { cookies ->
                allCookies = cookies
                applyFilters()
            }
        }
    }

    private fun deleteRecord(record: CookieRecord, position: Int) {
        val userUid = getCurrentUserUid()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.cookieDao().delete(record)
                if (userUid.isNotEmpty()) {
                    BackendApiManager.deleteCookie(this@SheetActivity, userUid, record.uid, record.host)
                }
            }
            Toast.makeText(this@SheetActivity, "Record deleted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showClearConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_history_title))
            .setMessage(getString(R.string.clear_history_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                clearAllData()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun checkAccounts() {
        val btnCheck = binding.btnCheckAccounts
        btnCheck.isEnabled = false
        btnCheck.text = getString(R.string.checking_accounts)
        Toast.makeText(this, getString(R.string.checking_accounts), Toast.LENGTH_SHORT).show()

        val userUid = getCurrentUserUid()
        lifecycleScope.launch(Dispatchers.IO) {
            val cookies = if (userUid.isNotEmpty()) {
                database.cookieDao().getCookiesByUser(userUid)
            } else {
                database.cookieDao().getAllCookies()
            }
            if (cookies.isEmpty()) {
                withContext(Dispatchers.Main) {
                    btnCheck.isEnabled = true
                    btnCheck.text = getString(R.string.check_accounts)
                    Toast.makeText(this@SheetActivity, getString(R.string.no_accounts_to_check), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val liveCounter = java.util.concurrent.atomic.AtomicInteger(0)
            val deadCounter = java.util.concurrent.atomic.AtomicInteger(0)

            val jobs = cookies.map { cookie ->
                async {
                    val cleanUid = cookie.uid.trim()
                    var isLive = false

                    if (cleanUid.isNotEmpty()) {
                        var connection: HttpURLConnection? = null
                        try {
                            val url = URL("https://graph.facebook.com/$cleanUid/picture?type=normal")
                            connection = (url.openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                instanceFollowRedirects = false
                                connectTimeout = 6000
                                readTimeout = 6000
                                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            }
                            val code = connection.responseCode
                            // Check if the redirect URL (Location header) contains '100x100'
                            val location = connection.getHeaderField("Location") ?: ""
                            isLive = location.contains("100x100")
                        } catch (e: Exception) {
                            e.printStackTrace()
                            isLive = false
                        } finally {
                            connection?.disconnect()
                        }
                    }

                    if (isLive) {
                        liveCounter.incrementAndGet()
                    } else {
                        deadCounter.incrementAndGet()
                    }

                    if (cookie.isLive != isLive) {
                        val updated = cookie.copy(isLive = isLive, isSynced = false)
                        database.cookieDao().update(updated)
                        BackendApiManager.syncCookies(this@SheetActivity, listOf(updated))
                    }
                }
            }
            jobs.awaitAll()

            withContext(Dispatchers.Main) {
                btnCheck.isEnabled = true
                btnCheck.text = getString(R.string.check_accounts)
                Toast.makeText(
                    this@SheetActivity,
                    getString(R.string.check_accounts_complete, cookies.size, liveCounter.get(), deadCounter.get()),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun clearAllData() {
        val userUid = getCurrentUserUid()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (userUid.isNotEmpty()) {
                    database.cookieDao().deleteAllForUser(userUid)
                    BackendApiManager.clearCookies(this@SheetActivity, userUid)
                } else {
                    database.cookieDao().deleteAll()
                }
            }
            Toast.makeText(this@SheetActivity, getString(R.string.history_cleared), Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportToExcel(uri: Uri) {
        val exportUid = selectedExportFields.getOrNull(0) ?: true
        val exportPassword = selectedExportFields.getOrNull(1) ?: true
        val exportCookies = selectedExportFields.getOrNull(2) ?: true
        val exportTimestamp = selectedExportFields.getOrNull(3) ?: true

        val userUid = getCurrentUserUid()
        lifecycleScope.launch {
            try {
                val cookies = withContext(Dispatchers.IO) {
                    if (userUid.isNotEmpty()) {
                        database.cookieDao().getCookiesByUser(userUid)
                    } else {
                        database.cookieDao().getAllCookies()
                    }
                }

                withContext(Dispatchers.IO) {
                    val workbook = XSSFWorkbook()
                    val sheet = workbook.createSheet("Cookies")
                    val headerRow = sheet.createRow(0)

                    val headers = mutableListOf<String>()
                    if (exportUid) headers.add("UID")
                    if (exportPassword) headers.add("Password")
                    if (exportCookies) headers.add("Cookies")
                    if (exportTimestamp) headers.add("Timestamp")

                    headers.forEachIndexed { colIndex, headerName ->
                        headerRow.createCell(colIndex).setCellValue(headerName)
                    }

                    cookies.forEachIndexed { rowIndex, cookie ->
                        val row = sheet.createRow(rowIndex + 1)
                        var colIndex = 0
                        if (exportUid) {
                            row.createCell(colIndex++).setCellValue(cookie.uid)
                        }
                        if (exportPassword) {
                            row.createCell(colIndex++).setCellValue(cookie.password)
                        }
                        if (exportCookies) {
                            row.createCell(colIndex++).setCellValue(cookie.cookies)
                        }
                        if (exportTimestamp) {
                            row.createCell(colIndex++).setCellValue(Date(cookie.timestamp).toString())
                        }
                    }

                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        workbook.write(outputStream)
                    }
                    workbook.close()
                }
                Toast.makeText(this@SheetActivity, getString(R.string.export_successful), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@SheetActivity, getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareExcelFile() {
        val exportUid = selectedExportFields.getOrNull(0) ?: true
        val exportPassword = selectedExportFields.getOrNull(1) ?: true
        val exportCookies = selectedExportFields.getOrNull(2) ?: true
        val exportTimestamp = selectedExportFields.getOrNull(3) ?: true

        val userUid = getCurrentUserUid()
        lifecycleScope.launch {
            try {
                val cookies = withContext(Dispatchers.IO) {
                    if (userUid.isNotEmpty()) {
                        database.cookieDao().getCookiesByUser(userUid)
                    } else {
                        database.cookieDao().getAllCookies()
                    }
                }

                val sharedFile = withContext(Dispatchers.IO) {
                    val workbook = XSSFWorkbook()
                    val sheet = workbook.createSheet("Cookies")
                    val headerRow = sheet.createRow(0)

                    val headers = mutableListOf<String>()
                    if (exportUid) headers.add("UID")
                    if (exportPassword) headers.add("Password")
                    if (exportCookies) headers.add("Cookies")
                    if (exportTimestamp) headers.add("Timestamp")

                    headers.forEachIndexed { colIndex, headerName ->
                        headerRow.createCell(colIndex).setCellValue(headerName)
                    }

                    cookies.forEachIndexed { rowIndex, cookie ->
                        val row = sheet.createRow(rowIndex + 1)
                        var colIndex = 0
                        if (exportUid) {
                            row.createCell(colIndex++).setCellValue(cookie.uid)
                        }
                        if (exportPassword) {
                            row.createCell(colIndex++).setCellValue(cookie.password)
                        }
                        if (exportCookies) {
                            row.createCell(colIndex++).setCellValue(cookie.cookies)
                        }
                        if (exportTimestamp) {
                            row.createCell(colIndex++).setCellValue(Date(cookie.timestamp).toString())
                        }
                    }

                    val file = java.io.File(cacheDir, "cookies_export_${System.currentTimeMillis()}.xlsx")
                    java.io.FileOutputStream(file).use { outputStream ->
                        workbook.write(outputStream)
                    }
                    workbook.close()
                    file
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@SheetActivity,
                    "${packageName}.fileprovider",
                    sharedFile
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share Export File"))
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@SheetActivity, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun syncData() {
        val userUid = getCurrentUserUid()
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val sheetUrl = sharedPref.getString("SHEET_WEB_APP_URL", "") ?: ""
        val isBackendConfigured = BackendApiManager.isConfigured(this)

        if (sheetUrl.isEmpty() && !isBackendConfigured) {
            Toast.makeText(this, "Backend API is not configured in Settings", Toast.LENGTH_LONG).show()
            return
        }

        val btnSync = binding.btnSyncNow
        btnSync.isEnabled = false
        val originalText = btnSync.text.toString()
        btnSync.text = "Syncing..."

        Toast.makeText(this, "Starting two-way synchronization...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val dao = database.cookieDao()

                // Determine user UID, fallback to any existing cookie's userUid if not authenticated yet
                var effectiveUserUid = userUid
                if (effectiveUserUid.isEmpty()) {
                    val anyCookie = withContext(Dispatchers.IO) {
                        dao.getAllCookies().firstOrNull { it.userUid.isNotEmpty() }
                    }
                    if (anyCookie != null) {
                        effectiveUserUid = anyCookie.userUid
                    }
                }

                if (effectiveUserUid.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        dao.claimOrphanCookies(effectiveUserUid)
                    }
                }

                var uploadedCount = 0
                var downloadedCount = 0
                var updatedCount = 0
                var errorMsg: String? = null

                if (isBackendConfigured) {
                    // ==========================================
                    // 1. APP -> SERVER SYNC
                    // ==========================================
                    val localCookies = withContext(Dispatchers.IO) {
                        if (effectiveUserUid.isNotEmpty()) {
                            dao.getCookiesByUser(effectiveUserUid)
                        } else {
                            dao.getAllCookies()
                        }
                    }

                    // On explicit "Sync Now", upload any unsynced cookies, or all local cookies to guarantee server is updated
                    val unsynced = localCookies.filter { !it.isSynced }
                    val toUpload = if (unsynced.isNotEmpty()) unsynced else localCookies

                    if (toUpload.isNotEmpty()) {
                        val pushResult = BackendApiManager.syncCookies(this@SheetActivity, toUpload)
                        if (pushResult.isSuccess) {
                            uploadedCount = pushResult.getOrDefault(toUpload.size)
                            withContext(Dispatchers.IO) {
                                for (record in toUpload) {
                                    dao.update(record.copy(isSynced = true))
                                }
                            }
                        } else {
                            errorMsg = pushResult.exceptionOrNull()?.message
                            android.util.Log.w("SheetActivity", "App->Server sync failed: $errorMsg")
                        }
                    }

                    // ==========================================
                    // 2. SERVER -> APP SYNC
                    // ==========================================
                    if (effectiveUserUid.isNotEmpty()) {
                        val pullResult = withContext(Dispatchers.IO) {
                            BackendApiManager.getCookies(this@SheetActivity, effectiveUserUid)
                        }
                        if (pullResult.isSuccess) {
                            val remoteCookies = pullResult.getOrDefault(emptyList())
                            withContext(Dispatchers.IO) {
                                for (remote in remoteCookies) {
                                    val local = dao.getByUserAndUid(effectiveUserUid, remote.uid)
                                    if (local == null) {
                                        dao.insert(remote.copy(id = 0, userUid = effectiveUserUid, isSynced = true))
                                        downloadedCount++
                                    } else {
                                        val shouldUpdate = remote.timestamp > local.timestamp ||
                                                remote.isLive != local.isLive ||
                                                (remote.cookies.isNotEmpty() && remote.cookies != local.cookies) ||
                                                (remote.password.isNotEmpty() && remote.password != local.password)
                                        if (shouldUpdate) {
                                            dao.update(local.copy(
                                                host = remote.host,
                                                cookies = if (remote.cookies.isNotEmpty()) remote.cookies else local.cookies,
                                                password = if (remote.password.isNotEmpty()) remote.password else local.password,
                                                timestamp = Math.max(local.timestamp, remote.timestamp),
                                                isLive = remote.isLive,
                                                isSynced = true
                                            ))
                                            updatedCount++
                                        } else if (!local.isSynced) {
                                            dao.update(local.copy(isSynced = true))
                                        }
                                    }
                                }
                            }
                        } else if (errorMsg == null) {
                            errorMsg = pullResult.exceptionOrNull()?.message
                        }
                    }
                }

                // ==========================================
                // 3. GOOGLE SHEETS SYNC (Optional)
                // ==========================================
                var sheetCount = 0
                if (sheetUrl.isNotEmpty()) {
                    val unsyncedForSheet = withContext(Dispatchers.IO) {
                        if (effectiveUserUid.isNotEmpty()) dao.getUnsyncedCookiesByUser(effectiveUserUid)
                        else dao.getUnsyncedCookies()
                    }
                    if (unsyncedForSheet.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            for (record in unsyncedForSheet) {
                                if (sendToSheet(sheetUrl, record)) {
                                    sheetCount++
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // 4. USER FEEDBACK
                // ==========================================
                val feedback = buildString {
                    append("Sync complete! ")
                    if (uploadedCount > 0) append("Uploaded: $uploadedCount. ")
                    if (downloadedCount > 0) append("Downloaded: $downloadedCount. ")
                    if (updatedCount > 0) append("Updated: $updatedCount. ")
                    if (sheetCount > 0) append("Sheets: $sheetCount. ")
                    if (uploadedCount == 0 && downloadedCount == 0 && updatedCount == 0 && sheetCount == 0) {
                        if (errorMsg != null) {
                            append("Server message: $errorMsg")
                        } else {
                            append("All records are already up to date.")
                        }
                    }
                }
                Toast.makeText(this@SheetActivity, feedback.trim(), Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                android.util.Log.e("SheetActivity", "Sync error: ${e.message}", e)
                Toast.makeText(this@SheetActivity, "Sync error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                btnSync.isEnabled = true
                btnSync.text = originalText
            }
        }
    }

    private suspend fun sendToSheet(baseUrl: String, record: CookieRecord): Boolean {
        return try {
            val postData = "host=" + URLEncoder.encode(record.host, "UTF-8") +
                    "&uid=" + URLEncoder.encode(record.uid, "UTF-8") +
                    "&password=" + URLEncoder.encode(record.password, "UTF-8") +
                    "&cookies=" + URLEncoder.encode(record.cookies, "UTF-8")

            val url = URL(baseUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(postData.toByteArray()) }

            val responseCode = conn.responseCode
            responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_sheet

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_sheet -> true
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }
    }
}
