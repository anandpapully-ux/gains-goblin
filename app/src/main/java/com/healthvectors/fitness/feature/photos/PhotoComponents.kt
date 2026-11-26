package com.healthvectors.fitness.feature.photos

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.designsystem.*
import com.healthvectors.fitness.core.util.Units
import com.healthvectors.fitness.data.local.dao.ProfileDao
import com.healthvectors.fitness.data.local.entity.ProgressPhotoEntity
import com.healthvectors.fitness.data.photos.PhotoRepository
import com.healthvectors.fitness.data.photos.PhotoStore
import com.healthvectors.fitness.domain.logic.PhotoLogic
import com.healthvectors.fitness.domain.model.PhotoPose
import com.healthvectors.fitness.domain.model.UnitSystem
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

// ---------------------------------------------------------------------------------------------------------------
// Loading encrypted images in Compose

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PhotoEntryPoint { fun store(): PhotoStore }

@Composable
private fun rememberPhotoStore(): PhotoStore {
    val ctx = LocalContext.current.applicationContext
    return remember { EntryPointAccessors.fromApplication(ctx, PhotoEntryPoint::class.java).store() }
}

/** A stored progress photo, decrypted off the main thread. Shows a quiet placeholder while it loads. */
@Composable
fun PhotoImage(
    fileName: String, modifier: Modifier = Modifier, thumb: Boolean = true, contentScale: ContentScale = ContentScale.Crop,
    description: String? = null, backdrop: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {
    val store = rememberPhotoStore()
    var bitmap by remember(fileName, thumb) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(fileName, thumb) { bitmap = store.load(fileName, thumb) }
    Box(modifier.background(backdrop), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = contentScale) }
            ?: Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.outline)
    }
}

val PhotoDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

fun weightText(kg: Double?, unit: UnitSystem) = kg?.let { Units.fmtWeight(it, unit) }

// ---------------------------------------------------------------------------------------------------------------
// Adding a photo: pick a source, process it, then confirm its details.

@HiltViewModel
class AddPhotoViewModel @Inject constructor(private val repo: PhotoRepository, private val profiles: ProfileDao) : ViewModel() {
    data class Draft(val pending: PhotoStore.Pending, val date: LocalDate, val pose: PhotoPose, val fromCamera: Boolean)

    private val _draft = MutableStateFlow<Draft?>(null)
    val draft: StateFlow<Draft?> = _draft
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val unit = profiles.observe().map { it?.unitSystem ?: UnitSystem.METRIC }.stateIn(viewModelScope, SharingStarted.Eagerly, UnitSystem.METRIC)
    private val shots = repo.photos.map { l -> l.map { PhotoLogic.Shot(it.id, it.takenOn, it.pose, it.weightKg) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun cameraUri(ctx: Context): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", repo.cameraTarget())

    fun process(uri: Uri, fromCamera: Boolean) = viewModelScope.launch {
        _busy.value = true
        val p = runCatching { repo.store.process(uri) }.getOrNull()
        if (fromCamera) repo.clearCameraTarget()
        _busy.value = false
        if (p == null) { messages.emit("That image couldn't be read. Try another one."); return@launch }
        val date = p.takenOn?.takeIf { !it.isAfter(LocalDate.now()) } ?: LocalDate.now()
        _draft.value = Draft(p, date, PhotoLogic.suggestPose(shots.value, date), fromCamera)
    }

    fun cameraCancelled() = repo.clearCameraTarget()

    suspend fun weightOn(date: LocalDate): Double? = profiles.weightNear(date)?.weightKg

    /** Saves the draft; [another] keeps the flow going for the next pose. */
    fun save(date: LocalDate, pose: PhotoPose, note: String, onSaved: (another: Boolean, fromCamera: Boolean) -> Unit, another: Boolean) = viewModelScope.launch {
        val d = _draft.value ?: return@launch
        _busy.value = true
        repo.add(d.pending, date, pose, note)
        _busy.value = false
        _draft.value = null
        messages.emit("Saved ${pose.label.lowercase()} photo")
        onSaved(another, d.fromCamera)
    }

    fun discard() { _draft.value = null }
}

/**
 * The add-photo flow: a source sheet (camera or gallery), then the details sheet. Mount it while [open] is true;
 * it calls [onClose] when finished or cancelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPhotoFlow(vm: AddPhotoViewModel, open: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val draft by vm.draft.collectAsState()
    val busy by vm.busy.collectAsState()
    val unit by vm.unit.collectAsState()
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    // Once the camera or gallery is open, the source sheet stays hidden until the flow closes.
    var launched by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (!open) launched = false }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) vm.process(uri, fromCamera = true) else { vm.cameraCancelled(); onClose() }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.process(uri, fromCamera = false) else onClose()
    }
    fun shoot() {
        cameraUri = vm.cameraUri(ctx)
        launched = true
        try { camera.launch(cameraUri!!) } catch (_: android.content.ActivityNotFoundException) {
            launched = false
            android.widget.Toast.makeText(ctx, "No camera app found. Choose a photo from your gallery instead.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun pick() { launched = true; gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    if (busy && draft == null) Dialog { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Spacer(Modifier.width(16.dp)); Text("Preparing photo…") } }

    val d = draft
    if (d != null) {
        PhotoDetailsSheet(
            title = "New photo", preview = d.pending.preview, initialDate = d.date, initialPose = d.pose, initialNote = "",
            unit = unit, weightOn = vm::weightOn, busy = busy,
            primary = "Save", secondary = if (d.fromCamera) "Save and take the next pose" else "Save and add another",
            onPrimary = { date, pose, note -> vm.save(date, pose, note, { _, _ -> onClose() }, another = false) },
            onSecondary = { date, pose, note -> vm.save(date, pose, note, { _, cam -> if (cam) shoot() else pick() }, another = true) },
            onDismiss = { vm.discard(); onClose() }
        )
    } else if (open && !busy && !launched) {
        ModalBottomSheet(onDismissRequest = onClose, modifier = Modifier.statusBarsPadding()) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add a progress photo", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Same spot, same light and the same time of day make photos easy to compare. Mornings before eating work well.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                    ActionTile(Icons.Outlined.PhotoCamera, "Take photo", "With your camera app", Modifier.weight(1f)) { shoot() }
                    ActionTile(Icons.Outlined.PhotoLibrary, "Choose photo", "From your gallery", Modifier.weight(1f)) { pick() }
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Photos are encrypted on this phone, location data is removed, and they never appear in your gallery.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun Dialog(content: @Composable () -> Unit) =
    androidx.compose.ui.window.Dialog(onDismissRequest = {}) { Surface(shape = MaterialTheme.shapes.large) { Box(Modifier.padding(24.dp)) { content() } } }

/** Date, pose and note for a new or existing photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailsSheet(
    title: String, preview: Bitmap?, previewName: String? = null, initialDate: LocalDate, initialPose: PhotoPose, initialNote: String,
    unit: UnitSystem, weightOn: suspend (LocalDate) -> Double?, busy: Boolean,
    primary: String, onPrimary: (LocalDate, PhotoPose, String) -> Unit,
    secondary: String? = null, onSecondary: ((LocalDate, PhotoPose, String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var date by remember { mutableStateOf(initialDate) }
    var pose by remember { mutableStateOf(initialPose) }
    var note by remember { mutableStateOf(initialNote) }
    var picking by remember { mutableStateOf(false) }
    var weight by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(date) { weight = weightOn(date) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val shape = MaterialTheme.shapes.medium
                val m = Modifier.width(112.dp).aspectRatio(3f / 4f).clip(shape)
                if (preview != null) Image(preview.asImageBitmap(), "Photo preview", m, contentScale = ContentScale.Crop)
                else if (previewName != null) PhotoImage(previewName, m)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(onClick = { picking = true }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Taken on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (date == LocalDate.now()) "Today" else date.format(PhotoDate), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.MonitorWeight, null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("Body weight", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(weightText(weight, unit) ?: "Not logged", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
            Text("Pose", style = MaterialTheme.typography.labelLarge)
            ChoiceRow(PhotoPose.entries, pose, { it.label }) { pose = it }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") }, placeholder = { Text("e.g. morning, fasted, relaxed") }, singleLine = true)
            Button(onClick = { onPrimary(date, pose, note) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(52.dp)) { Text(primary) }
            if (secondary != null && onSecondary != null) OutlinedButton(onClick = { onSecondary(date, pose, note) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(secondary) }
        }
    }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(LocalDate.now())
            }
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }
        ) { DatePicker(state) }
    }
}

/** A photo tile for grids and strips: the thumbnail with its date and pose along the bottom. */
@Composable
fun PhotoTile(p: ProgressPhotoEntity, modifier: Modifier = Modifier, selected: Boolean = false, showPose: Boolean = true, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Box(
        modifier.aspectRatio(3f / 4f).clip(shape).clickable(onClick = onClick)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary).padding(3.dp).clip(shape) else Modifier)
    ) {
        PhotoImage(p.fileName, Modifier.fillMaxSize(), description = "${p.pose.label} photo, ${p.takenOn.format(PhotoDate)}")
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                p.takenOn.format(DateTimeFormatter.ofPattern("d MMM")) + if (showPose) " · ${p.pose.label}" else "",
                style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White, maxLines = 1
            )
        }
    }
}
