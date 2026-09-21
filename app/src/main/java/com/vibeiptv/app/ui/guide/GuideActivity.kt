package com.vibeiptv.app.ui.guide

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.vibeiptv.app.R
import com.vibeiptv.app.data.model.Channel
import com.vibeiptv.app.data.model.EpgProgramme
import com.vibeiptv.app.data.repo.ContentRepository
import com.vibeiptv.app.data.repo.EpgRepository
import com.vibeiptv.app.databinding.ActivityGuideBinding
import com.vibeiptv.app.databinding.ItemGuideRowBinding
import com.vibeiptv.app.util.Format
import com.vibeiptv.app.util.PlayerContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vibeiptv.app.util.applySystemBarPadding

class GuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuideBinding
    private lateinit var repo: ContentRepository
    private lateinit var epgRepo: EpgRepository

    private var windowStart = 0L
    private var windowEnd = 0L
    private var now = 0L

    private data class Row(val channel: Channel, val programmes: List<EpgProgramme>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        findViewById<View>(android.R.id.content).applySystemBarPadding()
        repo = ContentRepository(this)
        epgRepo = EpgRepository(this)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.btnNow.setOnClickListener { loadGuide() }
        loadGuide()
    }

    private fun loadGuide() {
        now = System.currentTimeMillis()
        windowStart = now - 30 * 60 * 1000L
        windowEnd = now + 90 * 60 * 1000L
        binding.txtDate.text = Format.dateHm(now)
        buildTimelineHeader()
        lifecycleScope.launch {
            val (chs, epgMap, portalName) = withContext(Dispatchers.IO) {
                val c = try { repo.liveChannels(null).take(80) } catch (e: Exception) { emptyList() }
                val w = try { epgRepo.windowForChannels(c, windowStart, windowEnd) } catch (e: Exception) { emptyMap<String, List<EpgProgramme>>() }
                val p = try { repo.portalStore.getActivePortal()?.name ?: "" } catch (e: Exception) { "" }
                Triple(c, w, p)
            }
            binding.txtPortal.text = portalName
            binding.recycler.adapter = GuideAdapter(chs.map { Row(it, epgMap[it.id].orEmpty()) })
        }
    }

    private fun buildTimelineHeader() {
        val header = binding.timelineHeader
        header.removeAllViews()
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(dp(180), dp(28))
        header.addView(spacer)
        var t = windowStart
        while (t < windowEnd) {
            val tv = TextView(this)
            tv.layoutParams = LinearLayout.LayoutParams(dp(180), dp(28))
            tv.text = Format.timeHm(t)
            tv.setTextColor(getColor(R.color.text_secondary))
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            header.addView(tv)
            t += 30 * 60 * 1000L
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private inner class GuideAdapter(private val rows: List<Row>) :
        RecyclerView.Adapter<GuideAdapter.VH>() {

        inner class VH(val b: ItemGuideRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemGuideRowBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val row = rows[pos]
            val ch = row.channel
            h.b.txtName.text = ch.name
            h.b.txtNumber.text = ch.number.toString()
            if (!ch.logo.isNullOrBlank()) h.b.imgLogo.load(ch.logo) { crossfade(true) }

            val box = h.b.blocksContainer
            box.removeAllViews()
            // Dimmed filler for empty time slots (leading offset, gaps, trailing) so
            // the timeline never shows blank holes.
            var cursor = windowStart
            for (prog in row.programmes.sortedBy { it.startUtc }) {
                val s = maxOf(prog.startUtc, windowStart)
                val e = minOf(prog.stopUtc, windowEnd)
                if (s >= e) continue
                val gapMin = ((s - cursor) / 60000L).toInt()
                if (gapMin >= 5) box.addView(gapView(gapMin * 6))
                box.addView(blockView(ch, prog))
                cursor = maxOf(cursor, e)
            }
            val tailMin = ((windowEnd - cursor) / 60000L).toInt()
            if (tailMin >= 5) box.addView(gapView(tailMin * 6))
            // NOW-LINE: 2dp accent line at the current time position inside the
            // scrollable timeline (6dp per minute, same scale as the blocks).
            val nowLine = h.b.nowLine
            if (now in windowStart..windowEnd) {
                val lp = nowLine.layoutParams as FrameLayout.LayoutParams
                lp.leftMargin = dp((((now - windowStart) / 60000L) * 6).toInt())
                nowLine.layoutParams = lp
                nowLine.visibility = View.VISIBLE
            } else {
                nowLine.visibility = View.GONE
            }
        }
    }

    /** Dimmed, non-focusable filler block for empty EPG time slots. */
    private fun gapView(widthDp: Int): View {
        val v = View(this)
        val lp = LinearLayout.LayoutParams(dp(maxOf(24, widthDp)), dp(64))
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        v.layoutParams = lp
        v.setBackgroundColor(getColor(R.color.surface))
        v.isFocusable = false
        return v
    }

    private fun blockView(ch: Channel, prog: EpgProgramme): TextView {
        val start = maxOf(prog.startUtc, windowStart)
        val stop = minOf(prog.stopUtc, windowEnd)
        val durMin = maxOf(1, ((stop - start) / 60000L).toInt())
        val tv = TextView(this)
        val lp = LinearLayout.LayoutParams(dp(maxOf(48, durMin * 6)), dp(64))
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        tv.layoutParams = lp
        tv.text = "${Format.timeHm(prog.startUtc)}\n${prog.title}"
        tv.maxLines = 2
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setTextColor(getColor(R.color.text_primary))
        tv.setPadding(dp(6), dp(4), dp(6), dp(4))
        tv.isFocusable = true
        tv.isClickable = true
        val isNow = prog.startUtc <= now && now < prog.stopUtc
        tv.setBackgroundResource(R.drawable.card_focusable)
        // Current programme: accent tint highlight (focus ring still shows on top).
        tv.backgroundTintList = if (isNow)
            ColorStateList.valueOf((getColor(R.color.accent) and 0x00FFFFFF) or 0x40000000)
        else
            null
        tv.setOnClickListener { onProgrammeClick(ch, prog) }
        return tv
    }

    private fun onProgrammeClick(ch: Channel, prog: EpgProgramme) {
        val t = System.currentTimeMillis()
        when {
            prog.startUtc <= t && t < prog.stopUtc ->
                PlayerContract.playLive(this, listOf(ch), 0)
            prog.stopUtc <= t -> {
                val durMin = ((prog.stopUtc - prog.startUtc) / 60000L).toInt().coerceAtLeast(5)
                val url = try { repo.timeshiftUrl(ch, prog, durMin) } catch (e: Exception) { null }
                if (url != null) PlayerContract.playUrl(this, url, "${ch.name} — ${prog.title}")
                else Toast.makeText(this, "No catch-up available", Toast.LENGTH_SHORT).show()
            }
            else -> AlertDialog.Builder(this)
                .setTitle(prog.title)
                .setMessage("${Format.dateHm(prog.startUtc)} – ${Format.timeHm(prog.stopUtc)}\n\n${prog.desc ?: "No description."}")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
