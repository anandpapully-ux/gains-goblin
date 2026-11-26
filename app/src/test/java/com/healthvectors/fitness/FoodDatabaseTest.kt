package com.healthvectors.fitness

import com.healthvectors.fitness.data.catalog.FoodIndex
import com.healthvectors.fitness.data.local.entity.FoodEntity
import com.healthvectors.fitness.domain.model.FoodSource
import com.healthvectors.fitness.feature.nutrition.amountLabel
import com.healthvectors.fitness.feature.nutrition.gramsPerServing
import com.healthvectors.fitness.feature.nutrition.parseServing
import com.healthvectors.fitness.feature.nutrition.servingLabel
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Guards the bundled food database (assets/food_db.json) and its search. */
class FoodDatabaseTest {
    private val index = FoodIndex.parse(File("src/main/assets/food_db.json").readText())
    private fun first(q: String) = index.search(q).firstOrNull()?.name

    @Test fun databaseLoadsWithSaneRows() {
        val foods = index.foods
        assertTrue(foods.count { it.source == FoodSource.INDB } > 1000)
        assertTrue(foods.count { it.source == FoodSource.USDA } > 150)
        assertEquals(foods.size, foods.map { it.source to it.name.lowercase() }.toSet().size)
        for (f in foods) {
            assertTrue(f.name, f.servingSize > 0 && f.servingUnit.isNotBlank())
            assertTrue(f.name, f.kcal >= 0 && f.protein >= 0 && f.carbs >= 0 && f.fat >= 0)
            // Energy roughly matches the macros (4/4/9 kcal per g; fibre and rounding make up the rest). Beer's energy
            // is mostly alcohol, which has no macro column.
            if (f.name.startsWith("Beer")) continue
            val fromMacros = 4 * f.protein + 4 * f.carbs + 9 * f.fat
            assertTrue("${f.name}: ${f.kcal} vs $fromMacros", f.kcal < 5 || f.kcal in fromMacros * 0.75 - 8..fromMacros * 1.3 + 8)
        }
    }

    @Test fun indianDishesAreLoggedPerServingWithRealisticEnergy() {
        fun food(name: String) = index.foods.first { it.name == name }
        val roti = food("Chapati/Roti")
        assertEquals("chapati", roti.servingUnit)
        assertNull("INDB dishes have no gram weight", gramsPerServing(roti))
        assertTrue(roti.kcal in 50.0..120.0)
        assertTrue(food("Idli").kcal in 25.0..80.0)
        assertTrue(food("Plain dosa").kcal in 80.0..200.0)
        // The source counts the whole pan of frying oil; the generator keeps only what is absorbed.
        val poori = food("Poori")
        assertTrue("poori ${poori.kcal}", poori.kcal in 70.0..160.0)
        val samosa = food("Potato samosa (Aloo ka samosa)")
        assertTrue("samosa ${samosa.kcal}", samosa.kcal in 120.0..300.0)
        // The source codes broth as dry stock cubes (protein and fat, no energy); the generator counts it as water.
        val lentilSoup = food("Lentil soup")
        assertTrue("lentil soup fat ${lentilSoup.fat}", lentilSoup.fat < 10)
        assertTrue("consomme fat ${food("Chicken consomme (Clear chicken soup)").fat}", food("Chicken consomme (Clear chicken soup)").fat < 10)
        // A guessed serving unit that holds the whole batch falls back to 100 g; a believable one is kept.
        assertEquals("g", food("Bhel puri").servingUnit)
        assertEquals("bowl", food("Dal makhani").servingUnit)
        // No single serving of anything is a day's food.
        index.foods.forEach { assertTrue("${it.name}: ${it.kcal} kcal per ${it.servingUnit}", it.kcal < 1500) }
    }

    @Test fun searchPrefersIndianDishesAndKnowsHindiNames() {
        assertTrue(index.search("dal").take(5).all { it.source == FoodSource.INDB })
        assertEquals("Idli", first("idli"))
        assertTrue(index.search("dahi").any { it.name.startsWith("Curd") })
        assertTrue(index.search("chawal").any { it.name.startsWith("Rice") })
        assertTrue(index.search("toor").any { it.name.startsWith("Toor dal") })
        assertTrue(index.search("chicken breast").first().name.startsWith("Chicken breast"))
        assertTrue(index.search("aloo paratha").first().name.contains("Potato parantha"))
        assertTrue(index.search("palak paneer").isNotEmpty())
        // An exact name beats dishes that merely start with it, by either name.
        assertEquals("Banana (Kela)", first("banana"))
        assertEquals("Banana (Kela)", first("kela"))
        assertEquals("Apple (Seb)", first("apple"))
        assertEquals("Chapati/Roti", first("roti"))
        assertEquals("Chapati/Roti", first("chapati"))
        assertTrue(index.search("  ").isEmpty())
        assertTrue(index.search("zzqx").isEmpty())
    }

    @Test fun everyQueryWordMustMatch() {
        val results = index.search("egg curry")
        assertTrue(results.isNotEmpty())
        results.forEach { assertTrue(it.name, it.name.lowercase().contains("egg") || it.name.lowercase().contains("anda")) }
    }

    @Test fun gramsPerServingReadsTheServingWeight() {
        fun f(size: Double, unit: String) = FoodEntity(name = "x", servingSize = size, servingUnit = unit, kcal = 1.0, protein = 0.0, carbs = 0.0, fat = 0.0)
        assertEquals(100.0, gramsPerServing(f(100.0, "g"))!!, 0.0)
        assertEquals(118.0, gramsPerServing(f(1.0, "medium (118 g)"))!!, 0.0)
        assertEquals(87.0, gramsPerServing(f(0.5, "cup arils (87 g)"))!!, 0.0)
        assertNull(gramsPerServing(f(1.0, "bowl")))
        assertNull(gramsPerServing(f(1.0, "serving")))
        val banana = index.foods.first { it.name == "Banana (Kela)" }
        assertEquals(118.0, gramsPerServing(banana)!!, 0.0)
    }

    @Test fun amountsReadInTheFoodsOwnUnits() {
        fun f(size: Double, unit: String) = FoodEntity(name = "x", servingSize = size, servingUnit = unit, kcal = 1.0, protein = 0.0, carbs = 0.0, fat = 0.0)
        val banana = f(1.0, "medium (118 g)")
        assertEquals("1 medium · 118 g", servingLabel(banana))
        assertEquals("1 medium", amountLabel(banana, 1.0))
        assertEquals("1.5 medium", amountLabel(banana, 1.5))
        assertEquals("200 g", amountLabel(banana, 200 / 118.0))
        assertEquals("100 g", servingLabel(f(100.0, "g")))
        assertEquals("150 g", amountLabel(f(100.0, "g"), 1.5))
        assertEquals("2 chapati", amountLabel(f(1.0, "chapati"), 2.0))
        assertEquals("1 serving", amountLabel(f(1.0, "serving"), 1.0))
        assertEquals("2 servings", amountLabel(f(1.0, "serving"), 2.0))
        assertEquals("1.5 servings", amountLabel(null, 1.5))
        assertEquals(100.0 to "g", parseServing("100 g"))
        assertEquals(2.0 to "cups", parseServing(" 2 cups"))
        assertEquals(1.0 to "plate", parseServing("plate"))
        assertEquals(1.0 to "serving", parseServing(""))
    }
}
