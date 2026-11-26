package com.healthvectors.fitness.data.catalog

import android.content.Context
import com.healthvectors.fitness.data.local.entity.FoodEntity
import com.healthvectors.fitness.domain.model.FoodSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The offline food database in `assets/food_db.json` (built by tools/generate_food_db.py): about 1,000 cooked Indian
 * dishes from the Indian Nutrient Databank plus common single ingredients from USDA FoodData Central. Rows are
 * ready to log: macros are per serving (1 bowl, 1 dosa, 1 medium (118 g), or 100 g). A row becomes a Room food only
 * when it is logged or starred.
 */
@Singleton
class FoodCatalog @Inject constructor(@ApplicationContext private val context: Context) {
    private val lock = Mutex()
    private var index: FoodIndex? = null

    suspend fun search(query: String, limit: Int = 30): List<FoodEntity> = withContext(Dispatchers.Default) {
        load().search(query, limit)
    }

    private suspend fun load(): FoodIndex = lock.withLock {
        index ?: FoodIndex.parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }).also { index = it }
    }

    companion object { const val ASSET = "food_db.json" }
}

/** Parsed food database with search. Plain Kotlin so it can be tested on the JVM. */
class FoodIndex(val foods: List<FoodEntity>) {
    private val names = foods.map { " " + normalize(it.name) + " " }
    /** The name without its bracketed alias, and each half of "A/B": "banana" for "Banana (Kela)", "chapati" and "roti" for "Chapati/Roti". */
    private val bases = foods.map { f -> f.name.substringBefore('(').split('/').map(::normalize).toSet() }

    /**
     * Every word of the query must start a word of the food's name, either as typed or as one of its common
     * Hindi/English equivalents (dahi finds curd, chawal finds rice). A food named exactly what was typed comes
     * first ("banana" finds the fruit before banana cake); after that, Indian dishes rank above single ingredients.
     */
    fun search(query: String, limit: Int = 30): List<FoodEntity> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ')
        val alternatives = words.map { w -> listOf(w) + SYNONYMS[w].orEmpty().filter { it != w } }
        val scored = ArrayList<Pair<Int, Int>>()
        for (i in foods.indices) {
            val name = names[i]
            var score = 0
            var ok = true
            for (alts in alternatives) {
                val hit = alts.indexOfFirst { name.contains(" $it") }
                if (hit < 0) { ok = false; break }
                val alt = alts[hit]
                score += if (name.contains(" $alt ")) 10 else 5   // whole word beats a prefix
                if (hit == 0) score += 2                            // as typed beats a synonym
                if (name.startsWith(" $alt")) score += 15           // the name starts with it
            }
            if (!ok) continue
            if (name.startsWith(" $q")) score += 30
            if (q in bases[i] || (alternatives.size == 1 && alternatives[0].any { it in bases[i] })) score += 40
            if (foods[i].source == FoodSource.INDB) score += 8
            score -= name.length / 12                               // shorter, plainer names first
            scored += score to i
        }
        return scored.sortedWith(compareByDescending<Pair<Int, Int>> { it.first }.thenBy { foods[it.second].name.length })
            .take(limit).map { foods[it.second] }
    }

    companion object {
        fun parse(json: String): FoodIndex {
            val arr = JSONObject(json).getJSONArray("foods")
            val list = ArrayList<FoodEntity>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                list += FoodEntity(
                    name = o.getString("n"),
                    servingSize = o.getDouble("ss"),
                    servingUnit = o.getString("su"),
                    kcal = o.getDouble("k"), protein = o.getDouble("p"), carbs = o.getDouble("c"), fat = o.getDouble("f"),
                    source = FoodSource.valueOf(id.substringBefore(':'))
                )
            }
            return FoodIndex(list)
        }

        fun normalize(s: String): String =
            s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

        /** Spelling variants and Hindi/English pairs. Each group is searched as one word. */
        private val GROUPS = listOf(
            "dahi curd yogurt yoghurt", "chawal chaval rice bhaat bhat", "aloo alu potato", "anda ande egg",
            "doodh dudh milk", "roti chapati chapatti phulka", "gobi gobhi cauliflower", "bhindi okra ladyfinger",
            "palak spinach", "chana channa chole chhole chickpea chickpeas", "rajma rajmah kidney",
            "toor tur tuvar arhar pigeon", "moong mung", "masoor lentil lentils", "besan gram",
            "suji sooji rava rawa semolina", "kela banana", "aam mango", "murgh murg chicken",
            "machli machhli macchi fish", "gosht mutton goat", "keema kheema mince minced",
            "paratha parantha parotta", "khichdi khichri khitchdi", "pakora pakoda bhajji bhaji fritters",
            "kadhi kadi", "baingan brinjal eggplant", "matar mutter peas", "methi fenugreek", "chai tea",
            "cheeni chini sugar", "makhan butter", "malai cream", "biryani biriyani", "idli idly", "dosa dosai",
            "sambar sambhar", "upma uppittu", "poha aval chiwda chivda", "halwa halva", "ladoo laddu laddoo",
            "kheer payasam", "sabzi sabji subzi", "chaat chat", "amrood guava", "anar anaar pomegranate",
            "seb apple", "badam almond almonds", "kaju cashew cashews", "moongphali mungfali peanut peanuts",
            "gajar carrot", "pyaaz pyaz onion", "tamatar tomato", "kheera cucumber", "lauki ghiya dudhi gourd",
            "karela bitter", "nariyal coconut", "chaas chhach chhachh buttermilk", "atta wheat", "maida refined",
            "jhinga prawn prawns shrimp",
        )
        private val SYNONYMS: Map<String, List<String>> = buildMap {
            for (g in GROUPS) { val words = g.split(' '); for (w in words) put(w, words) }
        }
    }
}
