package com.hismith.session.core

import kotlin.math.roundToInt

/**
 * Scales the lube recipe from the Mega-Lube document. Amounts are grams per kilo; water is
 * the remainder so every batch always adds up. Osmolality is the document's calculated
 * contribution per ingredient (not measured).
 */
object Recipe {

    enum class Variant(val label: String) { STANDARD("Grundrecept (HEC/CMC)"), XLUBE("XLube som bas") }

    data class Options(
        val variant: Variant = Variant.STANDARD,
        val batchGrams: Int = 1000,
        val anhydrousCitrate: Boolean = false,
        val preservative: Boolean = false,
        val longerStrings: Boolean = false,
        val thinner: Boolean = false,
        val whiter: Boolean = false,
        val lessSalt: Boolean = false,
        /** XLube powder per kilo (manufacturer gives no ratio – start at 4 g, test 3–6 g). */
        val xlubePerKg: Double = 4.0,
    )

    data class Line(val name: String, val grams: Double, val perKg: Double, val role: String)

    data class Result(val lines: List<Line>, val osmolality: Int, val osmLow: Int, val osmHigh: Int, val notes: List<String>)

    /** WHO: 380 mOsm/kg desirable, 1200 acceptable. */
    const val WHO_DESIRABLE = 380

    // mOsm/kg per gram/kg, from the document's table (311 total for the base recipe).
    private const val OSM_GLYCERIN = 11.2
    private const val OSM_NACL = 148.0 / 4.5
    private const val OSM_KCL = 26.0
    private const val OSM_CITRATE_DIHYDRATE = 11.0
    private const val OSM_CITRATE_ANHYDROUS = 22.0 / 1.75
    private const val OSM_CACO3 = 2.0 / 7.0
    private const val OSM_HEC = 0.25
    private const val OSM_PHENOXYETHANOL = 7.73

    fun calculate(o: Options): Result {
        val batch = o.batchGrams.coerceIn(50, 20_000)
        val items = ArrayList<Triple<String, Double, String>>()
        var osm = 0.0
        val notes = ArrayList<String>()

        val salt = when {
            o.preservative -> 3.0
            o.lessSalt -> 3.5
            else -> 4.5
        }
        val citrate = if (o.anhydrousCitrate) 1.75 else 2.0
        val citrateName = if (o.anhydrousCitrate) "Natriumcitrat (vattenfritt)" else "Natriumcitrat (dihydrat)"

        items += Triple("Glycerin 99,5 %", 10.0, "glid, mild sötma"); osm += 10.0 * OSM_GLYCERIN
        items += Triple("Koksalt (NaCl)", salt, "salthet"); osm += salt * OSM_NACL
        items += Triple("Kaliumklorid", 1.0, "elektrolytbalans"); osm += 1.0 * OSM_KCL
        items += Triple(citrateName, citrate, "citrat, som i sperma")
        osm += citrate * if (o.anhydrousCitrate) OSM_CITRATE_ANHYDROUS else OSM_CITRATE_DIHYDRATE
        val caco3 = if (o.whiter) 10.0 else 7.0
        items += Triple("Kalciumkarbonat E170", caco3, "kritvit färg"); osm += caco3 * OSM_CACO3

        when (o.variant) {
            Variant.STANDARD -> {
                val hec = if (o.thinner) 3.0 else 4.0
                val xanthan = if (o.longerStrings) 2.0 else 1.5
                items += Triple("HEC (eller CMC)", hec, "kropp"); osm += hec * OSM_HEC
                items += Triple("Xantangummi", xanthan, "trådighet")
                if (o.thinner) notes += "Tunnare: HEC 3 g/kg. Räcker inte det, sänk xantan till 1,0 g."
                if (o.longerStrings) notes += "Längre trådar: xantan 2,0 g/kg (prova upp till 2,5 g)."
            }
            Variant.XLUBE -> {
                val x = o.xlubePerKg.coerceIn(2.0, 8.0)
                items += Triple("XLube-pulver", x, "gelen och trådarna")
                items += Triple("Xantangummi", 1.0, "håller kalken svävande (valfritt)")
                notes += "XLube: oprovade startvärden. Salt gör PEO-gel tunnare – räkna med 5–6 g och öka 0,5 g i taget."
            }
        }
        if (o.preservative) {
            items += Triple("Fenoxietanol", 10.0, "konservering (1 %)"); osm += 10.0 * OSM_PHENOXYETHANOL
            notes += "Med fenoxietanol sänks koksaltet till 3,0 g/kg, annars hamnar osmolaliteten över 380."
        }
        if (o.whiter) notes += "Vitare: mer kalk blir sandigare och höjer pH."
        if (o.lessSalt && !o.preservative) notes += "Mindre salt sparar cirka 33 mOsm/kg."

        val solids = items.sumOf { it.second }
        val water = 1000.0 - solids
        val lines = ArrayList<Line>()
        lines += Line("Vatten (destillerat, kokat, avsvalnat)", water * batch / 1000.0, water, "lösningsmedel")
        items.forEach { (name, perKg, role) -> lines += Line(name, perKg * batch / 1000.0, perKg, role) }

        val total = osm.roundToInt()
        // The document gives a ±10 % band around the calculated value.
        return Result(lines, total, (osm * 0.9).roundToInt(), (osm * 1.1).roundToInt(), notes)
    }
}
