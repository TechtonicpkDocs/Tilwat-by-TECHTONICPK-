package pk.techtonic.tilawa.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import pk.techtonic.tilawa.R
import pk.techtonic.tilawa.databinding.ActivityMainBinding

/**
 * Home screen: mode cards, surah picker (114 surahs), ayah-range pickers,
 * start / history / about. Asks for the microphone with a plain Urdu
 * explanation before the first session; a denial is explained, never a crash.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val MIC_PERMISSION_REQUEST_CODE = 101
    }

    private lateinit var binding: ActivityMainBinding
    private var memorizationMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupModeCards()
        setupSurahSpinner()
        setupAyahPickers()

        binding.startButton.setOnClickListener { onStartClicked() }
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun setupModeCards() {
        val selectMemorization = { setMode(true) }
        val selectFree = { setMode(false) }
        binding.modeMemorizationCard.setOnClickListener { selectMemorization() }
        binding.modeMemorizationRadio.setOnClickListener { selectMemorization() }
        binding.modeFreeCard.setOnClickListener { selectFree() }
        binding.modeFreeRadio.setOnClickListener { selectFree() }
        setMode(true)
    }

    private fun setMode(memorization: Boolean) {
        memorizationMode = memorization
        binding.modeMemorizationRadio.isChecked = memorization
        binding.modeFreeRadio.isChecked = !memorization
        val visibility = if (memorization) View.VISIBLE else View.GONE
        binding.surahSection.visibility = visibility
        binding.ayahSection.visibility = visibility
    }

    private fun setupSurahSpinner() {
        val labels = SurahData.surahs.map { surah ->
            getString(
                R.string.surah_spinner_format,
                surah.number,
                surah.urduName,
                surah.ayahCount
            )
        }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.surahSpinner.adapter = adapter
        binding.surahSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    updateAyahPickers(SurahData.surahs[position].ayahCount)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    // Keep the current pickers; nothing to do.
                }
            }
    }

    private fun setupAyahPickers() {
        binding.fromAyahPicker.minValue = 1
        binding.toAyahPicker.minValue = 1
        updateAyahPickers(SurahData.surahs[0].ayahCount)
    }

    private fun updateAyahPickers(ayahCount: Int) {
        binding.fromAyahPicker.maxValue = ayahCount
        binding.toAyahPicker.maxValue = ayahCount
        binding.fromAyahPicker.value = 1
        binding.toAyahPicker.value = ayahCount
    }

    private fun onStartClicked() {
        if (memorizationMode &&
            binding.fromAyahPicker.value > binding.toAyahPicker.value
        ) {
            Toast.makeText(this, R.string.error_invalid_range, Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startSession()
        } else {
            // Plain Urdu explanation first, then the system prompt.
            AlertDialog.Builder(this)
                .setTitle(R.string.mic_permission_title)
                .setMessage(R.string.mic_permission_rationale)
                .setPositiveButton(R.string.btn_allow) { _, _ -> requestMicPermission() }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            MIC_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_PERMISSION_REQUEST_CODE) return
        if (grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            binding.permissionDeniedText.visibility = View.GONE
            startSession()
        } else {
            // Denied: explain in plain words on screen and stay on Home.
            binding.permissionDeniedText.visibility = View.VISIBLE
            Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun startSession() {
        val intent = Intent(this, SessionActivity::class.java)
        if (memorizationMode) {
            intent.putExtra(SessionActivity.EXTRA_MODE, SessionActivity.MODE_MEMORIZATION)
            intent.putExtra(
                SessionActivity.EXTRA_SURAH,
                binding.surahSpinner.selectedItemPosition + 1
            )
            intent.putExtra(SessionActivity.EXTRA_FROM_AYAH, binding.fromAyahPicker.value)
            intent.putExtra(SessionActivity.EXTRA_TO_AYAH, binding.toAyahPicker.value)
        } else {
            intent.putExtra(SessionActivity.EXTRA_MODE, SessionActivity.MODE_FREE)
        }
        startActivity(intent)
    }
}
