package com.strider.quanto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryScoringTest {

    @Test
    fun art_doesNotMatchInsideSmartBudget() {
        assertFalse(QueryScoring.textContainsToken("smart budget", "art"))
        assertFalse(QueryScoring.tokenMatchesGlued("smart budget", "art"))
    }

    @Test
    fun nda_matchesNdaQuantooStem() {
        assertTrue(QueryScoring.textContainsToken("nda quantoo", "nda"))
        assertTrue(QueryScoring.filenameCoversAllTokens("nda quantoo", listOf("nda", "quantoo")))
    }

    @Test
    fun doc_doesNotMatchInsideDocument() {
        assertFalse(QueryScoring.textContainsToken("document final", "doc"))
    }

    @Test
    fun q2_matchesDelimitedAndGluedStem() {
        assertTrue(QueryScoring.textContainsToken("q2 budget", "q2"))
        assertTrue(QueryScoring.tokenMatchesGlued("q2budget", "q2"))
        assertTrue(QueryScoring.tokenMatchesGluedWord("q2budget", "q2"))
    }

    @Test
    fun budget_doesNotMatchStrictInsideQ2budget() {
        assertFalse(QueryScoring.textContainsToken("q2budget", "budget"))
    }

    @Test
    fun budget_matchesRelaxedGluedOnQ2budget() {
        assertTrue(QueryScoring.tokenMatchesGlued("q2budget", "budget"))
    }

    @Test
    fun tanuj_matchesInContentSnippet() {
        val content = "this agreement is signed by tanuj sharma and others"
        assertTrue(QueryScoring.textContainsToken(content, "tanuj"))
    }

    @Test
    fun tokenMatchRatio_usesWordBoundaryNotSubstring() {
        val ratio = QueryScoring.tokenMatchRatio(
            nameStem = "smart budget",
            meta = "",
            content = "",
            tokens = listOf("art", "budget")
        )
        assertTrue(ratio == 0.5f)
    }

    @Test
    fun filenameCoverageBoost_rewardsFullCoverage() {
        val boost = QueryScoring.filenameCoverageBoost(
            nameTokens = listOf("nda", "quantoo"),
            queryTokens = listOf("nda", "quantoo")
        )
        assertTrue(boost >= SearchWeights.FILENAME_COVERAGE_PER_TOKEN + SearchWeights.FILENAME_COVERAGE_FULL_BONUS)
    }

    @Test
    fun specificityMultiplier_cappedAtFour() {
        val mult = QueryScoring.specificityMultiplier(
            nameStem = "q2 budget report",
            meta = "q2 budget report quarterly",
            content = "q2 budget report details",
            tokens = listOf("q2", "budget", "report"),
            periodHints = listOf("q2"),
            entities = ""
        )
        assertTrue(mult <= SearchWeights.SPECIFICITY_MULT_CAP)
    }

    @Test
    fun hasStrongFilenameHit_acceptsThreeCharTokens() {
        assertTrue(MultilingualBridge.hasStrongFilenameHit("nda quantoo", listOf("nda")))
    }

    @Test
    fun queryMatchesFilenameStem_certificateQuery() {
        val stem = "project certificate mudith july 2023"
        val tokens = listOf("project", "certificate", "mudith", "july", "2023")
        assertTrue(QueryScoring.queryMatchesFilenameStem(stem, tokens))
    }

    @Test
    fun queryMatchesFilenameStem_rejectsPartialProjectOnly() {
        val stem = "project plan draft"
        val tokens = listOf("project", "certificate", "mudith")
        assertFalse(QueryScoring.queryMatchesFilenameStem(stem, tokens))
    }
}
