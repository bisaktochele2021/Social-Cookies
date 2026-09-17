package dev.daymond.socialcookies

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import dev.daymond.socialcookies.databinding.ActivitySplashBinding

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        val bgCircle = binding.vBgCircle
        val glowRing = binding.vGlowRing
        val ivIcon = binding.ivSplashIcon
        val tvTitle = binding.tvSplashTitle
        val tvTagline = binding.tvSplashTagline
        val tvFooter = binding.tvSplashFooter

        // Reset states
        bgCircle.scaleX = 0f
        bgCircle.scaleY = 0f
        bgCircle.alpha = 0f

        glowRing.scaleX = 0f
        glowRing.scaleY = 0f
        glowRing.alpha = 0f

        ivIcon.scaleX = 0f
        ivIcon.scaleY = 0f
        ivIcon.alpha = 0f

        tvTitle.translationY = 100f
        tvTitle.alpha = 0f

        tvTagline.translationY = 50f
        tvTagline.alpha = 0f

        tvFooter.alpha = 0f
        tvFooter.translationY = 20f

        // 1. Background Ripple Effect
        bgCircle.animate()
            .scaleX(5f)
            .scaleY(5f)
            .alpha(0.15f)
            .setDuration(1200)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction {
                bgCircle.animate()
                    .alpha(0f)
                    .setDuration(800)
                    .start()
            }
            .start()

        // 2. Continuous rotating glowing ring
        glowRing.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .alpha(1f)
            .setDuration(1000)
            .setInterpolator(OvershootInterpolator(1.2f))
            .withEndAction {
                // Continuous rotation
                glowRing.animate()
                    .rotationBy(360f)
                    .setDuration(4000)
                    .setInterpolator(LinearInterpolator())
                    .withEndAction {
                        glowRing.animate().rotationBy(360f).setDuration(4000).start()
                    }
                    .start()
            }
            .start()

        // 3. App Icon pop-in
        ivIcon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(800)
            .setStartDelay(200)
            .setInterpolator(AnticipateOvershootInterpolator(1.5f))
            .start()

        // 4. Text slide-up cascade
        tvTitle.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(600)
            .setStartDelay(500)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        tvTagline.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(600)
            .setStartDelay(650)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        tvFooter.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(800)
            .setStartDelay(900)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Transition after 2.2 seconds to allow animations to play out
        Handler(Looper.getMainLooper()).postDelayed({
            checkAuthAndProceed()
        }, 2200)
    }

    private fun checkAuthAndProceed() {
        val currentUser = auth.currentUser
        val destination = if (currentUser != null) {
            getSharedPreferences("AppPrefs", MODE_PRIVATE)
                .edit()
                .putString("ACTIVE_USER_UID", currentUser.uid)
                .apply()
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, AuthActivity::class.java)
        }

        // Add crossfade transition
        startActivity(destination)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
