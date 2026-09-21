package com.vibeiptv.app.ui.portal

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vibeiptv.app.data.model.PortalConfig
import com.vibeiptv.app.data.model.PortalType
import com.vibeiptv.app.data.repo.PortalStore
import com.vibeiptv.app.databinding.ActivityPortalManagerBinding
import com.vibeiptv.app.databinding.ItemPortalBinding
import com.vibeiptv.app.ui.home.HomeActivity
import com.vibeiptv.app.util.applySystemBarPadding

class PortalManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPortalManagerBinding
    private lateinit var store: PortalStore
    private lateinit var adapter: PortalAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPortalManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        findViewById<View>(android.R.id.content).applySystemBarPadding()
        store = PortalStore(this)

        adapter = PortalAdapter(
            onSwitch = { p ->
                store.setActivePortal(p.id)
                startActivity(Intent(this, HomeActivity::class.java))
                finish()
            },
            onEdit = { p ->
                startActivity(
                    Intent(this, PortalSetupActivity::class.java)
                        .putExtra(PortalSetupActivity.EXTRA_EDIT_ID, p.id)
                )
            },
            onDelete = { p -> confirmDelete(p) }
        )
        binding.rvPortals.layoutManager = LinearLayoutManager(this)
        binding.rvPortals.adapter = adapter

        binding.btnAdd.setOnClickListener {
            startActivity(Intent(this, PortalSetupActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.submit(store.getPortals(), store.getActivePortal()?.id)
    }

    private fun confirmDelete(p: PortalConfig) {
        AlertDialog.Builder(this)
            .setTitle("Delete portal?")
            .setMessage("Remove \"${p.name}\" from this device?")
            .setPositiveButton("Delete") { _, _ ->
                val wasActive = store.getActivePortal()?.id == p.id
                store.deletePortal(p.id)
                val remaining = store.getPortals()
                if (wasActive) {
                    if (remaining.isEmpty()) {
                        startActivity(Intent(this, PortalSetupActivity::class.java))
                        finish()
                        return@setPositiveButton
                    }
                    // Active portal is gone — fall back to the first remaining one.
                    store.setActivePortal(remaining.first().id)
                }
                adapter.submit(remaining, store.getActivePortal()?.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class PortalAdapter(
        private val onSwitch: (PortalConfig) -> Unit,
        private val onEdit: (PortalConfig) -> Unit,
        private val onDelete: (PortalConfig) -> Unit
    ) : RecyclerView.Adapter<PortalAdapter.VH>() {

        private var items: List<PortalConfig> = emptyList()
        private var activeId: String? = null

        fun submit(list: List<PortalConfig>, active: String?) {
            items = list
            activeId = active
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemPortalBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemPortalBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            val isActive = p.id == activeId
            holder.b.tvName.text = if (isActive) "● ${p.name}" else p.name
            holder.b.tvType.text = when (p.type) {
                PortalType.XTREAM -> "Xtream Codes"
                PortalType.M3U -> "M3U Playlist"
            }
            holder.b.tvServer.text = p.serverUrl
            holder.b.btnSwitch.isEnabled = !isActive
            holder.b.btnSwitch.alpha = if (isActive) 0.4f else 1f
            holder.b.btnSwitch.setOnClickListener { onSwitch(p) }
            holder.b.btnEdit.setOnClickListener { onEdit(p) }
            holder.b.btnDelete.setOnClickListener { onDelete(p) }
        }
    }
}
