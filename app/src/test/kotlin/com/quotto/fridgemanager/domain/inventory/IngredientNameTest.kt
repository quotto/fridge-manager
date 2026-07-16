package com.quotto.fridgemanager.domain.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientNameTest {
    @Test
    fun `前後空白を除去しNFKCで正規化する`() {
        val name = IngredientName.from("\u3000 ｔｏｍａｔｏ　")

        assertEquals("ｔｏｍａｔｏ", name.value)
        assertEquals("tomato", name.normalizedValue)
    }

    @Test
    fun `NBSPも前後空白として除去する`() {
        val name = IngredientName.from("\u00A0りんご\u00A0")

        assertEquals("りんご", name.value)
        assertEquals("りんご", name.normalizedValue)
    }

    @Test
    fun `正規化後の完全一致を同一名として扱う`() {
        val fullWidth = IngredientName.from("ＡＢＣ")
        val ascii = IngredientName.from("ABC")

        assertEquals(fullWidth, ascii)
        assertEquals(fullWidth.hashCode(), ascii.hashCode())
        assertEquals(IngredientName.from("ﾄﾏﾄ"), IngredientName.from("トマト"))
        assertEquals(IngredientName.from("は\u3099"), IngredientName.from("ば"))
        assertNotEquals(IngredientName.from("玉ねぎ"), IngredientName.from("タマネギ"))
        assertNotEquals(IngredientName.from("Apple"), IngredientName.from("apple"))
        assertNotEquals(IngredientName.from("青 ねぎ"), IngredientName.from("青ねぎ"))
    }

    @Test
    fun `1文字と30文字を受け入れる`() {
        assertEquals("米", IngredientName.from("米").value)
        assertEquals("あ".repeat(30), IngredientName.from("あ".repeat(30)).value)
    }

    @Test
    fun `補助文字を1文字として数える`() {
        val supplementaryCharacter = "\uD840\uDC0B"

        assertEquals(supplementaryCharacter.repeat(30), IngredientName.from(supplementaryCharacter.repeat(30)).value)
    }

    @Test
    fun `空白のみと31文字を拒否する`() {
        val required = assertThrows(DomainValidationException::class.java) { IngredientName.from("　 \t") }
        val tooLong = assertThrows(DomainValidationException::class.java) { IngredientName.from("あ".repeat(31)) }

        assertEquals(DomainErrorCode.NAME_REQUIRED, required.code)
        assertEquals(DomainErrorCode.NAME_TOO_LONG, tooLong.code)
        assertTrue(required.message!!.isNotBlank())
    }

    @Test
    fun `表示用文字列は正規化前の原表記を返す`() {
        val name = IngredientName.from("　ｔｏｍａｔｏ　")

        assertEquals("ｔｏｍａｔｏ", name.toString())
    }
}
