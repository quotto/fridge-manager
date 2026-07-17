package com.quotto.fridgemanager.ui.feature.registration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.presentation.registration.RegistrationPresenter
import com.quotto.fridgemanager.presentation.registration.ExistingIngredientResult
import com.quotto.fridgemanager.ui.component.ScreenHeader

/** #15の編集実装へ既存在庫IDを安全に引き渡す更新導線。 */
@Composable
fun ExistingIngredientUpdateScreen(
    ingredientId: String,
    presenter: RegistrationPresenter,
    onBack: () -> Unit,
) {
    var ingredient by remember(ingredientId) { mutableStateOf<StoredIngredient?>(null) }
    var loaded by remember(ingredientId) { mutableStateOf(false) }
    var failed by remember(ingredientId) { mutableStateOf(false) }
    LaunchedEffect(ingredientId) {
        when (val result = presenter.existingIngredient(ingredientId)) {
            is ExistingIngredientResult.Found -> ingredient = result.ingredient
            ExistingIngredientResult.NotFound -> Unit
            ExistingIngredientResult.Failed -> failed = true
        }
        loaded = true
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "在庫を更新", onBack = onBack)
        ingredient?.let { item ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("既存在庫の更新操作に切り替えました")
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(item.name.value)
                        Text("現在の在庫: ${item.quantity} ${item.unit.symbol}")
                    }
                }
                Text("在庫数の編集は次の実装で利用できます")
            }
        } ?: Text(
            if (failed) "在庫を読み込めませんでした。戻ってやり直してください"
            else if (loaded) "対象の在庫が見つかりません。戻って選び直してください"
            else "在庫を読み込んでいます",
            modifier = Modifier.padding(24.dp),
        )
    }
}
