package com.quotto.fridgemanager.ui.feature.candidate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.quotto.fridgemanager.domain.analysis.AnalysisApiResult
import com.quotto.fridgemanager.domain.analysis.AnalysisCandidate
import com.quotto.fridgemanager.domain.inventory.IngredientDraft
import com.quotto.fridgemanager.domain.inventory.InventoryBatch
import com.quotto.fridgemanager.domain.inventory.InventoryRepository
import com.quotto.fridgemanager.domain.inventory.StoredIngredient
import com.quotto.fridgemanager.presentation.candidate.CandidateReviewPresenter
import com.quotto.fridgemanager.presentation.candidate.ReviewedCandidate
import com.quotto.fridgemanager.ui.feature.image.CandidateReviewScreen
import com.quotto.fridgemanager.ui.theme.FridgeManagerTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CandidateReviewScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `候補の根拠と要確認と警告と未入力と既存在庫を表示する`() {
        val repository = RecordingRepository(listOf(storedIngredient()))
        composeRule.setContent {
            FridgeManagerTheme {
                CandidateReviewScreen(
                    result = success(
                        candidates = listOf(
                            AnalysisCandidate("豆腐", "2", "丁", "VISIBLE_COUNT", false),
                            AnalysisCandidate(null, null, null, "VISUAL_ESTIMATE", true),
                        ),
                        warnings = listOf("LOW_CONFIDENCE"),
                    ),
                    presenter = CandidateReviewPresenter(repository),
                    onValidated = {},
                )
            }
        }

        composeRule.onNodeWithText("根拠: 画像内の個数").assertIsDisplayed()
        composeRule.onNodeWithText("根拠: 画像からの推定").assertIsDisplayed()
        composeRule.onNodeWithText("要確認").assertIsDisplayed()
        composeRule.onNodeWithText("警告: 推定の確度が低い候補があります").assertIsDisplayed()
        composeRule.onNodeWithText("現在の在庫: 1 丁").assertIsDisplayed()
        composeRule.onAllNodesWithText("食材名")[1].assertTextContains("食材名を入力してください")
        composeRule.onAllNodesWithText("推定数量")[1].assertTextContains("在庫数は小数2桁までの数値で入力してください")
    }

    @Test
    fun `候補の追加編集と除外復帰に応じて次へボタンを制御する`() {
        composeRule.setContent {
            FridgeManagerTheme {
                CandidateReviewScreen(
                    result = success(listOf(AnalysisCandidate("卵", "6", "個", "VISIBLE_COUNT", false))),
                    presenter = CandidateReviewPresenter(RecordingRepository()),
                    onValidated = {},
                )
            }
        }

        composeRule.onNodeWithText("在庫に一括反映する").assertIsEnabled()
        composeRule.onNodeWithText("候補を追加する").performClick()
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithText("在庫に一括反映する").assertIsNotEnabled()

        composeRule.onAllNodesWithText("食材名")[1].performTextInput("牛乳")
        composeRule.onAllNodesWithText("推定数量")[1].performTextInput("1")
        composeRule.onNodeWithContentDescription("単位、必須、未入力").performClick()
        composeRule.onNodeWithContentDescription("単位 本").performClick()
        composeRule.onNodeWithText("在庫に一括反映する").assertIsEnabled()

        composeRule.onAllNodesWithText("除外する")[1].performClick()
        composeRule.onNodeWithContentDescription("除外したAI候補").assertIsDisplayed()
        composeRule.onNodeWithText("在庫に一括反映する").assertIsEnabled()
        composeRule.onNodeWithText("候補に戻す").performClick()
        composeRule.onAllNodesWithText("食材名")[1].assertIsDisplayed()
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.onNodeWithText("在庫に一括反映する").assertIsEnabled()
    }

    @Test
    fun `全候補が有効なら一括保存後に通知する`() {
        val repository = RecordingRepository()
        var handedOff: List<ReviewedCandidate> = emptyList()
        composeRule.setContent {
            FridgeManagerTheme {
                CandidateReviewScreen(
                    result = success(listOf(AnalysisCandidate("米", "2", "kg", "VISIBLE_COUNT", false))),
                    presenter = CandidateReviewPresenter(repository),
                    onValidated = { handedOff = it },
                )
            }
        }

        composeRule.onNodeWithText("在庫に一括反映する").performClick()
        composeRule.waitUntil { handedOff.size == 1 }
        assertEquals(1, repository.saveCount)
    }

    @Test
    fun `重複候補は統合するまで一括反映できず数量を自動合算しない`() {
        composeRule.setContent {
            FridgeManagerTheme {
                CandidateReviewScreen(
                    result = success(
                        listOf(
                            AnalysisCandidate("ＮＦＫＣ", "1", "個", "VISIBLE_COUNT", false),
                            AnalysisCandidate("NFKC", "2", "個", "VISIBLE_COUNT", false),
                        ),
                    ),
                    presenter = CandidateReviewPresenter(RecordingRepository()),
                    onValidated = {},
                )
            }
        }

        composeRule.onAllNodesWithText("この候補に統合する")[0].performClick()
        repeat(3) { composeRule.onRoot().performTouchInput { swipeUp() } }
        composeRule.onNodeWithText("在庫に一括反映する").assertIsEnabled()
        composeRule.onAllNodesWithText("推定数量")[0].assertTextContains("1")
    }

    @Test
    fun `既存在庫候補は反映方法を選ぶまで確定できず最終絶対値を表示する`() {
        composeRule.setContent {
            FridgeManagerTheme {
                CandidateReviewScreen(
                    result = success(listOf(AnalysisCandidate("豆腐", "2", "丁", "VISIBLE_COUNT", false))),
                    presenter = CandidateReviewPresenter(RecordingRepository(listOf(storedIngredient()))),
                    onValidated = {},
                )
            }
        }

        composeRule.onNodeWithText("在庫に一括反映する").assertIsNotEnabled()
        composeRule.onNodeWithText("増加").performClick()
        composeRule.onNodeWithText("反映後の在庫: 3 丁").assertIsDisplayed()
        composeRule.onNodeWithText("在庫に一括反映する").assertIsEnabled()
    }
}

private fun success(
    candidates: List<AnalysisCandidate>,
    warnings: List<String> = emptyList(),
) = AnalysisApiResult.Success("candidate-review-test", candidates, warnings)

private fun storedIngredient(): StoredIngredient {
    val draft = IngredientDraft.create("豆腐", "1", "丁")
    return StoredIngredient("stored", draft.name, draft.quantity, draft.unit, 1, 1)
}

private class RecordingRepository(
    private val items: List<StoredIngredient> = emptyList(),
) : InventoryRepository {
    var saveCount = 0
        private set

    override suspend fun hasItems() = items.isNotEmpty()
    override suspend fun getAll() = items
    override fun observeAll(): Flow<List<StoredIngredient>> = flowOf(items)
    override suspend fun searchByName(normalizedQuery: String) = items
    override suspend fun saveBatch(batch: InventoryBatch) { saveCount++ }
}
