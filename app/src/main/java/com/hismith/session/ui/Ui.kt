package com.hismith.session.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Toast
import com.hismith.session.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Small UI helpers shared by the tabs. */
object Ui {
    val SV: Locale = Locale.forLanguageTag("sv-SE")

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun dialog(context: Context): AlertDialog.Builder = AlertDialog.Builder(context, R.style.Dialog_Hismith)

    fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** "1,5" / "1.5" → 1.5. Swedish keyboards type a comma. */
    fun parseDouble(s: CharSequence?): Double? = s?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()

    fun ml(v: Double): String {
        val rounded = (v * 10).roundToLong() / 10.0
        return if (abs(rounded - rounded.toLong()) < 1e-9) "${rounded.toLong()} ml"
        else String.format(SV, "%.1f ml", rounded)
    }

    fun num(v: Double, decimals: Int = 1): String {
        val f = String.format(SV, "%.${decimals}f", v)
        return if (decimals > 0) f.trimEnd('0').trimEnd(',') else f
    }

    fun clock(sec: Long): String {
        val s = sec.coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.4f
    }
}

fun SeekBar.onChange(block: (value: Int, fromUser: Boolean) -> Unit, onStop: ((Int) -> Unit)? = null) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = block(progress, fromUser)
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) { onStop?.invoke(seekBar.progress) }
    })
}

fun EditText.intOr(def: Int): Int = text.toString().trim().toIntOrNull() ?: def

fun EditText.doubleOr(def: Double): Double = Ui.parseDouble(text) ?: def

/** Sets text only when the user is not editing the field (state updates arrive 10×/s). */
fun EditText.setIfIdle(value: String) {
    if (!hasFocus() && text.toString() != value) setText(value)
}

fun <T> Spinner.setup(context: Context, items: List<T>, label: (T) -> String, onPick: (T) -> Unit = {}) {
    val adapter = ArrayAdapter(context, R.layout.spinner_item, items.map(label))
    adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
    this.adapter = adapter
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onPick(items[position])
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}
