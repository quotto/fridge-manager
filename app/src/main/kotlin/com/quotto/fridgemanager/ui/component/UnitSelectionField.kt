package com.quotto.fridgemanager.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.quotto.fridgemanager.domain.inventory.InventoryUnit

@Composable
fun UnitSelectionField(
    selectedSymbol: String,
    modifier: Modifier = Modifier,
    onSelected: (String) -> Unit,
) {
    var dialogVisible by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(modifier) {
        Text("単位（必須）")
        OutlinedButton(
            onClick = { dialogVisible = true },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = if (selectedSymbol.isBlank()) {
                    "単位、必須、未入力"
                } else {
                    "単位、必須、現在値は$selectedSymbol"
                }
            },
        ) {
            Text(if (selectedSymbol.isBlank()) "単位を選択（未入力）" else selectedSymbol)
        }
    }

    if (dialogVisible) {
        AlertDialog(
            onDismissRequest = { dialogVisible = false },
            title = { Text("単位を選択") },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(InventoryUnit.entries, key = { it.symbol }) { unit ->
                        val selected = unit.symbol == selectedSymbol
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        dialogVisible = false
                                        onSelected(unit.symbol)
                                    },
                                )
                                .semantics { contentDescription = "単位 ${unit.symbol}" },
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(unit.symbol, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogVisible = false }) { Text("閉じる") }
            },
        )
    }
}
