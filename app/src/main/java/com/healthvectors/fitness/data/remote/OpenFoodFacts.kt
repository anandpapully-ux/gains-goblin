package com.healthvectors.fitness.data.remote

import com.healthvectors.fitness.data.local.entity.FoodEntity
import com.healthvectors.fitness.domain.model.FoodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Open Food Facts client. Every call returns null / empty on any failure so the app degrades
 * gracefully offline. Only the search text or barcode is sent: no identifiers, no account data.
 * Values are stored per 100 g.
 */
@Singleton
class OpenFoodFacts @Inject constructor() {
    private val fields = "code,product_name,brands,nutriments"

    suspend fun byBarcode(code: String): FoodEntity? = withContext(Dispatchers.IO) {
        runCatching {
            val json = get("https://world.openfoodfacts.org/api/v2/product/${enc(code)}.json?fields=$fields")
            if (json.optInt("status") != 1) null else toFood(json.getJSONObject("product"))
        }.getOrNull()
    }

    suspend fun search(query: String): List<FoodEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get(
                "https://world.openfoodfacts.org/cgi/search.pl?search_terms=${enc(query)}" +
                    "&search_simple=1&action=process&json=1&page_size=15&fields=$fields"
            )
            val arr = json.optJSONArray("products") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { toFood(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun toFood(p: JSONObject): FoodEntity? {
        val name = p.optString("product_name").trim()
        val n = p.optJSONObject("nutriments") ?: return null
        if (name.isEmpty() || !n.has("energy-kcal_100g")) return null
        return FoodEntity(
            barcode = p.optString("code").ifBlank { null },
            name = name,
            brand = p.optString("brands").ifBlank { null },
            servingSize = 100.0,
            servingUnit = "g",
            kcal = n.optDouble("energy-kcal_100g", 0.0),
            protein = n.optDouble("proteins_100g", 0.0),
            carbs = n.optDouble("carbohydrates_100g", 0.0),
            fat = n.optDouble("fat_100g", 0.0),
            source = FoodSource.OFF
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "FitnessTracker-Android/1.0")
        try {
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
