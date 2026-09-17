package dev.daymond.socialcookies

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

import dev.daymond.socialcookies.databinding.ActivityGuideBinding

class GuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGuideBack.setOnClickListener {
            finish()
        }

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "2.0.0"
            binding.tvAppVersion.text = "$versionName (Pro)"
        } catch (e: Exception) {
            binding.tvAppVersion.text = "2.0.0 (Pro)"
        }
    }
}
