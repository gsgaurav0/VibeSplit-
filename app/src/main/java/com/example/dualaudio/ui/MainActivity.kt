package com.example.dualaudio.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.dualaudio.R
import com.example.dualaudio.audio.DualAudioEngine
import com.example.dualaudio.data.MusicRepository
import com.example.dualaudio.data.Song
import com.example.dualaudio.service.AudioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.documentfile.provider.DocumentFile
import android.media.MediaMetadataRetriever

class MainActivity : AppCompatActivity() {

    private var audioService: AudioService? = null
    private var isBound = false
    private lateinit var repository: MusicRepository
    private var allSongs: List<Song> = emptyList()
    private var currentIndexA = -1
    private var currentIndexB = -1
    
    // Playback Modes
    private var isShuffleA = false
    private var isRepeatA = false
    private var isShuffleB = false
    private var isRepeatB = false
    private var isMiniPlayerClosed = false

    // UI
    // UI Containers
    private lateinit var containerLibrary: FrameLayout
    private lateinit var containerPlayer: FrameLayout
    private lateinit var btnBack: View
    private lateinit var btnSplitToggle: View
    
    // Player UI Elements
    private lateinit var tvPlayerSongA: TextView
    private lateinit var tvPlayerSongB: TextView
    private lateinit var btnPlayerPlayA: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var btnPlayerPlayB: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var containerPlayerB: View
    
    // Adapter
    private lateinit var songAdapter: SongAdapter
    
    private var updateJob: kotlinx.coroutines.Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioService.LocalBinder
            audioService = binder.getService()
            isBound = true
            
            // Auto-Next Logic
            audioService?.setOnCompletionListener(object : DualAudioEngine.OnCompletionListener {
                override fun onCompletion(source: DualAudioEngine.Source) {
                    playNext(source)
                }
            })
            
            updateButtonState()
            startProgressUpdater()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }
    
    // Vinyl Rotators
    private var vinylAnimatorA: android.animation.ObjectAnimator? = null
    private var vinylAnimatorB: android.animation.ObjectAnimator? = null
    
    // Visualizer Animators
    private val visualizerAnimatorsA = mutableListOf<android.animation.ValueAnimator>()
    private val visualizerAnimatorsB = mutableListOf<android.animation.ValueAnimator>()

    private fun setupVinylAnimation(view: View): android.animation.ObjectAnimator {
        val anim = android.animation.ObjectAnimator.ofFloat(view, "rotation", 0f, 360f)
        anim.duration = 4000 // 4 seconds per rotation
        anim.repeatCount = android.animation.ObjectAnimator.INFINITE
        anim.interpolator = android.view.animation.LinearInterpolator()
        return anim
    }
    
    private fun controlVinylAnimation(animator: android.animation.ObjectAnimator?, isPlaying: Boolean) {
        if (isPlaying) {
            if (animator?.isPaused == true) animator.resume()
            else if (animator?.isRunning == false) animator.start()
        } else {
            if (animator?.isRunning == true) animator.pause()
        }
    }
    
    // Simulated Visualizer Logic
    private fun startVisualizer(container: LinearLayout, animators: MutableList<android.animation.ValueAnimator>, isPlaying: Boolean) {
        if (!isPlaying) {
            animators.forEach { it.cancel() }
            animators.clear()
            // Reset bars to default height
            for (i in 0 until container.childCount) {
                val bar = container.getChildAt(i)
                val params = bar.layoutParams
                params.height = 10
                bar.layoutParams = params
            }
            return
        }
        
        if (animators.isNotEmpty()) return // Already running
        
        val random = java.util.Random()
        for (i in 0 until container.childCount) {
            val bar = container.getChildAt(i)
            val anim = android.animation.ValueAnimator.ofInt(10, 80) // Height range
            anim.duration = (200 + random.nextInt(300)).toLong() // Random duration
            anim.repeatCount = android.animation.ValueAnimator.INFINITE
            anim.repeatMode = android.animation.ValueAnimator.REVERSE
            
            anim.addUpdateListener { valueAnimator ->
                val params = bar.layoutParams
                params.height = valueAnimator.animatedValue as Int
                bar.layoutParams = params
            }
            // Stagger start times
            anim.startDelay = random.nextInt(200).toLong()
            anim.start()
            animators.add(anim)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = MusicRepository(this)
        setupUI()
        checkPermissions()
        
        Intent(this, AudioService::class.java).also { intent ->
            startService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        
        val filter = IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, filter)
    }

    private fun loadSongs() {
        // MANUAL MODE: Do NOT auto-load all songs from MediaStore.
        // User wants empty state until "Add Folder" is clicked.
        // Keeping this method signature empty or just updating UI to empty state.
        allSongs = emptyList()
        if (::songAdapter.isInitialized) {
            songAdapter.submitList(allSongs)
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_CONNECTION_STATE, android.bluetooth.BluetoothAdapter.STATE_DISCONNECTED)
                updateBluetoothStatus(state)
            }
        }
    }

    private fun updateBluetoothStatus(state: Int) {
        val tv = findViewById<TextView>(R.id.tvBluetoothStatus) ?: return
        val statusText = when (state) {
            android.bluetooth.BluetoothAdapter.STATE_CONNECTED -> "Bluetooth Connected 🎧"
            android.bluetooth.BluetoothAdapter.STATE_CONNECTING -> "Connecting..."
            android.bluetooth.BluetoothAdapter.STATE_DISCONNECTED -> "Bluetooth Disconnected"
            else -> "Bluetooth Status: $state"
        }
        tv.text = statusText
    }

    // Launchers for System File Picker
    private val openFolderLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            loadSongsFromFolder(it)
        }
    }

    private val pickSongA = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val songName = getFileName(it)
            tvPlayerSongA.text = songName
            audioService?.audioEngine?.setSourceA(it)
        }
    }

    private val pickSongB = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val songName = getFileName(it)
            tvPlayerSongB.text = songName
            audioService?.audioEngine?.setSourceB(it)
        }
    }

    private val permissionsLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            Toast.makeText(this, "Permissions Granted", Toast.LENGTH_SHORT).show()
            loadSongs()
        } else {
            Toast.makeText(this, "Permissions Denied. App may not work correctly.", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            loadSongs()
        }
    }
    
    // Custom Folder Picker to Bypass System Restrictions
    private fun showCustomFolderPicker() {
        var currentDir = android.os.Environment.getExternalStorageDirectory()
        val parentPath = currentDir.parent ?: "/"

        // Inflate Custom Layout
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_folder_picker, null)
        val tvCurrentPath = dialogView.findViewById<TextView>(R.id.tvCurrentPath)
        val rv = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvFolderList)
        val btnUp = dialogView.findViewById<ImageView>(R.id.btnNavUp)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancelPicker)
        val btnSelect = dialogView.findViewById<android.widget.Button>(R.id.btnSelectFolder)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        fun refreshList(dir: java.io.File) {
            currentDir = dir
            // Display path relative to emulated/0 for cleaner look or full path
            tvCurrentPath.text = dir.absolutePath.replace(parentPath, "")

            val files = dir.listFiles()?.filter { it.isDirectory && !it.isHidden }?.sortedBy { it.name } ?: emptyList()
            
            // Adapter
            rv.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                    val v = layoutInflater.inflate(R.layout.item_folder, parent, false)
                    return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {}
                }
                override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                    val file = files[position]
                    val tv = holder.itemView.findViewById<TextView>(R.id.tvFolderName)
                    tv.text = file.name
                    
                    holder.itemView.setOnClickListener {
                         refreshList(file)
                    }
                }
                override fun getItemCount() = files.size
            }
        }
        
        btnUp.setOnClickListener {
             val parent = currentDir.parentFile
             // Prevent going above /storage/emulated/0 usually, but let's allow going up to root if needed
             if (parent != null && currentDir.absolutePath != android.os.Environment.getExternalStorageDirectory().absolutePath) {
                 refreshList(parent)
             } else {
                 Toast.makeText(this, "Root Directory Reached", Toast.LENGTH_SHORT).show()
             }
        }
        
        btnCancel.setOnClickListener { dialog.dismiss() }
        
        btnSelect.setOnClickListener {
             loadSongsFromRawFolder(currentDir)
             dialog.dismiss()
        }

        refreshList(currentDir)
        dialog.show()
    }

    private fun loadSongsFromRawFolder(dir: java.io.File) {
         CoroutineScope(Dispatchers.IO).launch {
             val newSongs = mutableListOf<Song>()
             fun scan(d: java.io.File) {
                 d.listFiles()?.forEach { f ->
                     if (f.isDirectory) scan(f)
                     else {
                         if (f.name.endsWith(".mp3", true) || f.name.endsWith(".m4a", true) || f.name.endsWith(".wav", true)) {
                             val id = f.hashCode().toLong() // Simple ID
                             val title = f.nameWithoutExtension
                             val artist = "Local File"
                             val duration = 0L 
                             newSongs.add(Song(id, title, artist, duration, android.net.Uri.fromFile(f)))
                         }
                     }
                 }
             }
             scan(dir)
             
             withContext(Dispatchers.Main) {
                 val currentList = allSongs.toMutableList()
                 currentList.addAll(newSongs)
                 allSongs = currentList
                 if (::songAdapter.isInitialized) {
                    songAdapter.submitList(allSongs)
                 }
                 Toast.makeText(this@MainActivity, "Added ${newSongs.size} songs", Toast.LENGTH_SHORT).show()
             }
         }
    }

    // NEW: View Switching
    private fun showLibrary() {
        containerLibrary.visibility = View.VISIBLE
        containerPlayer.visibility = View.GONE
    }
    
    private fun showPlayer() {
        containerLibrary.visibility = View.GONE
        containerPlayer.visibility = View.VISIBLE
    }

    private fun setupUI() {
        containerLibrary = findViewById(R.id.containerLibrary)
        containerPlayer = findViewById(R.id.containerPlayer)
        btnBack = findViewById(R.id.btnBack)
        btnSplitToggle = findViewById(R.id.btnSplitToggle)
        
        tvPlayerSongA = findViewById(R.id.tvPlayerSongA)
        tvPlayerSongB = findViewById(R.id.tvPlayerSongB)
        btnPlayerPlayA = findViewById(R.id.btnPlayerPlayA)
        btnPlayerPlayB = findViewById(R.id.btnPlayerPlayB)
        containerPlayerB = findViewById(R.id.playerContainerB)
        
        // Default to Full Screen
        // Default to Full Screen
        val lpPlayerA = findViewById<View>(R.id.playerContainerA).layoutParams as LinearLayout.LayoutParams
        lpPlayerA.weight = 2f
        findViewById<View>(R.id.playerContainerA).layoutParams = lpPlayerA
        
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvSongs)
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        songAdapter = SongAdapter { song ->
            // On Song Click:
            // 1. Play in Same Mode (Default)
            audioService?.audioEngine?.mode = DualAudioEngine.Mode.SAME
            containerPlayerB.visibility = View.GONE // Reset split view
            
            // Set source
            audioService?.audioEngine?.setSourceA(song.uri)
            // Let's set both for cleanliness or just A since SAME uses primarySource
            audioService?.audioEngine?.primarySource = DualAudioEngine.Source.A
            
            // Fix: Ensure Full Screen Layout
            // Fix: Ensure Full Screen Layout
            val lpPlayerA = findViewById<View>(R.id.playerContainerA).layoutParams as LinearLayout.LayoutParams
            lpPlayerA.weight = 2f
            findViewById<View>(R.id.playerContainerA).layoutParams = lpPlayerA
            
            currentIndexA = allSongs.indexOf(song)
            tvPlayerSongA.text = song.title
            tvPlayerSongB.text = song.title // Metadata
            
            audioService?.play()
            updateButtonState()
            showPlayer()
        }
        rv.adapter = songAdapter
        
        btnBack.setOnClickListener { showLibrary() }
        
        val btnSwap = findViewById<View>(R.id.btnSwap)
        btnSwap.setOnClickListener {
             // 1. Button Animation (Rotate)
             btnSwap.animate().rotationBy(180f).setDuration(300).start()
        
             audioService?.toggleSwap()
             val swapped = audioService?.isSwapped() == true
             
             // 2. Vinyl Slide Animation (Visual Feedback of Left <-> Right)
             val vinylA = findViewById<View>(R.id.vinylContainerA)
             val vinylB = findViewById<View>(R.id.vinylContainerB)
             
             // Slide A to Right, B to Left (Simulating ear swap)
             vinylA.animate().translationX(100f).alpha(0.5f).setDuration(150).withEndAction {
                 vinylA.animate().translationX(0f).alpha(1f).setDuration(200).setInterpolator(android.view.animation.OvershootInterpolator()).start()
             }.start()
             
             if (containerPlayerB.visibility == View.VISIBLE) {
                 vinylB.animate().translationX(-100f).alpha(0.5f).setDuration(150).withEndAction {
                     vinylB.animate().translationX(0f).alpha(1f).setDuration(200).setInterpolator(android.view.animation.OvershootInterpolator()).start()
                 }.start()
             }
             
             // Custom Toast with Icon if possible, or just text
             Toast.makeText(this, if (swapped) "\uD83D\uDD00 Channels Swapped (Right <-> Left)" else "\u21AA Normal Channels", Toast.LENGTH_SHORT).show()
        }

        btnSplitToggle.setOnClickListener {
            // Toggle Split Mode
            val engine = audioService?.audioEngine ?: return@setOnClickListener
            if (engine.mode == DualAudioEngine.Mode.SAME) {
                engine.mode = DualAudioEngine.Mode.SPLIT
                containerPlayerB.visibility = View.VISIBLE
                btnSwap.visibility = View.VISIBLE
                
                val lpPlayerA = findViewById<View>(R.id.playerContainerA).layoutParams as LinearLayout.LayoutParams
                lpPlayerA.weight = 1f
                findViewById<View>(R.id.playerContainerA).layoutParams = lpPlayerA
                
                 // FIX: Do NOT auto-copy music. Start B empty/paused.
                 // We don't setSourceB here.
                 // Reset Metadata for B
                 tvPlayerSongB.text = "Select Music"
                 currentIndexB = -1
                 
                 // Ensure B is paused initially
                 audioService?.pauseB()
                 updateButtonState() // Update UI icons

                Toast.makeText(this, "Split Mode Activated", Toast.LENGTH_SHORT).show()
            } else {
                showSameModeDialog()
            }
        }
        
        btnPlayerPlayA.setOnClickListener {
             it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                 it.animate().scaleX(1f).scaleY(1f).setDuration(100).setInterpolator(android.view.animation.OvershootInterpolator()).start()
             }.start()
             
             if (audioService?.isPlayingA() == true) {
                 audioService?.pauseA()
                 updateButtonState()
             } else {
                 audioService?.resumeA()
                 updateButtonState()
             }
        }
        
        btnPlayerPlayB.setOnClickListener {
             it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                 it.animate().scaleX(1f).scaleY(1f).setDuration(100).setInterpolator(android.view.animation.OvershootInterpolator()).start()
             }.start()
             
             if (audioService?.isPlayingB() == true) {
                 audioService?.pauseB()
                 updateButtonState()
             } else {
                 audioService?.resumeB()
                 updateButtonState()
             }
        }
        
        // Next/Prev A
        findViewById<View>(R.id.btnPlayerNextA).setOnClickListener { playNext(DualAudioEngine.Source.A) }
        findViewById<View>(R.id.btnPlayerPrevA).setOnClickListener { playPrev(DualAudioEngine.Source.A) }
        
        // Next/Prev B
        findViewById<View>(R.id.btnPlayerNextB).setOnClickListener { playNext(DualAudioEngine.Source.B) }
        findViewById<View>(R.id.btnPlayerPrevB).setOnClickListener { playPrev(DualAudioEngine.Source.B) }
        
        findViewById<View>(R.id.btnAddLibrary).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (android.os.Environment.isExternalStorageManager()) {
                    showCustomFolderPicker()
                } else {
                    requestAllFilesAccess()
                }
            } else {
                // Legacy (Pre-Android 11)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    showCustomFolderPicker()
                } else {
                    permissionsLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                }
            }
        }
        
        findViewById<View>(R.id.btnSort).setOnClickListener {
            Toast.makeText(this, "Sort Feature Coming Soon", Toast.LENGTH_SHORT).show()
        }
        
        findViewById<View>(R.id.btnSearch).setOnClickListener {
             val etSearch = findViewById<EditText>(R.id.etSearch)
             if (etSearch.visibility == View.VISIBLE) {
                 etSearch.visibility = View.GONE
                 // Clear search
                 etSearch.setText("")
                 // Hide keyboard
                 val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                 imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
             } else {
                 etSearch.visibility = View.VISIBLE
                 etSearch.requestFocus()
                 // Show keyboard
                 val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                 imm.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
             }
        }
        
        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s.toString()
                if (query.isEmpty()) {
                    if (::songAdapter.isInitialized) songAdapter.submitList(allSongs)
                } else {
                    val filtered = allSongs.filter { it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true) }
                    if (::songAdapter.isInitialized) songAdapter.submitList(filtered)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        
        findViewById<View>(R.id.btnSelectMusicA).setOnClickListener {
             showPlaylistDialog(DualAudioEngine.Source.A)
        }

        findViewById<View>(R.id.btnSelectMusicB).setOnClickListener {
            showPlaylistDialog(DualAudioEngine.Source.B)
        }
        
        val sbVolA = findViewById<SeekBar>(R.id.seekBarVolumeA)
        sbVolA.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audioService?.setVolumeA(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        val sbVolB = findViewById<SeekBar>(R.id.seekBarVolumeB)
        sbVolB.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
             override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audioService?.setVolumeB(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Progress SeekBars
        val sbProgressA = findViewById<SeekBar>(R.id.seekBarProgressA)
        sbProgressA.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Optional: Update text while dragging
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { audioService?.audioEngine?.seekA(it.progress.toLong()) }
            }
        })

        val sbProgressB = findViewById<SeekBar>(R.id.seekBarProgressB)
        sbProgressB.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { audioService?.audioEngine?.seekB(it.progress.toLong()) }
            }
        })
        
        // Buttons Wiring (Visual only for now)
        // Buttons Wiring (Visual only for now)
        // Buttons Wiring
        val btnShuffleA = findViewById<View>(R.id.btnRecycleA)
        btnShuffleA.alpha = 0.5f // Default Off
        btnShuffleA.setOnClickListener { 
            isShuffleA = !isShuffleA
            it.alpha = if (isShuffleA) 1.0f else 0.5f
            it.animate().rotationBy(360f).setDuration(500).start()
            val msg = if (isShuffleA) "Shuffle On" else "Shuffle Off"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() 
        }

        val btnRepeatA = findViewById<View>(R.id.btnRepeatA)
        btnRepeatA.alpha = 0.5f // Default Off
        btnRepeatA.setOnClickListener { 
            isRepeatA = !isRepeatA
            it.alpha = if (isRepeatA) 1.0f else 0.5f
            it.animate().scaleX(1.2f).scaleY(1.2f).setDuration(150).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }.start()
            val msg = if (isRepeatA) "Repeat On" else "Repeat Off"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() 
        }
        
        val btnShuffleB = findViewById<View>(R.id.btnRecycleB)
        btnShuffleB.alpha = 0.5f // Default Off
        btnShuffleB.setOnClickListener { 
             isShuffleB = !isShuffleB
             it.alpha = if (isShuffleB) 1.0f else 0.5f
             it.animate().rotationBy(360f).setDuration(500).start()
             val msg = if (isShuffleB) "Shuffle On" else "Shuffle Off"
             Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() 
        }

        val btnRepeatB = findViewById<View>(R.id.btnRepeatB)
        btnRepeatB.alpha = 0.5f // Default Off
        btnRepeatB.setOnClickListener { 
             isRepeatB = !isRepeatB
             it.alpha = if (isRepeatB) 1.0f else 0.5f
             it.animate().scaleX(1.2f).scaleY(1.2f).setDuration(150).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }.start()
             val msg = if (isRepeatB) "Repeat On" else "Repeat Off"
             Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() 
        }
        
        // Initialize Vinyl Animators
        vinylAnimatorA = setupVinylAnimation(findViewById(R.id.vinylContainerA))
        vinylAnimatorB = setupVinylAnimation(findViewById(R.id.vinylContainerB))
        
        setupMiniPlayer()
    }
    
    private fun setupMiniPlayer() {
        // Wiring Mini Player
        val miniPlay = findViewById<View>(R.id.btnMiniPlay)
        val miniNext = findViewById<View>(R.id.btnMiniNext)
        val miniPrev = findViewById<View>(R.id.btnMiniPrev)
        val miniClose = findViewById<View>(R.id.btnMiniClose)
        val layoutMini = findViewById<View>(R.id.layoutMiniPlayer)
        
        // Open Full Player on Click
        layoutMini?.setOnClickListener {
             showPlayer()
        }
        
        miniPlay?.setOnClickListener {
             // Smart Toggle:
             // If ANY player is playing, Pause them.
             // If NO player is playing, Play A (default).
             val playingA = audioService?.isPlayingA() == true
             val playingB = audioService?.isPlayingB() == true
             
             if (playingA || playingB) {
                 if (playingA) btnPlayerPlayA.performClick()
                 if (playingB) btnPlayerPlayB.performClick()
             } else {
                 btnPlayerPlayA.performClick()
             }
        }
        
        miniNext?.setOnClickListener {
            playNext(DualAudioEngine.Source.A)
        }
        
        miniPrev?.setOnClickListener {
            playPrev(DualAudioEngine.Source.A)
        }
        
        miniClose?.setOnClickListener {
            isMiniPlayerClosed = true
            updateButtonState()
        }
    }
    
    private fun showPlaylistDialog(source: DualAudioEngine.Source) {
        if (allSongs.isEmpty()) {
            Toast.makeText(this, "No songs in library", Toast.LENGTH_SHORT).show()
            loadSongs()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_playlist, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvPlaylistTitle)
        val rv = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvPlaylist)
        val btnClose = dialogView.findViewById<View>(R.id.btnClosePlaylist)
        
        tvTitle.text = "Select Song for ${if (source == DualAudioEngine.Source.A) "Player A" else "Player B"}"
        
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
            
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        val adapter = SongAdapter { song ->
            playSongAt(allSongs.indexOf(song), source)
            dialog.dismiss()
        }
        adapter.submitList(allSongs)
        rv.adapter = adapter
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.black)
        dialog.window?.attributes?.windowAnimations = R.style.PlaylistDialogAnimation
        dialog.show()
        
        // Full Screen Sizing
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }
    
    // INNER CLASS ADAPTER
    class SongAdapter(private val onClick: (Song) -> Unit) : androidx.recyclerview.widget.RecyclerView.Adapter<SongAdapter.ViewHolder>() {
        private var list: List<Song> = emptyList()
        private var lastPosition = -1

        fun submitList(songs: List<Song>) {
            list = songs
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val song = list[position]
            
            // Bind Views
            val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvTitle)
            val tvArtist = holder.itemView.findViewById<TextView>(R.id.tvArtist)
            val tvDuration = holder.itemView.findViewById<TextView>(R.id.tvDuration)
            
            tvTitle.text = song.title
            tvArtist.text = song.artist
            
            val minutes = (song.duration / 1000) / 60
            val seconds = (song.duration / 1000) % 60
            tvDuration.text = String.format("%02d:%02d", minutes, seconds)
            
            holder.itemView.setOnClickListener { onClick(song) }
            
            setAnimation(holder.itemView, position)
        }
        
        private fun setAnimation(viewToAnimate: View, position: Int) {
            if (position > lastPosition) {
                val animation = android.view.animation.AnimationUtils.loadAnimation(viewToAnimate.context, android.R.anim.slide_in_left)
                viewToAnimate.startAnimation(animation)
                lastPosition = position
            }
        }
        
        override fun getItemCount(): Int = list.size
        
        class ViewHolder(itemView: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView)
    }


    
    // Playlist Helpers
    // Playlist Helpers
    private fun playNext(source: DualAudioEngine.Source) {
        if (allSongs.isEmpty()) { loadSongs(); return }
        
        val isShuffle = if (source == DualAudioEngine.Source.A) isShuffleA else isShuffleB
        var index = if (source == DualAudioEngine.Source.A) currentIndexA else currentIndexB
        
        if (isShuffle) {
            index = (0 until allSongs.size).random()
        } else {
            index = (index + 1) % allSongs.size
        }
        playSongAt(index, source)
    }

    private fun playPrev(source: DualAudioEngine.Source) {
        if (allSongs.isEmpty()) { loadSongs(); return }
        // Prev usually ignores shuffle (goes to history), but for simple logic we just go back
        // If shuffle is on, we could go random, but history is better. 
        // For MVP, Prev just goes purely Previous in list.
        var index = if (source == DualAudioEngine.Source.A) currentIndexA else currentIndexB
        index = if (index - 1 < 0) allSongs.size - 1 else index - 1
        playSongAt(index, source)
    }

    private fun playSongAt(index: Int, source: DualAudioEngine.Source) {
        if (allSongs.isEmpty()) return
        
        // Reset Mini Player closed state when user explicitly plays a song
        isMiniPlayerClosed = false
        
        val song = allSongs[index]
        
        val container = if (source == DualAudioEngine.Source.A) findViewById<View>(R.id.vinylContainerA) else findViewById<View>(R.id.vinylContainerB)
        val textView = if (source == DualAudioEngine.Source.A) tvPlayerSongA else tvPlayerSongB
        
        // More dramatic animation: Scale down to 0.5, Rotate, and longer duration
        container.animate()
            .scaleX(0.5f)
            .scaleY(0.5f)
            .alpha(0f)
            .rotation(180f)
            .setDuration(300)
            .withEndAction {
                 // Update Logic safely
                if (source == DualAudioEngine.Source.A) {
                    currentIndexA = index
                    tvPlayerSongA.text = song.title
                    audioService?.audioEngine?.setSourceA(song.uri)
                } else {
                    currentIndexB = index
                    tvPlayerSongB.text = song.title
                    audioService?.audioEngine?.setSourceB(song.uri)
                }
                
                // Reset rotation for the "In" animation
                container.rotation = -180f
                
                // Animate In: Overshoot for bounce effect
                container.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .rotation(0f)
                    .setDuration(400)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
                    
                if (audioService?.isPlaying() != true) {
                     audioService?.play()
                }
                
                // Explicitly resume the specific channel because it might have been auto-paused by completion
                if (source == DualAudioEngine.Source.A) {
                     audioService?.resumeA()
                } else {
                     audioService?.resumeB()
                }
                
                updateButtonState()
            }.start()
        
        // Text cross-fade
        textView.animate().alpha(0f).translationY(20f).setDuration(300).withEndAction {
            textView.translationY = -20f
            textView.animate().alpha(1f).translationY(0f).setDuration(300).start()
        }.start()
    }
    


    private fun showSameModeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_source_switch, null)
        val btnSourceA = dialogView.findViewById<View>(R.id.btnSourceA)
        val btnSourceB = dialogView.findViewById<View>(R.id.btnSourceB)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseSwitch)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
            
        btnSourceA.setOnClickListener {
            handleSourceSwitch(0)
            dialog.dismiss()
        }
        
        btnSourceB.setOnClickListener {
            handleSourceSwitch(1)
            dialog.dismiss()
        }
        
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.attributes?.windowAnimations = R.style.PlaylistDialogAnimation
        dialog.show()
    }

    private fun handleSourceSwitch(which: Int) {
        val engine = audioService?.audioEngine ?: return
        engine.mode = DualAudioEngine.Mode.SAME

        if (which == 1) {
             // Selected Right Earbud (Player B) -> Becomes Single Player
             if (currentIndexB != -1 && allSongs.isNotEmpty()) {
                 val song = allSongs[currentIndexB]
                 engine.setSourceA(song.uri)
                 currentIndexA = currentIndexB
                 tvPlayerSongA.text = song.title
             } else if (engine.decoderB?.uri != null && engine.decoderB!!.uri != android.net.Uri.EMPTY) {
                 engine.setSourceA(engine.decoderB!!.uri)
                 tvPlayerSongA.text = tvPlayerSongB.text
             }
        }
        
        engine.primarySource = DualAudioEngine.Source.A
        
        val sourceName = if (which == 0) "Player A" else "Player B (Moved to Main)"
        Toast.makeText(this, "Playing $sourceName on both ears", Toast.LENGTH_SHORT).show()

        val playerA = findViewById<View>(R.id.playerContainerA)
        val playerB = findViewById<View>(R.id.playerContainerB)
        
        playerA.visibility = View.VISIBLE
        playerB.visibility = View.GONE
        
        val params = playerA.layoutParams as LinearLayout.LayoutParams
        params.weight = 2f
        playerA.layoutParams = params
        
        findViewById<ImageView>(R.id.btnSplitToggle).setImageResource(R.drawable.ic_split_mode)
        findViewById<ImageView>(R.id.btnSwap).visibility = View.GONE
        
        if (audioService?.isPlaying() == true) {
            audioService?.play()
        }
        updateButtonState()
        
        audioService?.pauseB()
        currentIndexB = -1
    }

    private fun updateButtonState() {
        val playingA = audioService?.isPlayingA() == true
        val playingB = audioService?.isPlayingB() == true
        
        val resA = if (playingA) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val resB = if (playingB) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        
        btnPlayerPlayA.setImageResource(resA)
        btnPlayerPlayB.setImageResource(resB)
        
        // Control Animations
        controlVinylAnimation(vinylAnimatorA, playingA)
        controlVinylAnimation(vinylAnimatorB, playingB)
        
        startVisualizer(findViewById(R.id.visualizerContainerA), visualizerAnimatorsA, playingA)
        startVisualizer(findViewById(R.id.visualizerContainerB), visualizerAnimatorsB, playingB)
        
        // Update Mini Player
        val layoutMini = findViewById<View>(R.id.layoutMiniPlayer)
        val hasSourceLoaded = currentIndexA != -1 || currentIndexB != -1 // Simple check if any song is active
        val shouldShowMiniPlayer = !isMiniPlayerClosed && hasSourceLoaded
        
        if (shouldShowMiniPlayer) {
            layoutMini?.visibility = View.VISIBLE
            // Update Icon
             val miniPlayBtn = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnMiniPlay)
             val anyPlaying = playingA || playingB
             val iconRes = if (anyPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
             miniPlayBtn?.setImageResource(iconRes)
             
             // Update Text
            val tvMiniTitle = findViewById<TextView>(R.id.tvMiniTitle)
            val tvMiniArtist = findViewById<TextView>(R.id.tvMiniArtist)
            
            val isSplit = audioService?.audioEngine?.mode == DualAudioEngine.Mode.SPLIT
            
            if (isSplit) {
                 // Split Mode: Show info for both
                 // We need safe access to titles. tvPlayerSongA/B are unreliable if we are not on the player screen? 
                 // Actually they are member variables updated in playSongAt, so they should be current.
                 val textA = tvPlayerSongA.text.toString()
                 val textB = tvPlayerSongB.text.toString()
                 
                 // Show concise split info
                 tvMiniTitle?.text = "L: ${textA} | R: ${textB}"
                 tvMiniArtist?.text = "Split Mode Active"
            } else {
                 // Stereo Mode (A is master usually)
                 // Use current song from list if possible, or just the text view
                 tvMiniTitle?.text = tvPlayerSongA.text
                 tvMiniArtist?.text = "Stereo Mode"
            }
        } else {
             layoutMini?.visibility = View.GONE
        }
        
        // Update System Notification
        audioService?.updateNotification(
            titleA = tvPlayerSongA.text.toString(),
            titleB = tvPlayerSongB.text.toString(),
            isPlayingA = playingA,
            isPlayingB = playingB,
            mode = audioService?.audioEngine?.mode ?: DualAudioEngine.Mode.SAME
        )
    }

    private fun startProgressUpdater() {
        updateJob?.cancel()
        updateJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                if (isBound && audioService?.isPlaying() == true) {
                    val engine = audioService?.audioEngine
                    val progressA = audioService?.getProgressA() ?: 0
                    val durationA = audioService?.getDurationA() ?: 1
                    val progressB = engine?.getProgressB() ?: 0
                    val durationB = engine?.getDurationB() ?: 1
                    
                    val pbA = findViewById<ProgressBar>(R.id.progressBarA)
                    val sbA = findViewById<SeekBar>(R.id.seekBarProgressA)
                    val tvA = findViewById<TextView>(R.id.tvPlayerDurationA)
                    
                    // Mini Player Progress
                    val pbMini = findViewById<ProgressBar>(R.id.miniProgressBar)
                    if (pbMini != null && durationA > 0) {
                        pbMini.max = durationA.toInt()
                        pbMini.progress = progressA.toInt()
                    }
                    
                    if (pbA != null && durationA > 0) {
                        pbA.max = durationA.toInt()
                        pbA.progress = progressA.toInt()
                        sbA?.max = durationA.toInt()
                        sbA?.progress = progressA.toInt()
                        tvA?.text = "${formatTime(progressA)} / ${formatTime(durationA)}"
                    }
                    
                    val pbB = findViewById<ProgressBar>(R.id.progressBarB)
                    val sbB = findViewById<SeekBar>(R.id.seekBarProgressB) // NEW
                    val tvB = findViewById<TextView>(R.id.tvPlayerDurationB)
                    
                    if (pbB != null && durationB > 0) {
                        pbB.max = durationB.toInt()
                        pbB.progress = progressB.toInt()
                        sbB?.max = durationB.toInt()
                        sbB?.progress = progressB.toInt()
                        tvB?.text = "${formatTime(progressB)} / ${formatTime(durationB)}"
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun getFileName(uri: android.net.Uri): String {
        var result: String? = null
        if (uri.scheme ==ContentResolver.SCHEME_CONTENT) {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = it.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1 && cut != null) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "Unknown Song"
    }

    private fun loadSongsFromFolder(uri: android.net.Uri) {
         CoroutineScope(Dispatchers.IO).launch {
             val docFile = DocumentFile.fromTreeUri(applicationContext, uri)
             val newSongs = mutableListOf<Song>()
             if (docFile != null && docFile.isDirectory) {
                 docFile.listFiles().forEach { file ->
                     if (file.type?.startsWith("audio/") == true) {
                         val id = System.currentTimeMillis() + newSongs.size
                         val title = file.name ?: "Unknown Song"
                         val artist = "Imported"
                         val duration = 0L 
                         newSongs.add(Song(id, title, artist, duration, file.uri))
                     }
                 }
             }
             withContext(Dispatchers.Main) {
                 val currentList = allSongs.toMutableList()
                 currentList.addAll(newSongs)
                 allSongs = currentList
                 if (::songAdapter.isInitialized) {
                    songAdapter.submitList(allSongs)
                 }
                 Toast.makeText(this@MainActivity, "Added ${newSongs.size} songs from folder", Toast.LENGTH_SHORT).show()
             }
         }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        updateJob?.cancel()
    }
    private fun requestAllFilesAccess() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Permission Required")
        builder.setMessage("To access music in custom folders, this app needs 'All Files Access'. Please grant this permission in the next screen.")
        builder.setPositiveButton("Grant") { _, _ ->
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = android.net.Uri.parse(String.format("package:%s", applicationContext.packageName))
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                startActivity(intent)
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
}
