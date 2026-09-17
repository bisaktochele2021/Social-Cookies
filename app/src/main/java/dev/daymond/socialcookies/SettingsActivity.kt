package dev.daymond.socialcookies

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebSettings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import dev.daymond.socialcookies.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNavigation()

        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val deviceUA = WebSettings.getDefaultUserAgent(this)

        // Setup current auth user info
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            val emailOrName = currentUser.displayName ?: currentUser.email ?: "Authenticated User"
            binding.tvSignedInUser.text = getString(R.string.signed_in_as, emailOrName)
        } else {
            binding.tvSignedInUser.text = "Not signed in"
        }

        binding.etSheetUrl.setText(sharedPref.getString("SHEET_WEB_APP_URL", ""))
        binding.etMasterPassword.setText(sharedPref.getString("MASTER_PASSWORD", ""))
        binding.etUserAgent.setText(sharedPref.getString("USER_AGENT", deviceUA))

        binding.btnSignOut.setOnClickListener {
            sharedPref.edit().remove("ACTIVE_USER_UID").apply()
            FirebaseAuth.getInstance().signOut()
            GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut().addOnCompleteListener {
                val intent = Intent(this, AuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
        }

        binding.btnResetUa.setOnClickListener {
            val defaultUA = WebSettings.getDefaultUserAgent(this)
            binding.etUserAgent.setText(defaultUA)
            Toast.makeText(this, getString(R.string.user_agent_reset), Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveSettings.setOnClickListener {
            val sheetUrl = binding.etSheetUrl.text.toString().trim()
            val password = binding.etMasterPassword.text.toString().trim()
            val ua = binding.etUserAgent.text.toString().trim()
            
            sharedPref.edit().apply {
                putString("SHEET_WEB_APP_URL", sheetUrl)
                putString("MASTER_PASSWORD", password)
                putString("USER_AGENT", ua)
                apply()
            }

            // Broadcast settings update to any active floating windows across processes (:process1, :process2)
            val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val updateIntent = Intent(FloatingService.ACTION_UPDATE_SETTINGS).apply {
                setPackage(packageName)
                putExtra(FloatingService.EXTRA_MASTER_PASSWORD, password)
                putExtra(FloatingService.EXTRA_SHEET_URL, sheetUrl)
                putExtra(FloatingService.EXTRA_USER_AGENT, ua)
                putExtra(FloatingService.EXTRA_USER_UID, currentUid)
            }
            sendBroadcast(updateIntent)
            
            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        }

        binding.btnTestUa.setOnClickListener {
            val ua = binding.etUserAgent.text.toString()
            sharedPref.edit().putString("USER_AGENT", ua).apply()

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission required to test", Toast.LENGTH_SHORT).show()
            } else {
                startFloatingService("test_ua")
            }
        }

        binding.cardOpenGuide.setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.selectedItemId = R.id.nav_settings

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_sheet -> {
                    startActivity(Intent(this, SheetActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_settings -> true
                else -> false
            }
        }
    }

    private fun startFloatingService(platform: String) {
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val intent = Intent(this, FloatingService1::class.java).apply {
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
    }
}
