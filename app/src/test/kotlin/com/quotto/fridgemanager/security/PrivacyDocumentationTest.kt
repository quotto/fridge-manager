package com.quotto.fridgemanager.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyDocumentationTest {
    private val policy = File("../docs/privacy-policy.html").readText()
    private val dataSafety = File("../docs/data-safety.md").readText()

    @Test
    fun `公開ポリシー原稿は取得目的送信先保持削除を説明する`() {
        listOf(
            "AWS",
            "Amazon Bedrock",
            "Firebase Authentication",
            "Firebase Crashlytics",
            "米国",
            "グローバル",
            "最大30日",
            "90日",
            "180日",
            "1時間以内",
            "アプリ外からの削除案内",
        ).forEach { required -> assertTrue(required, policy.contains(required)) }
    }

    @Test
    fun `Data safety根拠は実装外の収集を申告しない`() {
        listOf(
            "Photos and videos",
            "User IDs",
            "Device or other IDs",
            "Crash logs",
            "Diagnostics",
            "収集",
            "共有しない",
            "任意",
            "ephemeral",
            "TLS",
            "Analytics SDKは使用しない",
        )
            .forEach { required -> assertTrue(required, dataSafety.contains(required)) }
        assertTrue(policy.contains("https://quotto.github.io/fridge-manager/privacy-policy.html"))
        assertFalse(policy.contains("一時データも遅くとも1時間以内"))
        // 公開前にこの2項目を実値へ置換し、HTTP smoke testをIssueへ記録する。
        assertTrue(policy.contains("施行日・最終更新日"))
        assertTrue(policy.contains("運営者・問い合わせ先"))
    }
}
