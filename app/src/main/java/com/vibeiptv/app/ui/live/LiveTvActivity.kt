package com.vibeiptv.app.ui.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.vibeiptv.app.R
import com.vibeiptv.app.data.db.AppDatabase
import com.vibeiptv.app.data.db.FavoriteEntity
import com.vibeiptv.app.data.model.Category
import com.vibeiptv.app.data.model.Channel
import com.vibeiptv.app.data.model.EpgProgramme
import com.vibeiptv.app.data.model.FavType
import com.vibeiptv.app.data.repo.ContentRepository
import com.vibeiptv.app.data.repo.EpgRepository
import com.vibeiptv.app.data.repo.PortalStore
import com.vibeiptv.app.databinding.ActivityLiveTvBinding
import com.vibeiptv.app.databinding.ItemCategoryBinding
import com.vibeiptv.app.databinding.ItemChannelBinding
import com.vibeiptv.app.util.Format
import com.vibeiptv.app.util.ParentalGate
import com.vibeiptv.app.util.PlayerContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveTvActivity : AppCompatActivity() {

    private data class CatRow(val id: String, val label: String, val locked: Boolean)

    private lateinit var binding: ActivityLiveTvBinding
    private lateinit var repo: ContentRepository
    private lateinit var epg: EpgRepository
    private lateinit var db: AppDatabase
    private lateinit var store: PortalStore

    private lateinit var catAdapter: CatAdapter
    private lateinit var chAdapter: ChannelAdapter

    private var cats: List<CatRow> = emptyList()
    private var selectedCat = "all"
    private val unlockedCats = mutableSetOf<String>()
    private var baseChannels: List<Channel> = emptyList()   // numbered 1..N in displayed order
    private var favIds: Set<String> = emptySet()
    private val epgCache = mutableMapOf<String, Pair<EpgProgramme?, EpgProgramme?>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveTvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ContentRepository(this)
        epg = EpgRepository(this)
        db = AppDatabase.get(this)
        store = PortalStore(this)

        catAdapter = CatAdapter { row ->
            if (row.locked && row.id !in unlockedCats) {
                ParentalGate.check(this, row.id) {
                    unlockedCats.add(row.id)
                    selectCategory(row.id)
                }
            } else {
                selectCategory(row.id)
            }
        }
        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        binding.rvCategories.adapter = catAdapter

        chAdapter = ChannelAdapter(
            onClick = { pos ->
                val list = chAdapter.items
                if (pos in list.indices) PlayerContract.playLive(this, list, pos)
            },
            onLongPress = { pos -> toggleFavorite(pos) }
        )
        binding.rvChannels.layoutManager = LinearLayoutManager(this)
        binding.rvChannels.adapter = chAdapter

        binding.etSearch.addTextChangedListener { applySearch(it?.toString().orEmpty()) }

        loadCategories()
    }

    override fun onResume() {
        super.onResume()
        // Favorites may have changed; refresh star markers.
        lifecycleScope.launch(Dispatchers.IO) {
            favIds = db.favoriteDao().idsByType(FavType.LIVE)
            withContext(Dispatchers.Main) { chAdapter.notifyDataSetChanged() }
        }
    }

    private fun loadCategories() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val locked = store.getLockedCategoryIds()
                val live: List<Category> = repo.liveCategories()
                val rows = mutableListOf(
                    CatRow("all", "All Channels", false),
                    CatRow("fav", "Favorites ⭐", false)
                )
                live.forEach { c ->
                    val isLocked = c.id in locked
                    rows.add(CatRow(c.id, (if (isLocked) "🔒 " else "") + "${c.name} (${c.count})", isLocked))
                }
                favIds = db.favoriteDao().idsByType(FavType.LIVE)
                withContext(Dispatchers.Main) {
                    cats = rows
                    catAdapter.submit(rows, selectedCat)
                    selectCategory(selectedCat)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LiveTvActivity, "Failed to load: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun selectCategory(catId: String) {
        selectedCat = catId
        catAdapter.submit(cats, selectedCat)
        binding.etSearch.setText("")
        chAdapter.submit(emptyList()) // clear stale rows while the new category loads
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val raw: List<Channel> = when (catId) {
                    "all" -> repo.liveChannels(null)
                    "fav" -> repo.liveChannels(null).filter { it.id in favIds }
                    else -> repo.liveChannels(catId)
                }
                // Number 1..N in displayed order — this order drives CH+/- in the player.
                val numbered = raw.mapIndexed { i, ch -> ch.copy(number = i + 1) }
                withContext(Dispatchers.Main) {
                    baseChannels = numbered
                    applySearch("")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LiveTvActivity, "Failed to load channels", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applySearch(q: String) {
        val list = if (q.isBlank()) baseChannels
        else baseChannels.filter { it.name.contains(q, ignoreCase = true) }
        chAdapter.submit(list)
    }

    private fun toggleFavorite(pos: Int) {
        val list = chAdapter.items
        if (pos !in list.indices) return
        val ch = list[pos]
        lifecycleScope.launch(Dispatchers.IO) {
            val isFav = ch.id in db.favoriteDao().idsByType(FavType.LIVE)
            if (isFav) {
                db.favoriteDao().delete(ch.id)
            } else {
                db.favoriteDao().upsert(
                    FavoriteEntity(
                        refId = ch.id, type = FavType.LIVE,
                        name = ch.name, logo = ch.logo,
                        addedAt = System.currentTimeMillis()
                    )
                )
            }
            favIds = db.favoriteDao().idsByType(FavType.LIVE)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@LiveTvActivity,
                    if (isFav) "Removed from favorites" else "Added to favorites",
                    Toast.LENGTH_SHORT
                ).show()
                if (selectedCat == "fav") selectCategory("fav") else chAdapter.notifyItemChanged(pos)
            }
        }
    }

    private fun epgLine(ch: Channel, onReady: (String) -> Unit) {
        val cached = epgCache[ch.id]
        if (cached != null) {
            onReady(formatNowNext(cached))
            return
        }
        onReady("…")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nn = epg.nowNextForChannel(ch)
                epgCache[ch.id] = nn
                withContext(Dispatchers.Main) {
                    // Only update if this channel is still on screen.
                    val pos = chAdapter.items.indexOfFirst { it.id == ch.id }
                    if (pos >= 0) chAdapter.notifyItemChanged(pos)
                }
            } catch (_: Exception) { /* leave placeholder */ }
        }
    }

    private fun formatNowNext(nn: Pair<EpgProgramme?, EpgProgramme?>): String {
        val (now, next) = nn
        if (now == null && next == null) return "No EPG info"
        val sb = StringBuilder()
        if (now != null) sb.append("Now: ${Format.timeHm(now.startUtc)} ${now.title}")
        if (next != null) {
            if (sb.isNotEmpty()) sb.append("  •  ")
            sb.append("Next: ${Format.timeHm(next.startUtc)} ${next.title}")
        }
        return sb.toString()
    }

    private inner class CatAdapter(private val onPick: (CatRow) -> Unit) :
        RecyclerView.Adapter<CatAdapter.VH>() {
        private var rows: List<CatRow> = emptyList()
        private var selected = "all"

        fun submit(r: List<CatRow>, sel: String) {
            rows = r; selected = sel; notifyDataSetChanged()
        }

        inner class VH(val b: ItemCategoryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.b.tvCategory.text = row.label
            holder.b.tvCategory.alpha = if (row.id == selected) 1f else 0.75f
            holder.b.tvCategory.setOnClickListener { onPick(row) }
        }
    }

    private inner class ChannelAdapter(
        private val onClick: (Int) -> Unit,
        private val onLongPress: (Int) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.VH>() {

        var items: List<Channel> = emptyList()
            private set

        fun submit(list: List<Channel>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            holder.b.ivLogo.load(ch.logo) {
                crossfade(true)
                error(R.drawable.item_focusable)
            }
            holder.b.tvNumber.text = ch.number.toString()
            holder.b.tvName.text = (if (ch.id in favIds) "⭐ " else "") + ch.name
            holder.b.tvEpg.text = "…"
            epgLine(ch) { line ->
                // Guard against recycled holders.
                if (holder.bindingAdapterPosition in items.indices &&
                    items[holder.bindingAdapterPosition].id == ch.id
                ) {
                    holder.b.tvEpg.text = line
                }
            }
            holder.b.root.setOnClickListener { onClick(holder.bindingAdapterPosition) }
            holder.b.root.setOnLongClickListener {
                onLongPress(holder.bindingAdapterPosition)
                true
            }
        }
    }
}
