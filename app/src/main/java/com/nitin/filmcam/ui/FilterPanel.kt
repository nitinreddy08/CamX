package com.nitin.filmcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.filter.HueAdjust
import com.nitin.filmcam.filter.HueBand
import com.nitin.filmcam.filter.NEUTRAL_HUES
import com.nitin.filmcam.filter.Tint
import com.nitin.filmcam.ui.theme.Ink
import com.nitin.filmcam.ui.theme.InkSoft
import com.nitin.filmcam.ui.theme.Paper
import com.nitin.filmcam.ui.theme.Sand
import com.nitin.filmcam.ui.theme.Terracotta

/**
 * The filter "sheet": slides in above the camera controls (instead of a modal sheet that would
 * cover the preview). Its height is capped at about a third of the screen so the preview stays
 * big while you drag sliders.
 */
@Composable
fun FilterPanel(
    params: FilmParams,
    onChange: (FilmParams) -> Unit,
    onReset: () -> Unit,
    presetSection: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.34f).dp
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        presetSection()

        LabeledSlider("Intensity", params.intensity, 0f..1f, { onChange(params.copy(intensity = it)) })
        LabeledSlider("Exposure", params.exposure, -1f..1f, { onChange(params.copy(exposure = it)) })
        LabeledSlider("Warmth", params.warmth, -1f..1f, { onChange(params.copy(warmth = it)) })
        LabeledSlider("Grain amount", params.grainAmount, 0f..0.25f, { onChange(params.copy(grainAmount = it)) }, decimals = 3)
        LabeledSlider("Grain size", params.grainSize, 0.8f..4f, { onChange(params.copy(grainSize = it)) })
        LabeledSlider("Halation (glow)", params.halation, 0f..1f, { onChange(params.copy(halation = it)) })
        LabeledSlider("Softness", params.softness, 0f..1f, { onChange(params.copy(softness = it)) })
        LabeledSlider("Vignette (dark corners)", params.vignette, 0f..1f, { onChange(params.copy(vignette = it)) })

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReset) { Text("Reset to Field", color = Terracotta) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Advanced ▴" else "Advanced ▾", color = InkSoft)
            }
        }

        if (advanced) {
            HorizontalDivider(color = Sand)
            Spacer(Modifier.height(6.dp))
            SectionTitle("Tone")
            LabeledSlider("Film curve (0 = classic, 1 = film)", params.filmCurve, 0f..1f, { onChange(params.copy(filmCurve = it)) })
            LabeledSlider("Contrast", params.contrast, 0.5f..1.2f, { onChange(params.copy(contrast = it)) })
            LabeledSlider("Black lift (fade)", params.blackLift, 0f..0.2f, { onChange(params.copy(blackLift = it)) }, decimals = 3)
            LabeledSlider("White roll-off", params.whiteRoll, 0.8f..1f, { onChange(params.copy(whiteRoll = it)) }, decimals = 3)

            SectionTitle("Colour")
            LabeledSlider("Saturation", params.saturation, 0f..1.5f, { onChange(params.copy(saturation = it)) })
            LabeledSlider("Pastel highlights", params.pastel, 0f..1f, { onChange(params.copy(pastel = it)) })
            LabeledSlider("Dye mixing", params.dyeMix, 0f..1f, { onChange(params.copy(dyeMix = it)) })
            LabeledSlider("Colour density", params.colorDensity, 0f..1f, { onChange(params.copy(colorDensity = it)) })
            LabeledSlider("Green desaturation", params.greenDesat, 0f..1f, { onChange(params.copy(greenDesat = it)) })
            LabeledSlider("Green → olive", params.greenToOlive, 0f..0.1f, { onChange(params.copy(greenToOlive = it)) }, decimals = 3)

            ColourBands(params, onChange)

            TintSliders("Shadow tint", params.shadowTint) { onChange(params.copy(shadowTint = it)) }
            TintSliders("Midtone tint", params.midTint) { onChange(params.copy(midTint = it)) }
            TintSliders("Highlight tint", params.highlightTint) { onChange(params.copy(highlightTint = it)) }

            SectionTitle("Grain character")
            LabeledSlider("Film grain model (0 = classic, 1 = film)", params.grainDensity, 0f..1f, { onChange(params.copy(grainDensity = it)) })
            LabeledSlider("Colour grain", params.grainColor, 0f..1f, { onChange(params.copy(grainColor = it)) })

            SectionTitle("Video")
            LabeledSlider("Film motion (weave + flicker)", params.filmMotion, 0f..1f, { onChange(params.copy(filmMotion = it)) })
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Swatch colours for the band chips (display only). */
private val BAND_SWATCH = mapOf(
    HueBand.RED to Color(0xFFC0392B), HueBand.ORANGE to Color(0xFFE08A4F), HueBand.YELLOW to Color(0xFFE2C044),
    HueBand.GREEN to Color(0xFF5E8C4A), HueBand.TEAL to Color(0xFF4FA3A5), HueBand.BLUE to Color(0xFF4062A8),
    HueBand.PURPLE to Color(0xFF7A55A3), HueBand.MAGENTA to Color(0xFFB04E8F),
)

/** Per-colour controls: pick a colour band, then set its saturation, brightness and hue shift. */
@Composable
private fun ColourBands(params: FilmParams, onChange: (FilmParams) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(HueBand.ORANGE) }
    SectionTitle("Colours (one at a time)")
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (band in HueBand.entries) {
            val isSel = band == selected
            val changed = !params.hue(band).isNeutral
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSel) Sand else Paper)
                    .border(1.dp, if (isSel) Terracotta else Sand, RoundedCornerShape(50))
                    .clickable { selected = band }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(BAND_SWATCH.getValue(band)))
                Spacer(Modifier.width(6.dp))
                Text(band.label + if (changed) " •" else "", fontSize = 12.sp, color = Ink)
            }
        }
    }
    val adj = params.hue(selected)
    LabeledSlider("${selected.label}: saturation", adj.saturation, 0f..1.5f,
        { onChange(params.withHue(selected, adj.copy(saturation = it))) })
    LabeledSlider("${selected.label}: brightness", adj.brightness, -1f..1f,
        { onChange(params.withHue(selected, adj.copy(brightness = it))) })
    LabeledSlider("${selected.label}: hue shift", adj.shift, -1f..1f,
        { onChange(params.withHue(selected, adj.copy(shift = it))) })
    Row {
        TextButton(onClick = { onChange(params.withHue(selected, HueAdjust())) }) { Text("Reset ${selected.label}", color = InkSoft) }
        TextButton(onClick = { onChange(params.copy(hues = NEUTRAL_HUES)) }) { Text("Reset all colours", color = InkSoft) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Terracotta, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun TintSliders(label: String, tint: Tint, onChange: (Tint) -> Unit) {
    SectionTitle(label)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        LabeledSlider("Red", tint.r, -0.1f..0.1f, { onChange(tint.copy(r = it)) }, decimals = 3)
        LabeledSlider("Green", tint.g, -0.1f..0.1f, { onChange(tint.copy(g = it)) }, decimals = 3)
        LabeledSlider("Blue", tint.b, -0.1f..0.1f, { onChange(tint.copy(b = it)) }, decimals = 3)
    }
}
