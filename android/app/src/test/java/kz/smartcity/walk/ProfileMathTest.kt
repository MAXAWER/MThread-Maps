package kz.smartcity.walk

import kz.smartcity.walk.data.repo.ProfileMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProfileMathTest {
    private val day = 86_400_000L

    @Test
    fun mergeRules() {
        assertEquals(0.8, ProfileMath.added(explicit = true), 1e-9)
        assertEquals(0.3, ProfileMath.added(explicit = false), 1e-9)
        assertEquals(0.44, ProfileMath.reinforced(0.3), 1e-9)   // c + 0.2(1 − c)
        assertEquals(0.24, ProfileMath.weakened(0.3), 1e-9)     // 0.8c
        assertEquals(0.4, ProfileMath.decayed(0.8, 0, 30 * day), 1e-9)  // полураспад 30 дней
    }

    @Test
    fun thompsonPrefersLikedCategories() {
        val rnd = Random(42)
        var wins = 0
        repeat(1000) {
            if (ProfileMath.sampleBeta(9.0, 2.0, rnd) > ProfileMath.sampleBeta(2.0, 9.0, rnd)) wins++
        }
        assertTrue("liked category should win most draws: $wins", wins > 950)
        val mean = (1..4000).map { ProfileMath.sampleBeta(9.0, 2.0, rnd) }.average()
        assertEquals(9.0 / 11.0, mean, 0.02)
    }

    @Test
    fun diversityAvoidsFiveCoffeesInARow() {
        data class Card(val id: Int, val cat: String, val group: String, val s: Double)
        val cards = (1..5).map { Card(it, "coffee", "food", 0.9) } + listOf(
            Card(10, "park", "walks", 0.7), Card(11, "museum", "culture", 0.65), Card(12, "cafe", "food", 0.7))
        val top = ProfileMath.diversify(cards, { it.s }, { it.cat }, { it.group }, take = 5)
        assertTrue(top.take(3).map { it.cat }.toSet().size == 3)
        assertTrue(top.count { it.cat == "coffee" } <= 2)
    }

    @Test
    fun deckScoreFormula() {
        val s = ProfileMath.deckScore(theta = 1.0, k = 1.0, etaMin = 0.0, q = 1.0, novelty = 1.0)
        assertEquals(1.0, s, 1e-9)
    }

    @Test
    fun swipeFeaturesShiftTheScore() {
        val f = kz.smartcity.walk.data.repo.ProfileMath.features(indoor = false, avgCheck = 9000, etaMin = 25, rating = 4.8)
        org.junit.Assert.assertEquals(listOf("feat:outdoor", "feat:pricey", "feat:far", "feat:toprated"), f)
        val neutral = kz.smartcity.walk.data.repo.ProfileMath.featureFactor(f) { null }
        org.junit.Assert.assertEquals(1.0, neutral, 1e-9)
        // «Не то» далеко и дорого — такие карточки опускаются, любимые черты — поднимаются.
        val disliked = kz.smartcity.walk.data.repo.ProfileMath.featureFactor(f) { if (it == "feat:far" || it == "feat:pricey") 0.2 else 0.5 }
        val liked = kz.smartcity.walk.data.repo.ProfileMath.featureFactor(f) { if (it == "feat:outdoor") 0.9 else 0.5 }
        org.junit.Assert.assertTrue(disliked < 0.75 && liked > 1.15)
    }
}
