package com.healthvectors.fitness.feature.workout

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthvectors.fitness.core.util.Units
import com.healthvectors.fitness.data.datastore.AppSettings
import com.healthvectors.fitness.data.datastore.SettingsStore
import com.healthvectors.fitness.data.local.dao.RoutineExerciseWithExercise
import com.healthvectors.fitness.data.local.entity.WorkoutSetEntity
import com.healthvectors.fitness.data.repository.*
import com.healthvectors.fitness.domain.logic.*
import com.healthvectors.fitness.domain.model.DayType
import com.healthvectors.fitness.domain.model.ProgressionRule
import com.healthvectors.fitness.domain.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetRowUi(
    val index: Int, val weight: String, val reps: String, val rpe: String,
    val done: Boolean, val setId: Long?, val isPr: Boolean
)

data class BlockUi(
    val item: RoutineExerciseWithExercise, val rows: List<SetRowUi>,
    val previousText: String, val bestText: String, val suggestion: String, val showWeight: Boolean, val unitLabel: String
)

data class SessionUi(
    val title: String = "", val blocks: List<BlockUi> = emptyList(), val startedAt: Long = 0,
    val weightUnit: String = "kg", val loading: Boolean = true
)

data class RestState(val remaining: Int, val total: Int)

private data class ExerciseStatic(val previous: List<WorkoutSetEntity>, val best: String, val suggestion: Suggestion)
private data class Static(
    val title: String, val dayType: DayType, val startedAt: Long, val items: List<RoutineExerciseWithExercise>,
    val info: Map<Long, ExerciseStatic>, val unit: UnitSystem
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    saved: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val workouts: WorkoutRepository,
    private val routines: RoutineRepository,
    private val profiles: ProfileRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {
    val sessionId: Long = checkNotNull(saved["sessionId"])

    private val static = MutableStateFlow<Static?>(null)
    private val inputs = MutableStateFlow<Map<String, SetRowUi>>(emptyMap())
    private val extraSets = MutableStateFlow<Map<Long, Int>>(emptyMap())

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _rest = MutableStateFlow<RestState?>(null)
    val rest: StateFlow<RestState?> = _rest
    private var restJob: Job? = null

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    private val _celebrate = MutableStateFlow<String?>(null)
    val celebrate: StateFlow<String?> = _celebrate

    private val _finished = MutableStateFlow<FinishState?>(null)
    val finished: StateFlow<FinishState?> = _finished

    data class FinishState(val summary: SessionSummary?)

    val ui: StateFlow<SessionUi> = combine(static, workouts.sets(sessionId), inputs, extraSets) { st, sets, inp, extra ->
        if (st == null) SessionUi() else buildUi(st, sets, inp, extra)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionUi())

    init { viewModelScope.launch { load() } }

    private suspend fun load() {
        val session = workouts.session(sessionId).first() ?: return
        val dayId = session.routineDayId ?: return
        val day = routines.day(dayId).first()
        val items = routines.getItems(dayId)
        val profile = profiles.getProfile()
        val unit = profile?.unitSystem ?: UnitSystem.METRIC
        val info = items.associate { r ->
            val step = Units.stepKg(unit, r.exercise.equipment, r.exercise.incrementKg)
            val prev = workouts.lastSessionSets(r.exercise.id, sessionId)
            val repsBased = r.exercise.repUnit == "reps"
            val best = if (repsBased) {
                val b = prev.maxOfOrNull { OneRepMax.estimate(it.weightKg, it.reps) ?: 0.0 } ?: 0.0
                if (b > 0) "Last e1RM ${Units.fmtWeight(b, unit)}" else ""
            } else ""
            val sug = if (repsBased) Progression.suggest(
                prev.map { SetResult(it.weightKg, it.reps, null) },
                ProgressionTarget(r.item.targetSets, r.item.repMin, r.item.repMax, r.exercise.progressionRule), step
            ) else Suggestion.NoHistory
            r.exercise.id to ExerciseStatic(prev, best, sug)
        }
        static.value = Static(day?.title ?: "Workout", day?.type ?: DayType.HYPERTROPHY, session.startedAt, items, info, unit)
    }

    private fun key(itemId: Long, index: Int) = "$itemId:$index"

    private fun buildUi(st: Static, sets: List<WorkoutSetEntity>, inp: Map<String, SetRowUi>, extra: Map<Long, Int>): SessionUi {
        val unit = st.unit
        val blocks = st.items.map { r ->
            val info = st.info[r.exercise.id]
            val prev = info?.previous.orEmpty()
            val logged = sets.filter { it.exerciseId == r.exercise.id && !it.isWarmup }.sortedBy { it.setIndex }
            val repsBased = r.exercise.repUnit == "reps"
            val count = maxOf(r.item.targetSets + (extra[r.item.id] ?: 0), (logged.maxOfOrNull { it.setIndex } ?: -1) + 1)
            val lastLogged = logged.lastOrNull()
            val rows = (0 until count).map { i ->
                val done = logged.firstOrNull { it.setIndex == i }
                if (done != null) {
                    SetRowUi(i, Units.fmt(Units.weightToDisplay(done.weightKg, unit)), done.reps.toString(),
                        done.rpe?.let { Units.fmt(it) } ?: "", true, done.id, done.isPr)
                } else {
                    val edited = inp[key(r.item.id, i)]
                    val prevAt = prev.getOrNull(i) ?: prev.lastOrNull()
                    val defaultKg = when (val s = info?.suggestion) {
                        is Suggestion.IncreaseWeight -> if (lastLogged != null) lastLogged.weightKg else s.newWeightKg
                        is Suggestion.DecreaseWeight -> if (lastLogged != null) lastLogged.weightKg else s.newWeightKg
                        else -> lastLogged?.weightKg ?: prevAt?.weightKg ?: 0.0
                    }
                    val defaultReps = when {
                        lastLogged != null -> lastLogged.reps
                        info?.suggestion is Suggestion.IncreaseWeight -> r.item.repMin
                        prevAt != null -> prevAt.reps
                        else -> r.item.repMin
                    }
                    SetRowUi(
                        i, edited?.weight ?: Units.fmt(Units.weightToDisplay(defaultKg, unit)),
                        edited?.reps ?: defaultReps.toString(), edited?.rpe ?: "", false, null, false
                    )
                }
            }
            val prevText = if (prev.isEmpty()) "First time doing this one" else "Last time: " + prev.joinToString("  ") {
                if (repsBased) "${Units.fmt(Units.weightToDisplay(it.weightKg, unit))}×${it.reps}" else "${it.reps}"
            }
            val sugText = when (val s = info?.suggestion) {
                is Suggestion.IncreaseWeight -> "Overload: go up to ${Units.fmtWeight(s.newWeightKg, unit)}"
                is Suggestion.DecreaseWeight -> "Too heavy last time: try ${Units.fmtWeight(s.newWeightKg, unit)}"
                is Suggestion.KeepWeight -> "Keep the weight, aim for ${s.targetReps} reps"
                else -> ""
            }
            BlockUi(r, rows, prevText, info?.best.orEmpty(), sugText, repsBased, if (repsBased) "reps" else r.exercise.repUnit)
        }
        return SessionUi(st.title, blocks, st.startedAt, Units.weightLabel(unit), false)
    }

    fun edit(item: RoutineExerciseWithExercise, row: SetRowUi, weight: String? = null, reps: String? = null, rpe: String? = null) {
        inputs.update { m ->
            val k = key(item.item.id, row.index)
            val cur = m[k] ?: row
            m + (k to cur.copy(weight = weight ?: cur.weight, reps = reps ?: cur.reps, rpe = rpe ?: cur.rpe))
        }
    }

    fun addSet(item: RoutineExerciseWithExercise) =
        extraSets.update { it + (item.item.id to ((it[item.item.id] ?: 0) + 1)) }

    /** Logs a row (1 tap or swipe) and starts the rest timer; a second tap on a done row removes it. */
    fun toggle(block: BlockUi, row: SetRowUi) = viewModelScope.launch {
        if (row.done) { row.setId?.let { workouts.undoSet(it) }; return@launch }
        val unit = static.value?.unit ?: UnitSystem.METRIC
        val kg = if (block.showWeight) Units.weightFromDisplay(row.weight.toDoubleOrNull() ?: 0.0, unit) else 0.0
        val reps = row.reps.toDoubleOrNull()?.toInt() ?: 0
        if (reps <= 0) { _events.tryEmit("Enter a value first"); return@launch }
        val res = workouts.logSet(sessionId, block.item.exercise.id, row.index, kg, reps, row.rpe.toDoubleOrNull())
        inputs.update { it - key(block.item.item.id, row.index) }
        if (res.prs.isNotEmpty()) {
            val what = if (PrType.ESTIMATED_1RM in res.prs) "estimated 1RM" else "weight"
            _celebrate.value = "New PR on ${block.item.exercise.name}: $what!"
            viewModelScope.launch { delay(3500); _celebrate.value = null }
        }
        if (block.item.item.restMaxSec > 0) startRest(block.item.item.restMaxSec)
    }

    fun startRest(seconds: Int, total: Int = seconds) {
        restJob?.cancel()
        restJob = viewModelScope.launch {
            for (s in seconds downTo 1) { _rest.value = RestState(s, total); delay(1000) }
            _rest.value = null
            alert()
        }
    }
    fun addRest(delta: Int) { _rest.value?.let { startRest(it.remaining + delta, it.total + delta) } }
    fun skipRest() { restJob?.cancel(); _rest.value = null }

    private fun alert() {
        runCatching {
            val v = context.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 300), -1))
            if (settings.value.restSound) ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        }
    }

    /** Hands-free logging: "bench press, 60 kilos, 8 reps" fills the first open row of the best-matching exercise. */
    fun voice(text: String) = viewModelScope.launch {
        val parsed = VoiceCommandParser.parse(text)
        if (parsed == null) { _events.tryEmit("Didn't catch that: \"$text\""); return@launch }
        val blocks = ui.value.blocks
        val match = VoiceCommandParser.bestMatch(parsed.exerciseQuery, blocks.map { it.item.exercise.name })
        val block = blocks.firstOrNull { it.item.exercise.name == match }
        val row = block?.rows?.firstOrNull { !it.done }
        if (block == null || row == null) { _events.tryEmit("No open set for \"${parsed.exerciseQuery}\""); return@launch }
        val unit = static.value?.unit ?: UnitSystem.METRIC
        val w = parsed.weightKg?.let { Units.fmt(Units.weightToDisplay(it, unit)) } ?: row.weight
        val r = parsed.reps?.toString() ?: row.reps
        toggle(block, row.copy(weight = w, reps = r))
        _events.tryEmit("Logged ${block.item.exercise.name}: $w x $r")
    }

    fun finish() = viewModelScope.launch {
        val weight = profiles.latestWeightKg() ?: 70.0
        val met = if (static.value?.dayType == DayType.CARDIO) CalorieCalculator.MET_CARDIO_EASY else CalorieCalculator.MET_STRENGTH
        _finished.value = FinishState(workouts.finish(sessionId, weight, met))
    }

    fun discard(onDone: () -> Unit) = viewModelScope.launch { workouts.discard(sessionId); onDone() }
}
