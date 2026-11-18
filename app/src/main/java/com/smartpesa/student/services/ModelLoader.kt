package com.smartpesa.student.services

import android.content.Context
import com.smartpesa.student.domain.Categorizer
import com.smartpesa.student.domain.ModelWeights
import com.smartpesa.student.domain.expenseCategories
import org.json.JSONObject

/** Loading is synchronous on the ingestion IO executor, so prediction cannot race initialization. */
object ModelLoader {
    fun load(context: Context): Categorizer {
        val map = runCatching {
            val rules = JSONObject(context.assets.open("merchant_rules.json").bufferedReader().use { it.readText() })
            rules.keys().asSequence().associateWith { rules.getString(it).also { category -> require(category in expenseCategories) } }
        }.getOrDefault(emptyMap())
        val weights = runCatching { decodeWeights(context.assets.open("category_model.json").bufferedReader().use { it.readText() }) }.getOrNull()
        return Categorizer(map, weights)
    }
    internal fun decodeWeights(text: String): ModelWeights? = runCatching {
            val json = JSONObject(text)
            fun strings(name: String) = json.getJSONArray(name).let { a -> List(a.length()) { a.getString(it) } }
            fun doubles(name: String) = json.getJSONArray(name).let { a -> List(a.length()) { a.getDouble(it) } }
            val matrix = json.getJSONArray("likelihoods")
            ModelWeights(strings("labels"), strings("vocabulary"), doubles("idf"), doubles("priors"),
                List(matrix.length()) { i -> matrix.getJSONArray(i).let { a -> List(a.length()) { a.getDouble(it) } } }, json.optBoolean("validated", false)).also { model ->
                require(model.labels.isNotEmpty() && model.labels.distinct().size == model.labels.size && model.labels.all { it in expenseCategories })
                require(model.vocabulary.isNotEmpty() && model.vocabulary.distinct().size == model.vocabulary.size)
                require(model.idf.size == model.vocabulary.size && model.idf.all { it.isFinite() && it > 0 })
                require(model.priors.size == model.labels.size && model.priors.all { it.isFinite() })
                require(model.likelihoods.size == model.labels.size && model.likelihoods.all { row -> row.size == model.vocabulary.size && row.all { it.isFinite() } })
            }
        }.getOrNull()
}
