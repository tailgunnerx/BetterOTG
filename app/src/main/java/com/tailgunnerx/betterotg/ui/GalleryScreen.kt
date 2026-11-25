package com.tailgunnerx.betterotg.ui

import android.graphics.Bitmap
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tailgunnerx.betterotg.media.GalleryItem
import com.tailgunnerx.betterotg.media.GalleryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: BetterOtgViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val allItems by viewModel.galleryItems.collectAsState()
    var tabIndex by remember { mutableIntStateOf(0) } // 0 = Images, 1 = Videos
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var activeItem by remember { mutableStateOf<GalleryItem?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshGallery() }

    val filtered = remember(allItems, tabIndex) {
        allItems.filter { if (tabIndex == 0) !it.isVideo else it.isVideo }
    }

    if (activeItem != null) {
        MediaViewerScreen(
            item = activeItem!!,
            onBack = { activeItem = null },
            onShare = { GalleryRepository.shareItem(context, activeItem!!) },
            onDelete = {
                viewModel.deleteGalleryItems(listOf(activeItem!!))
                activeItem = null
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedIds.isEmpty()) "BetterOTG Gallery"
                        else "${selectedIds.size} selected"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedIds.isNotEmpty()) selectedIds = emptySet() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (filtered.isNotEmpty()) {
                        IconButton(onClick = {
                            selectedIds = if (selectedIds.size == filtered.size) emptySet()
                            else filtered.map { it.id }.toSet()
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                        }
                    }
                    if (selectedIds.isNotEmpty()) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0; selectedIds = emptySet() },
                    text = { Text("Images (${allItems.count { !it.isVideo }})") }
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1; selectedIds = emptySet() },
                    text = { Text("Videos (${allItems.count { it.isVideo }})") }
                )
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (tabIndex == 0) "No snapshots saved yet" else "No recordings saved yet",
                        color = Color(0xFF94A3B8)
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    contentPadding = PaddingValues(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filtered, key = { it.id }) { item ->
                        val isSelected = item.id in selectedIds
                        GalleryThumbTile(
                            item = item,
                            selected = isSelected,
                            onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                                } else {
                                    activeItem = item
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val toDelete = filtered.filter { it.id in selectedIds }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${toDelete.size} item(s)?") },
            text = { Text("Selected files will be permanently removed from device storage.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteGalleryItems(toDelete)
                    selectedIds = emptySet()
                    confirmDelete = false
                }) { Text("Delete", color = Color(0xFFEF4444)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun GalleryThumbTile(item: GalleryItem, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id) {
        thumb = withContext(Dispatchers.IO) { GalleryRepository.loadThumbnail(context, item) }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E293B))
            .then(if (selected) Modifier.border(3.dp, Color(0xFF00E5A0), RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
    ) {
        thumb?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (item.isVideo) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000))
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White)
            }
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = Color(0xFF00E5A0),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaViewerScreen(
    item: GalleryItem,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.name, fontSize = 15.sp, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(padding)
        ) {
            if (item.isVideo) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val mc = MediaController(ctx)
                            mc.setAnchorView(this)
                            setMediaController(mc)
                            setVideoURI(item.uri)
                            start()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                var fullBmp by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(item.uri) {
                    fullBmp = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(item.uri)?.use {
                                android.graphics.BitmapFactory.decodeStream(it)
                            }
                        }.getOrNull()
                    }
                }
                fullBmp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
