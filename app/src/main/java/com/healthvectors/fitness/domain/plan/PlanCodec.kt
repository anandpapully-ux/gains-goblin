package com.healthvectors.fitness.domain.plan

import com.healthvectors.fitness.domain.model.DayType
import com.healthvectors.fitness.domain.model.ProgressionRule
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads and writes shareable plan files.
 *
 * Current format (`"format": "gains-goblin-plan"`):
 * ```
 * { "format": "gains-goblin-plan", "version": 1, "name": "...", "description": "...",
 *   "days": [ { "weekday": 1, "title": "Push", "type": "HYPERTROPHY", "minutes": 60,
 *               "exercises": [ { "exercise": "barbell_bench_press", "sets": 3, "reps": [6, 10],
 *                                "rest": [120, 180], "rir": [1, 2], "perSide": false, "notes": "" } ] } ],
 *   "exerciseDefinitions": [ { "slug": "...", "name": "...", "primaryMuscle": "...", ... } ] }
 * ```
 * Weekdays left out are rest days. `rest`, `rir`, `perSide` and `notes` are optional.
 * The original `{"routine": {"splitDays": [...]}}` format (the bundled workout_plan.json) is also accepted.
 */
object PlanCodec {
    const val FORMAT = "gains-goblin-plan"
    const val VERSION = 1
    val UNITS = setOf("reps", "seconds", "minutes")
    private const val MAX_ITEMS = 200

    fun encode(plan: Plan): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("name", plan.name)
        put("description", plan.description)
        put("days", JSONArray().also { arr ->
            plan.days.sortedBy { it.weekday }.forEach { d ->
                arr.put(JSONObject().apply {
                    put("weekday", d.weekday)
                    put("title", d.title)
                    put("type", d.type.name)
                    put("minutes", d.minutes)
                    put("exercises", JSONArray().also { items ->
                        d.items.forEach { i ->
                            items.put(JSONObject().apply {
                                put("exercise", i.slug)
                                put("sets", i.sets)
                                put("reps", JSONArray().put(i.repMin).put(i.repMax))
                                put("rest", JSONArray().put(i.restMinSec).put(i.restMaxSec))
                                put("rir", JSONArray().put(i.rirMin).put(i.rirMax))
                                put("perSide", i.perSide)
                                if (i.notes.isNotBlank()) put("notes", i.notes)
                            })
                        }
                    })
                })
            }
        })
        put("exerciseDefinitions", JSONArray().also { arr -> plan.exercises.forEach { arr.put(encodeExercise(it)) } })
    }.toString(2)

    fun encodeExercise(e: PlanExercise): JSONObject = JSONObject().apply {
        put("slug", e.slug)
        put("name", e.name)
        put("primaryMuscle", e.primaryMuscle)
        put("secondaryMuscles", JSONArray().also { a -> e.secondaryMuscles.forEach { a.put(it) } })
        put("equipment", e.equipment)
        put("rule", e.rule.name)
        put("unit", e.repUnit)
        put("unilateral", e.unilateral)
        if (e.alternatives.isNotEmpty()) put("alternatives", JSONArray().also { a -> e.alternatives.forEach { a.put(it) } })
        if (e.instructions.isNotBlank()) put("instructions", e.instructions)
        e.incrementKg?.let { put("incrementKg", it) }
    }

    /** Parses and validates a plan file. Throws [PlanFormatException] with a readable message. */
    fun decode(text: String): Plan {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw PlanFormatException("This file is not valid JSON.")
        }
        val plan = try {
            when {
                root.optString("format") == FORMAT -> decodeCurrent(root)
                root.has("routine") -> decodeLegacy(root.getJSONObject("routine"))
                else -> throw PlanFormatException("This file is not a Gains Goblin plan.")
            }
        } catch (e: JSONException) {
            throw PlanFormatException("The plan file is missing something: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw PlanFormatException("The plan file has an unknown value: ${e.message}")
        }
        validate(plan)
        return plan
    }

    /** Reads the built-in exercise library (`{"exercises": [...]}`). */
    fun decodeLibrary(text: String): List<PlanExercise> =
        JSONObject(text).getJSONArray("exercises").objects().map(::decodeExercise)

    private fun decodeCurrent(root: JSONObject): Plan {
        val version = root.optInt("version", 1)
        if (version > VERSION) throw PlanFormatException("This plan was made with a newer version of the app. Update the app first.")
        val days = root.getJSONArray("days").objects().map { d ->
            PlanDay(
                weekday = d.getInt("weekday"),
                title = d.optString("title").ifBlank { "Day ${d.getInt("weekday")}" },
                type = DayType.valueOf(d.optString("type", "HYPERTROPHY")),
                minutes = d.optInt("minutes", 0),
                items = d.optJSONArray("exercises").objects().map { i ->
                    val reps = i.getJSONArray("reps")
                    val rest = i.optJSONArray("rest")
                    val rir = i.optJSONArray("rir")
                    PlanItem(
                        slug = i.getString("exercise"),
                        sets = i.getInt("sets"),
                        repMin = reps.getInt(0),
                        repMax = reps.getInt(if (reps.length() > 1) 1 else 0),
                        restMinSec = rest?.getInt(0) ?: 90,
                        restMaxSec = rest?.let { it.getInt(if (it.length() > 1) 1 else 0) } ?: 120,
                        rirMin = rir?.getInt(0) ?: 1,
                        rirMax = rir?.let { it.getInt(if (it.length() > 1) 1 else 0) } ?: 2,
                        perSide = i.optBoolean("perSide", false),
                        notes = i.optString("notes", "")
                    )
                }
            )
        }
        return Plan(
            name = root.optString("name").ifBlank { "Imported plan" },
            description = root.optString("description", ""),
            days = days,
            exercises = root.optJSONArray("exerciseDefinitions").objects().map(::decodeExercise)
        )
    }

    fun decodeExercise(e: JSONObject): PlanExercise {
        val rule = parseRule(e.optString("rule", "COMPOUND"))
        val defaultUnit = when (rule) {
            ProgressionRule.TIMED_CORE -> "seconds"
            ProgressionRule.DURATION_DISTANCE -> "minutes"
            else -> "reps"
        }
        return PlanExercise(
            slug = e.getString("slug"),
            name = e.getString("name"),
            primaryMuscle = e.optString("primaryMuscle").ifBlank { "Other" },
            secondaryMuscles = e.optJSONArray("secondaryMuscles").strings(),
            equipment = e.optString("equipment", ""),
            rule = rule,
            repUnit = e.optString("unit").ifBlank { defaultUnit },
            unilateral = e.optBoolean("unilateral", false),
            alternatives = e.optJSONArray("alternatives").strings(),
            instructions = e.optString("instructions", ""),
            incrementKg = if (e.has("incrementKg") && !e.isNull("incrementKg")) e.getDouble("incrementKg") else null
        )
    }

    /** Accepts full enum names and the short forms used in the bundled library. */
    fun parseRule(s: String): ProgressionRule = when (s.uppercase()) {
        "COMPOUND" -> ProgressionRule.COMPOUND_DOUBLE_PROGRESSION
        "ISOLATION" -> ProgressionRule.ISOLATION_HIGH_REP_PROGRESSION
        "TIMED" -> ProgressionRule.TIMED_CORE
        "CARDIO" -> ProgressionRule.DURATION_DISTANCE
        else -> ProgressionRule.valueOf(s.uppercase())
    }

    /** The original workout_plan.json layout. Per-day duplicates (`..._day4`) collapse to one exercise. */
    private fun decodeLegacy(r: JSONObject): Plan {
        val defs = LinkedHashMap<String, PlanExercise>()
        val days = r.getJSONArray("splitDays").objects().map { d ->
            PlanDay(
                weekday = d.getInt("dayNumber"),
                title = d.getString("title"),
                type = DayType.valueOf(d.getString("type")),
                minutes = d.optInt("estimatedDurationMinutes", 0),
                items = d.optJSONArray("exercises").objects().sortedBy { it.optInt("order") }.map { e ->
                    val slug = e.getString("id").replace(Regex("_day\\d+$"), "")
                    val reps = e.getJSONObject("repRange")
                    val rest = e.getJSONObject("restTimeSeconds")
                    val rir = e.getJSONObject("targetRIR")
                    defs.getOrPut(slug) {
                        PlanExercise(
                            slug = slug,
                            name = e.getString("name"),
                            primaryMuscle = e.getString("primaryMuscle"),
                            secondaryMuscles = e.optJSONArray("secondaryMuscles").strings(),
                            equipment = e.optString("equipment", ""),
                            rule = ProgressionRule.valueOf(e.getString("progressionRule")),
                            repUnit = reps.getString("unit"),
                            unilateral = reps.optBoolean("perSide", false),
                            alternatives = e.optJSONArray("alternatives").strings(),
                            instructions = e.optString("notes", "")
                        )
                    }
                    PlanItem(
                        slug = slug,
                        sets = e.getInt("targetSets"),
                        repMin = reps.getInt("min"),
                        repMax = reps.getInt("max"),
                        restMinSec = rest.getInt("min"),
                        restMaxSec = rest.getInt("max"),
                        rirMin = rir.getInt("min"),
                        rirMax = rir.getInt("max"),
                        perSide = reps.optBoolean("perSide", false),
                        notes = e.optString("notes", "")
                    )
                }
            )
        }
        return Plan(r.getString("name"), r.optString("description", ""), days, defs.values.toList())
    }

    private fun validate(p: Plan) {
        if (p.days.isEmpty()) throw PlanFormatException("The plan has no days.")
        p.days.forEach { d ->
            if (d.weekday !in 1..7) throw PlanFormatException("Day \"${d.title}\" has weekday ${d.weekday}; use 1 (Monday) to 7 (Sunday).")
        }
        val dupes = p.days.groupBy { it.weekday }.filterValues { it.size > 1 }.keys
        if (dupes.isNotEmpty()) throw PlanFormatException("More than one day is set to weekday ${dupes.first()}.")
        if (p.days.sumOf { it.items.size } > MAX_ITEMS) throw PlanFormatException("The plan has more than $MAX_ITEMS exercises.")
        p.days.forEach { d ->
            d.items.forEach { i ->
                val where = "${d.title}: ${i.slug}"
                if (i.sets !in 1..20) throw PlanFormatException("$where has ${i.sets} sets; use 1 to 20.")
                if (i.repMin < 0 || i.repMax < i.repMin) throw PlanFormatException("$where has an invalid range ${i.repMin}-${i.repMax}.")
                if (i.restMinSec < 0 || i.restMaxSec < i.restMinSec) throw PlanFormatException("$where has an invalid rest time.")
                if (i.rirMin < 0 || i.rirMax < i.rirMin || i.rirMax > 10) throw PlanFormatException("$where has an invalid RIR range.")
            }
        }
        p.exercises.forEach { e ->
            if (e.slug.isBlank() || e.name.isBlank()) throw PlanFormatException("An exercise definition has no slug or name.")
            if (e.repUnit !in UNITS) throw PlanFormatException("${e.name} has unknown unit \"${e.repUnit}\".")
        }
    }

    /** Slugs the plan uses that are neither defined in the file nor in [known]. */
    fun missingExercises(plan: Plan, known: Set<String>): Set<String> =
        plan.referencedSlugs - known - plan.exercises.map { it.slug }.toSet()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
