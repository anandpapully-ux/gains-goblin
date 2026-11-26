package com.healthvectors.fitness.domain.logic

import com.healthvectors.fitness.domain.model.PhotoPose
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** Decisions behind progress photos, kept free of Android so they can be unit tested. */
object PhotoLogic {
    /** Long edge of a stored photo; enough for a full-screen view, small enough to stay around 200-400 KB. */
    const val FULL_EDGE = 1600
    /** Long edge of a grid thumbnail. */
    const val THUMB_EDGE = 360
    /** After this many days without a photo, the Progress tab suggests taking one. */
    const val NUDGE_DAYS = 14

    /** A minimal view of a photo for the decisions below. */
    data class Shot(val id: Long, val takenOn: LocalDate, val pose: PhotoPose, val weightKg: Double?)

    /** The pose to preselect: the first of front, side, back not yet taken on [date] (people shoot all three in a row). */
    fun suggestPose(taken: List<Shot>, date: LocalDate): PhotoPose {
        val done = taken.filter { it.takenOn == date }.map { it.pose }.toSet()
        return PhotoPose.entries.firstOrNull { it !in done } ?: PhotoPose.FRONT
    }

    /**
     * The pose and pair a comparison opens with: if [anchorId] is given, that photo against the latest of its pose
     * (or the earliest, when it is the latest itself); otherwise the pose with the most photos, earliest against latest.
     */
    fun defaultPair(photos: List<Shot>, anchorId: Long? = null): Triple<PhotoPose, Long, Long>? {
        val anchor = photos.firstOrNull { it.id == anchorId }
        val pose = anchor?.pose ?: photos.groupBy { it.pose }.filter { it.value.size >= 2 }.maxByOrNull { it.value.size }?.key ?: return null
        val same = photos.filter { it.pose == pose }.sortedWith(compareBy({ it.takenOn }, { it.id }))
        if (same.size < 2) return null
        if (anchor == null) return Triple(pose, same.first().id, same.last().id)
        val other = if (anchor.id == same.last().id) same.first() else same.last()
        val (before, after) = if (other.takenOn < anchor.takenOn || (other.takenOn == anchor.takenOn && other.id < anchor.id)) other to anchor else anchor to other
        return Triple(pose, before.id, after.id)
    }

    /** Days since the newest photo, or null when there are none. */
    fun daysSinceLast(photos: List<Shot>, today: LocalDate): Long? =
        photos.maxOfOrNull { it.takenOn }?.let { ChronoUnit.DAYS.between(it, today) }

    fun shouldNudge(photos: List<Shot>, today: LocalDate): Boolean =
        (daysSinceLast(photos, today) ?: -1) >= NUDGE_DAYS

    /** Width and height after fitting the long edge into [maxEdge] (never enlarging). */
    fun fit(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= maxEdge) return width to height
        val s = maxEdge.toDouble() / long
        return (width * s).roundToInt().coerceAtLeast(1) to (height * s).roundToInt().coerceAtLeast(1)
    }

    /** Largest power-of-two decode subsampling that still leaves the long edge at least [maxEdge]. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var s = 1
        while (max(width, height) / (s * 2) >= maxEdge) s *= 2
        return s
    }

    // -----------------------------------------------------------------------------------------------------------
    // ZIP export: photos as plain JPEGs plus a manifest that lets the app import them back with their details.

    const val MANIFEST = "photos.json"

    data class ManifestEntry(val file: String, val takenOn: LocalDate, val pose: PhotoPose, val weightKg: Double?, val note: String)

    fun manifestJson(entries: List<ManifestEntry>): String = JSONObject()
        .put("format", "gains-goblin-photos").put("version", 1)
        .put("photos", JSONArray().apply {
            entries.forEach { e ->
                put(JSONObject().put("file", e.file).put("takenOn", e.takenOn.toString()).put("pose", e.pose.name)
                    .put("weightKg", e.weightKg ?: JSONObject.NULL).put("note", e.note))
            }
        }).toString(2)

    /** Parses a manifest; unknown poses fall back to front and bad rows are skipped. */
    fun parseManifest(json: String): List<ManifestEntry> {
        val root = JSONObject(json)
        require(root.optString("format") == "gains-goblin-photos") { "Not a Gains Goblin photo export" }
        val arr = root.optJSONArray("photos") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                ManifestEntry(
                    o.getString("file"), LocalDate.parse(o.getString("takenOn")),
                    PhotoPose.entries.firstOrNull { it.name == o.optString("pose") } ?: PhotoPose.FRONT,
                    if (o.isNull("weightKg")) null else o.optDouble("weightKg").takeIf { !it.isNaN() },
                    o.optString("note", "")
                )
            }.getOrNull()
        }
    }

    /** ZIP entry name for an exported photo: sortable and readable ("2026-11-26_front_1.jpg"). */
    fun exportName(takenOn: LocalDate, pose: PhotoPose, n: Int) = "${takenOn}_${pose.name.lowercase()}_$n.jpg"
}
