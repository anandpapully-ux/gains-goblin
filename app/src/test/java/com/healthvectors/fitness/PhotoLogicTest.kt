package com.healthvectors.fitness

import com.healthvectors.fitness.domain.logic.PhotoLogic
import com.healthvectors.fitness.domain.logic.PhotoLogic.Shot
import com.healthvectors.fitness.domain.model.PhotoPose.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PhotoLogicTest {
    private val d1 = LocalDate.of(2026, 9, 1)
    private val d2 = LocalDate.of(2026, 10, 1)
    private val d3 = LocalDate.of(2026, 11, 1)

    @Test fun suggestsTheNextPoseNotYetTakenThatDay() {
        assertEquals(FRONT, PhotoLogic.suggestPose(emptyList(), d1))
        val shots = listOf(Shot(1, d1, FRONT, null), Shot(2, d2, SIDE, null))
        assertEquals(SIDE, PhotoLogic.suggestPose(shots, d1))
        assertEquals(FRONT, PhotoLogic.suggestPose(shots, d2))
        assertEquals(FRONT, PhotoLogic.suggestPose(listOf(Shot(1, d1, FRONT, null), Shot(2, d1, SIDE, null), Shot(3, d1, BACK, null)), d1))
    }

    @Test fun defaultPairIsFirstAgainstLatestOfTheBestCoveredPose() {
        assertNull(PhotoLogic.defaultPair(listOf(Shot(1, d1, FRONT, null), Shot(2, d2, SIDE, null))))
        val shots = listOf(Shot(1, d1, FRONT, null), Shot(2, d2, FRONT, null), Shot(3, d3, FRONT, null), Shot(4, d1, SIDE, null), Shot(5, d3, SIDE, null))
        assertEquals(Triple(FRONT, 1L, 3L), PhotoLogic.defaultPair(shots))
        // From a photo: compared with the latest of its pose (or the first, if it is the latest), older one first.
        assertEquals(Triple(FRONT, 2L, 3L), PhotoLogic.defaultPair(shots, anchorId = 2))
        assertEquals(Triple(FRONT, 1L, 3L), PhotoLogic.defaultPair(shots, anchorId = 3))
        assertEquals(Triple(SIDE, 4L, 5L), PhotoLogic.defaultPair(shots, anchorId = 4))
    }

    @Test fun nudgesAfterTwoWeeksWithoutAPhoto() {
        assertFalse(PhotoLogic.shouldNudge(emptyList(), d3))
        val shots = listOf(Shot(1, d1, FRONT, null))
        assertFalse(PhotoLogic.shouldNudge(shots, d1.plusDays(13)))
        assertTrue(PhotoLogic.shouldNudge(shots, d1.plusDays(14)))
        assertEquals(31L, PhotoLogic.daysSinceLast(shots + Shot(2, d2, BACK, null), d3))
    }

    @Test fun imagesAreFittedAndSubsampledWithoutEnlarging() {
        assertEquals(1200 to 1600, PhotoLogic.fit(3000, 4000, 1600))
        assertEquals(1600 to 900, PhotoLogic.fit(4000, 2250, 1600))
        assertEquals(800 to 600, PhotoLogic.fit(800, 600, 1600))
        assertEquals(2, PhotoLogic.sampleSize(4000, 3000, 1600))
        assertEquals(1, PhotoLogic.sampleSize(3000, 2000, 1600))
        assertEquals(4, PhotoLogic.sampleSize(8000, 6000, 1600))
    }

    @Test fun manifestRoundTripsAndToleratesBadRows() {
        val entries = listOf(
            PhotoLogic.ManifestEntry(PhotoLogic.exportName(d1, FRONT, 1), d1, FRONT, 80.5, "fasted"),
            PhotoLogic.ManifestEntry(PhotoLogic.exportName(d1, SIDE, 1), d1, SIDE, null, "")
        )
        assertEquals("2026-09-01_front_1.jpg", entries[0].file)
        assertEquals(entries, PhotoLogic.parseManifest(PhotoLogic.manifestJson(entries)))
        val messy = """{"format":"gains-goblin-photos","photos":[{"file":"a.jpg","takenOn":"bad"},{"file":"b.jpg","takenOn":"2026-01-02","pose":"WEIRD"}]}"""
        assertEquals(listOf(PhotoLogic.ManifestEntry("b.jpg", LocalDate.of(2026, 1, 2), FRONT, null, "")), PhotoLogic.parseManifest(messy))
        assertThrows(IllegalArgumentException::class.java) { PhotoLogic.parseManifest("""{"format":"something-else"}""") }
    }
}
