package com.vibeiptv.app.ui.vod

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.vibeiptv.app.R
import com.vibeiptv.app.data.db.AppDatabase
import com.vibeiptv.app.data.db.FavoriteEntity
import com.vibeiptv.app.data.model.FavType
import com.vibeiptv.app.data.model.VodItem
import com.vibeiptv.app.data.repo.ContentRepository
import com.vibeiptv.app.data.repo.PortalStore
import com.vibeiptv.app.data.repo.RatingRepository
import com.vibeiptv.app.databinding.ActivityVodDetailBinding
import com.vibeiptv.app.util.Format
import com.vibeiptv.app.util.Json
import com.vibeiptv.app.util.PlayerContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vibeiptv.app.util.applySystemBarPadding

class VodDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVodDetailBinding
    private lateinit var repo: ContentRepository
    private lateinit var vod: VodItem
    private var resumeMs: Long = 0L
    private var isFav: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        findViewById<View>(android.R.id.content).applySystemBarPadding()
        repo = ContentRepository(this)

        val json = intent.getStringExtra(PlayerContract.EXTRA_VOD_JSON)
        if (json.isNullOrBlank()) {
            finish()
            return
        }
        vod = Json.gson.fromJson(json, VodItem::class.java)

        binding.txtTitle.text = vod.name
        val meta = listOfNotNull(
            vod.year?.takeIf { it.isNotBlank() },
            vod.genre?.takeIf { it.isNotBlank() },
            vod.duration?.takeIf { it.isNotBlank() },
            if (vod.rating > 0) "★ TMDB %.1f".format(vod.rating) else null
        ).joinToString(" • ")
        binding.txtMeta.text = meta
        binding.txtPlot.text = vod.plot ?: "No plot available."
        // External rating only when the provider has none and a key is configured.
        if (vod.rating <= 0) loadExternalRating()
        binding.txtDirector.text = vod.director ?: "—"
        binding.txtCast.text = vod.cast ?: "—"
        if (!vod.backdrop.isNullOrBlank()) {
            binding.imgBackdrop.load(vod.backdrop) {
                crossfade(true)
                placeholder(R.drawable.bg_image_placeholder)
                error(R.drawable.bg_image_placeholder)
            }
        }
        if (!vod.poster.isNullOrBlank()) {
            binding.imgPoster.load(vod.poster) {
                crossfade(true)
                placeholder(R.drawable.bg_image_placeholder)
                error(R.drawable.bg_image_placeholder)
            }
        }

        binding.btnPlay.requestFocus()
        binding.btnPlay.setOnClickListener { play() }
        binding.btnResume.setOnClickListener { play() }
        binding.btnFav.setOnClickListener { toggleFavorite() }

        loadResumeAndFavorite()
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
                RatingRepository(this@VodDetailActivity)
                    .ratingFor(vod.name, vod.year, isSeries = false)
            } catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                if (r != null) {
                    binding.txtExtRating.text = "${r.source} ${"%.1f".format(r.value)}"
                    binding.txtExtRating.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun loadResumeAndFavorite() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.get(this@VodDetailActivity)
            val resume = db.resumeDao().get(PlayerContract.vodContentId(vod))
            val favIds = db.favoriteDao().idsByType(FavType.MOVIE)
            withContext(Dispatchers.Main) {
                if (resume != null && !PlayerContract.isWatched(resume)) {
                    resumeMs = resume.positionMs
                    binding.btnResume.text = "▶ Resume from ${Format.durationHm(resume.positionMs / 1000, true)}"
                    binding.btnResume.visibility = View.VISIBLE
                }
                isFav = favIds.contains(vod.id)
                renderFav()
            }
        }
    }

    private fun renderFav() {
        binding.btnFav.text = if (isFav) "★ Favorite" else "☆ Favorite"
    }

    private fun toggleFavorite() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.get(this@VodDetailActivity).favoriteDao()
            if (isFav) dao.delete(vod.id)
            else dao.upsert(FavoriteEntity(vod.id, FavType.MOVIE, vod.name, vod.poster, System.currentTimeMillis()))
            isFav = !isFav
            withContext(Dispatchers.Main) { renderFav() }
        }
    }

    private fun play() {
        binding.btnPlay.isEnabled = false
        lifecycleScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { repo.vodDetail(vod) }
                PlayerContract.playVod(this@VodDetailActivity, detail, resumeMs)
            } catch (e: Exception) {
                Toast.makeText(this@VodDetailActivity, "Failed to load movie: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnPlay.isEnabled = true
            }
        }
    }
}
