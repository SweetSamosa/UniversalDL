package com.sweetsamosa.universaldl.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.util.Date

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val cookiePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Manager.importCookies(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Manager.init(this)
        handleIncomingIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    MainScreen(onPickCookies = { cookiePicker.launch(arrayOf("*/*")) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(i: Intent?) {
        val intent = i ?: return
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            Regex("https?://\\S+").find(text)?.value?.let { Manager.setUrl(it) }
        } else if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            val type = intent.type ?: ""
            if (type.startsWith("audio/") || isAudioUri(uri, intent)) {
                val fileName = getFileNameFromUri(this, uri) ?: "External Audio"
                val item = DownloadedItem(
                    id = uri.toString().hashCode().toString(),
                    name = fileName,
                    path = uri.path ?: "",
                    uri = uri,
                    size = 0L,
                    dateAdded = System.currentTimeMillis(),
                    kind = Kind.AUDIO,
                    mimeType = type.ifBlank { "audio/mpeg" }
                )
                AudioPlayerController.play(this, item)
            }
        }
    }

    private fun isAudioUri(uri: android.net.Uri, intent: Intent): Boolean {
        val path = uri.path?.lowercase().orEmpty()
        val ext = path.substringAfterLast('.', "")
        return ext in setOf("mp3", "m4a", "flac", "wav", "opus", "aac", "ogg") || intent.type?.startsWith("audio/") == true
    }

    private fun getFileNameFromUri(context: android.content.Context, uri: android.net.Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) return cursor.getString(nameIndex)
                    }
                }
            }
        }
        return uri.lastPathSegment
    }
}

@Composable
fun MainScreen(onPickCookies: () -> Unit) {
    val s by Manager.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var activeViewerItem by remember { mutableStateOf<DownloadedItem?>(null) }
    val activeAudioItem by AudioPlayerController.currentItem.collectAsStateWithLifecycle()

    BackHandler(enabled = true) {
        when {
            activeViewerItem != null -> {
                activeViewerItem = null
            }
            s.currentTab != 0 -> {
                Manager.setTab(0)
            }
            activeAudioItem != null -> {
                AudioPlayerController.stop()
            }
            else -> {
                (ctx as? ComponentActivity)?.finish()
            }
        }
    }

    LaunchedEffect(s.downloadCompletedEvent) {
        if (s.downloadCompletedEvent > 0L) {
            android.widget.Toast.makeText(ctx, "🎉 Download Completed!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                AudioPlayerBottomBar()
                NavigationBar {
                    NavigationBarItem(
                        selected = s.currentTab == 0,
                        onClick = { Manager.setTab(0) },
                        icon = { Icon(if (s.currentTab == 0) Icons.Filled.Home else Icons.Outlined.Home, contentDescription = "Home") },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = s.currentTab == 1,
                        onClick = { Manager.setTab(1) },
                        icon = { Icon(if (s.currentTab == 1) Icons.Filled.ContentCut else Icons.Outlined.ContentCut, contentDescription = "Clip Cutter") },
                        label = { Text("Clip Cutter") }
                    )
                    NavigationBarItem(
                        selected = s.currentTab == 2,
                        onClick = {
                            Manager.setTab(2)
                            Manager.refreshDownloads()
                        },
                        icon = {
                            if (s.hasNewDownloads) {
                                BadgedBox(
                                    badge = { Badge() }
                                ) {
                                    Icon(if (s.currentTab == 2) Icons.Filled.Download else Icons.Outlined.Download, contentDescription = "Downloads")
                                }
                            } else {
                                Icon(if (s.currentTab == 2) Icons.Filled.Download else Icons.Outlined.Download, contentDescription = "Downloads")
                            }
                        },
                        label = { Text("Downloads") }
                    )
                    NavigationBarItem(
                        selected = s.currentTab == 3,
                        onClick = { Manager.setTab(3) },
                        icon = { Icon(if (s.currentTab == 3) Icons.Filled.Settings else Icons.Outlined.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (s.currentTab) {
                0 -> HomeScreen(s)
                1 -> ClipCutterScreen(s)
                2 -> DownloadsScreen(
                    items = s.downloads,
                    onViewItem = { activeViewerItem = it }
                )
                else -> SettingsScreen(s, onPickCookies)
            }

            activeViewerItem?.let { item ->
                when (item.kind) {
                    Kind.VIDEO -> VideoPlayerDialog(item = item, onDismiss = { activeViewerItem = null })
                    Kind.IMAGE -> PhotoViewerDialog(item = item, onDismiss = { activeViewerItem = null })
                    else -> {}
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(s: UiState) {
    val clipboard = LocalClipboardManager.current
    val o = s.opts

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // Hero Logo & Title Above
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Text(
                text = "Universal DL",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )

            Text(
                text = "Download Video, Audio & Images from any link",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(8.dp))

        // Paste Box in Middle
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = s.url,
                    onValueChange = Manager::setUrl,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste link here…") },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.url.isNotEmpty()) {
                                IconButton(onClick = { Manager.setUrl("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                }
                            }
                            IconButton(onClick = {
                                clipboard.getText()?.text?.let { Manager.setUrl(it) }
                            }) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = "Paste from clipboard")
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = Manager::fetch,
                        enabled = s.ready && !s.busy && s.url.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Fetch info")
                    }
                }
            }
        }

        // Format Selection Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Type & Format Options",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )

                // Type selector chips
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Kind.entries.forEach { kind ->
                        FilterChip(
                            selected = o.kind == kind,
                            onClick = { Manager.setOpts(o.copy(kind = kind)) },
                            label = { Text(kind.name) },
                            leadingIcon = {
                                val icon = when (kind) {
                                    Kind.VIDEO -> Icons.Filled.Movie
                                    Kind.AUDIO -> Icons.Filled.Audiotrack
                                    Kind.IMAGE -> Icons.Filled.Image
                                    Kind.GIF -> Icons.Filled.Gif
                                }
                                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Detailed formats based on Kind
                when (o.kind) {
                    Kind.VIDEO -> {
                        Text("Format Container", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("mp4", "mkv", "webm").forEach { fmt ->
                                FilterChip(
                                    selected = o.videoFormat == fmt,
                                    onClick = { Manager.setOpts(o.copy(videoFormat = fmt)) },
                                    label = { Text(fmt.uppercase()) }
                                )
                            }
                        }

                        Text("Max Resolution", style = MaterialTheme.typography.labelMedium)
                        val fetchedQualities = s.info?.availableQualities.orEmpty()
                        val qualityOptions = remember(fetchedQualities) {
                            val standardQualities = listOf(2160, 1440, 1080, 720, 480, 360)
                            val combined = (fetchedQualities + standardQualities).distinct().sortedDescending()
                            listOf("Best (Highest)" to 0) + combined.map { res ->
                                val label = when {
                                    res >= 2160 -> "4K (${res}p)"
                                    res >= 1440 -> "2K (${res}p)"
                                    else -> "${res}p"
                                }
                                label to res
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            qualityOptions.forEach { (label, res) ->
                                FilterChip(
                                    selected = o.maxHeight == res,
                                    onClick = { Manager.setOpts(o.copy(maxHeight = res)) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                    Kind.AUDIO -> {
                        Text("Audio Extraction Format", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("mp3", "m4a", "opus", "flac", "wav", "aac").forEach { fmt ->
                                FilterChip(
                                    selected = o.audioFormat == fmt,
                                    onClick = { Manager.setOpts(o.copy(audioFormat = fmt)) },
                                    label = { Text(fmt.uppercase()) }
                                )
                            }
                        }
                    }
                    Kind.GIF -> {
                        Text(
                            text = "Converts the first 15 seconds to a 480p, 12 fps GIF animation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Kind.IMAGE -> {
                        Text(
                            text = "Downloads photos & image galleries (Instagram, X, Pinterest, Reddit, Imgur) or direct image links.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Media Preview Card
        s.info?.let { info ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    info.thumbnail?.let { thumb ->
                        AsyncImage(
                            model = thumb,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Text(info.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val dur = if (info.seconds > 0) "%d:%02d".format(info.seconds / 60, info.seconds % 60) else ""
                    val subtitle = listOf(info.uploader, dur).filter { it.isNotEmpty() }.joinToString(" • ")
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Download Action Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (s.busy) {
                    if (s.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        )
                    }
                }

                Text(
                    text = s.status,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium
                )

                if (!s.busy && (s.status.contains("bot check", ignoreCase = true) || s.status.contains("cookies", ignoreCase = true))) {
                    OutlinedButton(
                        onClick = { Manager.setTab(2) },
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Settings to import cookies.txt / update engines", fontSize = 12.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (s.busy) {
                        OutlinedButton(
                            onClick = Manager::cancel,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Cancel")
                        }
                    } else {
                        Button(
                            onClick = Manager::download,
                            enabled = s.ready && s.url.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = if (s.isDownloadedForCurrentOpts) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) {
                            val buttonIcon = if (s.isDownloadedForCurrentOpts) Icons.Filled.CheckCircle else Icons.Filled.Download
                            val buttonText = if (s.isDownloadedForCurrentOpts) "Download Completed ✓" else "Download ${o.kind.name}"
                            Icon(buttonIcon, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(buttonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun DownloadsScreen(
    items: List<DownloadedItem>,
    onViewItem: (DownloadedItem) -> Unit
) {
    val ctx = LocalContext.current
    var selectedFilter by remember { mutableIntStateOf(0) } // 0 = All, 1 = Videos, 2 = Images, 3 = Audios
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedItemIds = remember { mutableStateListOf<String>() }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val filteredList = remember(items, selectedFilter) {
        when (selectedFilter) {
            1 -> items.filter { it.kind == Kind.VIDEO }
            2 -> items.filter { it.kind == Kind.IMAGE }
            3 -> items.filter { it.kind == Kind.AUDIO }
            else -> items
        }
    }

    val isAllSelected = remember(filteredList, selectedItemIds.size) {
        filteredList.isNotEmpty() && filteredList.all { it.id in selectedItemIds }
    }

    fun toggleSelectAll() {
        if (isAllSelected) {
            selectedItemIds.clear()
        } else {
            selectedItemIds.clear()
            selectedItemIds.addAll(filteredList.map { it.id })
        }
    }

    fun toggleItemSelection(item: DownloadedItem) {
        if (!isSelectionMode) {
            isSelectionMode = true
            selectedItemIds.add(item.id)
        } else {
            if (selectedItemIds.contains(item.id)) {
                selectedItemIds.remove(item.id)
                if (selectedItemIds.isEmpty()) {
                    isSelectionMode = false
                }
            } else {
                selectedItemIds.add(item.id)
            }
        }
    }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedItemIds.clear()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
    ) {
        if (isSelectionMode) {
            // Selection Mode Header Bar
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = isAllSelected,
                        onCheckedChange = { toggleSelectAll() }
                    )

                    Text(
                        text = if (isAllSelected) "Select All (${filteredList.size})" else "${selectedItemIds.size} Selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        enabled = selectedItemIds.isNotEmpty(),
                        onClick = {
                            val selectedItems = items.filter { it.id in selectedItemIds }
                            DownloadHistory.shareMultiple(ctx, selectedItems)
                        }
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Share Selected")
                    }

                    IconButton(
                        enabled = selectedItemIds.isNotEmpty(),
                        onClick = { showDeleteConfirmDialog = true }
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        } else {
            // Normal Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "My Downloads",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Section Tabs (All | Videos | Images | Audios)
        ScrollableTabRow(
            selectedTabIndex = selectedFilter,
            edgePadding = 0.dp,
            divider = {}
        ) {
            Tab(selected = selectedFilter == 0, onClick = { selectedFilter = 0 }) {
                Text("All (${items.size})", Modifier.padding(vertical = 10.dp, horizontal = 12.dp))
            }
            Tab(selected = selectedFilter == 1, onClick = { selectedFilter = 1 }) {
                Text("Videos (${items.count { it.kind == Kind.VIDEO }})", Modifier.padding(vertical = 10.dp, horizontal = 12.dp))
            }
            Tab(selected = selectedFilter == 2, onClick = { selectedFilter = 2 }) {
                Text("Images (${items.count { it.kind == Kind.IMAGE }})", Modifier.padding(vertical = 10.dp, horizontal = 12.dp))
            }
            Tab(selected = selectedFilter == 3, onClick = { selectedFilter = 3 }) {
                Text("Audios (${items.count { it.kind == Kind.AUDIO }})", Modifier.padding(vertical = 10.dp, horizontal = 12.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = when (selectedFilter) {
                            1 -> Icons.Outlined.Movie
                            2 -> Icons.Outlined.Image
                            3 -> Icons.Outlined.Audiotrack
                            else -> Icons.Outlined.FolderOpen
                        },
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = when (selectedFilter) {
                            1 -> "No videos downloaded yet"
                            2 -> "No images downloaded yet"
                            3 -> "No audio files downloaded yet"
                            else -> "No downloads found"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(filteredList, key = { it.id + it.path }) { item ->
                    DownloadItemCard(
                        item = item,
                        isSelected = selectedItemIds.contains(item.id),
                        isSelectionMode = isSelectionMode,
                        onClick = {
                            if (isSelectionMode) {
                                toggleItemSelection(item)
                            } else {
                                if (item.kind == Kind.AUDIO) {
                                    AudioPlayerController.play(ctx, item)
                                } else {
                                    onViewItem(item)
                                }
                            }
                        },
                        onLongClick = {
                            if (!isSelectionMode) {
                                isSelectionMode = true
                                selectedItemIds.clear()
                                selectedItemIds.add(item.id)
                            } else {
                                toggleItemSelection(item)
                            }
                        }
                    )
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Selected Files?") },
            text = { Text("Are you sure you want to delete ${selectedItemIds.size} file(s)? This action cannot be undone.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val selectedItems = items.filter { it.id in selectedItemIds }
                        DownloadHistory.deleteMultiple(ctx, selectedItems)
                        selectedItemIds.clear()
                        isSelectionMode = false
                        showDeleteConfirmDialog = false
                        Manager.refreshDownloads()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadItemCard(
    item: DownloadedItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val ctx = LocalContext.current
    val audioCoverArt by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = item.uri) {
        if (item.kind == Kind.AUDIO) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                getAudioCoverArt(ctx, item)
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = when (item.kind) {
                    Kind.VIDEO -> MaterialTheme.colorScheme.primaryContainer
                    Kind.IMAGE -> MaterialTheme.colorScheme.tertiaryContainer
                    Kind.AUDIO -> MaterialTheme.colorScheme.secondaryContainer
                    Kind.GIF -> MaterialTheme.colorScheme.surfaceTint
                },
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val coverBmp = audioCoverArt
                    if (coverBmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = coverBmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else if (item.kind == Kind.VIDEO || item.kind == Kind.IMAGE || item.kind == Kind.GIF) {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            error = rememberVectorPainter(
                                when (item.kind) {
                                    Kind.VIDEO -> Icons.Filled.Movie
                                    Kind.IMAGE -> Icons.Filled.Image
                                    Kind.GIF -> Icons.Filled.Gif
                                    else -> Icons.Filled.Audiotrack
                                }
                            )
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Audiotrack,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Distinct Extension/Format Badge (.MP4, .MP3, .FLAC, .JPG, .PNG, etc.)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when (item.kind) {
                            Kind.VIDEO -> MaterialTheme.colorScheme.primaryContainer
                            Kind.AUDIO -> MaterialTheme.colorScheme.secondaryContainer
                            Kind.IMAGE -> MaterialTheme.colorScheme.tertiaryContainer
                            Kind.GIF -> MaterialTheme.colorScheme.surfaceTint
                        }
                    ) {
                        Text(
                            text = item.extTag,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = when (item.kind) {
                                Kind.VIDEO -> MaterialTheme.colorScheme.onPrimaryContainer
                                Kind.AUDIO -> MaterialTheme.colorScheme.onSecondaryContainer
                                Kind.IMAGE -> MaterialTheme.colorScheme.onTertiaryContainer
                                Kind.GIF -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    // Distinct Resolution / Quality Badge (4K, 2K, 1080p, 720p, LOSSLESS)
                    item.qualityTag?.let { qTag ->
                        val badgeBg = when {
                            qTag == "4K" -> Color(0xFFFFB74D) // Amber Gold
                            qTag == "2K" -> Color(0xFFCE93D8) // Soft Purple
                            qTag == "1080p" -> Color(0xFF64B5F6) // Soft Blue
                            qTag == "720p" -> Color(0xFF4DB6AC) // Teal
                            qTag == "LOSSLESS" -> Color(0xFF81C784) // Green
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeBg
                        ) {
                            Text(
                                text = qTag,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = Color.Black
                            )
                        }
                    }

                    val dateStr = DateFormat.format("MMM dd, yyyy", Date(item.dateAdded)).toString()
                    Text(
                        text = "${formatFileSize(item.size)}  •  $dateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(s: UiState, onPickCookies: () -> Unit) {
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings & Engines",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Private / Age-Restricted Content",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (s.hasCookies) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Cookies Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                Text(
                    "Import a Netscape format cookies.txt exported from your desktop browser where you are logged in to access private or age-gated posts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onPickCookies,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (s.hasCookies) "Replace cookies.txt" else "Import cookies.txt", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (s.hasCookies) {
                        OutlinedButton(
                            onClick = { Manager.clearCookies(ctx) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Remove")
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Extractor Engines", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                if (s.ytVersion.isNotEmpty() || s.gdlVersion.isNotEmpty()) {
                    Text(
                        text = "• yt-dlp: ${s.ytVersion.ifBlank { "Installed" }}\n• gallery-dl: ${s.gdlVersion.ifBlank { "Installed" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    "Updating yt-dlp and gallery-dl fetches the newest releases and extractor fixes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (s.busy && s.engineStatus.contains("Updating")) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }

                if (s.engineStatus.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = s.engineStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Button(
                    onClick = Manager::updateEngine,
                    enabled = s.ready && !s.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.SystemUpdateAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (s.busy) "Updating Engines…" else "Update Engines (yt-dlp + gallery-dl)")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Save Location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("• Videos: Movies/UniversalDL", style = MaterialTheme.typography.bodySmall)
                Text("• Images: Pictures/UniversalDL", style = MaterialTheme.typography.bodySmall)
                Text("• Audios: Music/UniversalDL", style = MaterialTheme.typography.bodySmall)
                Text("• Downloads: Download/UniversalDL", style = MaterialTheme.typography.bodySmall)
            }
        }

        // AMOLED Synced Lyrics Mode Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("AMOLED Lyrics Display", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Use Pure Pitch Black (#000000) background for lyrics cards to save battery and maximize contrast on OLED/AMOLED screens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = s.isAmoledLyrics,
                    onCheckedChange = { Manager.toggleAmoledLyrics() }
                )
            }
        }

        // Developer & GitHub Credits Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = GithubIconVector,
                            contentDescription = "GitHub",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Developed by sweetsamosa",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = Color(0xFFE91E63),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Text(
                        text = "UniversalDL v1.1 • Open Source",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⭐ Enjoying UniversalDL? Give us a star on GitHub to support development!",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sweetsamosa/UniversalDL"))
                            ctx.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Star",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Star on GitHub", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sweetsamosa"))
                            ctx.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = GithubIconVector,
                            contentDescription = "GitHub Profile",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("sweetsamosa", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

val GithubIconVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "Github",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 26f,
        viewportHeight = 26f
    ).addGroup(
        translationX = 1f,
        translationY = 1f
    ).addPath(
        pathData = PathParser().parsePathString(
            "M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"
        ).toNodes(),
        fill = SolidColor(Color.White)
    ).build()
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return "%.1f %s".format(bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ClipCutterScreen(s: UiState) {
    val clipboard = LocalClipboardManager.current
    val cs = s.clipState
    val info = cs.info

    val scrollState = rememberScrollState()

    val startTotal = cs.startHours * 3600 + cs.startMinutes * 60 + cs.startSeconds
    val endTotal = cs.endHours * 3600 + cs.endMinutes * 60 + cs.endSeconds
    val clipDurationSec = (endTotal - startTotal).coerceAtLeast(0)
    val isValidRange = endTotal > startTotal

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Banner Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.ContentCut,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Clip Cutter & Trimmer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Paste YouTube or Twitch link & cut exact video section by timestamp.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // URL Input Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Video Link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = cs.url,
                    onValueChange = { Manager.setClipUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Paste YouTube, Twitch, or video URL") },
                    leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    trailingIcon = {
                        Row {
                            if (cs.url.isNotEmpty()) {
                                IconButton(onClick = { Manager.setClipUrl("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                }
                            }
                            IconButton(onClick = {
                                val clipText = clipboard.getText()?.text.orEmpty().trim()
                                if (clipText.isNotBlank()) Manager.setClipUrl(clipText)
                            }) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { Manager.fetchClipInfo() },
                        enabled = cs.url.isNotBlank() && !cs.busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (cs.busy) "Loading Info…" else "Fetch Clip Info")
                    }
                }
            }
        }

        // Preview Card (if fetched)
        info?.let { media ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    media.thumbnail?.let { thumb ->
                        AsyncImage(
                            model = thumb,
                            contentDescription = null,
                            modifier = Modifier
                                .size(80.dp, 60.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = media.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (media.uploader.isNotBlank()) {
                            Text(
                                text = media.uploader,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val h = media.seconds / 3600
                        val m = (media.seconds % 3600) / 60
                        val sSec = media.seconds % 60
                        val durStr = String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, sSec)
                        Text(
                            text = "Full Duration: $durStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Timestamp Trim Controls Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Select Trim Timestamps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                // Start Timestamp Row
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Start Time (HH : MM : SS)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TimeNumberField(
                            value = cs.startHours,
                            onValueChange = { Manager.updateClipState { copy(startHours = it) } },
                            label = "HH",
                            modifier = Modifier.weight(1f)
                        )
                        Text(":", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        TimeNumberField(
                            value = cs.startMinutes,
                            onValueChange = { Manager.updateClipState { copy(startMinutes = it) } },
                            label = "MM",
                            modifier = Modifier.weight(1f)
                        )
                        Text(":", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        TimeNumberField(
                            value = cs.startSeconds,
                            onValueChange = { Manager.updateClipState { copy(startSeconds = it) } },
                            label = "SS",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Quick adjustment presets for start time
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        AssistChip(
                            onClick = { Manager.updateClipState { copy(startHours = 0, startMinutes = 0, startSeconds = 0) } },
                            label = { Text("00:00:00") }
                        )
                        AssistChip(
                            onClick = {
                                val total = startTotal + 10
                                val h = total / 3600
                                val m = (total % 3600) / 60
                                val sec = total % 60
                                Manager.updateClipState { copy(startHours = h, startMinutes = m, startSeconds = sec) }
                            },
                            label = { Text("+10s") }
                        )
                        AssistChip(
                            onClick = {
                                val total = (startTotal - 10).coerceAtLeast(0)
                                val h = total / 3600
                                val m = (total % 3600) / 60
                                val sec = total % 60
                                Manager.updateClipState { copy(startHours = h, startMinutes = m, startSeconds = sec) }
                            },
                            label = { Text("-10s") }
                        )
                        AssistChip(
                            onClick = {
                                val total = startTotal + 60
                                val h = total / 3600
                                val m = (total % 3600) / 60
                                val sec = total % 60
                                Manager.updateClipState { copy(startHours = h, startMinutes = m, startSeconds = sec) }
                            },
                            label = { Text("+1m") }
                        )
                    }
                }

                HorizontalDivider()

                // End Timestamp Row
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("End Time (HH : MM : SS)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TimeNumberField(
                            value = cs.endHours,
                            onValueChange = { Manager.updateClipState { copy(endHours = it) } },
                            label = "HH",
                            modifier = Modifier.weight(1f)
                        )
                        Text(":", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        TimeNumberField(
                            value = cs.endMinutes,
                            onValueChange = { Manager.updateClipState { copy(endMinutes = it) } },
                            label = "MM",
                            modifier = Modifier.weight(1f)
                        )
                        Text(":", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        TimeNumberField(
                            value = cs.endSeconds,
                            onValueChange = { Manager.updateClipState { copy(endSeconds = it) } },
                            label = "SS",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Quick adjustment presets for end time
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        AssistChip(
                            onClick = {
                                val total = endTotal + 15
                                val h = total / 3600
                                val m = (total % 3600) / 60
                                val sec = total % 60
                                Manager.updateClipState { copy(endHours = h, endMinutes = m, endSeconds = sec) }
                            },
                            label = { Text("+15s") }
                        )
                        AssistChip(
                            onClick = {
                                val total = endTotal + 30
                                val h = total / 3600
                                val m = (total % 3600) / 60
                                val sec = total % 60
                                Manager.updateClipState { copy(endHours = h, endMinutes = m, endSeconds = sec) }
                            },
                            label = { Text("+30s") }
                        )
                        AssistChip(
                            onClick = {
                                val total = endTotal + 60
                                val h = total / 3600
                                val m = (total % 3600) / 60
                                val sec = total % 60
                                Manager.updateClipState { copy(endHours = h, endMinutes = m, endSeconds = sec) }
                            },
                            label = { Text("+1m") }
                        )
                        info?.seconds?.takeIf { it > 0 }?.let { fullSec ->
                            AssistChip(
                                onClick = {
                                    val h = fullSec / 3600
                                    val m = (fullSec % 3600) / 60
                                    val sec = fullSec % 60
                                    Manager.updateClipState { copy(endHours = h, endMinutes = m, endSeconds = sec) }
                                },
                                label = { Text("Full Length") }
                            )
                        }
                    }
                }

                // Selected Clip Duration Banner
                val hDur = clipDurationSec / 3600
                val mDur = (clipDurationSec % 3600) / 60
                val sDur = clipDurationSec % 60
                val clipDurStr = String.format(java.util.Locale.US, "%02d:%02d:%02d", hDur, mDur, sDur)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isValidRange) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isValidRange) "Clip Duration: $clipDurStr" else "⚠️ End time must be after Start time",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isValidRange) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Quality & Format Selection Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Quality & Format", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Text("Video Quality / Resolution:", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    val qualities = remember(info?.availableQualities) {
                        val base = mutableListOf<Pair<String, Int>>()
                        base.add("Best (1080p+)" to 0)
                        val fetched = info?.availableQualities.orEmpty()
                        val standard = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
                        val combined = (fetched + standard).distinct().sortedDescending()
                        for (q in combined) {
                            base.add("${q}p" to q)
                        }
                        base
                    }

                    qualities.forEach { (label, height) ->
                        FilterChip(
                            selected = !cs.isAudioOnly && cs.maxHeight == height,
                            onClick = {
                                Manager.updateClipState { copy(maxHeight = height, isAudioOnly = false) }
                            },
                            label = { Text(label) }
                        )
                    }

                    FilterChip(
                        selected = cs.isAudioOnly,
                        onClick = {
                            Manager.updateClipState { copy(isAudioOnly = true) }
                        },
                        label = { Text("🎵 Audio Only") }
                    )
                }

                Text("Output Container Format:", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (cs.isAudioOnly) {
                        listOf("mp3", "m4a", "opus", "wav").forEach { fmt ->
                            FilterChip(
                                selected = cs.videoFormat == fmt,
                                onClick = { Manager.updateClipState { copy(videoFormat = fmt) } },
                                label = { Text(fmt.uppercase()) }
                            )
                        }
                    } else {
                        listOf("mp4", "mkv", "webm").forEach { fmt ->
                            FilterChip(
                                selected = cs.videoFormat == fmt,
                                onClick = { Manager.updateClipState { copy(videoFormat = fmt) } },
                                label = { Text(fmt.uppercase()) }
                            )
                        }
                    }
                }
            }
        }

        // Action Button & Download Progress Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { Manager.downloadClip() },
                    enabled = cs.url.isNotBlank() && isValidRange && !cs.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.ContentCut, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (cs.busy) "Trimming Clip…" else "Cut & Download Clip")
                }

                if (cs.busy) {
                    if (cs.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { cs.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Button(
                        onClick = { Manager.cancelClip() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel Clip Download")
                    }
                }

                if (cs.status.isNotBlank()) {
                    Text(
                        text = cs.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun TimeNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var textState by remember { mutableStateOf(if (value == 0) "" else value.toString()) }

    LaunchedEffect(value) {
        val currentParsed = textState.toIntOrNull() ?: 0
        if (currentParsed != value) {
            textState = if (value == 0) "" else value.toString()
        }
    }

    OutlinedTextField(
        value = textState,
        onValueChange = { str ->
            val digits = str.filter { it.isDigit() }.take(2)
            textState = digits
            val parsed = digits.toIntOrNull() ?: 0
            onValueChange(parsed)
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        placeholder = { Text("00", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
