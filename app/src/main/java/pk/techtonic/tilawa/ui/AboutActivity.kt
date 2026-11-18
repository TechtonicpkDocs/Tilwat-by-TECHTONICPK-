package pk.techtonic.tilawa.ui

import android.os.Bundle
import pk.techtonic.tilawa.BuildConfig
import pk.techtonic.tilawa.R
import pk.techtonic.tilawa.databinding.ActivityAboutBinding
import androidx.appcompat.app.AppCompatActivity

/**
 * About screen: app name, version (from the build), TECHTONICPK credit,
 * the full upstream attribution, the honest-limits line and the offline
 * promise. All text comes from resources; the version from BuildConfig.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.about_title)
        binding.versionText.text = getString(
            R.string.about_version_format,
            BuildConfig.VERSION_NAME
        )
    }
}
