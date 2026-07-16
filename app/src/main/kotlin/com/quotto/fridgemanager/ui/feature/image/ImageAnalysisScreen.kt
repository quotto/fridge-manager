package com.quotto.fridgemanager.ui.feature.image

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quotto.fridgemanager.ui.component.ScreenHeader

@Composable
fun ImageAnalysisScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "画像解析")
        Text(
            text = "撮影または画像選択を開始する画面です",
            modifier = Modifier.padding(24.dp),
        )
    }
}
