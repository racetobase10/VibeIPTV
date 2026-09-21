package com.vibeiptv.app.ui.vod

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.vibeiptv.app.R
import com.vibeiptv.app.data.model.Category
import com.vibeiptv.app.data.model.PortalType
import com.vibeiptv.app.data.model.VodItem
import com.vibeiptv.app.data.repo.ContentRepository
import com.vibeiptv.app.data.repo.PortalStore
import com.vibeiptv.app.databinding.ActivityMoviesBinding
import com.vibeiptv.app.databinding.ItemCategoryRowBinding
import com.vibeiptv.app.databinding.ItemPosterBinding
import com.vibeiptv.app.util.Json
import com.vibeiptv.app.util.ParentalGate
import com.vibeiptv.app.util.PlayerContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vibeiptv.app.util.applySystemBarPadding

class MoviesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMoviesBinding
    private lateinit var repo: ContentRepository
    private lateinit var portalStore: PortalStore

    private var allVods: List<VodItem> = emptyList()
    private var categories: List<Category> = emptyList()
    private var selectedCat: String = "all"
    private var query: String = ""
    private var sortMode: Int = 0 // 0 = recently added, 1 = name, 2 = rating

    private lateinit var posterAdapter: PosterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoviesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        findViewById<View>(android.R.id.content).applySystemBarPadding()
        repo = ContentRepository(this)
        portalStore = PortalStore(this)

        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        posterAdapter = PosterAdapter { vod -> openDetail(vod) }
        binding.rvGrid.layoutManager = GridLayoutManager(this, 3)
        binding.rvGrid.adapter = posterAdapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.btnSort.setOnClickListener { showSortDialog() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val isM3u = withContext(Dispatchers.IO) {
                try { repo.portalStore.getActivePortal()?.type == PortalType.M3U } catch (e: Exception) { false }
            }
            if (isM3u) {
                showEmpty("Movies unavailable for M3U playlists")
                return@launch
            }
            val (cats, vods) = withContext(Dispatchers.IO) {
                val c = try { repo.vodCategories() } catch (e: Exception) { emptyList() }
                val v = try { repo.vodList(null) } catch (e: Exception) { emptyList() }
                Pair(c, v)
            }
            categories = cats
            allVods = vods
            renderCategories()
            applyFilter()
        }
    }

    private fun renderCategories() {
        val locked = portalStore.getLockedCategoryIds()
        val items = mutableListOf<Category>()
        items.add(Category("all", "All", allVods.size))
        items.addAll(categories)
        binding.rvCategories.adapter = CategoryAdapter(items, locked) { cat ->
            if (cat.id != "all" && locked.contains(cat.id)) {
                ParentalGate.check(this, cat.id) { selectCategory(cat.id) }
            } else selectCategory(cat.id)
        }
    }

    private fun selectCategory(id: String) {
        selectedCat = id
        binding.rvCategories.adapter?.notifyDataSetChanged()
        applyFilter()
    }

    private fun showSortDialog() {
        val labels = arrayOf("Recently added", "Name", "Rating")
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(labels, sortMode) { d, which ->
                sortMode = which
                applyFilter()
                d.dismiss()
            }
            .show()
    }

    private fun applyFilter() {
        var list = if (selectedCat == "all") allVods else allVods.filter { it.categoryId == selectedCat }
        if (query.isNotBlank()) list = list.filter { it.name.contains(query, ignoreCase = true) }
        list = when (sortMode) {
            1 -> list.sortedBy { it.name.lowercase() }
            2 -> list.sortedByDescending { it.rating }
            else -> list.sortedByDescending { it.addedEpoch }
        }
        posterAdapter.submit(list)
        if (list.isEmpty()) showEmpty("No movies found") else hideEmpty()
    }

    private fun showEmpty(msg: String) {
        binding.txtEmpty.text = msg
        binding.txtEmpty.visibility = View.VISIBLE
        binding.rvGrid.visibility = View.GONE
    }

    private fun hideEmpty() {
        binding.txtEmpty.visibility = View.GONE
        binding.rvGrid.visibility = View.VISIBLE
    }

    private fun openDetail(vod: VodItem) {
        startActivity(
            Intent(this, VodDetailActivity::class.java)
                .putExtra(PlayerContract.EXTRA_VOD_JSON, Json.gson.toJson(vod))
        )
    }

    private inner class CategoryAdapter(
        private val items: List<Category>,
        private val locked: Set<String>,
        private val onClick: (Category) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.VH>() {

        inner class VH(val b: ItemCategoryRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemCategoryRowBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val cat = items[pos]
            val isSel = cat.id == selectedCat
            val lockMark = if (cat.id != "all" && locked.contains(cat.id)) "🔒 " else ""
            h.b.txtCategory.text = "$lockMark${cat.name}"
            h.b.txtCategory.setBackgroundResource(
                if (isSel) R.drawable.btn_accent_focusable else R.drawable.item_focusable
            )
            h.b.root.setOnClickListener { onClick(cat) }
        }
    }

    private inner class PosterAdapter(private val onClick: (VodItem) -> Unit) :
        RecyclerView.Adapter<PosterAdapter.VH>() {

        private var items: List<VodItem> = emptyList()

        fun submit(list: List<VodItem>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemPosterBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemPosterBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val vod = items[pos]
            h.b.txtTitle.text = vod.name
            h.b.txtRating.text = if (vod.rating > 0) "★ %.1f".format(vod.rating) else ""
            if (!vod.poster.isNullOrBlank()) {
                h.b.imgPoster.load(vod.poster) {
                    crossfade(true)
                    placeholder(R.drawable.bg_image_placeholder)
                    error(R.drawable.bg_image_placeholder)
                }
            } else {
                h.b.imgPoster.setImageResource(R.drawable.bg_image_placeholder)
            }
            h.b.root.setOnClickListener { onClick(vod) }
        }
    }
}
