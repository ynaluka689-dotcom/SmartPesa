package com.smartpesa.student.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

val expenseCategories = listOf("Food & Dining", "Groceries", "Transport", "Airtime & Data", "Tuition & Fees",
    "Accommodation", "Utilities", "Shopping", "Health", "Entertainment", "Other")
/** This exact normalization is mirrored in research/evaluate.py for portable inference. */
fun normalize(text: String) = text.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ")
    .replace(Regex("\\s+"), " ").trim()
data class Prediction(val category: String, val score: Double, val source: String) {
    val review: String get() = if (score > 0.9) "confirmed" else if (score >= 0.7) "suggested" else "required"
}
data class ModelWeights(val labels: List<String>, val vocabulary: List<String>, val idf: List<Double>,
    val priors: List<Double>, val likelihoods: List<List<Double>>, val validated: Boolean = false)
class Categorizer(private val rules: Map<String, String>, private val weights: ModelWeights?) {
    fun predict(merchant: String, income: Boolean, correction: String? = null): Prediction {
        // Direction is a financial fact, not a merchant prediction.
        if (income) return Prediction("Income", 1.0, "direction")
        if (correction in expenseCategories) return Prediction(correction!!, 1.0, "correction")
        val text = normalize(merchant)
        rules.entries.sortedWith(compareByDescending<Map.Entry<String, String>> { it.key.length }.thenBy { it.key })
            .firstOrNull { (" $text ").contains(" ${it.key} ") }?.let { return Prediction(it.value, 1.0, "rule") }
        val model = weights ?: return Prediction("Other", 0.0, "unknown")
        val features = features(text, model)
        if (features.all { it == 0.0 }) return Prediction("Other", 0.0, "unknown")
        val scores = model.labels.indices.map { i -> model.priors[i] + features.indices.sumOf { j -> features[j] * model.likelihoods[i][j] } }
        val max = scores.max(); val probabilities = scores.map { exp(it - max) }; val total = probabilities.sum()
        val index = scores.indices.maxBy { scores[it] }
        // Bootstrap weights cannot silently auto-confirm transactions before research validation.
        val score = (probabilities[index] / total).let { if (model.validated) it else it.coerceAtMost(0.9) }
        return Prediction(model.labels[index], score, "naive_bayes")
    }
    companion object {
        fun features(text: String, model: ModelWeights): List<Double> {
            val counts = normalize(text).split(" ").groupingBy { it }.eachCount()
            val vector = model.vocabulary.mapIndexed { i, token -> (counts[token]?.let { 1 + ln(it.toDouble()) } ?: 0.0) * model.idf[i] }
            val norm = sqrt(vector.sumOf { it * it })
            return vector.map { if (norm == 0.0) 0.0 else it / norm }
        }
    }
}
