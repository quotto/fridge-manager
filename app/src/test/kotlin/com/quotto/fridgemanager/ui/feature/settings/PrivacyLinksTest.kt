package com.quotto.fridgemanager.ui.feature.settings

import org.junit.Assert.assertEquals
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
}
