package com.quotto.fridgemanager

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCompatibilityWorkflowTest {
    private val workflow = File("../.github/workflows/android-compatibility.yml").readText()

    @Test
    fun `Android 11から17の各世代を代表するAPIでinstrumentationを実行する`() {
        val configuredApis = Regex("""api-level:\s*\[([^\]]+)]""")
            .find(workflow)
            ?.groupValues
            ?.get(1)
            ?.split(",")
            ?.map(String::trim)

        assertEquals(listOf("30", "31", "32", "33", "34", "35", "36", "37"), configuredApis)
        assertTrue(workflow.contains("connectedDebugAndroidTest"))
    }

    @Test
    fun `互換性検証は定期実行と手動再実行ができる`() {
        assertTrue(workflow.contains("workflow_dispatch:"))
        assertTrue(workflow.contains("schedule:"))
    }

    @Test
    fun `APIごとの失敗証跡を成功失敗にかかわらず保存する`() {
        assertTrue(workflow.contains("if: always()"))
        assertTrue(workflow.contains("actions/upload-artifact@"))
        assertTrue(workflow.contains("android-compatibility-\${{ matrix.api-level }}"))
        assertTrue(workflow.contains("app/build/reports/androidTests/connected/"))
        assertTrue(workflow.contains("app/build/outputs/androidTest-results/connected/"))
    }
}
