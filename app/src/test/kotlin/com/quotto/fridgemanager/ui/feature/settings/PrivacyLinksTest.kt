package com.quotto.fridgemanager.ui.feature.settings

import androidx.compose.ui.platform.UriHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyLinksTest {
    @Test
    fun `公開導線はGitHub Pagesの確定URLを使用する`() {
        assertEquals(
            "https://quotto.github.io/fridge-manager/privacy-policy.html",
            PrivacyLinks.policy,
        )
        assertEquals(
            "https://quotto.github.io/fridge-manager/data-deletion.html",
            PrivacyLinks.dataDeletion,
        )
    }

    @Test
    fun `外部URLを開けない場合は失敗として画面へ返す`() {
        assertFalse(openPrivacyLink(ThrowingUriHandler(), PrivacyLinks.policy))
        assertTrue(openPrivacyLink(RecordingUriHandler(), PrivacyLinks.dataDeletion))
    }

    private class ThrowingUriHandler : UriHandler {
        override fun openUri(uri: String): Unit = error("handler unavailable")
    }

    private class RecordingUriHandler : UriHandler {
        override fun openUri(uri: String) = Unit
    }
}
