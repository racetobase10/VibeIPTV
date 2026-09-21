package com.vibeiptv.app.ui.favorites

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.vibeiptv.app.R
import com.vibeiptv.app.data.db.AppDatabase
import com.vibeiptv.app.data.db.FavoriteDao
import com.vibeiptv.app.data.model.Channel
import com.vibeiptv.app.data.model.FavType
import com.vibeiptv.app.data.model.SeriesItem
import com.vibeiptv.app.data.model.VodItem
import com.vibeiptv.app.data.repo.ContentRepository
import com.vibeiptv.app.databinding.ActivityFavoritesBinding
import com.vibeiptv.app.databinding.ItemFavRowBinding
import com.vibeiptv.app.ui.live.LiveTvActivity
import com.vibeiptv.app.ui.series.SeriesDetailActivity
import com.vibeiptv.app.util.Json
import com.vibeiptv.app.util.PlayerContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vibeiptv.app.util.applySystemBarPadding

class FavoritesActivity : AppCompatActivity() {

    private sealed class FavTarget {
        abstract val refId: String
        abstract val name: String
        abstract val logo: String?
        abstract val hint: String
        data class Live(val ch: Channel) : FavTarget() {
            override val refId = ch.id
            override val name = ch.name
            override val logo = ch.logo
            override val hint = "Live TV"
        }
        data class Movie(val vod: VodItem) : FavTarget() {
            override val refId = vod.id
            override val name = vod.name
            override val logo = vod.poster
            override val hint = "Movie"
        }
        data class Series(val s: SeriesItem) : FavTarget() {
            override val refId = s.id
            override val name = s.name
            override val logo = s.cover
            override val hint = "Series"
        }
    }

    private lateinit var binding: ActivityFavoritesBinding
    private lateinit var repo: ContentRepository
    private lateinit var favDao: FavoriteDao

    private var tab: Int = 0 // 0 = live, 1 = movies, 2 = series
    private var liveCache: List<Channel>? = null
    private var vodCache: List<VodItem>? = null
    private var seriesCache: List<SeriesItem>? = null

    private lateinit var adapter: FavAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        findViewById<View>(android.R.id.content).applySystemBarPadding()
        repo = ContentRepository(this)
        favDao = AppDatabase.get(this).favoriteDao()

        adapter = FavAdapter(
            onClick = { playTarget(it) },
            onLongClick = { confirmDelete(it) }
        )
        binding.rvFavs.layoutManager = LinearLayoutManager(this)
        binding.rvFavs.adapter = adapter

        binding.tabLive.setOnClickListener { selectTab(0) }
        binding.tabMovies.setOnClickListener { selectTab(1) }
        binding.tabSeries.setOnClickListener { selectTab(2) }
        binding.btnBrowseLive.setOnClickListener {
            startActivity(Intent(this, LiveTvActivity::class.java))
        }
        selectTab(0)
    }

    private fun selectTab(t: Int) {
        tab = t
        val tabs: List<Button> = listOf(binding.tabLive, binding.tabMovies, binding.tabSeries)
        tabs.forEachIndexed { i, b ->
            b.setBackgroundResource(if (i == t) R.drawable.btn_accent_focusable else R.drawable.btn_ghost_focusable)
        }
        loadTab()
    }

    private fun loadTab() {
        lifecycleScope.launch {
            val items: List<FavTarget> = withContext(Dispatchers.IO) {
                when (tab) {
                    0 -> {
                        if (liveCache == null) liveCache = safe { repo.liveChannels(null) }
                        val byId = liveCache.orEmpty().associateBy { it.id }
                        favDao.idsByType(FavType.LIVE).mapNotNull { id -> byId[id]?.let { FavTarget.Live(it) } }
                    }
                    1 -> {
                        if (vodCache == null) vodCache = safe { repo.vodList(null) }
                        val byId = vodCache.orEmpty().associateBy { it.id }
                        favDao.idsByType(FavType.MOVIE).mapNotNull { id -> byId[id]?.let { FavTarget.Movie(it) } }
                    }
                    else -> {
                        if (seriesCache == null) seriesCache = safe { repo.seriesList(null) }
                        val byId = seriesCache.orEmpty().associateBy { it.id }
                        favDao.idsByType(FavType.SERIES).mapNotNull { id -> byId[id]?.let { FavTarget.Series(it) } }
                    }
                }
            }
            adapter.submit(items)
            binding.txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.rvFavs.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private suspend fun <T> safe(block: suspend () -> List<T>): List<T> =
        try { block() } catch (e: Exception) { emptyList() }

    private fun playTarget(t: FavTarget) {
        when (t) {
            is FavTarget.Live -> PlayerContract.playLive(this, listOf(t.ch), 0)
            is FavTarget.Movie -> lifecycleScope.launch {
                try {
                    val detail = withContext(Dispatchers.IO) { repo.vodDetail(t.vod) }
                    val r = withContext(Dispatchers.IO) {
                        AppDatabase.get(this@FavoritesActivity).resumeDao()
                            .get(PlayerContract.vodContentId(t.vod))
                    }
                    val resumeMs = if (r != null && !PlayerContract.isWatched(r)) r.positionMs else 0L
                    PlayerContract.playVod(this@FavoritesActivity, detail, resumeMs)
                } catch (e: Exception) {
                    Toast.makeText(this@FavoritesActivity, "Failed to load movie", Toast.LENGTH_SHORT).show()
                }
            }
            is FavTarget.Series -> startActivity(
                Intent(this, SeriesDetailActivity::class.java)
                    .putExtra(SeriesDetailActivity.EXTRA_SERIES_JSON, Json.gson.toJson(t.s))
            )
        }
    }

    private fun confirmDelete(t: FavTarget) {
        AlertDialog.Builder(this)
            .setTitle("Remove favorite")
            .setMessage("Remove \"${t.name}\" from favorites?")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    favDao.delete(t.refId)
                    withContext(Dispatchers.Main) { loadTab() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class FavAdapter(
        private val onClick: (FavTarget) -> Unit,
        private val onLongClick: (FavTarget) -> Unit
    ) : RecyclerView.Adapter<FavAdapter.VH>() {

        private var items: List<FavTarget> = emptyList()

        fun submit(list: List<FavTarget>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemFavRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemFavRowBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val t = items[pos]
            h.b.txtName.text = t.name
            h.b.txtHint.text = t.hint
            if (!t.logo.isNullOrBlank()) {
                h.b.imgLogo.load(t.logo) {
                    crossfade(true)
                    placeholder(R.drawable.bg_image_placeholder)
                    error(R.drawable.bg_image_placeholder)
                }
            } else {
                h.b.imgLogo.setImageResource(R.drawable.bg_image_placeholder)
            }
            h.b.root.setOnClickListener { onClick(t) }
            h.b.root.setOnLongClickListener { onLongClick(t); true }
        }
    }
}
