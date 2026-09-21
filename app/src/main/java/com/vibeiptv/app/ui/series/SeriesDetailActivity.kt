package com.vibeiptv.app.ui.series

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.vibeiptv.app.R
import com.vibeiptv.app.data.db.AppDatabase
import com.vibeiptv.app.data.db.FavoriteEntity
import com.vibeiptv.app.data.model.Episode
import com.vibeiptv.app.data.model.FavType
import com.vibeiptv.app.data.model.SeriesDetail
import com.vibeiptv.app.data.model.SeriesItem
import com.vibeiptv.app.data.repo.ContentRepository
import com.vibeiptv.app.data.repo.PortalStore
import com.vibeiptv.app.data.repo.RatingRepository
import com.vibeiptv.app.databinding.ActivitySeriesDetailBinding
import com.vibeiptv.app.databinding.ItemCategoryRowBinding
import com.vibeiptv.app.databinding.ItemEpisodeBinding
import com.vibeiptv.app.util.Format
import com.vibeiptv.app.util.Json
import com.vibeiptv.app.util.PlayerContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeriesDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERIES_JSON = "series_json"
    }

    private lateinit var binding: ActivitySeriesDetailBinding
    private lateinit var repo: ContentRepository

    private lateinit var series: SeriesItem
    private var detail: SeriesDetail? = null
    private var seasons: List<Int> = emptyList()
    private var selectedSeason: Int = 0
    private var isFav: Boolean = false
    private val watchedCache = mutableMapOf<String, Boolean>()

    private lateinit var seasonAdapter: SeasonAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ContentRepository(this)

        val json = intent.getStringExtra(EXTRA_SERIES_JSON)
        if (json.isNullOrBlank()) {
            finish()
            return
        }
        series = Json.gson.fromJson(json, SeriesItem::class.java)

        binding.txtTitle.text = series.name
        val meta = listOfNotNull(
            series.releaseDate?.takeIf { it.isNotBlank() },
            series.genre?.takeIf { it.isNotBlank() },
            if (series.rating > 0) "★ %.1f".format(series.rating) else null
        ).joinToString(" • ")
        binding.txtMeta.text = meta
        binding.txtPlot.text = series.plot ?: "No plot available."
        // External rating only when the provider has none and a key is configured.
        if (series.rating <= 0) loadExternalRating()
        binding.txtCast.text = series.cast ?: ""
        if (!series.cover.isNullOrBlank()) {
            binding.imgCover.load(series.cover) {
                crossfade(true)
                placeholder(R.drawable.bg_image_placeholder)
                error(R.drawable.bg_image_placeholder)
            }
        }

        seasonAdapter = SeasonAdapter { s -> selectSeason(s) }
        binding.rvSeasons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSeasons.adapter = seasonAdapter

        episodeAdapter = EpisodeAdapter { ep -> playEpisode(ep) }
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        binding.rvEpisodes.adapter = episodeAdapter

        binding.btnFav.setOnClickListener { toggleFavorite() }
        load()
    }

    /**
     * External rating (TMDB, then IMDb via OMDb) — only used when the provider
     * supplied no rating. Shown with an honest source label.
     */
    private fun loadExternalRating() {
        val store = PortalStore(this)
        if (store.getTmdbApiKey().isNullOrBlank() && store.getOmdbApiKey().isNullOrBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val r = try {
                RatingRepository(this@SeriesDetailActivity)
                    .ratingFor(series.name, series.releaseDate, isSeries = true)
            } catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                if (r != null) {
                    binding.txtExtRating.text = "${r.source} ${"%.1f".format(r.value)}"
                    binding.txtExtRating.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun load() {
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) {
                try { repo.seriesDetail(series) } catch (e: Exception) { null }
            }
            if (d == null) {
                Toast.makeText(this@SeriesDetailActivity, "Failed to load series", Toast.LENGTH_SHORT).show()
                return@launch
            }
            detail = d
            seasons = d.seasons.keys.sorted()
            if (seasons.isNotEmpty()) selectedSeason = seasons.first()
            seasonAdapter.submit(seasons)
            renderEpisodes()
            val favIds = withContext(Dispatchers.IO) {
                AppDatabase.get(this@SeriesDetailActivity).favoriteDao().idsByType(FavType.SERIES)
            }
            isFav = favIds.contains(series.id)
            renderFav()
        }
    }

    private fun selectSeason(s: Int) {
        selectedSeason = s
        seasonAdapter.notifyDataSetChanged()
        renderEpisodes()
    }

    private fun renderEpisodes() {
        val eps = detail?.seasons?.get(selectedSeason).orEmpty()
        episodeAdapter.submit(eps)
    }

    private fun renderFav() {
        binding.btnFav.text = if (isFav) "★ Favorite" else "☆ Favorite"
    }

    private fun toggleFavorite() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.get(this@SeriesDetailActivity).favoriteDao()
            if (isFav) dao.delete(series.id)
            else dao.upsert(FavoriteEntity(series.id, FavType.SERIES, series.name, series.cover, System.currentTimeMillis()))
            isFav = !isFav
            withContext(Dispatchers.Main) { renderFav() }
        }
    }

    private fun playEpisode(ep: Episode) {
        lifecycleScope.launch(Dispatchers.IO) {
            val r = AppDatabase.get(this@SeriesDetailActivity).resumeDao()
                .get(PlayerContract.episodeContentId(ep))
            val resumeMs = if (r != null && !PlayerContract.isWatched(r)) r.positionMs else 0L
            withContext(Dispatchers.Main) {
                PlayerContract.playEpisode(this@SeriesDetailActivity, series, ep, resumeMs)
            }
        }
    }

    private inner class SeasonAdapter(private val onClick: (Int) -> Unit) :
        RecyclerView.Adapter<SeasonAdapter.VH>() {

        private var items: List<Int> = emptyList()

        fun submit(list: List<Int>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemCategoryRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemCategoryRowBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.b.txtCategory.text = "S$s"
            h.b.txtCategory.setBackgroundResource(
                if (s == selectedSeason) R.drawable.btn_accent_focusable else R.drawable.item_focusable
            )
            h.b.root.setOnClickListener { onClick(s) }
        }
    }

    private inner class EpisodeAdapter(private val onClick: (Episode) -> Unit) :
        RecyclerView.Adapter<EpisodeAdapter.VH>() {

        private var items: List<Episode> = emptyList()

        fun submit(list: List<Episode>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemEpisodeBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemEpisodeBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val ep = items[pos]
            h.b.txtTitle.text = "E${ep.episodeNum} · ${ep.title}"
            h.b.txtPlot.text = ep.plot ?: ""
            h.b.txtMeta.text = listOfNotNull(
                ep.airDate?.takeIf { it.isNotBlank() },
                ep.durationSecs?.let { Format.durationHm(it, true) }
            ).joinToString(" • ")
            h.b.txtWatched.visibility = View.GONE

            val cid = PlayerContract.episodeContentId(ep)
            val cached = watchedCache[cid]
            if (cached != null) {
                h.b.txtWatched.visibility = if (cached) View.VISIBLE else View.GONE
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    val w = PlayerContract.isWatched(
                        AppDatabase.get(this@SeriesDetailActivity).resumeDao().get(cid)
                    )
                    watchedCache[cid] = w
                    withContext(Dispatchers.Main) {
                        if (h.bindingAdapterPosition == pos) {
                            h.b.txtWatched.visibility = if (w) View.VISIBLE else View.GONE
                        }
                    }
                }
            }
            h.b.root.setOnClickListener { onClick(ep) }
        }
    }
}
