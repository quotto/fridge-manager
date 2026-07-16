package com.quotto.fridgemanager.ui.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quotto.fridgemanager.ui.component.ScreenHeader

@Composable
fun SettingsScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "設定")
        Text(
            text = "利用データの削除",
            modifier = Modifier.padding(24.dp),
        )
    }
}
