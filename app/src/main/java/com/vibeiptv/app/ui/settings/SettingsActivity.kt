package com.vibeiptv.app.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vibeiptv.app.data.repo.ContentRepository
import com.vibeiptv.app.data.repo.EpgRepository
import com.vibeiptv.app.data.repo.PortalStore
import com.vibeiptv.app.databinding.ActivitySettingsBinding
import com.vibeiptv.app.ui.portal.PortalManagerActivity
import com.vibeiptv.app.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var portalStore: PortalStore
    private lateinit var repo: ContentRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        portalStore = PortalStore(this)
        repo = ContentRepository(this)

        binding.btnManagePortals.setOnClickListener {
            startActivity(Intent(this, PortalManagerActivity::class.java))
        }
        binding.btnDecoder.setOnClickListener { showDecoderDialog() }
        binding.btnBuffer.setOnClickListener { showBufferDialog() }
        binding.btnEpgRefresh.setOnClickListener { refreshEpg() }
        binding.btnEpgClear.setOnClickListener { clearEpgCache() }
        binding.btnSetPin.setOnClickListener { showSetPin() }
        binding.btnRemovePin.setOnClickListener { confirmRemovePin() }
        binding.btnLockedCats.setOnClickListener { showLockedCategories() }
        binding.btnTmdbKey.setOnClickListener { showApiKeyDialog("TMDB") }
        binding.btnOmdbKey.setOnClickListener { showApiKeyDialog("OMDb") }

        refreshPortalInfo()
        refreshDecoderButton()
        refreshBufferButton()
        refreshEpgUpdated()
        refreshApiKeyButtons()
    }

    private fun refreshApiKeyButtons() {
        val tmdbSet = !portalStore.getTmdbApiKey().isNullOrBlank()
        val omdbSet = !portalStore.getOmdbApiKey().isNullOrBlank()
        binding.btnTmdbKey.text = if (tmdbSet) "TMDB API key: Set ✓ (tap to change/remove)"
        else "TMDB API key: Not set"
        binding.btnOmdbKey.text = if (omdbSet) "OMDb API key: Set ✓ (tap to change/remove)"
        else "OMDb API key: Not set"
    }

    private fun showApiKeyDialog(which: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
            hint = "Paste $which API key"
        }
        AlertDialog.Builder(this)
            .setTitle("$which API key")
            .setMessage("Used to look up ratings on detail screens. Stored encrypted on this device only.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim()
                if (which == "TMDB") portalStore.setTmdbApiKey(v.ifBlank { null })
                else portalStore.setOmdbApiKey(v.ifBlank { null })
                refreshApiKeyButtons()
                Toast.makeText(this, "$which API key updated", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Remove") { _, _ ->
                if (which == "TMDB") portalStore.setTmdbApiKey(null)
                else portalStore.setOmdbApiKey(null)
                refreshApiKeyButtons()
                Toast.makeText(this, "$which API key removed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshPortalInfo()
    }

    private fun refreshPortalInfo() {
        val p = try { portalStore.getActivePortal() } catch (e: Exception) { null }
        binding.txtPortalInfo.text = if (p != null) "${p.name} (${p.type})\n${p.serverUrl}" else "No portal selected"
    }

    private fun refreshDecoderButton() {
        val label = if (portalStore.decoderMode == "software") "Software" else "Auto"
        binding.btnDecoder.text = "Decoder mode: $label"
    }

    private fun refreshBufferButton() {
        val label = when (portalStore.bufferMs) {
            15000 -> "15s"
            60000 -> "60s"
            else -> "30s"
        }
        binding.btnBuffer.text = "Buffer size: $label"
    }

    private fun showDecoderDialog() {
        val options = arrayOf("Auto", "Software")
        val current = if (portalStore.decoderMode == "software") 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Decoder mode")
            .setSingleChoiceItems(options, current) { d, which ->
                portalStore.decoderMode = if (which == 1) "software" else "auto"
                refreshDecoderButton()
                d.dismiss()
            }
            .show()
    }

    private fun showBufferDialog() {
        val options = arrayOf("15 seconds", "30 seconds", "60 seconds")
        val values = intArrayOf(15000, 30000, 60000)
        val current = values.indexOf(portalStore.bufferMs).let { if (it < 0) 1 else it }
        AlertDialog.Builder(this)
            .setTitle("Buffer size")
            .setSingleChoiceItems(options, current) { d, which ->
                portalStore.bufferMs = values[which]
                refreshBufferButton()
                d.dismiss()
            }
            .show()
    }

    private fun refreshEpg() {
        binding.btnEpgRefresh.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                EpgRepository(this@SettingsActivity).refreshIfNeeded(force = true)
                toast("EPG refreshed")
            } catch (e: Exception) {
                toast("EPG refresh failed: ${e.message}")
            }
            withContext(Dispatchers.Main) {
                binding.btnEpgRefresh.isEnabled = true
                refreshEpgUpdated()
            }
        }
    }

    private fun clearEpgCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                EpgRepository(this@SettingsActivity).clearCache()
                toast("EPG cache cleared")
            } catch (e: Exception) {
                toast("Failed to clear EPG cache")
            }
            withContext(Dispatchers.Main) { refreshEpgUpdated() }
        }
    }

    private fun refreshEpgUpdated() {
        lifecycleScope.launch(Dispatchers.IO) {
            val lu = try { EpgRepository(this@SettingsActivity).lastUpdated() } catch (e: Exception) { 0L }
            withContext(Dispatchers.Main) {
                binding.txtEpgUpdated.text =
                    "Last updated: " + if (lu > 0) Format.dateHm(lu) else "Never"
            }
        }
    }

    private fun pinInput(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(4))
        setPadding(48, 32, 48, 32)
    }

    private fun showSetPin() {
        val first = pinInput()
        AlertDialog.Builder(this)
            .setTitle("Enter new 4-digit PIN")
            .setView(first)
            .setPositiveButton("Next") { _, _ ->
                val pin1 = first.text.toString()
                if (pin1.length != 4) {
                    Toast.makeText(this, "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val second = pinInput()
                AlertDialog.Builder(this)
                    .setTitle("Confirm PIN")
                    .setView(second)
                    .setPositiveButton("Save") { _, _ ->
                        if (second.text.toString() == pin1) {
                            portalStore.setPin(pin1)
                            Toast.makeText(this, "PIN set", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "PINs do not match", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemovePin() {
        if (portalStore.getPin().isNullOrBlank()) {
            Toast.makeText(this, "No PIN set", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove PIN")
            .setMessage("Remove the parental control PIN?")
            .setPositiveButton("Remove") { _, _ ->
                portalStore.setPin(null)
                Toast.makeText(this, "PIN removed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLockedCategories() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cats = try { repo.liveCategories() } catch (e: Exception) { emptyList() }
            if (cats.isEmpty()) {
                toast("No live categories available")
                return@launch
            }
            val locked = portalStore.getLockedCategoryIds()
            val names = cats.map { it.name }.toTypedArray()
            val checked = cats.map { locked.contains(it.id) }.toBooleanArray()
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Locked categories (applies to Live TV)")
                    .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("Save") { _, _ ->
                        val ids = cats.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                        portalStore.setLockedCategoryIds(ids)
                        Toast.makeText(this@SettingsActivity, "Locked categories updated", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private suspend fun toast(msg: String) = withContext(Dispatchers.Main) {
        Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
    }
}
