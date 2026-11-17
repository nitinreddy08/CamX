package com.nitin.filmcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.filter.Tint
import com.nitin.filmcam.ui.theme.InkSoft
import com.nitin.filmcam.ui.theme.Paper
import com.nitin.filmcam.ui.theme.Sand
import com.nitin.filmcam.ui.theme.Terracotta

/**
 * The filter "sheet": slides in above the camera controls (instead of a modal sheet that would
 * cover the preview), so every slider change is visible live in the image.
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
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        presetSection()

        LabeledSlider("Intensity", params.intensity, 0f..1f, { onChange(params.copy(intensity = it)) })
        LabeledSlider("Grain amount", params.grainAmount, 0f..0.25f, { onChange(params.copy(grainAmount = it)) }, decimals = 3)
        LabeledSlider("Grain size", params.grainSize, 0.8f..4f, { onChange(params.copy(grainSize = it)) })
        LabeledSlider("Softness", params.softness, 0f..1f, { onChange(params.copy(softness = it)) })
        LabeledSlider("Vignette", params.vignette, 0f..1f, { onChange(params.copy(vignette = it)) })

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReset) { Text("Reset to default", color = Terracotta) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Advanced ▴" else "Advanced ▾", color = InkSoft)
            }
        }

        if (advanced) {
            HorizontalDivider(color = Sand)
            Spacer(Modifier.height(6.dp))
            SectionTitle("Tone")
            LabeledSlider("Contrast", params.contrast, 0.5f..1.2f, { onChange(params.copy(contrast = it)) })
            LabeledSlider("Black lift", params.blackLift, 0f..0.2f, { onChange(params.copy(blackLift = it)) }, decimals = 3)
            LabeledSlider("White roll-off", params.whiteRoll, 0.8f..1f, { onChange(params.copy(whiteRoll = it)) }, decimals = 3)
            SectionTitle("Colour")
            LabeledSlider("Saturation", params.saturation, 0f..1.5f, { onChange(params.copy(saturation = it)) })
            LabeledSlider("Green desaturation", params.greenDesat, 0f..1f, { onChange(params.copy(greenDesat = it)) })
            LabeledSlider("Green → olive", params.greenToOlive, 0f..0.1f, { onChange(params.copy(greenToOlive = it)) }, decimals = 3)
            TintSliders("Shadow tint", params.shadowTint) { onChange(params.copy(shadowTint = it)) }
            TintSliders("Midtone tint", params.midTint) { onChange(params.copy(midTint = it)) }
            TintSliders("Highlight tint", params.highlightTint) { onChange(params.copy(highlightTint = it)) }
            Spacer(Modifier.height(8.dp))
        }
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
