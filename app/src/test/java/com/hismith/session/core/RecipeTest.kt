package com.hismith.session.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeTest {

    @Test
    fun baseRecipeMatchesDocument() {
        val r = Recipe.calculate(Recipe.Options())
        assertEquals(311, r.osmolality)
        assertEquals(280, r.osmLow)
        assertEquals(342, r.osmHigh)
        assertEquals(1000.0, r.lines.sumOf { it.grams }, 1e-9)
        assertEquals(970.0, r.lines.first().grams, 1e-9)
    }

    @Test
    fun scalesToBatch() {
        val r = Recipe.calculate(Recipe.Options(batchGrams = 5000))
        assertEquals(5000.0, r.lines.sumOf { it.grams }, 1e-9)
        assertEquals(4850.0, r.lines.first().grams, 1e-9)
        assertEquals(22.5, r.lines.first { it.name.startsWith("Koksalt") }.grams, 1e-9)
    }

    @Test
    fun preservativeLowersSaltAndStaysUnderWhoLimit() {
        val r = Recipe.calculate(Recipe.Options(preservative = true))
        assertEquals(3.0, r.lines.first { it.name.startsWith("Koksalt") }.perKg, 1e-9)
        assertEquals(339, r.osmolality)
        assertTrue(r.osmolality < Recipe.WHO_DESIRABLE)
    }

    @Test
    fun anhydrousCitrateWeighsLess() {
        val r = Recipe.calculate(Recipe.Options(anhydrousCitrate = true))
        assertEquals(1.75, r.lines.first { it.name.startsWith("Natriumcitrat") }.perKg, 1e-9)
        assertEquals(311, r.osmolality)
    }

    @Test
    fun lessSaltSavesAbout33() {
        val base = Recipe.calculate(Recipe.Options()).osmolality
        val less = Recipe.calculate(Recipe.Options(lessSalt = true)).osmolality
        assertEquals(33, base - less)
    }

    @Test
    fun xlubeVariant() {
        val r = Recipe.calculate(Recipe.Options(variant = Recipe.Variant.XLUBE, xlubePerKg = 5.0))
        assertEquals(5.0, r.lines.first { it.name.startsWith("XLube") }.perKg, 1e-9)
        assertTrue(r.lines.none { it.name.startsWith("HEC") })
        assertEquals(1000.0, r.lines.sumOf { it.grams }, 1e-9)
    }
}
