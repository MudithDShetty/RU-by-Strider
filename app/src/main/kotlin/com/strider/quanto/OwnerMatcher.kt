package com.strider.quanto

enum class OwnerIntent { SELF, NAMED, NONE }

enum class NameRole {
    PRIMARY,
    CO_PRIMARY,
    FATHER,
    SPOUSE,
    GUARDIAN,
    NOMINEE,
    WITNESS,
    EMPLOYER
}

data class NameEntity(
    val tokens: List<String>,
    val role: NameRole,
    val confidence: Float
) {
    fun matchesTarget(targetTokens: List<String>, partialOk: Boolean = true): Boolean {
        if (targetTokens.isEmpty() || tokens.isEmpty()) return false
        val targetSet = targetTokens.map { it.lowercase() }.toSet()
        val entitySet = tokens.map { it.lowercase() }.toSet()
        if (targetSet.all { it in entitySet }) return true
        if (!partialOk) return false
        val entityText = entitySet.joinToString(" ")
        return targetSet.any { t ->
            entitySet.any { e -> e == t || QueryScoring.tokenMatchesGluedWord(e, t) } ||
                QueryScoring.textContainsToken(entityText, t)
        }
    }
}

object OwnerMatcher {

    private val SCORING_ROLES = setOf(NameRole.PRIMARY, NameRole.CO_PRIMARY)

    /**
     * Multiplier applied to lexical / rerank scores when owner intent is active.
     * Returns 1f when intent is NONE or no target tokens.
     */
    fun scoreMultiplier(
        entities: List<NameEntity>,
        ownerConfidence: Float,
        intent: OwnerIntent,
        targetTokens: List<String>
    ): Float {
        if (intent == OwnerIntent.NONE || targetTokens.isEmpty()) return 1f
        if (entities.isEmpty()) return 1f

        val primaryEntities = entities.filter { it.role in SCORING_ROLES }
        val primaryMatch = primaryEntities.any { it.matchesTarget(targetTokens) }
        val coPrimaryMatch = entities.any { it.role == NameRole.CO_PRIMARY && it.matchesTarget(targetTokens) }

        when {
            primaryMatch -> return 1.5f
            coPrimaryMatch -> return 1.15f
        }

        // Target appears only in secondary roles — no boost
        val anySecondaryMatch = entities.any { it.role !in SCORING_ROLES && it.matchesTarget(targetTokens) }
        if (anySecondaryMatch) return 1f

        // Confident primary owner is someone else
        val confidentPrimary = primaryEntities.firstOrNull { it.confidence >= 0.6f && it.tokens.isNotEmpty() }
            ?: primaryEntities.firstOrNull { ownerConfidence >= 0.6f && it.tokens.isNotEmpty() }
        if (confidentPrimary != null && !confidentPrimary.matchesTarget(targetTokens)) {
            return 0.35f
        }

        return 1f
    }

    fun primaryTokensForFts(entities: List<NameEntity>): String =
        entities.filter { it.role in SCORING_ROLES }
            .flatMap { it.tokens }
            .distinct()
            .joinToString(" ")

    fun serializeEntities(entities: List<NameEntity>): String {
        if (entities.isEmpty()) return "[]"
        val arr = org.json.JSONArray()
        for (e in entities.take(4)) {
            val obj = org.json.JSONObject()
            obj.put("tokens", org.json.JSONArray(e.tokens))
            obj.put("role", e.role.name)
            obj.put("confidence", e.confidence.toDouble())
            arr.put(obj)
        }
        return arr.toString()
    }

    fun deserializeEntities(json: String?): List<NameEntity> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val tokensArr = obj.getJSONArray("tokens")
                    val tokens = buildList {
                        for (j in 0 until tokensArr.length()) {
                            add(tokensArr.getString(j))
                        }
                    }
                    val role = try {
                        NameRole.valueOf(obj.getString("role"))
                    } catch (_: Exception) {
                        NameRole.PRIMARY
                    }
                    add(NameEntity(tokens, role, obj.optDouble("confidence", 0.5).toFloat()))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun primaryConfidence(entities: List<NameEntity>): Float =
        entities.filter { it.role == NameRole.PRIMARY }
            .maxOfOrNull { it.confidence } ?: 0f
}
