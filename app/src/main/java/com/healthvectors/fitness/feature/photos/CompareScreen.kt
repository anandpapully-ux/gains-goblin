package com.healthvectors.fitness.feature.photos

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.designsystem.*
import com.healthvectors.fitness.core.util.Units
import com.healthvectors.fitness.data.local.dao.ProfileDao
import com.healthvectors.fitness.data.local.entity.ProgressPhotoEntity
import com.healthvectors.fitness.data.photos.PhotoRepository
import com.healthvectors.fitness.domain.logic.PhotoLogic
import com.healthvectors.fitness.domain.model.PhotoPose
import com.healthvectors.fitness.domain.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class CompareViewModel @Inject constructor(saved: SavedStateHandle, private val repo: PhotoRepository, profiles: ProfileDao) : ViewModel() {
    private val anchor: Long? = saved.get<Long>("anchor")?.takeIf { it > 0 }
    val photos = repo.photos.map { it.sortedWith(compareBy({ p -> p.takenOn }, { p -> p.id })) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val unit = profiles.observe().map { it?.unitSystem ?: UnitSystem.METRIC }.stateIn(viewModelScope, SharingStarted.Eagerly, UnitSystem.METRIC)

    /** Pose, before id, after id. */
    val selection = MutableStateFlow<Triple<PhotoPose, Long, Long>?>(null)
    val sharing = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val l = photos.filterNotNull().first()
            selection.value = PhotoLogic.defaultPair(l.map { PhotoLogic.Shot(it.id, it.takenOn, it.pose, it.weightKg) }, anchor)
        }
    }

    fun choosePose(pose: PhotoPose) {
        val same = photos.value.orEmpty().filter { it.pose == pose }
        if (same.size >= 2) selection.value = Triple(pose, same.first().id, same.last().id)
    }

    fun setBefore(id: Long) = selection.update { it?.copy(second = id) }
    fun setAfter(id: Long) = selection.update { it?.copy(third = id) }

    /** Renders the shareable before/after image, then hands the file to [onReady]. */
    fun share(before: ProgressPhotoEntity, after: ProgressPhotoEntity, caption: String, onReady: (java.io.File) -> Unit) = viewModelScope.launch {
        sharing.value = true
        val f = repo.renderComparison(before, after, caption)
        sharing.value = false
        f?.let(onReady)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(onBack: () -> Unit, vm: CompareViewModel = hiltViewModel()) {
    val photos by vm.photos.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var slider by rememberSaveable { mutableStateOf(true) }
    val all = photos.orEmpty()
    val s = sel
    val before = all.firstOrNull { it.id == s?.second }
    val after = all.firstOrNull { it.id == s?.third }
    val days = if (before != null && after != null) ChronoUnit.DAYS.between(before.takenOn, after.takenOn) else 0
    val change = if (before?.weightKg != null && after?.weightKg != null) after.weightKg - before.weightKg else null

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Compare") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (sharing) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                else IconButton(onClick = {
                    if (before != null && after != null) vm.share(before, after, caption(days, change, unit)) { file ->
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                        val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(Intent.createChooser(send, "Share comparison"))
                    }
                }, enabled = before != null && after != null) { Icon(Icons.Outlined.Share, "Share comparison as an image") }
            }
        )
    }) { pad ->
        if (photos == null) return@Scaffold
        if (s == null || before == null || after == null) {
            EmptyState(
                Icons.Filled.UnfoldMore, "Nothing to compare yet",
                "Take at least two photos of the same pose on different days.", Modifier.padding(pad)
            )
            return@Scaffold
        }
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val poses = PhotoPose.entries.filter { p -> all.count { it.pose == p } >= 2 }
            if (poses.size > 1) ChoiceRow(poses, s.first, { it.label }, vm::choosePose)
            ChoiceRow(listOf(true, false), slider, { if (it) "Slider" else "Side by side" }) { slider = it }

            if (slider) SliderCompare(before, after)
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Before" to before, "After" to after).forEach { (label, p) ->
                    Box(Modifier.weight(1f).aspectRatio(3f / 4f).clip(MaterialTheme.shapes.medium)) {
                        PhotoImage(p.fileName, Modifier.fillMaxSize(), thumb = false, description = "$label, ${p.takenOn.format(PhotoDate)}")
                        DateTag(label, p, Modifier.align(Alignment.TopStart).padding(8.dp))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(if (days == 1L) "1 day" else "$days days", "Between photos", Modifier.weight(1f))
                StatTile(
                    change?.let { (if (it > 0) "+" else "") + Units.fmtWeight(it, unit) } ?: "–",
                    if (change == null) "Weight not logged" else "Weight change", Modifier.weight(1f)
                )
            }

            val same = all.filter { it.pose == s.first }
            Picker("Before", same, before.id, vm::setBefore)
            Picker("After", same, after.id, vm::setAfter)
        }
    }
}

private fun caption(days: Long, change: Double?, unit: UnitSystem): String =
    listOfNotNull("$days days apart", change?.let { (if (it > 0) "+" else "") + Units.fmtWeight(it, unit) }).joinToString(" · ")

@Composable
private fun DateTag(label: String, p: ProgressPhotoEntity, modifier: Modifier) =
    Pill("$label · ${p.takenOn.format(PhotoDate)}", modifier, container = Color.Black.copy(alpha = 0.6f), content = Color.White)

/** Both photos in one frame; drag the divider to reveal more of either. */
@Composable
private fun SliderCompare(before: ProgressPhotoEntity, after: ProgressPhotoEntity) {
    var frac by remember { mutableFloatStateOf(0.5f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val line = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(MaterialTheme.shapes.medium)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures { o -> if (size.width > 0) frac = (o.x / size.width).coerceIn(0f, 1f) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> if (size.width > 0) frac = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .semantics { contentDescription = "Before and after comparison; drag to move the divider" }
    ) {
        PhotoImage(after.fileName, Modifier.fillMaxSize(), thumb = false)
        PhotoImage(before.fileName, Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * frac) { this@drawWithContent.drawContent() } }, thumb = false)
        DateTag("Before", before, Modifier.align(Alignment.TopStart).padding(8.dp))
        DateTag("After", after, Modifier.align(Alignment.TopEnd).padding(8.dp))
        val x = with(androidx.compose.ui.platform.LocalDensity.current) { (size.width * frac).toDp() }
        Box(Modifier.fillMaxHeight().width(3.dp).offset(x = x - 1.5.dp).background(line))
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = x - 18.dp).size(36.dp).clip(CircleShape).background(line),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.UnfoldMore, null, Modifier.rotate(90f), tint = MaterialTheme.colorScheme.onPrimary) }
    }
}

@Composable
private fun Picker(label: String, photos: List<ProgressPhotoEntity>, selected: Long, onPick: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos, key = { it.id }) { p -> PhotoTile(p, Modifier.width(84.dp), selected = p.id == selected, showPose = false) { onPick(p.id) } }
        }
    }
}
