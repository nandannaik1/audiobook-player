package com.example.audiobookplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.Locale
import kotlin.math.abs

// Structured data container for playlist items
data class PlaylistItem(
    val uriString: String,
    val fileName: String,
    val title: String?,
    val trackNumber: Int?,
    val album: String?
)

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    private var mediaPlayer: MediaPlayer? = null

    // UI elements
    private lateinit var btnAddFiles: Button
    private lateinit var btnSort: Button
    private lateinit var btnPrevious: Button
    private lateinit var btnNext: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPlaybackPosition: TextView
    private lateinit var tvCurrentFileName: TextView
    private lateinit var spinnerInterval: Spinner
    private lateinit var spinnerSpeed: Spinner
    private lateinit var touchArea: FrameLayout
    private lateinit var rvPlaylist: RecyclerView

    private lateinit var playlistAdapter: PlaylistAdapter
    private val playlist = mutableListOf<PlaylistItem>()
    private var currentIndex: Int = -1

    // State persistence keys
    private val PREFS_NAME = "AudiobookPrefs"
    private val KEY_PLAYLIST = "saved_playlist"
    private val KEY_CURRENT_INDEX = "saved_current_index"
    private val KEY_POSITION = "saved_position"
    private val KEY_SPEED = "saved_speed"
    private val KEY_INTERVAL = "saved_interval"

    // Loaded configuration state
    private var savedPosition: Int = 0
    private var currentSpeed: Float = 1.0f
    private var skipIntervalSeconds: Int = 10

    // For updating seek progress UI
    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateProgressTask = object : Runnable {
        override fun run() {
            updateProgressUi()
            updateHandler.postDelayed(this, 1000)
        }
    }

    private val SWIPE_THRESHOLD = 100
    private val SWIPE_VELOCITY_THRESHOLD = 100

    // File Picker registration (Accepts single or multi-selection)
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val clipData = result.data?.clipData
            val dataUri = result.data?.data
            val uris = mutableListOf<Uri>()

            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            } else if (dataUri != null) {
                uris.add(dataUri)
            }

            if (uris.isNotEmpty()) {
                handleSelectedFiles(uris)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        initUi()
        loadPreferences()
        setupSpinners()
        setupGestureDetector()
        setupRecyclerViewAndDragDrop()

        // Restore media if index was valid
        if (currentIndex >= 0 && currentIndex < playlist.size) {
            val item = playlist[currentIndex]
            tvCurrentFileName.text = item.title ?: item.fileName
            initializeMediaPlayer(Uri.parse(item.uriString), startImmediately = false)
        }
    }

    private fun initUi() {
        btnAddFiles = findViewById(R.id.btnAddFiles)
        btnSort = findViewById(R.id.btnSort)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnNext = findViewById(R.id.btnNext)
        tvStatus = findViewById(R.id.tvStatus)
        tvPlaybackPosition = findViewById(R.id.tvPlaybackPosition)
        tvCurrentFileName = findViewById(R.id.tvCurrentFileName)
        spinnerInterval = findViewById(R.id.spinnerInterval)
        spinnerSpeed = findViewById(R.id.spinnerSpeed)
        touchArea = findViewById(R.id.touchArea)
        rvPlaylist = findViewById(R.id.rvPlaylist)

        btnAddFiles.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            filePickerLauncher.launch(intent)
        }

        btnSort.setOnClickListener {
            sortPlaylistByMetadata()
        }

        btnPrevious.setOnClickListener {
            playPrevious()
        }

        btnNext.setOnClickListener {
            playNext()
        }
    }

    private fun loadPreferences() {
        savedPosition = sharedPreferences.getInt(KEY_POSITION, 0)
        currentSpeed = sharedPreferences.getFloat(KEY_SPEED, 1.0f)
        skipIntervalSeconds = sharedPreferences.getInt(KEY_INTERVAL, 10)
        loadPlaylistState()
    }

    private fun saveState() {
        val editor = sharedPreferences.edit()
        mediaPlayer?.let {
            editor.putInt(KEY_POSITION, it.currentPosition)
        } ?: run {
            editor.putInt(KEY_POSITION, savedPosition)
        }
        editor.putFloat(KEY_SPEED, currentSpeed)
        editor.putInt(KEY_INTERVAL, skipIntervalSeconds)
        editor.putInt(KEY_CURRENT_INDEX, currentIndex)
        savePlaylistState()
        editor.apply()
    }

    private fun handleSelectedFiles(uris: List<Uri>) {
        val newItems = mutableListOf<PlaylistItem>()

        for (uri in uris) {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: SecurityException) {
                // Ignore safety errors for unsupported content providers
            }
            newItems.add(extractMetadata(uri))
        }

        playlist.addAll(newItems)
        playlistAdapter.notifyDataSetChanged()

        if (currentIndex == -1 && playlist.isNotEmpty()) {
            currentIndex = 0
            savedPosition = 0
            val item = playlist[currentIndex]
            tvCurrentFileName.text = item.title ?: item.fileName
            initializeMediaPlayer(Uri.parse(item.uriString), startImmediately = false)
        }

        saveState()
    }

    private fun extractMetadata(uri: Uri): PlaylistItem {
        val retriever = MediaMetadataRetriever()
        var title: String? = null
        var trackString: String? = null
        var album: String? = null
        val fileName = getFileNameFromUri(uri)

        try {
            retriever.setDataSource(applicationContext, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            trackString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val trackNumber = trackString?.let {
            try {
                it.split("/")[0].trim().toInt()
            } catch (e: Exception) {
                null
            }
        }

        return PlaylistItem(uri.toString(), fileName, title, trackNumber, album)
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Unknown Audio File"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return name
    }

    private fun sortPlaylistByMetadata() {
        if (playlist.isEmpty()) return

        // Sort: primary album -> track list -> title -> alphabetical file name fallback
        playlist.sortWith(
            compareBy<PlaylistItem> { it.album ?: "" }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy { it.title ?: "" }
                .thenBy { it.fileName }
        )

        // Reset current tracks pointer safety to stay on correct playing URI
        currentIndex = 0
        savedPosition = 0

        playlistAdapter.notifyDataSetChanged()
        if (playlist.isNotEmpty()) {
            val item = playlist[currentIndex]
            tvCurrentFileName.text = item.title ?: item.fileName
            initializeMediaPlayer(Uri.parse(item.uriString), startImmediately = false)
        }
        saveState()
    }

    private fun initializeMediaPlayer(uri: Uri, startImmediately: Boolean) {
        releaseMediaPlayer()

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(applicationContext, uri)
                prepare()
                seekTo(savedPosition.coerceAtMost(duration))
                applyPlaybackSpeed(currentSpeed)

                // Triggers next file automatically on ending
                setOnCompletionListener {
                    playNext()
                }
            }

            updateProgressUi()

            if (startImmediately) {
                startPlayback()
            } else {
                tvStatus.text = "Paused"
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Could not load the audio file", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun startPlayback() {
        mediaPlayer?.let { player ->
            player.start()
            applyPlaybackSpeed(currentSpeed)
            tvStatus.text = "Playing"
            updateHandler.post(updateProgressTask)
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            }
            savedPosition = player.currentPosition
            tvStatus.text = "Paused"
            saveState()
        }
        updateHandler.removeCallbacks(updateProgressTask)
    }

    private fun togglePlayPause() {
        val player = mediaPlayer ?: run {
            Toast.makeText(this, "Please add files to start", Toast.LENGTH_SHORT).show()
            return
        }

        if (player.isPlaying) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    private fun seekForward() {
        mediaPlayer?.let { player ->
            val targetPosition = player.currentPosition + (skipIntervalSeconds * 1000)
            player.seekTo(targetPosition.coerceAtMost(player.duration))
            updateProgressUi()
            saveState()
        }
    }

    private fun seekBackward() {
        mediaPlayer?.let { player ->
            val targetPosition = player.currentPosition - (skipIntervalSeconds * 1000)
            player.seekTo(targetPosition.coerceAtLeast(0))
            updateProgressUi()
            saveState()
        }
    }

    private fun playNext() {
        if (playlist.isEmpty()) return
        if (currentIndex < playlist.size - 1) {
            currentIndex++
            savedPosition = 0
            val item = playlist[currentIndex]
            tvCurrentFileName.text = item.title ?: item.fileName
            initializeMediaPlayer(Uri.parse(item.uriString), startImmediately = true)
            playlistAdapter.notifyDataSetChanged()
            saveState()
        } else {
            Toast.makeText(this, "End of Playlist", Toast.LENGTH_SHORT).show()
            tvStatus.text = "Finished"
            updateHandler.removeCallbacks(updateProgressTask)
        }
    }

    private fun playPrevious() {
        if (playlist.isEmpty()) return
        if (currentIndex > 0) {
            currentIndex--
            savedPosition = 0
            val item = playlist[currentIndex]
            tvCurrentFileName.text = item.title ?: item.fileName
            initializeMediaPlayer(Uri.parse(item.uriString), startImmediately = true)
            playlistAdapter.notifyDataSetChanged()
            saveState()
        } else {
            Toast.makeText(this, "First track playing", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPlaybackSpeed(speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mediaPlayer?.let { player ->
                try {
                    val isPlaying = player.isPlaying
                    player.playbackParams = player.playbackParams.setSpeed(speed)
                    if (!isPlaying) {
                        player.pause()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun savePlaylistState() {
        val jsonArray = JSONArray()
        playlist.forEach { item ->
            val jsonObject = JSONObject().apply {
                put("uri", item.uriString)
                put("fileName", item.fileName)
                put("title", item.title ?: "")
                put("track", item.trackNumber ?: -1)
                put("album", item.album ?: "")
            }
            jsonArray.put(jsonObject)
        }
        sharedPreferences.edit().putString(KEY_PLAYLIST, jsonArray.toString()).apply()
    }

    private fun loadPlaylistState() {
        val playlistStr = sharedPreferences.getString(KEY_PLAYLIST, null)
        playlist.clear()
        if (playlistStr != null) {
            try {
                val jsonArray = JSONArray(playlistStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val uriString = obj.getString("uri")
                    val fileName = obj.getString("fileName")
                    val title = obj.optString("title").takeIf { it.isNotEmpty() }
                    val track = obj.optInt("track").takeIf { it != -1 }
                    val album = obj.optString("album").takeIf { it.isNotEmpty() }
                    playlist.add(PlaylistItem(uriString, fileName, title, track, album))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        currentIndex = sharedPreferences.getInt(KEY_CURRENT_INDEX, -1)
    }

    private fun setupRecyclerViewAndDragDrop() {
        playlistAdapter = PlaylistAdapter(playlist) { clickedIndex ->
            currentIndex = clickedIndex
            savedPosition = 0
            val item = playlist[currentIndex]
            tvCurrentFileName.text = item.title ?: item.fileName
            initializeMediaPlayer(Uri.parse(item.uriString), startImmediately = true)
            playlistAdapter.notifyDataSetChanged()
            saveState()
        }

        rvPlaylist.layoutManager = LinearLayoutManager(this)
        rvPlaylist.adapter = playlistAdapter

        // Drag & Drop gesture handler for rearranging
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition

                // Track currently playing index swap
                if (currentIndex == fromPos) {
                    currentIndex = toPos
                } else if (currentIndex in (fromPos + 1)..toPos) {
                    currentIndex--
                } else if (currentIndex in toPos until fromPos) {
                    currentIndex++
                }

                Collections.swap(playlist, fromPos, toPos)
                playlistAdapter.notifyItemMoved(fromPos, toPos)
                playlistAdapter.notifyItemChanged(fromPos)
                playlistAdapter.notifyItemChanged(toPos)
                saveState()
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })

        itemTouchHelper.attachToRecyclerView(rvPlaylist)
    }

    private fun setupSpinners() {
        val intervals = listOf(5, 10, 15, 30, 60)
        val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervals.map { "$it sec" })
        intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerInterval.adapter = intervalAdapter

        val defaultIntervalIndex = intervals.indexOf(skipIntervalSeconds).coerceAtLeast(0)
        spinnerInterval.setSelection(defaultIntervalIndex)
        spinnerInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                skipIntervalSeconds = intervals[position]
                saveState()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val speeds = listOf(0.5f, 0.75f, 0.8f, 0.85f, 0.9f, 0.95f, 1.0f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f, 1.5f, 1.75f, 2.0f)
        val speedAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, speeds.map { String.format(Locale.US, "%.2fx", it) })
        speedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSpeed.adapter = speedAdapter

        val defaultSpeedIndex = speeds.indexOf(currentSpeed).coerceAtLeast(0)
        spinnerSpeed.setSelection(defaultSpeedIndex)
        spinnerSpeed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentSpeed = speeds[position]
                applyPlaybackSpeed(currentSpeed)
                saveState()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupGestureDetector() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                togglePlayPause()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            seekForward()
                        } else {
                            seekBackward()
                        }
                        return true
                    }
                }
                return false
            }

            override fun onDown(e: MotionEvent): Boolean = true
        })

        touchArea.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun updateProgressUi() {
        mediaPlayer?.let { player ->
            val current = player.currentPosition
            val duration = player.duration
            tvPlaybackPosition.text = String.format(
                Locale.US, "%s / %s",
                formatTime(current),
                formatTime(duration)
            )
        }
    }

    private fun formatTime(millis: Int): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60))
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun releaseMediaPlayer() {
        updateHandler.removeCallbacks(updateProgressTask)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onPause() {
        super.onPause()
        pausePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
    }

    // RecyclerView Adapter definition nested for repository cleanliness
    inner class PlaylistAdapter(
        private val list: List<PlaylistItem>,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

        inner class PlaylistViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTrackNum: TextView = itemView.findViewById(R.id.tvTrackNum)
            val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
            val tvSubtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
            val ivDragHandle: ImageView = itemView.findViewById(R.id.ivDragHandle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
            return PlaylistViewHolder(view)
        }

        override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
            val item = list[position]
            holder.tvTrackNum.text = String.format(Locale.US, "%d", position + 1)
            holder.tvTitle.text = item.title ?: item.fileName

            val subtitle = if (item.album != null) {
                if (item.trackNumber != null) "Album: ${item.album} (Track ${item.trackNumber})" else "Album: ${item.album}"
            } else {
                item.fileName
            }
            holder.tvSubtitle.text = subtitle

            // Highlight currently playing item
            if (position == currentIndex) {
                holder.itemView.setBackgroundColor(Color.parseColor("#3A3A3C"))
                holder.tvTitle.setTextColor(Color.parseColor("#34C759")) // Green text
            } else {
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
                holder.tvTitle.setTextColor(Color.parseColor("#FFFFFF"))
            }

            holder.itemView.setOnClickListener {
                onItemClick(position)
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
