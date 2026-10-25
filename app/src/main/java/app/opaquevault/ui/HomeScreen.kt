package app.opaquevault.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.opaquevault.BuildConfig
import app.opaquevault.VaultController
import app.opaquevault.vault.VaultEntry
import app.opaquevault.vault.isVideo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: VaultController.UiState,
    controller: VaultController,
    snackbarHostState: SnackbarHostState,
    onAddFromPhotoPicker: () -> Unit,
    onAddFromFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    onExport: (List<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var showAddSheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val selectionMode = selected.isNotEmpty()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(state.entries) {
        val ids = state.entries.mapTo(HashSet()) { it.id }
        selected = selected.filterTo(HashSet()) { it in ids }
    }
    BackHandler(enabled = selectionMode || searchActive) {
        if (selectionMode) selected = emptySet() else {
            searchActive = false
            searchQuery = ""
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { selected = state.entries.mapTo(HashSet()) { it.id } }) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = {
                            val ids = selected.toList()
                            selected = emptySet()
                            onExport(ids)
                        }) {
                            Icon(Icons.Rounded.Download, contentDescription = "Export selected")
                        }
                        IconButton(onClick = {
                            if (state.settings.confirmVaultDeletion) {
                                confirmDelete = true
                            } else {
                                val ids = selected
                                selected = emptySet()
                                scope.launch { controller.deleteEntries(ids) }
                            }
                        }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete selected")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            } else if (searchActive) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { searchActive = false; searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close search")
                        }
                    },
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it.take(80) },
                            placeholder = { Text("Search photos and videos") },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Rounded.Close, "Clear search") } }
                            } else null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                        )
                    },
                    actions = {
                        IconButton(onClick = controller::lock) {
                            Icon(Icons.Rounded.Lock, contentDescription = "Lock vault")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(state.settings.homeTitle, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${state.entries.size} encrypted item${if (state.entries.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search vault")
                        }
                        IconButton(onClick = controller::lock) {
                            Icon(Icons.Rounded.Lock, contentDescription = "Lock vault")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode && !searchActive) {
                ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    icon = { Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null) },
                    text = { Text("Add media") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val sortedEntries = remember(state.entries, state.settings.sortOrder) {
            when (state.settings.sortOrder) {
                app.opaquevault.security.SortOrder.NEWEST -> state.entries.sortedByDescending { it.importedAtEpochMillis }
                app.opaquevault.security.SortOrder.OLDEST -> state.entries.sortedBy { it.importedAtEpochMillis }
                app.opaquevault.security.SortOrder.NAME -> state.entries.sortedBy { it.displayName.lowercase() }
                app.opaquevault.security.SortOrder.LARGEST -> state.entries.sortedByDescending { it.plaintextSize }
            }
        }
        val visibleEntries = remember(sortedEntries, searchQuery) {
            val query = searchQuery.trim().lowercase()
            if (query.isEmpty()) sortedEntries else sortedEntries.filter { entry ->
                entry.displayName.lowercase().contains(query) ||
                    entry.mimeType.lowercase().contains(query) ||
                    (entry.isVideo() && "video movie clip".contains(query)) ||
                    (!entry.isVideo() && "photo image picture".contains(query))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = state.settings.gridSize.minCellDp.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.corruptedObjectCount > 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    InfoBanner(
                        icon = Icons.Rounded.GppMaybe,
                        title = "Tampering detected",
                        body = "${state.corruptedObjectCount} encrypted item${if (state.corruptedObjectCount == 1) " was" else "s were"} " +
                            "hidden because ${if (state.corruptedObjectCount == 1) "it" else "they"} failed authentication.",
                        isError = true,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            if (!BuildConfig.DEBUG) {
                state.environmentWarning?.let { warning ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        InfoBanner(Icons.Rounded.GppMaybe, "Device integrity warning", warning, isError = true, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
            if (state.entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        icon = Icons.Rounded.Shield,
                        title = "Your vault is empty",
                        body = "Tap Add media, or select photos and videos in Gallery → Share → Opaque Vault. " +
                            "After they're encrypted you can remove the originals from your phone.",
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
            } else if (visibleEntries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = "No matching media",
                        body = "Try a filename, extension, photo, image, video or movie.",
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
            } else {
                items(visibleEntries, key = VaultEntry::id) { entry ->
                    ThumbnailCell(
                        entry = entry,
                        controller = controller,
                        selectionMode = selectionMode,
                        isSelected = entry.id in selected,
                        onClick = {
                            if (selectionMode) {
                                selected = if (entry.id in selected) selected - entry.id else selected + entry.id
                            } else {
                                onOpenPhoto(entry.id)
                            }
                        },
                        onLongClick = { selected = selected + entry.id },
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AddMediaSheet(
            onDismiss = { showAddSheet = false },
            onPhotoPicker = {
                showAddSheet = false
                onAddFromPhotoPicker()
            },
            onFiles = {
                showAddSheet = false
                onAddFromFiles()
            },
        )
    }

    if (confirmDelete) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            title = { Text("Delete $count item${if (count == 1) "" else "s"} from the vault?") },
            text = { Text("Each item's encryption key is destroyed first, then its ciphertext. This can't be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = selected
                        confirmDelete = false
                        selected = emptySet()
                        scope.launch { controller.deleteEntries(ids) }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbnailCell(
    entry: VaultEntry,
    controller: VaultController,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumbnail by produceState<Bitmap?>(initialValue = controller.cachedThumbnail(entry.id), entry.id) {
        if (value == null) value = controller.loadThumbnail(entry.id)
    }
    val shape = RoundedCornerShape(if (isSelected) 20.dp else 10.dp)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(if (isSelected) 6.dp else 0.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = "Open ${entry.displayName}"),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumbnail
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = entry.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                if (entry.isVideo()) Icons.Rounded.VideoLibrary else Icons.Rounded.Image,
                contentDescription = entry.displayName,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.isVideo()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(
                    if (entry.durationMillis > 0L) formatDuration(entry.durationMillis) else "Video",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (selectionMode) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.25f))
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMediaSheet(onDismiss: () -> Unit, onPhotoPicker: () -> Unit, onFiles: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Add photos and videos",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("Photo Picker") },
                supportingContent = { Text("Choose photos and videos. Originals can be removed afterwards.") },
                leadingContent = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onPhotoPicker),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("Files") },
                supportingContent = { Text("Browse folders, SD cards and USB drives for photos or videos.") },
                leadingContent = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onFiles),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("From Gallery") },
                supportingContent = { Text("Select photos or videos in Gallery → Share → Opaque Vault. Mixed selections work too.") },
                leadingContent = { Icon(Icons.Rounded.Share, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
