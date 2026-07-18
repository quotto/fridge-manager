package com.quotto.fridgemanager.ui.feature.image

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.quotto.fridgemanager.image.PreprocessedImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ImageAnalysisScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `画像選択と撮影と手動登録の導線を常に表示する`() {
        var pickerClicks = 0
        var cameraClicks = 0
        var manualClicks = 0
        composeRule.setContent {
            ImageInputContent(
                cameraMessage = CameraMessage.None,
                hasSelection = false,
                onPickImage = { pickerClicks++ },
                onTakePhoto = { cameraClicks++ },
                onManualRegistration = { manualClicks++ },
                onDiscardSelection = {},
                onOpenCameraSettings = {},
            )
        }

        composeRule.onNodeWithText("端末から1枚選ぶ").performClick()
        composeRule.onNodeWithText("写真を撮る").performClick()
        composeRule.onNodeWithText("手動で登録する").performClick()

        assertEquals(1, pickerClicks)
        assertEquals(1, cameraClicks)
        assertEquals(1, manualClicks)
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
                onManualRegistration = {},
                onDiscardSelection = {},
                onOpenCameraSettings = { settingsClicks++ },
            )
        }

        composeRule.onNodeWithText("端末のカメラ設定と、カメラアプリを利用できるか確認してください").assertIsDisplayed()
        composeRule.onNodeWithText("端末の設定を開く").performClick()
        composeRule.onNodeWithText("端末から1枚選ぶ").assertIsDisplayed()
        composeRule.onNodeWithText("手動で登録する").assertIsDisplayed()
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
                onManualRegistration = {},
                onDiscardSelection = { discards++ },
                onOpenCameraSettings = {},
                onUseSelection = { uses++ },
            )
        }

        composeRule.onNodeWithText("この画像を使用する").performClick()
        composeRule.onNodeWithText("選び直す").performClick()

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
    fun `送信中は画像変更と二重送信を無効化する`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val image = PreprocessedImage(File.createTempFile("preview-test-", ".jpg", context.cacheDir), 640, 480, false)
        composeRule.setContent {
            ImagePreviewContent(image, onSend = {}, onReselect = {}, sending = true)
        }
        composeRule.onNodeWithText("画像を送信しています").assertIsDisplayed()
        composeRule.onNodeWithText("この画像を送信する").assertIsNotEnabled()
        composeRule.onNodeWithText("選び直す").assertIsNotEnabled()
        image.close()
    }
}
