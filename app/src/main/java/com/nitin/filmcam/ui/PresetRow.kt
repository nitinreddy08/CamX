package com.nitin.filmcam.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nitin.filmcam.filter.FilmParams
import com.nitin.filmcam.filter.PresetCodec
import com.nitin.filmcam.ui.theme.Ink
import com.nitin.filmcam.ui.theme.InkSoft
import com.nitin.filmcam.ui.theme.Paper
import com.nitin.filmcam.ui.theme.Sand
import com.nitin.filmcam.ui.theme.Terracotta

/**
 * Named presets: tap to apply, long-press to delete (except the built-ins "Field" and "Golden"),
 * "+ Save" stores the current sliders under a new name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetRow(
    presets: Map<String, FilmParams>,
    current: FilmParams,
    onApply: (FilmParams) -> Unit,
    onSave: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(bottom = 4.dp)) {
        Text("Presets", style = MaterialTheme.typography.labelLarge, color = Terracotta)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            items(presets.entries.toList(), key = { it.key }) { (name, p) ->
                val selected = p == current
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Terracotta else Paper)
                        .border(1.dp, Sand, RoundedCornerShape(50))
                        .combinedClickable(
                            onClick = { onApply(p) },
                            onLongClick = { if (name !in FilmParams.BUILT_INS) deleting = name },
                        )
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(name, color = if (selected) Color.White else Ink, fontSize = 13.sp)
                }
            }
            item {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, Terracotta, RoundedCornerShape(50))
                        .combinedClickable(onClick = { saving = true })
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) { Text("+ Save", color = Terracotta, fontSize = 13.sp) }
            }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf("") }
        val clean = PresetCodec.cleanPresetName(name)
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save preset") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                    if (clean != null && presets.containsKey(clean)) {
                        Text("Will overwrite \"$clean\".", color = InkSoft, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = clean != null, onClick = { clean?.let(onSave); saving = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }

    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"$name\"?") },
            confirmButton = { TextButton(onClick = { onDelete(name); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
