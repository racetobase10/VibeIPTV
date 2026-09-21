package com.vibeiptv.app.ui.recordings

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vibeiptv.app.databinding.ActivityRecordingsBinding
import com.vibeiptv.app.databinding.ItemRecordingRowBinding
import com.vibeiptv.app.util.Format
import com.vibeiptv.app.util.PlayerContract
import java.io.File
import com.vibeiptv.app.util.applySystemBarPadding

class RecordingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var adapter: RecordingAdapter

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored; recordings still list */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        findViewById<View>(android.R.id.content).applySystemBarPadding()

        adapter = RecordingAdapter(
            onClick = { f -> PlayerContract.playFile(this, Uri.fromFile(f), f.nameWithoutExtension) },
            onLongClick = { f -> confirmDelete(f) }
        )
        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        binding.rvRecordings.adapter = adapter

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun recordingsDir(): File =
        File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Recordings")

    private fun load() {
        val dir = recordingsDir()
        val files = if (dir.exists()) {
            dir.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        } else emptyList()
        adapter.submit(files)
        binding.txtEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.rvRecordings.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete recording")
            .setMessage("Delete \"${f.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                f.delete()
                load()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class RecordingAdapter(
        private val onClick: (File) -> Unit,
        private val onLongClick: (File) -> Unit
    ) : RecyclerView.Adapter<RecordingAdapter.VH>() {

        private var items: List<File> = emptyList()

        fun submit(list: List<File>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemRecordingRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemRecordingRowBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val f = items[pos]
            h.b.txtName.text = f.name
            h.b.txtMeta.text = "${Format.fileSize(f.length())} • ${Format.dateHm(f.lastModified())}"
            h.b.root.setOnClickListener { onClick(f) }
            h.b.root.setOnLongClickListener { onLongClick(f); true }
        }
    }
}
