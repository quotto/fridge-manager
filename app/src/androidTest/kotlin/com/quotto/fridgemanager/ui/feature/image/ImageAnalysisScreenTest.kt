package com.quotto.fridgemanager.ui.feature.image

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.quotto.fridgemanager.image.PreprocessedImage
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.quotto.fridgemanager.presentation.image.ImageAnalysisState

class ImageAnalysisScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `画像選択と撮影の導線を表示し手動登録の重複導線は表示しない`() {
        var pickerClicks = 0
        var cameraClicks = 0
        composeRule.setContent {
            ImageInputContent(
                cameraMessage = CameraMessage.None,
                hasSelection = false,
                onPickImage = { pickerClicks++ },
                onTakePhoto = { cameraClicks++ },
                onDiscardSelection = {},
                onOpenCameraSettings = {},
            )
        }

        composeRule.onNodeWithText("端末から1枚選ぶ").performClick()
        composeRule.onNodeWithText("写真を撮る").performClick()
        composeRule.onNodeWithText("手動で登録する").assertDoesNotExist()
        composeRule.onNodeWithText(
            "食材以外や人物・個人情報が映り込んでいない画像を選んでください。",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "画像はAWSとAmazon Bedrockへ解析目的で送信され、永続保存やモデル学習には使用されません。",
        ).assertIsDisplayed()

        assertEquals(1, pickerClicks)
        assertEquals(1, cameraClicks)
    }

    @Test
    fun `カメラ利用不能でも理由と代替導線を表示する`() {
        var settingsClicks = 0
        composeRule.setContent {
            ImageInputContent(
                cameraMessage = CameraMessage.Unavailable,
                hasSelection = false,
                onPickImage = {},
                onTakePhoto = {},
                onDiscardSelection = {},
                onOpenCameraSettings = { settingsClicks++ },
            )
        }

        composeRule.onNodeWithText("端末のカメラ設定と、カメラアプリを利用できるか確認してください").assertIsDisplayed()
        composeRule.onNodeWithText("端末の設定を開く").performClick()
        composeRule.onNodeWithText("端末から1枚選ぶ").assertIsDisplayed()
        composeRule.onNodeWithText("手動で登録する").assertDoesNotExist()
        assertEquals(1, settingsClicks)
    }

    @Test
    fun `1枚選択後は使用と選び直しを選べる`() {
        var uses = 0
        var discards = 0
        composeRule.setContent {
            ImageInputContent(
                cameraMessage = CameraMessage.None,
                hasSelection = true,
                onPickImage = {},
                onTakePhoto = {},
                onDiscardSelection = { discards++ },
                onOpenCameraSettings = {},
                onUseSelection = { uses++ },
            )
        }

        composeRule.onNodeWithText("この画像を使用する").performScrollTo().performClick()
        composeRule.onNodeWithText("選び直す").performScrollTo().performClick()

        assertEquals(1, uses)
        assertEquals(1, discards)
    }

    @Test
    fun `短辺480未満は読み上げ可能な警告を表示して選び直せる`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("preview-test-", ".jpg", context.cacheDir)
        val image = PreprocessedImage(file, 640, 479, true)
        var reselects = 0
        composeRule.setContent {
            ImagePreviewContent(image, onSend = {}, onReselect = { reselects++ })
        }

        composeRule.onNodeWithText("AIへ実際に送信する変換後画像です").assertIsDisplayed()
        composeRule.onNodeWithText("画像の短辺が480px未満のため、認識精度が低下する可能性があります")
            .assertIsDisplayed()
        composeRule.onNodeWithText("選び直す").performClick()
        assertEquals(1, reselects)
        image.close()
    }

    @Test
    fun `送信中は二重送信を無効化し取消できる`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val image = PreprocessedImage(File.createTempFile("preview-test-", ".jpg", context.cacheDir), 640, 480, false)
        var cancellations = 0
        composeRule.setContent {
            ImagePreviewContent(image, onSend = {}, onReselect = { cancellations++ }, sending = true)
        }
        composeRule.onNodeWithText("画像を送信しています").assertIsDisplayed()
        composeRule.onNodeWithText("この画像を送信する").assertIsNotEnabled()
        composeRule.onNodeWithText("キャンセル").performClick()
        assertEquals(1, cancellations)
        image.close()
    }

    @Test
    fun `上限失敗は種別と再利用日時と画像操作の代替導線を表示する`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val image = PreprocessedImage(File.createTempFile("preview-test-", ".jpg", context.cacheDir), 640, 480, false)
        var manualFallbacks = 0
        composeRule.setContent {
            ImagePreviewContent(
                image, onSend = {}, onReselect = {},
                onManualFallback = { manualFallbacks++ },
                failure = ImageAnalysisState.Failed(
                    "AI解析の利用上限に達しました", image, "2026-07-21T15:00:00Z", "DAILY",
                ),
            )
        }
        composeRule.onNodeWithText("上限種別: 1日").assertIsDisplayed()
        composeRule.onNodeWithText("再利用日時: 2026-07-21T15:00:00Z").assertIsDisplayed()
        composeRule.onNodeWithText("再試行する").assertIsDisplayed()
        composeRule.onNodeWithText("選び直す").assertIsDisplayed()
        composeRule.onNodeWithText("手動入力に切り替える").performClick()
        assertEquals(1, manualFallbacks)
        image.close()
    }

    @Test
    fun `有効な変換画像を読み込んだ後もプレビューを描画して送信できる`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("preview-valid-", ".jpg", context.cacheDir)
        Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).also { bitmap ->
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
        }
        val image = PreprocessedImage(file, 640, 480, false)

        composeRule.setContent {
            ImagePreviewContent(image, onSend = {}, onReselect = {})
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("この画像を送信する").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("この画像を送信する")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        image.close()
    }
}
