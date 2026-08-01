package com.quotto.fridgemanager.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyDocumentationTest {
    private val policy = File("../docs/privacy-policy.html").readText()
    private val deletionGuide = File("../docs/data-deletion.html").readText()
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
            "対象食材の名前、現在数量、単位",
            "永続保存しません",
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
            "Other user-generated content",
            "食材名・現在数量・単位",
        )
            .forEach { required -> assertTrue(required, dataSafety.contains(required)) }
        assertTrue(policy.contains("https://quotto.github.io/fridge-manager/privacy-policy.html"))
        assertTrue(policy.contains("https://quotto.github.io/fridge-manager/data-deletion.html"))
        assertFalse(policy.contains("一時データも遅くとも1時間以内"))
        assertTrue(policy.contains("施行日: 2026年8月1日"))
        assertTrue(policy.contains("https://docs.google.com/forms/d/e/PLACEHOLDER/viewform"))
        assertFalse(policy.contains("公開時に記載"))
        assertFalse(policy.contains("公開前に"))
        assertFalse(policy.contains("公開主体名"))
        assertTrue(policy.contains("入出力token数"))
        assertTrue(policy.contains("モデルID"))
        assertTrue(policy.contains("provider呼出し有無"))
        assertTrue(policy.contains("ペイロードの不可逆hash"))
        assertTrue(dataSafety.contains("モデルID、入出力token数"))
        assertTrue(dataSafety.contains("provider呼出し有無"))
        assertTrue(dataSafety.contains("アプリ起動・利用時に必須"))
        assertFalse(dataSafety.contains("Firebase匿名IDとAWS上の不可逆hash。認証情報"))
    }

    @Test
    fun `削除案内は公開URLとアプリ内外の削除方法を説明する`() {
        listOf(
            "https://quotto.github.io/fridge-manager/data-deletion.html",
            "設定画面",
            "端末内の全食材データ",
            "一時画像",
            "Firebase匿名ユーザー",
            "最大180日",
            "最大30日",
            "90日",
            "https://docs.google.com/forms/d/e/PLACEHOLDER/viewform",
        ).forEach { required -> assertTrue(required, deletionGuide.contains(required)) }
        assertFalse(deletionGuide.contains("公開主体名"))
        assertFalse(deletionGuide.contains("公開前に"))
    }
}
