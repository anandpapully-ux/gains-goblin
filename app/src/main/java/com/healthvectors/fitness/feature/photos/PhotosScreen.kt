package com.healthvectors.fitness.feature.photos

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.designsystem.*
import com.healthvectors.fitness.data.local.entity.ProgressPhotoEntity
import com.healthvectors.fitness.data.photos.PhotoRepository
import com.healthvectors.fitness.domain.logic.PhotoLogic
import com.healthvectors.fitness.domain.model.PhotoPose
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class PhotosViewModel @Inject constructor(private val repo: PhotoRepository) : ViewModel() {
    val photos: StateFlow<List<ProgressPhotoEntity>?> = repo.photos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val filter = MutableStateFlow<PhotoPose?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun canCompare(l: List<ProgressPhotoEntity>) = PhotoLogic.defaultPair(l.map { PhotoLogic.Shot(it.id, it.takenOn, it.pose, it.weightKg) }) != null

    fun export(uri: Uri) = viewModelScope.launch {
        messages.emit(runCatching { repo.exportZip(uri) }.fold({ "Exported $it ${if (it == 1) "photo" else "photos"}" }, { "Export failed: ${it.message}" }))
    }

    fun import(uri: Uri) = viewModelScope.launch {
        messages.emit(runCatching { repo.importZip(uri) }.fold({ if (it == 0) "No new photos in that file" else "Imported $it ${if (it == 1) "photo" else "photos"}" }, { "Import failed: ${it.message}" }))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(
    startAdding: Boolean, onBack: () -> Unit, onOpen: (Long) -> Unit, onCompare: () -> Unit,
    vm: PhotosViewModel = hiltViewModel(), addVm: AddPhotoViewModel = hiltViewModel()
) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(startAdding) }
    var menu by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { merge(vm.messages, addVm.messages).collect { snack.showSnackbar(it) } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::export) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val all = photos.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progress photos") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onCompare, enabled = vm.canCompare(all)) { Icon(Icons.Outlined.Compare, "Compare photos") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Export all (ZIP)") }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null) }, enabled = all.isNotEmpty(),
                                onClick = { menu = false; export.launch("progress-photos-${LocalDate.now()}.zip") }
                            )
                            DropdownMenuItem(
                                text = { Text("Import from ZIP") }, leadingIcon = { Icon(Icons.Outlined.FileUpload, null) },
                                onClick = { menu = false; import.launch(arrayOf("application/zip", "application/octet-stream")) }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.AddAPhoto, null) }, text = { Text("Add photo") })
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        when {
            photos == null -> Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            all.isEmpty() -> Column(Modifier.padding(pad)) {
                EmptyState(
                    Icons.Outlined.PhotoCamera, "See the change the scale misses",
                    "Take front, side and back photos every couple of weeks, then compare any two dates side by side."
                ) { Button(onClick = { adding = true }) { Icon(Icons.Outlined.AddAPhoto, null); Spacer(Modifier.width(8.dp)); Text("Take your first photos") } }
                Tips(Modifier.padding(horizontal = 16.dp))
            }
            else -> {
                val shown = all.filter { filter == null || it.pose == filter }
                val byMonth = shown.groupBy { YearMonth.from(it.takenOn) }
                LazyVerticalGrid(
                    GridCells.Adaptive(104.dp), Modifier.padding(pad),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(filter == null, { vm.filter.value = null }, { Text("All · ${all.size}") })
                            PhotoPose.entries.forEach { p ->
                                val n = all.count { it.pose == p }
                                FilterChip(filter == p, { vm.filter.value = if (filter == p) null else p }, { Text("${p.label} · $n") }, enabled = n > 0)
                            }
                        }
                    }
                    if (all.size < 3) item(span = { GridItemSpan(maxLineSpan) }) { Tips(Modifier.padding(vertical = 8.dp)) }
                    byMonth.forEach { (month, list) ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "m$month") {
                            SectionTitle(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.padding(top = 4.dp))
                        }
                        items(list, key = { it.id }) { p -> PhotoTile(p) { onOpen(p.id) } }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "Encrypted on this phone and not in your gallery. Photos are not part of the full backup in Profile; " +
                                    "use Export all (ZIP) to keep a copy or move them to a new phone.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    AddPhotoFlow(addVm, open = adding, onClose = { adding = false })
}

@Composable
private fun Tips(modifier: Modifier = Modifier) {
    AppCard(modifier, padding = 16.dp) {
        Text("For photos you can compare", style = MaterialTheme.typography.titleSmall)
        listOf(
            "Same spot, same light, same time of day (mornings before eating work well)",
            "Phone at chest height, about 2 m away, whole body in frame",
            "One front, one side and one back photo each time",
            "Every 2 to 4 weeks is plenty; changes are slow day to day"
        ).forEach { t ->
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(t, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
