package com.tuneai.audio

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tuneai.audio.nativebridge.NativeAudioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MPlayTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MPlayScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MPlayScreen() {
    val context = LocalContext.current
    val engine = remember { NativeAudioEngine().apply { initEngine() } }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var showDspSheet by remember { mutableStateOf(false) }
    var currentProfile by remember { mutableIntStateOf(NativeAudioEngine.PROFILE_STUDIO_FLAT) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
    }

    val player = remember { MediaPlayer() }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            tracks = scanLocalAudio(context)
            if (tracks.isNotEmpty()) {
                currentIndex = 0
            }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
        }
    }

    val currentTrack = tracks.getOrNull(currentIndex)

    LaunchedEffect(currentTrack?.id) {
        if (currentTrack == null) return@LaunchedEffect
        runCatching {
            player.reset()
            player.setDataSource(context, currentTrack.uri)
            player.prepare()
            durationMs = player.duration.toLong().coerceAtLeast(0L)
            positionMs = 0L
            errorMessage = null
            if (isPlaying) {
                player.start()
            }
            player.setOnCompletionListener {
                if (tracks.isNotEmpty()) {
                    currentIndex = (currentIndex + 1) % tracks.size
                    isPlaying = true
                }
            }
        }.onFailure { error ->
            errorMessage = error.message ?: "Unable to play ${currentTrack.title}"
        }
    }

    LaunchedEffect(isPlaying) {
        if (currentTrack == null) return@LaunchedEffect
        if (isPlaying) {
            if (!player.isPlaying) {
                player.start()
            }
        } else if (player.isPlaying) {
            player.pause()
        }
    }

    LaunchedEffect(currentTrack?.id) {
        while (isActive) {
            if (!isSeeking && currentTrack != null && player.isPlaying) {
                positionMs = player.currentPosition.toLong().coerceAtLeast(0L)
            }
            delay(100L)
        }
    }

    LaunchedEffect(currentProfile) {
        engine.setProfile(currentProfile)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "MPlay",
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp
                    )
                },
                actions = {
                    IconButton(onClick = { showDspSheet = true }) {
                        Icon(imageVector = Icons.Default.Equalizer, contentDescription = "DSP Profiles")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!permissionGranted) {
                PermissionRequestCard { permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO) }
            } else {
                LibrarySection(
                    tracks = tracks,
                    currentIndex = currentIndex,
                    onSelect = { index ->
                        currentIndex = index
                        isPlaying = true
                    }
                )
            }

            HardwareVisualizer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0D0F14)),
                engine = engine
            )

            PlaybackPanel(
                track = currentTrack,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                onPlayPause = { isPlaying = !isPlaying },
                onPrevious = {
                    if (tracks.isNotEmpty()) {
                        currentIndex = if (currentIndex - 1 < 0) tracks.lastIndex else currentIndex - 1
                        isPlaying = true
                    }
                },
                onNext = {
                    if (tracks.isNotEmpty()) {
                        currentIndex = (currentIndex + 1) % tracks.size
                        isPlaying = true
                    }
                },
                onSeek = { newPosition ->
                    if (currentTrack != null) {
                        positionMs = newPosition
                        player.seekTo(newPosition.toInt())
                    }
                },
                onSeekStart = { isSeeking = true },
                onSeekEnd = { isSeeking = false }
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
        }
    }

    if (showDspSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDspSheet = false },
            containerColor = Color(0xFF12131A)
        ) {
            DspProfilePanel(
                currentProfile = currentProfile,
                onSelect = { profile ->
                    currentProfile = profile
                }
            )
        }
    }
}

@Composable
private fun PermissionRequestCard(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF11131A))
            .border(1.dp, Color(0xFF1F2430), RoundedCornerShape(18.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Allow MPlay to scan your library",
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
        Text(
            text = "We only request READ_MEDIA_AUDIO to list local MP3 and WAV tracks.",
            color = Color(0xFFB8BECC),
            fontSize = 13.sp
        )
        Button(onClick = onRequest) {
            Text(text = "Grant Access")
        }
    }
}

@Composable
private fun LibrarySection(
    tracks: List<Track>,
    currentIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Library",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (tracks.isEmpty()) {
            Text(
                text = "No local MP3 or WAV files found.",
                color = Color(0xFF9CA3B5),
                fontSize = 13.sp
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            ) {
                itemsIndexed(tracks) { index, track ->
                    TrackRow(
                        track = track,
                        isActive = index == currentIndex,
                        onClick = { onSelect(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, isActive: Boolean, onClick: () -> Unit) {
    val highlight = if (isActive) Color(0xFF1D2637) else Color(0xFF11131A)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(highlight)
            .border(1.dp, Color(0xFF1F2430), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                color = Color(0xFF9AA3B8),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = formatDuration(track.durationMs),
            fontSize = 12.sp,
            color = Color(0xFF7C879E)
        )
    }
}

@Composable
private fun PlaybackPanel(
    track: Track?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit
) {
    val panelShape = RoundedCornerShape(24.dp)
    val glassOverlay = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = 0.14f),
            Color.White.copy(alpha = 0.05f)
        )
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(panelShape)
            .background(Color(0xFF10131B))
            .border(1.dp, Color.White.copy(alpha = 0.08f), panelShape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(glassOverlay)
                .padding(12.dp)
        ) {
            Column {
                Text(
                    text = track?.title ?: "Select a track",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track?.artist ?: "",
                    color = Color(0xFFA5ADC2),
                    fontSize = 12.sp
                )
            }
        }

        val nonZeroDuration = max(durationMs, 1L)
        Slider(
            value = positionMs.coerceIn(0L, nonZeroDuration).toFloat(),
            onValueChange = { newValue ->
                onSeekStart()
                onSeek(newValue.toLong())
            },
            onValueChangeFinished = { onSeekEnd() },
            valueRange = 0f..nonZeroDuration.toFloat()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = formatDuration(positionMs), fontSize = 12.sp, color = Color(0xFF9AA3B8))
            Text(text = formatDuration(durationMs), fontSize = 12.sp, color = Color(0xFF9AA3B8))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color(0xFFDDE3F0),
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                    contentDescription = "Play/Pause",
                    tint = Color(0xFFE7ECF6),
                    modifier = Modifier.size(64.dp)
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color(0xFFDDE3F0),
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}

@Composable
private fun DspProfilePanel(
    currentProfile: Int,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "DSP Profiles",
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp
        )
        ProfileOption(
            title = "Studio Flat",
            description = "Bit-perfect passthrough",
            selected = currentProfile == NativeAudioEngine.PROFILE_STUDIO_FLAT,
            onClick = { onSelect(NativeAudioEngine.PROFILE_STUDIO_FLAT) }
        )
        ProfileOption(
            title = "Vocal Presence",
            description = "Mid-frequency EQ boost",
            selected = currentProfile == NativeAudioEngine.PROFILE_VOCAL_PRESENCE,
            onClick = { onSelect(NativeAudioEngine.PROFILE_VOCAL_PRESENCE) }
        )
        ProfileOption(
            title = "Dynamic Punch",
            description = "Multi-band compression for wired headphones",
            selected = currentProfile == NativeAudioEngine.PROFILE_DYNAMIC_PUNCH,
            onClick = { onSelect(NativeAudioEngine.PROFILE_DYNAMIC_PUNCH) }
        )
    }
}

@Composable
private fun ProfileOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) Color(0xFF6F9BFF) else Color(0xFF1F2430)
    val background = if (selected) Color(0xFF1A2234) else Color(0xFF0F1218)
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = background,
            contentColor = Color(0xFFE7ECF6)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(text = description, fontSize = 12.sp, color = Color(0xFFA8B1C7))
        }
    }
}

private suspend fun scanLocalAudio(context: Context): List<Track> = withContext(Dispatchers.IO) {
    val resolver: ContentResolver = context.contentResolver
    val collection: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.MIME_TYPE
    )

    val selection = (
        "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.MIME_TYPE} IN (?, ?, ?)"
        )

    val selectionArgs = arrayOf(
        "audio/mpeg",
        "audio/wav",
        "audio/x-wav"
    )

    val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

    val tracks = mutableListOf<Track>()
    resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            tracks += Track(
                id = id,
                title = cursor.getString(titleCol) ?: "Unknown Title",
                artist = cursor.getString(artistCol) ?: "Unknown Artist",
                durationMs = cursor.getLong(durationCol).coerceAtLeast(0L),
                uri = ContentUris.withAppendedId(collection, id)
            )
        }
    }
    tracks
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = max(durationMs, 0L) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri
)

@Composable
private fun MPlayTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Color(0xFF6F9BFF),
        secondary = Color(0xFF6FD2FF),
        background = Color(0xFF090B10),
        surface = Color(0xFF11131A)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
