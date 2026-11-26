package com.healthvectors.fitness.feature.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.designsystem.Pill
import com.healthvectors.fitness.data.local.dao.ProfileDao
import com.healthvectors.fitness.data.local.entity.ProgressPhotoEntity
import com.healthvectors.fitness.data.photos.PhotoRepository
import com.healthvectors.fitness.domain.model.PhotoPose
import com.healthvectors.fitness.domain.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(saved: SavedStateHandle, private val repo: PhotoRepository, private val profiles: ProfileDao) : ViewModel() {
    val startId: Long = checkNotNull(saved["id"])
    /** Oldest first, so swiping right moves forward in time. */
    val photos = repo.photos.map { it.reversed() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val unit = profiles.observe().map { it?.unitSystem ?: UnitSystem.METRIC }.stateIn(viewModelScope, SharingStarted.Eagerly, UnitSystem.METRIC)

    fun delete(p: ProgressPhotoEntity) = viewModelScope.launch { repo.delete(p) }
    fun update(p: ProgressPhotoEntity, date: LocalDate, pose: PhotoPose, note: String) = viewModelScope.launch { repo.update(p, date, pose, note) }
    suspend fun weightOn(date: LocalDate): Double? = profiles.weightNear(date)?.weightKg
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(onBack: () -> Unit, onCompare: (Long) -> Unit, vm: PhotoViewerViewModel = hiltViewModel()) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    val list = photos ?: return
    if (list.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val pager = rememberPagerState(initialPage = list.indexOfFirst { it.id == vm.startId }.coerceAtLeast(0)) { list.size }
    val current = list.getOrNull(pager.currentPage) ?: list.last()
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val first = list.filter { it.pose == current.pose }.minOf { it.takenOn }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { list[it].id }) { i ->
            PhotoImage(list[i].fileName, Modifier.fillMaxSize(), thumb = false, contentScale = ContentScale.Fit, backdrop = Color.Black,
                description = "${list[i].pose.label} photo, ${list[i].takenOn.format(PhotoDate)}")
        }
        TopAppBar(
            title = {
                Column {
                    Text(current.takenOn.format(PhotoDate))
                    Text("${pager.currentPage + 1} of ${list.size}", style = MaterialTheme.typography.labelMedium)
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { onCompare(current.id) }, enabled = list.count { it.pose == current.pose } >= 2) { Icon(Icons.Outlined.Compare, "Compare with another ${current.pose.label.lowercase()} photo") }
                IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, "Edit date, pose or note") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete photo") }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.45f), titleContentColor = Color.White,
                navigationIconContentColor = Color.White, actionIconContentColor = Color.White
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(current.pose.label)
                weightText(current.weightKg, unit)?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White) }
            }
            val days = ChronoUnit.DAYS.between(first, current.takenOn)
            Text(
                if (days == 0L) "Your first ${current.pose.label.lowercase()} photo" else "Day $days of your ${current.pose.label.lowercase()} photos",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f)
            )
            if (current.note.isNotBlank()) Text(current.note, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this photo?") },
        text = { Text("It is removed from this phone for good. Export your photos first if you want to keep a copy.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(current) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
    if (editing) PhotoDetailsSheet(
        title = "Edit photo", preview = null, previewName = current.fileName, initialDate = current.takenOn, initialPose = current.pose,
        initialNote = current.note, unit = unit, weightOn = vm::weightOn, busy = false,
        primary = "Save", onPrimary = { d, p, n -> vm.update(current, d, p, n); editing = false },
        onDismiss = { editing = false }
    )
}
