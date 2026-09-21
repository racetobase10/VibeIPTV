package com.vibeiptv.app.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vibeiptv.app.data.repo.EpgRepository
import com.vibeiptv.app.data.repo.PortalStore
import com.vibeiptv.app.databinding.ActivityHomeBinding
import com.vibeiptv.app.databinding.ItemTileBinding
import com.vibeiptv.app.ui.favorites.FavoritesActivity
import com.vibeiptv.app.ui.guide.GuideActivity
import com.vibeiptv.app.ui.live.LiveTvActivity
import com.vibeiptv.app.ui.portal.PortalSetupActivity
import com.vibeiptv.app.ui.recordings.RecordingsActivity
import com.vibeiptv.app.ui.series.SeriesActivity
import com.vibeiptv.app.ui.settings.SettingsActivity
import com.vibeiptv.app.ui.vod.MoviesActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.vibeiptv.app.util.applySystemBarPadding

class HomeActivity : AppCompatActivity() {

    private data class Tile(val icon: String, val label: String, val open: () -> Unit)

    private lateinit var binding: ActivityHomeBinding
    private lateinit var store: PortalStore
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PortalStore(this)

        // Portal may have been deleted elsewhere — bounce back to setup.
        if (store.getActivePortal() == null) {
            startActivity(Intent(this, PortalSetupActivity::class.java))
            finish()
            return
        }

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        findViewById<View>(android.R.id.content).applySystemBarPadding()

        val tiles = listOf(
            Tile("📺", "Live TV") { startActivity(Intent(this, LiveTvActivity::class.java)) },
            Tile("🎬", "Movies") { startActivity(Intent(this, MoviesActivity::class.java)) },
            Tile("📼", "Series") { startActivity(Intent(this, SeriesActivity::class.java)) },
            Tile("📅", "TV Guide") { startActivity(Intent(this, GuideActivity::class.java)) },
            Tile("⭐", "Favorites") { startActivity(Intent(this, FavoritesActivity::class.java)) },
            Tile("⏺", "Recordings") { startActivity(Intent(this, RecordingsActivity::class.java)) },
            Tile("⚙", "Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        )
        val span = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 3
        binding.rvTiles.layoutManager = GridLayoutManager(this, span)
        binding.rvTiles.adapter = TileAdapter(tiles)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Kick off the daily EPG refresh (no-op if refreshed within 24h).
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                EpgRepository(this@HomeActivity).refreshIfNeeded()
            } catch (_: Exception) {
                // EPG is best-effort; never break Home over it.
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) {
            binding.tvPortal.text = store.getActivePortal()?.name ?: ""
            updateClock()
            clockHandler.removeCallbacks(clockTick)
            clockHandler.postDelayed(clockTick, 30_000)
        }
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTick)
    }

    private fun updateClock() {
        binding.tvClock.text =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private class TileAdapter(private val tiles: List<Tile>) :
        RecyclerView.Adapter<TileAdapter.VH>() {

        inner class VH(val b: ItemTileBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemTileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = tiles.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = tiles[position]
            holder.b.tvIcon.text = t.icon
            holder.b.tvLabel.text = t.label
            holder.b.root.setOnClickListener { t.open() }
        }
    }
}
