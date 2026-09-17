package dev.daymond.socialcookies

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.daymond.socialcookies.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingPlatform: String? = null
    private var pendingServiceClass: Class<*>? = null

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Settings.canDrawOverlays(this)) {
            pendingPlatform?.let { platform ->
                pendingServiceClass?.let { serviceClass ->
                    startFloatingService(platform, serviceClass)
                }
            }
        } else {
            Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(
                this,
                "Notification permission denied. Background services might not show status.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkNotificationPermission()
        setupBottomNavigation()

        setupPlatformGrid()

        binding.btnOpenGuide.setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }

        observeAnalytics()
        fetchBannerNotice()
    }

    private var selectedPlatform: String = "facebook"

    private fun setupPlatformGrid() {
        val platforms = listOf(
            Triple("instagram", binding.itemPlatformInstagram, "Instagram"),
            Triple("facebook", binding.itemPlatformFacebook, "Facebook"),
            Triple("twitter", binding.itemPlatformTwitter, "X / Twitter")
        )

        val disabledPlatforms = setOf("instagram", "twitter")

        fun updateGridUI() {
            for ((key, view, _) in platforms) {
                if (disabledPlatforms.contains(key)) {
                    view.setBackgroundResource(R.drawable.bg_platform_item_unselected)
                    view.alpha = 0.45f
                } else if (key == selectedPlatform) {
                    view.setBackgroundResource(R.drawable.bg_platform_item_selected)
                    view.alpha = 1.0f
                } else {
                    view.setBackgroundResource(R.drawable.bg_platform_item_unselected)
                    view.alpha = 1.0f
                }
            }

            val currentTitle = platforms.find { it.first == selectedPlatform }?.third ?: "Facebook"
            binding.btnRunWebview.text = "Run WebView ($currentTitle)"
        }

        for ((key, view, title) in platforms) {
            view.setOnClickListener {
                if (disabledPlatforms.contains(key)) {
                    Toast.makeText(this, "$title selection is disabled", Toast.LENGTH_SHORT).show()
                } else {
                    selectedPlatform = key
                    updateGridUI()
                }
            }
        }

        binding.btnRunWebview.setOnClickListener {
            checkAndStart(selectedPlatform, FloatingService1::class.java)
        }

        updateGridUI()
    }

    override fun onStart() {
        super.onStart()
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
            val intent = Intent(this, AuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        } else {
            AppNoticeManager.checkUpdatesAndNotices(this)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_home

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_sheet -> {
                    startActivity(Intent(this, SheetActivity::class.java))
                    true
                }

                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }

                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.bottomNavigation.selectedItemId = R.id.nav_home
        autoSyncUserData()
    }

    private fun autoSyncUserData() {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Synchronize user profile with backend API
                BackendApiManager.syncUser(this@MainActivity, user)

                // Claim orphan cookies for active user
                val dao = AppDatabase.getDatabase(this@MainActivity).cookieDao()
                dao.claimOrphanCookies(user.uid)

                // Auto-sync unsynced local cookies to backend API
                val unsynced = dao.getUnsyncedCookiesByUser(user.uid)
                if (unsynced.isNotEmpty()) {
                    val result = BackendApiManager.syncCookies(this@MainActivity, unsynced)
                    if (result.isSuccess) {
                        for (rec in unsynced) {
                            dao.update(rec.copy(isSynced = true))
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error in autoSyncUserData: ${e.message}")
            }
        }
    }

    private fun observeAnalytics() {
        val dao = AppDatabase.getDatabase(this).cookieDao()
        val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
        
        lifecycleScope.launch {
            val flow = if (currentUid.isNotEmpty()) {
                dao.getCookiesByUserFlow(currentUid)
            } else {
                dao.getAllCookiesFlow()
            }
            flow.collectLatest { cookies ->
                data class AnalyticsData(
                    val today: Int, 
                    val sevenDays: Int, 
                    val month: Int,
                    val chartEntries: List<com.github.mikephil.charting.data.BarEntry>,
                    val chartLabels: List<String>
                )
                
                val analytics = withContext(Dispatchers.Default) {
                    val calendar = Calendar.getInstance()
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val todayStart = calendar.timeInMillis

                    calendar.add(Calendar.DAY_OF_YEAR, -6) // 7 days ago (including today)
                    val sevenDaysAgoStart = calendar.timeInMillis

                    // Reset for month
                    calendar.timeInMillis = System.currentTimeMillis()
                    calendar.set(Calendar.DAY_OF_MONTH, 1)
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    val monthStart = calendar.timeInMillis

                    val today = cookies.count { it.timestamp >= todayStart }
                    val seven = cookies.count { it.timestamp >= sevenDaysAgoStart }
                    val month = cookies.count { it.timestamp >= monthStart }
                    
                    // Prepare Chart Data
                    val sdf = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
                    val labels = mutableListOf<String>()
                    val dailyCounts = mutableMapOf<Int, Int>()
                    
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = sevenDaysAgoStart
                    for (i in 0..6) {
                        labels.add(sdf.format(cal.time))
                        dailyCounts[i] = 0
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                    }

                    for (cookie in cookies) {
                        if (cookie.timestamp >= sevenDaysAgoStart) {
                            val daysDiff = ((cookie.timestamp - sevenDaysAgoStart) / (1000 * 60 * 60 * 24)).toInt()
                            if (daysDiff in 0..6) {
                                dailyCounts[daysDiff] = (dailyCounts[daysDiff] ?: 0) + 1
                            }
                        }
                    }

                    val entries = ArrayList<com.github.mikephil.charting.data.BarEntry>()
                    for (i in 0..6) {
                        entries.add(com.github.mikephil.charting.data.BarEntry(i.toFloat(), dailyCounts[i]?.toFloat() ?: 0f))
                    }

                    AnalyticsData(today, seven, month, entries, labels)
                }

                binding.tvTodayCount.text = analytics.today.toString()
                binding.tv7daysCount.text = analytics.sevenDays.toString()
                binding.tvMonthCount.text = analytics.month.toString()
                
                // Update BarChart
                setupChart(analytics.chartEntries, analytics.chartLabels)
            }
        }
    }

    private fun setupChart(entries: List<com.github.mikephil.charting.data.BarEntry>, labels: List<String>) {
        val dataSet = com.github.mikephil.charting.data.BarDataSet(entries, "Daily Activity")
        dataSet.color = android.graphics.Color.parseColor("#3D8BFF")
        dataSet.valueTextColor = android.graphics.Color.WHITE
        dataSet.valueTextSize = 10f

        val barData = com.github.mikephil.charting.data.BarData(dataSet)
        barData.barWidth = 0.5f

        binding.barChart.apply {
            data = barData
            description.isEnabled = false
            legend.isEnabled = false
            setDrawGridBackground(false)
            setDrawBorders(false)

            xAxis.apply {
                position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
                valueFormatter = com.github.mikephil.charting.formatter.IndexAxisValueFormatter(labels)
                textColor = android.graphics.Color.parseColor("#A0A0A0")
                setDrawGridLines(false)
                setDrawAxisLine(false)
                granularity = 1f
                isGranularityEnabled = true
            }

            axisLeft.apply {
                textColor = android.graphics.Color.parseColor("#A0A0A0")
                setDrawGridLines(true)
                gridColor = android.graphics.Color.parseColor("#2E2E34")
                setDrawAxisLine(false)
                axisMinimum = 0f
                granularity = 1f
            }

            axisRight.isEnabled = false
            
            animateY(1000)
            invalidate()
        }
    }

    private fun checkAndStart(platform: String, serviceClass: Class<*>) {
        pendingPlatform = platform
        pendingServiceClass = serviceClass

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startFloatingService(platform, serviceClass)
        }
    }

    private fun startFloatingService(platform: String, serviceClass: Class<*>) {
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val intent = Intent(this, serviceClass).apply {
            putExtra(FloatingService.EXTRA_PLATFORM, platform)
            putExtra(FloatingService.EXTRA_USER_UID, currentUid)
            putExtra(FloatingService.EXTRA_MASTER_PASSWORD, sharedPref.getString("MASTER_PASSWORD", ""))
            putExtra(FloatingService.EXTRA_SHEET_URL, sharedPref.getString("SHEET_WEB_APP_URL", ""))
            putExtra(FloatingService.EXTRA_USER_AGENT, sharedPref.getString("USER_AGENT", ""))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Window started", Toast.LENGTH_SHORT).show()
    }

    private fun fetchBannerNotice() {
        try {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance("https://social-cookies-default-rtdb.asia-southeast1.firebasedatabase.app")
            val bannerRef = db.getReference("banner_notice")
            
            bannerRef.addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val enabled = snapshot.child("enabled").getValue(Boolean::class.java) ?: false
                    val message = snapshot.child("message").getValue(String::class.java) ?: ""

                    if (enabled && message.isNotEmpty()) {
                        binding.tvNoticeMarquee.text = message
                        binding.layoutNoticeBanner.visibility = View.VISIBLE
                    } else {
                        binding.layoutNoticeBanner.visibility = View.GONE
                    }
                }

                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    android.util.Log.e("MainActivity", "Failed to read banner notice.", error.toException())
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Firebase Database error: ${e.message}")
        }
    }
}

