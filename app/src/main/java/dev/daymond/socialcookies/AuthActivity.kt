package dev.daymond.socialcookies

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import dev.daymond.socialcookies.databinding.ActivityAuthBinding

class AuthActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private var googleSignInClient: GoogleSignInClient? = null
    private var isSignUpMode = false
    private lateinit var binding: ActivityAuthBinding

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account != null && account.idToken != null) {
                firebaseAuthWithGoogle(account)
            } else {
                hideLoading()
                Toast.makeText(this, "Google Sign-In was cancelled", Toast.LENGTH_SHORT).show()
            }
        } catch (e: ApiException) {
            hideLoading()
            val statusString = GoogleSignInStatusCodes.getStatusCodeString(e.statusCode)
            val errorMsg = "Google Sign-In failed (${e.statusCode}: $statusString)"
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        setupListeners()
        setupGoogleSignIn()
    }

    private fun setupListeners() {
        binding.tabSignIn.setOnClickListener { setMode(signUp = false) }
        binding.tabSignUp.setOnClickListener { setMode(signUp = true) }

        binding.btnSubmit.setOnClickListener {
            if (isSignUpMode) {
                handleSignUp()
            } else {
                handleSignIn()
            }
        }

        binding.tvForgotPassword.setOnClickListener {
            showForgotPasswordDialog()
        }

        binding.btnGoogleSignIn.setOnClickListener {
            handleGoogleSignIn()
        }
    }

    private fun setMode(signUp: Boolean) {
        isSignUpMode = signUp

        // Clear any previous error states
        binding.tilName.error = null
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tilConfirmPassword.error = null

        if (signUp) {
            binding.tabSignUp.setBackgroundResource(R.drawable.bg_tab_selected)
            binding.tabSignUp.setTextColor(getColor(R.color.white))
            binding.tabSignIn.setBackgroundResource(android.R.color.transparent)
            binding.tabSignIn.setTextColor(0x99FFFFFF.toInt())

            binding.tilName.visibility = View.VISIBLE
            binding.tilConfirmPassword.visibility = View.VISIBLE
            binding.tvForgotPassword.visibility = View.GONE

            binding.tvAuthTitle.text = getString(R.string.create_account)
            binding.tvAuthSubtitle.text = getString(R.string.sign_up_to_get_started)
            binding.btnSubmit.text = getString(R.string.sign_up)
        } else {
            binding.tabSignIn.setBackgroundResource(R.drawable.bg_tab_selected)
            binding.tabSignIn.setTextColor(getColor(R.color.white))
            binding.tabSignUp.setBackgroundResource(android.R.color.transparent)
            binding.tabSignUp.setTextColor(0x99FFFFFF.toInt())

            binding.tilName.visibility = View.GONE
            binding.tilConfirmPassword.visibility = View.GONE
            binding.tvForgotPassword.visibility = View.VISIBLE

            binding.tvAuthTitle.text = getString(R.string.welcome_back)
            binding.tvAuthSubtitle.text = getString(R.string.sign_in_to_continue)
            binding.btnSubmit.text = getString(R.string.sign_in)
        }
    }

    private fun handleSignIn() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (!validateEmail(email)) return
        if (password.isEmpty()) {
            binding.tilPassword.error = getString(R.string.error_empty_password)
            return
        }
        binding.tilPassword.error = null

        showLoading(getString(R.string.sign_in))

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        syncAndNavigate(user, provider = "password")
                    } else {
                        hideLoading()
                        Toast.makeText(this, "Authentication error: User data missing", Toast.LENGTH_LONG).show()
                    }
                } else {
                    hideLoading()
                    val msg = task.exception?.localizedMessage ?: "Authentication failed"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun handleSignUp() {
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()

        if (name.isEmpty()) {
            binding.tilName.error = getString(R.string.error_empty_name)
            return
        }
        binding.tilName.error = null

        if (!validateEmail(email)) return

        if (password.length < 6) {
            binding.tilPassword.error = getString(R.string.error_short_password)
            return
        }
        binding.tilPassword.error = null

        if (password != confirmPassword) {
            binding.tilConfirmPassword.error = getString(R.string.error_password_mismatch)
            return
        }
        binding.tilConfirmPassword.error = null

        showLoading(getString(R.string.create_account))

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        // Set display name in Firebase profile
                        val profileUpdates = UserProfileChangeRequest.Builder()
                            .setDisplayName(name)
                            .build()
                        user.updateProfile(profileUpdates)

                        syncAndNavigate(user, provider = "password", customName = name)
                    } else {
                        hideLoading()
                        Toast.makeText(this, "Registration error: User data missing", Toast.LENGTH_LONG).show()
                    }
                } else {
                    hideLoading()
                    val msg = task.exception?.localizedMessage ?: "Registration failed"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun setupGoogleSignIn() {
        val webClientId = try {
            getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            val resId = resources.getIdentifier("default_web_client_id", "string", packageName)
            if (resId != 0) getString(resId) else null
        }

        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()

        if (!webClientId.isNullOrBlank()) {
            gsoBuilder.requestIdToken(webClientId)
        }

        googleSignInClient = GoogleSignIn.getClient(this, gsoBuilder.build())
    }

    private fun handleGoogleSignIn() {
        val client = googleSignInClient
        if (client == null) {
            Toast.makeText(this, "Google Sign-In is not configured yet", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading("Connecting to Google...")
        // Sign out previous session so account picker is always shown
        client.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(client.signInIntent)
        }
    }

    private fun firebaseAuthWithGoogle(account: GoogleSignInAccount) {
        showLoading("Authenticating with Firebase...")
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        syncAndNavigate(user, provider = "google.com", customName = account.displayName)
                    } else {
                        hideLoading()
                        Toast.makeText(this, "Google auth error: User data missing", Toast.LENGTH_LONG).show()
                    }
                } else {
                    hideLoading()
                    val msg = task.exception?.localizedMessage ?: "Google authentication failed"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun syncAndNavigate(user: FirebaseUser, provider: String, customName: String? = null) {
        showLoading("Syncing profile with database...")

        getSharedPreferences("AppPrefs", MODE_PRIVATE)
            .edit()
            .putString("ACTIVE_USER_UID", user.uid)
            .apply()

        UserSyncManager.syncUserToServer(
            scope = lifecycleScope,
            context = this,
            user = user,
            provider = provider,
            customName = customName
        ) { success, msg ->
            hideLoading()
            Toast.makeText(this, getString(R.string.auth_success), Toast.LENGTH_SHORT).show()
            navigateToMain()
        }
    }

    private fun showForgotPasswordDialog() {
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.email)
            setText(binding.etEmail.text.toString().trim())
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setTextColor(getColor(R.color.white))
        }

        val container = FrameLayout(this).apply {
            setPadding(60, 20, 60, 0)
            addView(input)
        }

        AlertDialog.Builder(this, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_Dialog_Alert)
            .setTitle(getString(R.string.reset_password_title))
            .setMessage(getString(R.string.reset_password_desc))
            .setView(container)
            .setPositiveButton(getString(R.string.send_reset_link)) { _, _ ->
                val resetEmail = input.text.toString().trim()
                if (validateEmail(resetEmail)) {
                    showLoading("Sending reset link...")
                    auth.sendPasswordResetEmail(resetEmail)
                        .addOnCompleteListener { resetTask ->
                            hideLoading()
                            if (resetTask.isSuccessful) {
                                Toast.makeText(this, getString(R.string.reset_link_sent), Toast.LENGTH_LONG).show()
                            } else {
                                val error = resetTask.exception?.localizedMessage ?: "Failed to send reset email"
                                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                            }
                        }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun validateEmail(email: String): Boolean {
        if (email.isEmpty()) {
            binding.tilEmail.error = getString(R.string.error_empty_email)
            return false
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = getString(R.string.error_invalid_email)
            return false
        }
        binding.tilEmail.error = null
        return true
    }

    private fun showLoading(message: String) {
        binding.tvProgressMessage.text = message
        binding.progressOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        binding.progressOverlay.visibility = View.GONE
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
