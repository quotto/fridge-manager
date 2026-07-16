package com.quotto.fridgemanager.ui.feature.registration

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quotto.fridgemanager.ui.component.ScreenHeader

@Composable
fun RegistrationScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "手動登録", onBack = onBack)
        Text(
            text = "食材名・在庫数・単位を入力する画面です",
            modifier = Modifier.padding(24.dp),
        )
    }
}
