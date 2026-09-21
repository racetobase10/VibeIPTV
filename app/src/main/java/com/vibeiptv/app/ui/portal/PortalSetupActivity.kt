package com.vibeiptv.app.ui.portal

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vibeiptv.app.data.api.Http
import com.vibeiptv.app.data.model.PortalConfig
import com.vibeiptv.app.data.model.PortalType
import com.vibeiptv.app.data.repo.ContentRepository
import com.vibeiptv.app.data.repo.PortalStore
import com.vibeiptv.app.databinding.ActivityPortalSetupBinding
import com.vibeiptv.app.ui.home.HomeActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Buffer
import java.util.UUID
import com.vibeiptv.app.util.applySystemBarPadding

class PortalSetupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EDIT_ID = "edit_id"
    }

    private lateinit var binding: ActivityPortalSetupBinding
    private lateinit var store: PortalStore
    private var type = PortalType.XTREAM
    private var editId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PortalStore(this)
        editId = intent.getStringExtra(EXTRA_EDIT_ID)

        // First-run gate: an active portal means we belong on Home (unless editing).
        if (editId == null && store.getActivePortal() != null) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        binding = ActivityPortalSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        findViewById<View>(android.R.id.content).applySystemBarPadding()

        if (editId != null) {
            binding.tvFormTitle.text = "Edit Portal"
            binding.btnCancel.visibility = View.VISIBLE
            prefill(store.getPortals().find { it.id == editId })
            binding.btnCancel.setOnClickListener {
                startActivity(Intent(this, PortalManagerActivity::class.java))
                finish()
            }
        }

        binding.btnTypeXtream.setOnClickListener { setType(PortalType.XTREAM) }
        binding.btnTypeM3u.setOnClickListener { setType(PortalType.M3U) }
        binding.btnSave.setOnClickListener { saveAndConnect() }
    }

    private fun prefill(p: PortalConfig?) {
        if (p == null) return
        setType(p.type)
        if (p.type == PortalType.XTREAM) {
            binding.etName.setText(p.name)
            binding.etServer.setText(p.serverUrl)
            binding.etUsername.setText(p.username)
            binding.etPassword.setText(p.password)
        } else {
            binding.etM3uName.setText(p.name)
            binding.etM3uUrl.setText(p.serverUrl)
            binding.etM3uEpg.setText(p.epgUrl)
        }
    }

    private fun setType(t: PortalType) {
        type = t
        val xtream = t == PortalType.XTREAM
        binding.layoutXtream.visibility = if (xtream) View.VISIBLE else View.GONE
        binding.layoutM3u.visibility = if (xtream) View.GONE else View.VISIBLE
        binding.btnTypeXtream.setBackgroundResource(
            if (xtream) com.vibeiptv.app.R.drawable.btn_accent_focusable
            else com.vibeiptv.app.R.drawable.item_focusable
        )
        binding.btnTypeM3u.setBackgroundResource(
            if (xtream) com.vibeiptv.app.R.drawable.item_focusable
            else com.vibeiptv.app.R.drawable.btn_accent_focusable
        )
    }

    private fun saveAndConnect() {
        val portal = if (type == PortalType.XTREAM) {
            val name = binding.etName.text.toString().trim()
            val server = binding.etServer.text.toString().trim()
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString()
            if (name.isEmpty() || server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                toast("Fill in all Xtream fields"); return
            }
            PortalConfig(
                id = editId ?: UUID.randomUUID().toString(),
                name = name, type = PortalType.XTREAM,
                serverUrl = server, username = user, password = pass
            )
        } else {
            val name = binding.etM3uName.text.toString().trim()
            val url = binding.etM3uUrl.text.toString().trim()
            if (name.isEmpty() || url.isEmpty()) {
                toast("Fill in playlist name and URL"); return
            }
            PortalConfig(
                id = editId ?: UUID.randomUUID().toString(),
                name = name, type = PortalType.M3U,
                serverUrl = url, epgUrl = binding.etM3uEpg.text.toString().trim()
            )
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnSave.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                when (portal.type) {
                    PortalType.XTREAM -> {
                        val ok = ContentRepository(this@PortalSetupActivity).login(portal)
                        if (!ok) throw IllegalStateException("Login failed — check details")
                    }
                    PortalType.M3U -> verifyM3u(portal.serverUrl)
                }
                withContext(Dispatchers.Main) {
                    store.savePortal(portal)
                    store.setActivePortal(portal.id)
                    startActivity(Intent(this@PortalSetupActivity, HomeActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnSave.isEnabled = true
                    toast(e.message ?: "Connection failed")
                }
            }
        }
    }

    /** Downloads the first 64 KB of the playlist URL and requires an #EXTM3U header. */
    private fun verifyM3u(url: String) {
        val req = Request.Builder().url(url).header("User-Agent", "VibeIPTV/1.0").build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} fetching playlist")
            val src = resp.body?.source() ?: throw IllegalStateException("Empty playlist response")
            val buf = Buffer()
            var remaining = 65536L
            while (remaining > 0 && !src.exhausted()) {
                val read = src.read(buf, minOf(8192L, remaining))
                if (read == -1L) break
                remaining -= read
            }
            val head = buf.readUtf8()
            if (!head.contains("#EXTM3U")) throw IllegalStateException("URL is not an M3U playlist")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
