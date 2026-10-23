package com.hismith.session.ui

import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import com.hismith.session.MainActivity
import com.hismith.session.R
import com.hismith.session.core.Recipe

/** The Mega-Lube recipe with a batch calculator (grams scale, osmolality is recalculated). */
class RecipeTab(private val a: MainActivity) {
    private val prefs = a.settings.prefs

    private val variantStd: TextView = a.findViewById(R.id.recVariantStd)
    private val variantX: TextView = a.findViewById(R.id.recVariantX)
    private val batch: EditText = a.findViewById(R.id.recBatch)
    private val anhydrous: Switch = a.findViewById(R.id.recAnhydrous)
    private val preservative: Switch = a.findViewById(R.id.recPreservative)
    private val longer: Switch = a.findViewById(R.id.recLonger)
    private val thinner: Switch = a.findViewById(R.id.recThinner)
    private val whiter: Switch = a.findViewById(R.id.recWhiter)
    private val lessSalt: Switch = a.findViewById(R.id.recLessSalt)
    private val stdOptions: View = a.findViewById(R.id.recStdOptions)
    private val xOptions: View = a.findViewById(R.id.recXOptions)
    private val xlube: SeekBar = a.findViewById(R.id.recXlube)
    private val xlubeVal: TextView = a.findViewById(R.id.recXlubeVal)
    private val table: TableLayout = a.findViewById(R.id.recTable)
    private val osm: TextView = a.findViewById(R.id.recOsm)
    private val notes: TextView = a.findViewById(R.id.recNotes)
    private val steps: TextView = a.findViewById(R.id.recSteps)

    private var variant = Recipe.Variant.STANDARD
    private var loading = true

    init {
        a.findViewById<TextView>(R.id.recipeIntro).text = html(RecipeText.INTRO)
        a.findViewById<TextView>(R.id.recSafety).text = html(RecipeText.SAFETY)
        a.findViewById<TextView>(R.id.recLikeness).text = html(RecipeText.LIKENESS)
        a.findViewById<TextView>(R.id.recSources).apply {
            text = html(RecipeText.SOURCES)
            movementMethod = LinkMovementMethod.getInstance()
        }

        variant = if (prefs.getString("rec_variant", null) == Recipe.Variant.XLUBE.name) Recipe.Variant.XLUBE else Recipe.Variant.STANDARD
        batch.setText(prefs.getInt("rec_batch", 1000).toString())
        anhydrous.isChecked = prefs.getBoolean("rec_anhydrous", false)
        preservative.isChecked = prefs.getBoolean("rec_preservative", false)
        longer.isChecked = prefs.getBoolean("rec_longer", false)
        thinner.isChecked = prefs.getBoolean("rec_thinner", false)
        whiter.isChecked = prefs.getBoolean("rec_whiter", false)
        lessSalt.isChecked = prefs.getBoolean("rec_less_salt", false)
        xlube.progress = prefs.getInt("rec_xlube_half", 2) // 3 g + 0.5 g steps → 4 g

        variantStd.setOnClickListener { variant = Recipe.Variant.STANDARD; update() }
        variantX.setOnClickListener { variant = Recipe.Variant.XLUBE; update() }
        listOf(anhydrous, preservative, longer, thinner, whiter, lessSalt).forEach { sw ->
            sw.setOnCheckedChangeListener { _, _ -> update() }
        }
        xlube.onChange({ _, _ -> update() })
        batch.setOnFocusChangeListener { _, has -> if (!has) update() }
        batch.setOnEditorActionListener { _, _, _ -> update(); false }
        a.findViewById<Button>(R.id.recBatch100).setOnClickListener { batch.setText("100"); update() }
        a.findViewById<Button>(R.id.recBatch1k).setOnClickListener { batch.setText("1000"); update() }
        a.findViewById<Button>(R.id.recBatch5k).setOnClickListener { batch.setText("5000"); update() }
        loading = false
        update()
    }

    @Suppress("DEPRECATION")
    private fun html(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)

    private fun xlubePerKg(): Double = 3.0 + xlube.progress * 0.5

    private fun update() {
        if (loading) return
        val grams = batch.intOr(1000).coerceIn(50, 20_000)
        val opts = Recipe.Options(
            variant = variant,
            batchGrams = grams,
            anhydrousCitrate = anhydrous.isChecked,
            preservative = preservative.isChecked,
            longerStrings = longer.isChecked,
            thinner = thinner.isChecked,
            whiter = whiter.isChecked,
            lessSalt = lessSalt.isChecked,
            xlubePerKg = xlubePerKg(),
        )
        prefs.edit()
            .putString("rec_variant", variant.name)
            .putInt("rec_batch", grams)
            .putBoolean("rec_anhydrous", opts.anhydrousCitrate)
            .putBoolean("rec_preservative", opts.preservative)
            .putBoolean("rec_longer", opts.longerStrings)
            .putBoolean("rec_thinner", opts.thinner)
            .putBoolean("rec_whiter", opts.whiter)
            .putBoolean("rec_less_salt", opts.lessSalt)
            .putInt("rec_xlube_half", xlube.progress)
            .apply()

        val std = variant == Recipe.Variant.STANDARD
        variantStd.isSelected = std
        variantX.isSelected = !std
        variantStd.setTextColor(a.getColor(if (std) R.color.gold_pale else R.color.muted))
        variantX.setTextColor(a.getColor(if (!std) R.color.gold_pale else R.color.muted))
        stdOptions.visibility = if (std) View.VISIBLE else View.GONE
        xOptions.visibility = if (std) View.GONE else View.VISIBLE
        xlubeVal.text = "${Ui.num(xlubePerKg())} g"

        val r = Recipe.calculate(opts)
        table.removeAllViews()
        table.addView(row("Ingrediens", "per kg", "$grams g", header = true))
        r.lines.forEach { line ->
            table.addView(row("${line.name}\n${line.role}", grams(line.perKg), grams(line.grams), header = false))
        }
        osm.text = "Beräknad osmolalitet: cirka ${r.osmolality} mOsm/kg (spann ${r.osmLow}–${r.osmHigh}). WHO önskvärt: under ${Recipe.WHO_DESIRABLE}."
        osm.setTextColor(a.getColor(if (r.osmHigh > Recipe.WHO_DESIRABLE) R.color.amber else R.color.green))
        notes.text = (r.notes + "Väg allt på en 0,01 g-våg – osmolaliteten går inte att mäta hemma.").joinToString("\n• ", prefix = "• ")
        steps.text = html(if (std) RecipeText.STEPS_STANDARD else RecipeText.STEPS_XLUBE)
    }

    private fun grams(g: Double): String = when {
        g >= 100 -> Ui.num(g, 0) + " g"
        g >= 10 -> Ui.num(g, 1) + " g"
        else -> Ui.num(g, 2) + " g"
    }

    private fun row(name: String, perKg: String, amount: String, header: Boolean): TableRow {
        val color = a.getColor(if (header) R.color.gold else R.color.text)
        fun cell(text: String, gravity: Int, bold: Boolean) = TextView(a).apply {
            this.text = text
            this.gravity = gravity
            setTextColor(if (!header && gravity != Gravity.START) a.getColor(R.color.gold_pale) else color)
            textSize = if (header) 11f else 13f
            if (bold || header) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 4), Ui.dp(a, 6))
        }
        return TableRow(a).apply {
            addView(cell(name, Gravity.START, false))
            addView(cell(perKg, Gravity.END, false))
            addView(cell(amount, Gravity.END, true))
        }
    }
}

/** Text from the Mega-Lube document (Swedish, kept close to the original wording). */
object RecipeText {
    const val INTRO = """En mjölkvit, trådig gel på cirka <b>311 mOsm/kg</b> (beräknat, spann 280–342) – under WHO:s önskvärda gräns 380. Titandioxid (E171) är utbytt mot kalciumkarbonat (E170), eftersom E171 är förbjudet som livsmedelstillsats i EU sedan 2022.<br><br>
<b>Bara för anal och oral användning – inte vaginalt</b> (pH 8–8,5). Gör alltid ett armvecks-test först."""

    const val STEPS_STANDARD = """1. Diska all utrustning, skölj med 70 % isopropanol, låt torka och skölj sedan med kokt, avsvalnat vatten.<br>
2. Lös koksalt, kaliumklorid och citrat i cirka 90 % av vattnet.<br>
3. Blanda HEC, xantan och kalciumkarbonat torrt. Rör ut dem med glycerinet till en slät pasta utan klumpar.<br>
4. Häll pastan i vattnet under stavmixer på låg effekt, 1–2 minuter. Undvik luft.<br>
5. Låt svälla i en timme och rör om var 15:e minut.<br>
6. Mät pH med strips. Kalciumkarbonat håller den kring pH 8–8,5 (beräknat, ej mätt).<br>
7. Sila genom en fin sil (cirka 0,5 mm) så att inga klumpar fastnar i slang eller koppling.<br>
8. Fyll dunken, märk med datum och ställ i kylen. <b>Rör upp före varje användning</b> – kalken sjunker."""

    const val STEPS_XLUBE = """1. Rengör utrustningen som i grundreceptet.<br>
2. Lös först salterna i vattnet. Strö sedan pulvret <b>långsamt</b> i vattnet medan du rör om – aldrig tvärtom, annars blir det klumpar.<br>
3. Rör eller skaka, vila 15–20 minuter, rör eller skaka igen. Tillverkaren anger en jämn gel efter cirka 20 minuter.<br>
4. Kör inte stavmixern länge – PEO är känsligt för hård bearbetning. Rör hellre.<br>
5. Rör ut kalciumkarbonat, xantan och glycerin i lite vatten till en slät gröt och vänd försiktigt ned den i gelen.<br>
6. Sila, fyll dunken, märk med datum och förvara kallt.<br><br>
<b>Justera:</b> för tunn gel – öka pulvret 0,5 g per kilo i taget och vänta 20 minuter mellan stegen. För tjock för pumpen – späd med vatten. Börja hellre tunt.<br><br>
<b>Osäkert, prova först:</b> salt gör PEO-gel tunnare, så räkna med 5–6 g. Kalken sjunker snabbare i tunn PEO-gel. Mät flödet genom pumpen med gelen och kalibrera om – hård bearbetning kan bryta PEO."""

    const val SAFETY = """• pH är cirka 8–8,5. Sperma ligger på 7,2–8,0, men WHO:s råd för anal och allmän användning är 5,5–7 och för vaginal användning 4,5. <b>Använd därför inte receptet vaginalt.</b><br>
• Prova på armvecket och vänta 24 timmar före första användningen. Avbryt vid sveda, klåda eller rodnad.<br>
• Utan konserveringsmedel: <b>högst 3 dygn i kyl</b>. Släng vid ny lukt, färg eller mögelprickar.<br>
• Valfritt: 1 % fenoxietanol (10 g per kilo) och sänk koksaltet till 3,0 g – ger cirka 339 mOsm/kg. Utan sänkt salt blir det 390, över gränsen. SCCS bedömer fenoxietanol som säkert upp till 1,0 % i kosmetika men tar inte upp slemhinnor, därför är färsk sats standard.<br>
• Sorbat och bensoat fungerar inte här: de behöver surt pH, och kalken tar bort syran.<br>
• Efter varje pass: pumpa tillbaka gelen i dunken (Lube-fliken → Töm slangen, högst 60 % bakåt), spola med ljummet vatten (högst 40 °C), sedan kokt vatten, och låt torka.<br>
• En peristaltisk pump kan bygga upp högt tryck. Börja lågt, ha nödstoppet inom räckhåll och kör aldrig mot en blockerad utgång."""

    const val LIKENESS = """Konsistens och trådighet går att komma mycket nära med xantan och HEC (eller PEO i XLube-varianten). Färgen blir vit och ogenomskinlig, medan sperma är gråvit och halvgenomskinlig. Smaken blir mild och salt: salt, lite bitterhet från kaliumkloriden, syrlighet från citrat och mild sötma från glycerin.<br><br>
Doften går inte att efterlikna säkert – den kommer delvis från aminer, och ämnen som luktar så irriterar slemhinnor. Därför är hjorthornssalt och zinkglukonat borta. Låt gelen vara oparfymerad, eller tillsätt högst 0,05 % vattenlösligt, alkoholfritt vaniljarom. Ändra en sak i taget och gör hellre en liten sats på 100 g."""

    const val SOURCES = """• <a href="https://womensvoices.org/osmolality-ph-properties-commercial-lubricants">Women's Voices for the Earth</a> – WHO:s gränser 380/1200 mOsm/kg<br>
• <a href="https://extranet.who.int/prequal/node/31776">WHO Prequalification: Personal Lubricants &amp; Osmolality</a><br>
• <a href="https://gfmer.ch/Books/Reproductive_health/Infertility_evaluation_Table2.html">GFMER: spermaparametrar</a> – pH 7,2–8,0<br>
• <a href="https://www.reedsmith.com/our-insights/blogs/ehs-law-insights/102k44h/titanium-dioxide-e171-banned-as-an-additive-in-foods-in-the-eu/">Reed Smith: E171 förbjudet i EU</a><br>
• <a href="https://www.cosmeticsdesign-europe.com/Article/2016/05/12/Latest-SCCS-ruling-finds-Phenoxyethanol-safe-in-cosmetics">SCCS om fenoxietanol</a><br>
• <a href="https://xlube.com/products/xlube-powder-lubricant">XLube, tillverkarens sida</a>"""
}
