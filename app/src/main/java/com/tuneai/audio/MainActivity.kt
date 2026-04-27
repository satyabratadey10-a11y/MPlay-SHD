package com.tuneai.audio

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tuneai.audio.nativebridge.NativeAudioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AudioPlayerApp()
            }
        }
    }
}

private enum class Sender {
    USER,
    SYSTEM
}

private data class ChatMessage(
    val id: Long,
    val sender: Sender,
    val text: String
)

private data class AudioTrack(
    val title: String,
    val path: String,
    val contentUri: String,
    val durationMs: Long
)

private val supportedModels = listOf(
    "Gemini 3 Flash",
    "Gemini 2.5 Fast",
    "Qwen 3.5 (via Novita router)"
)
private const val CHAT_BUBBLE_WIDTH_FRACTION = 0.82f
private const val FFT_UPDATE_INTERVAL_MS = 16L
private const val MAX_CACHED_AUDIO_FILES = 20
private const val CACHE_MAX_AGE_HOURS = 12L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioPlayerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val engine = remember { NativeAudioEngine() }

    var queryText by rememberSaveable { mutableStateOf("") }
    var selectedModel by rememberSaveable { mutableStateOf(supportedModels.first()) }
    var isPlaying by rememberSaveable { mutableStateOf(false) }
    var selectedTrackIndex by rememberSaveable { mutableStateOf(-1) }
    var modelExpanded by remember { mutableStateOf(false) }
    var trackExpanded by remember { mutableStateOf(false) }
    val chatMessages = remember { mutableStateListOf<ChatMessage>() }
    val tracks = remember { mutableStateListOf<AudioTrack>() }

    LaunchedEffect(Unit) {
        engine.initEngine()
        chatMessages.add(
            ChatMessage(
                id = System.currentTimeMillis(),
                sender = Sender.SYSTEM,
                text = "Audio system is ready. Pick a model and track to begin."
            )
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch {
                val scanned = scanAudioFiles(context)
                tracks.clear()
                tracks.addAll(scanned)
                chatMessages.add(
                    ChatMessage(
                        id = System.currentTimeMillis(),
                        sender = Sender.SYSTEM,
                        text = "Scan complete: ${scanned.size} audio files available."
                    )
                )
                if (selectedTrackIndex !in scanned.indices) {
                    selectedTrackIndex = if (scanned.isNotEmpty()) 0 else -1
                }
            }
        } else {
            chatMessages.add(
                ChatMessage(
                    id = System.currentTimeMillis(),
                    sender = Sender.SYSTEM,
                    text = "READ_MEDIA_AUDIO permission is required for scanning .mp3/.wav files."
                )
            )
        }
    }

    fun startScan() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            scope.launch {
                val scanned = scanAudioFiles(context)
                tracks.clear()
                tracks.addAll(scanned)
                chatMessages.add(
                    ChatMessage(
                        id = System.currentTimeMillis(),
                        sender = Sender.SYSTEM,
                        text = "Scan complete: ${scanned.size} audio files available."
                    )
                )
                if (selectedTrackIndex !in scanned.indices) {
                    selectedTrackIndex = if (scanned.isNotEmpty()) 0 else -1
                }
            }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(16.dp))
                DrawerRow(title = "New Chat") {
                    queryText = ""
                    chatMessages.clear()
                    chatMessages.add(
                        ChatMessage(
                            id = System.currentTimeMillis(),
                            sender = Sender.SYSTEM,
                            text = "Started a new chat session."
                        )
                    )
                    scope.launch { drawerState.close() }
                }
                DrawerRow(title = "History") {
                    chatMessages.add(
                        ChatMessage(
                            id = System.currentTimeMillis(),
                            sender = Sender.SYSTEM,
                            text = "History loaded: ${chatMessages.count { it.sender == Sender.USER }} user queries."
                        )
                    )
                    scope.launch { drawerState.close() }
                }
                DrawerRow(title = "API Key Settings") {
                    chatMessages.add(
                        ChatMessage(
                            id = System.currentTimeMillis(),
                            sender = Sender.SYSTEM,
                            text = "API Key Settings opened. Configure your provider key here."
                        )
                    )
                    scope.launch { drawerState.close() }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text(text = "☰", fontSize = 26.sp)
                        }
                    },
                    title = { TurnItLogo() }
                )
            },
            bottomBar = {
                InputArea(
                    queryText = queryText,
                    onQueryChange = { queryText = it },
                    onSend = {
                        val trimmed = queryText.trim()
                        if (trimmed.isNotEmpty()) {
                            chatMessages.add(
                                ChatMessage(
                                    id = System.currentTimeMillis(),
                                    sender = Sender.USER,
                                    text = trimmed
                                )
                            )
                            chatMessages.add(
                                ChatMessage(
                                    id = System.currentTimeMillis() + 1,
                                    sender = Sender.SYSTEM,
                                    text = buildSystemResponse(
                                        query = trimmed,
                                        selectedModel = selectedModel,
                                        tracksCount = tracks.size,
                                        isPlaying = isPlaying,
                                        selectedTrackTitle = tracks.getOrNull(selectedTrackIndex)?.title
                                    )
                                )
                            )
                            queryText = ""
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModelSelector(
                    selectedModel = selectedModel,
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it },
                    onSelected = { selectedModel = it }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { trackExpanded = true }
                        ) {
                            val title = tracks.getOrNull(selectedTrackIndex)?.title ?: "Select audio track"
                            Text(text = title, textAlign = TextAlign.Start)
                        }
                        DropdownMenu(
                            expanded = trackExpanded,
                            onDismissRequest = { trackExpanded = false }
                        ) {
                            tracks.forEachIndexed { index, track ->
                                DropdownMenuItem(
                                    text = { Text(track.title) },
                                    onClick = {
                                        selectedTrackIndex = index
                                        trackExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Button(onClick = { startScan() }) {
                        Text("Scan")
                    }
                    Button(
                        onClick = {
                            val selected = tracks.getOrNull(selectedTrackIndex)
                            if (selected != null) {
                                scope.launch {
                                    val playablePath = if (selected.path.isNotBlank()) {
                                        selected.path
                                    } else {
                                        copyUriToCachePath(context, selected.contentUri, selected.title)
                                    }

                                    val ok = playablePath?.let { engine.play(it) } == true
                                    if (ok && selected.path.isBlank() && playablePath != null) {
                                        tracks[selectedTrackIndex] = selected.copy(path = playablePath)
                                    }
                                    isPlaying = ok
                                    chatMessages.add(
                                        ChatMessage(
                                            id = System.currentTimeMillis(),
                                            sender = Sender.SYSTEM,
                                            text = if (ok) {
                                                "Playing: ${selected.title}"
                                            } else {
                                                "Failed to play: ${selected.title}"
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    ) {
                        Text("Play")
                    }
                    Button(
                        onClick = {
                            engine.pause()
                            isPlaying = false
                            chatMessages.add(
                                ChatMessage(
                                    id = System.currentTimeMillis(),
                                    sender = Sender.SYSTEM,
                                    text = "Playback paused."
                                )
                            )
                        }
                    ) {
                        Text("Pause")
                    }
                }

                AudioVisualizer(
                    engine = engine,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .bg_glass_bubble(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                        .padding(10.dp),
                    config = VisualizerConfig()
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chatMessages, key = { it.id }) { message ->
                        ChatBubble(message = message)
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(title: String, onClick: () -> Unit) {
    TextButton(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        onClick = onClick
    ) {
        Text(text = title, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TurnItLogo() {
    val transition = rememberInfiniteTransition(label = "turnit_logo")
    val offset by transition.animateFloat(
        initialValue = -200f,
        targetValue = 200f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "logo_gradient"
    )
    Text(
        text = "TurnIt",
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        style = TextStyle(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFF3B30),
                    Color(0xFF34C759),
                    Color(0xFF0A84FF),
                    Color(0xFFFF3B30)
                ),
                start = Offset(offset, 0f),
                end = Offset(offset + 320f, 0f)
            )
        )
    )
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.sender == Sender.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Text(
            text = message.text,
            modifier = Modifier
                .fillMaxWidth(CHAT_BUBBLE_WIDTH_FRACTION)
                .bg_glass_bubble(
                    base = if (isUser) Color(0x552766FF) else Color(0x55FFFFFF)
                )
                .padding(12.dp),
            color = Color.White
        )
    }
}

@Composable
private fun ModelSelector(
    selectedModel: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Model", fontWeight = FontWeight.SemiBold)
        Box(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onExpandedChange(true) }
            ) {
                Text(selectedModel, modifier = Modifier.fillMaxWidth())
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) }
            ) {
                supportedModels.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            onSelected(model)
                            onExpandedChange(false)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InputArea(
    queryText: String,
    onQueryChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "input_border")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "input_rotation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .rotatingRgbNeonBorder(rotation)
                .background(Color(0x44141822))
                .padding(2.dp)
        ) {
            TextField(
                value = queryText,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Type your query") },
                singleLine = true
            )
        }
        Button(onClick = onSend) {
            Text("Send")
        }
    }
}

@Composable
private fun AudioVisualizer(
    engine: NativeAudioEngine,
    isPlaying: Boolean,
    modifier: Modifier,
    config: VisualizerConfig
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val fftData = remember {
        mutableStateListOf<Float>().apply {
            repeat(config.barCount) { add(0f) }
        }
    }

    LaunchedEffect(engine, isPlaying, lifecycleOwner) {
        var lastNativeSize = -1
        val indexMap = IntArray(config.barCount) { 0 }

        while (isActive) {
            val inForeground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!inForeground) {
                delay(250L)
                continue
            }

            if (isPlaying) {
                val native = engine.getFftData()
                if (native.isEmpty()) {
                    for (i in 0 until config.barCount) {
                        fftData[i] = 0f
                    }
                } else {
                    if (native.size != lastNativeSize) {
                        for (i in 0 until config.barCount) {
                            indexMap[i] = (i * native.size / config.barCount).coerceIn(0, native.lastIndex)
                        }
                        lastNativeSize = native.size
                    }
                    for (i in 0 until config.barCount) {
                        fftData[i] = native[indexMap[i]]
                    }
                }
            } else {
                for (i in 0 until config.barCount) {
                    fftData[i] = 0f
                }
            }
            delay(FFT_UPDATE_INTERVAL_MS)
        }
    }

    Canvas(modifier = modifier) {
        val barCount = config.barCount.coerceAtLeast(1)
        val gap = (size.width / barCount) * config.barGapRatio
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val minHeight = size.height * config.minBarHeight

        for (i in 0 until barCount) {
            val normalized = fftData.getOrNull(i)?.coerceIn(0f, 1f) ?: 0f
            val h = (minHeight + normalized * (size.height - minHeight)).coerceAtLeast(minHeight)
            val x = i * (barWidth + gap)
            drawRoundRect(
                brush = Brush.verticalGradient(config.gradientColors),
                topLeft = Offset(x, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(config.cornerRadius, config.cornerRadius)
            )
        }

        drawLine(
            color = Color(0x66FFFFFF),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = config.strokeWidth.toPx(),
            cap = StrokeCap.Round
        )
    }
}

private fun Modifier.bg_glass_bubble(base: Color): Modifier {
    return this
        .clip(RoundedCornerShape(18.dp))
        .background(base)
        .border(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.35f),
            shape = RoundedCornerShape(18.dp)
        )
}

private fun Modifier.rotatingRgbNeonBorder(rotation: Float): Modifier {
    return this.drawWithContent {
        drawContent()
        val strokePx = 3.dp.toPx()
        rotate(rotation, center) {
            drawRoundRect(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0xFFFF3B30),
                        Color(0xFF34C759),
                        Color(0xFF0A84FF),
                        Color(0xFFFF3B30)
                    )
                ),
                topLeft = Offset(strokePx / 2f, strokePx / 2f),
                size = Size(size.width - strokePx, size.height - strokePx),
                cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx()),
                style = Stroke(width = strokePx)
            )
        }
    }
}

private suspend fun scanAudioFiles(context: android.content.Context): List<AudioTrack> {
    return withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )

        val result = mutableListOf<AudioTrack>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val displayName = cursor.getString(nameIdx) ?: continue
                val duration = cursor.getLong(durationIdx)
                val mime = cursor.getString(mimeIdx).orEmpty().lowercase()
                val isSupported = displayName.lowercase().endsWith(".mp3") ||
                    displayName.lowercase().endsWith(".wav") ||
                    mime.contains("mpeg") ||
                    mime.contains("wav")

                if (isSupported) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    result.add(
                        AudioTrack(
                            title = displayName,
                            path = "",
                            contentUri = uri.toString(),
                            durationMs = duration
                        )
                    )
                }
            }
        }
        result
    }
}

private suspend fun copyUriToCachePath(
    context: android.content.Context,
    uriString: String,
    displayName: String
): String? = withContext(Dispatchers.IO) {
    cleanupAudioCache(context.cacheDir)
    val inputUri = Uri.parse(uriString)
    val extension = displayName.substringAfterLast('.', "audio")
    val output = File(context.cacheDir, "audio_${inputUri.lastPathSegment}_${System.currentTimeMillis()}.$extension")
    runCatching {
        context.contentResolver.openInputStream(inputUri)?.use { input ->
            output.outputStream().use { out ->
                input.copyTo(out)
            }
        } ?: return@withContext null
        output.absolutePath
    }.getOrNull()
}

private fun buildSystemResponse(
    query: String,
    selectedModel: String,
    tracksCount: Int,
    isPlaying: Boolean,
    selectedTrackTitle: String?
): String {
    val trackText = selectedTrackTitle ?: "no track selected"
    val playback = if (isPlaying) "playing" else "paused"
    return "Model: $selectedModel | Query: \"$query\" | Library: $tracksCount tracks | Track: $trackText | Playback: $playback"
}

private fun cleanupAudioCache(cacheDir: File) {
    val audioCacheFiles = cacheDir.listFiles()
        ?.filter { it.name.startsWith("audio_") }
        .orEmpty()
        .sortedByDescending { it.lastModified() }

    val keepLatest = MAX_CACHED_AUDIO_FILES
    val maxAgeMs = CACHE_MAX_AGE_HOURS * 60 * 60 * 1000L
    val now = System.currentTimeMillis()

    audioCacheFiles.forEachIndexed { index, file ->
        val isOld = now - file.lastModified() > maxAgeMs
        val exceedsCount = index >= keepLatest
        if (isOld || exceedsCount) {
            file.delete()
        }
    }
}
