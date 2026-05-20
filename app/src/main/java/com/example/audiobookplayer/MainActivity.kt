package com.example.audiobookplayer // Make sure this matches your project's package name

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    private var mediaPlayer: MediaPlayer? = null

    // UI elements
    private lateinit var btnSelectFile: Button
    private lateinit var tvFileName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPlaybackPosition: TextView
    private lateinit var spinnerInterval: Spinner
    private lateinit var spinnerSpeed: Spinner
    private lateinit var touchArea: FrameLayout

    // State persistence keys
    private val PREFS_NAME = "AudiobookPrefs"
    private val KEY_URI = "saved_uri"
    private val KEY_POSITION = "saved_position"
    private val KEY_SPEED = "saved_speed"
    private val KEY_INTERVAL = "saved_interval"
    private val KEY_FILE_NAME = "saved_file_name"

    // Loaded configuration state
    private var currentUri: Uri? = null
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

    // Swipe configuration thresholds
    private val SWIPE_THRESHOLD = 100
    private val SWIPE_VELOCITY_THRESHOLD = 100

    // File Picker registration
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFile(uri)
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

        // Attempt to load previously saved file
        currentUri?.let { uri ->
            initializeMediaPlayer(uri, startImmediately = false)
        }
    }

    private fun initUi() {
        btnSelectFile = findViewById(R.id.btnSelectFile)
        tvFileName = findViewById(R.id.tvFileName)
        tvStatus = findViewById(R.id.tvStatus)
        tvPlaybackPosition = findViewById(R.id.tvPlaybackPosition)
        spinnerInterval = findViewById(R.id.spinnerInterval)
        spinnerSpeed = findViewById(R.id.spinnerSpeed)
        touchArea = findViewById(R.id.touchArea)

        btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            filePickerLauncher.launch(intent)
        }
    }

    private fun loadPreferences() {
        val uriString = sharedPreferences.getString(KEY_URI, null)
        if (uriString != null) {
            currentUri = Uri.parse(uriString)
        }
        savedPosition = sharedPreferences.getInt(KEY_POSITION, 0)
        currentSpeed = sharedPreferences.getFloat(KEY_SPEED, 1.0f)
        skipIntervalSeconds = sharedPreferences.getInt(KEY_INTERVAL, 10)
        tvFileName.text = sharedPreferences.getString(KEY_FILE_NAME, "No file selected")
    }

    private fun saveState() {
        val editor = sharedPreferences.edit()
        editor.putString(KEY_URI, currentUri?.toString())
        mediaPlayer?.let {
            editor.putInt(KEY_POSITION, it.currentPosition)
        } ?: run {
            editor.putInt(KEY_POSITION, savedPosition)
        }
        editor.putFloat(KEY_SPEED, currentSpeed)
        editor.putInt(KEY_INTERVAL, skipIntervalSeconds)
        editor.putString(KEY_FILE_NAME, tvFileName.text.toString())
        editor.apply()
    }

    private fun handleSelectedFile(uri: Uri) {
        try {
            // Take persistable permission to keep accessing the file after device reboots
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: SecurityException) {
            // Fallback for file providers that do not support persistable permissions
        }

        currentUri = uri
        savedPosition = 0 // Reset position for a newly chosen file
        val displayName = getFileNameFromUri(uri)
        tvFileName.text = displayName

        saveState()
        initializeMediaPlayer(uri, startImmediately = false)
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Unknown Audio File"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
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
                seekTo(savedPosition)
                applyPlaybackSpeed(currentSpeed)
            }

            updateProgressUi()

            if (startImmediately) {
                startPlayback()
            } else {
                tvStatus.text = "Paused"
            }

        } catch (e: IOException) {
            Toast.makeText(this, "Error loading audio file", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun startPlayback() {
        mediaPlayer?.let { player ->
            player.start()
            // Re-apply playback parameters since starting can reset them on older API levels
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
            Toast.makeText(this, "Please select an audio file first", Toast.LENGTH_SHORT).show()
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

    private fun applyPlaybackSpeed(speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mediaPlayer?.let { player ->
                try {
                    val isPlaying = player.isPlaying
                    player.playbackParams = player.playbackParams.setSpeed(speed)
                    // If the player was paused, setting params might trigger playing state
                    // in some implementations, so we enforce the correct state.
                    if (!isPlaying) {
                        player.pause()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun setupSpinners() {
        // Setup Swiping Interval Options (5s, 10s, 15s, 30s, 60s)
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

        // Setup Playback Speed Options
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

                // Confirm horizontal movement is larger than vertical movement
                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            seekForward() // Swipe Right
                        } else {
                            seekBackward() // Swipe Left
                        }
                        return true
                    }
                }
                return false
            }

            // Down action needs to return true for other gestures (fling/tap) to be evaluated
            override fun onDown(e: MotionEvent): Boolean = true
        })

        // Pass touch events from FrameLayout to GestureDetector
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
}
