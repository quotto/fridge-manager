package com.quotto.fridgemanager

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureBoundaryTest {
    private val sourceRoot: File = sequenceOf(
        File("src/main/kotlin"),
        File("app/src/main/kotlin"),
    ).first(File::isDirectory)

    @Test
    fun `domain層はAndroidとUIへ依存しない`() {
        val domainFiles = sourceRoot.resolve("com/quotto/fridgemanager/domain")
            .walkTopDown()
            .filter(File::isFile)
            .toList()

        assertTrue("domain層の境界ファイルが必要", domainFiles.isNotEmpty())
        domainFiles.forEach { file ->
            val source = file.readText()
            assertFalse("${file.name} がAndroidへ依存している", source.contains("import android."))
            assertFalse("${file.name} がAndroidXへ依存している", source.contains("import androidx."))
            assertFalse("${file.name} がUIへ依存している", source.contains(".ui."))
            assertFalse("${file.name} がdata層へ依存している", source.contains(".data."))
        }
    }

    @Test
    fun `NavControllerはnavigationパッケージ内に閉じる`() {
        val violations = sourceRoot.walkTopDown()
            .filter(File::isFile)
            .filterNot { it.path.contains("/ui/navigation/") }
            .filter { it.readText().contains("NavController") }
            .toList()

        assertTrue("NavController境界違反: $violations", violations.isEmpty())
    }

    @Test
    fun `UI層はDI層とdata層へ依存しない`() {
        assertNoImports(
            relativeRoot = "com/quotto/fridgemanager/ui",
            forbiddenFragments = listOf(".di.", ".data."),
        )
    }

    @Test
    fun `presentation層はAndroidとUIとdata層へ依存しない`() {
        assertNoImports(
            relativeRoot = "com/quotto/fridgemanager/presentation",
            forbiddenFragments = listOf("import android.", "import androidx.", ".ui.", ".data."),
        )
    }

    @Test
    fun `feature間を直接参照しない`() {
        val featureRoot = sourceRoot.resolve("com/quotto/fridgemanager/ui/feature")
        val violations = featureRoot.walkTopDown()
            .filter(File::isFile)
            .filter { file ->
                val ownFeature = file.parentFile?.name.orEmpty()
                file.readLines()
                    .filter { it.startsWith("import ") && it.contains(".ui.feature.") }
                    .any { !it.contains(".ui.feature.$ownFeature.") }
            }
            .toList()

        assertTrue("feature間の直接依存: $violations", violations.isEmpty())
    }

    private fun assertNoImports(
        relativeRoot: String,
        forbiddenFragments: List<String>,
    ) {
        val files = sourceRoot.resolve(relativeRoot)
            .walkTopDown()
            .filter(File::isFile)
            .toList()
        assertTrue("境界を検査するソースが必要: $relativeRoot", files.isNotEmpty())

        val violations = files.filter { file ->
            file.readLines()
                .filter { it.startsWith("import ") }
                .any { importLine -> forbiddenFragments.any(importLine::contains) }
        }
        assertTrue("依存境界違反: $violations", violations.isEmpty())
    }
}
