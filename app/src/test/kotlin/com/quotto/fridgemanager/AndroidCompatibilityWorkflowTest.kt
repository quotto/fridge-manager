package com.quotto.fridgemanager

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCompatibilityWorkflowTest {
    private val workflow = File("../.github/workflows/android-compatibility.yml").readText()
    private val sanitizer = File("../.github/scripts/sanitize-android-test-results.mjs").readText()
    private val readinessScript =
        File("../.github/scripts/wait-for-android-package-service.sh").readText()

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
    fun `instrumentation開始前にpackage serviceの準備完了を有界時間で待つ`() {
        val readinessCommand = "bash .github/scripts/wait-for-android-package-service.sh"

        assertTrue(workflow.contains(readinessCommand))
        assertTrue(
            workflow.indexOf(readinessCommand) <
                workflow.indexOf("connectedDebugAndroidTest"),
        )
        assertTrue(readinessScript.contains("#!/usr/bin/env bash"))
        assertTrue(readinessScript.contains("set -euo pipefail"))
        assertTrue(readinessScript.contains("attempt <= 90"))
        assertTrue(readinessScript.contains("attempt == 90"))
        assertTrue(readinessScript.contains("adb shell service check package"))
        assertTrue(readinessScript.contains("sleep 2"))
        assertTrue(readinessScript.contains("exit 1"))
    }

    @Test
    fun `互換性検証は定期実行と手動再実行ができる`() {
        assertTrue(workflow.contains("workflow_dispatch:"))
        assertTrue(workflow.contains("schedule:"))
    }

    @Test
    fun `APIごとの機微情報を除いた証跡を成功失敗にかかわらず必須保存する`() {
        assertTrue(workflow.contains("if: always()"))
        assertTrue(workflow.contains("actions/upload-artifact@"))
        assertTrue(workflow.contains("android-compatibility-\${{ matrix.api-level }}"))
        assertTrue(workflow.contains("sanitize-android-test-results.mjs"))
        assertTrue(workflow.contains(".compatibility-evidence/summary.json"))
        assertTrue(workflow.contains("if-no-files-found: error"))
        assertTrue(!workflow.contains("path: app/build/outputs/androidTest-results/connected/"))
        assertTrue(!workflow.contains("path: app/build/reports/androidTests/connected/"))
    }

    @Test
    fun `証跡はテストごとの固定ステータスだけを保存して失敗本文を保存しない`() {
        assertTrue(sanitizer.contains("status:"))
        listOf("passed", "failed", "error", "skipped").forEach { status ->
            assertTrue(sanitizer.contains("'$status'"))
        }
        assertTrue(!sanitizer.contains("body:"))
        assertTrue(!sanitizer.contains("stackTrace"))
    }
}
