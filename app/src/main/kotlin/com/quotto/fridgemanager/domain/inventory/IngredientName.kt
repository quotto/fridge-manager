package com.quotto.fridgemanager.domain.inventory

import java.text.Normalizer

/** 表示名と、完全一致判定に使うNFKC正規化名を保持する。 */
class IngredientName private constructor(
    val value: String,
    val normalizedValue: String,
) {
    override fun equals(other: Any?): Boolean =
        other is IngredientName && normalizedValue == other.normalizedValue

    override fun hashCode(): Int = normalizedValue.hashCode()

    override fun toString(): String = value

    companion object {
        const val MAX_CODE_POINTS = 30

        fun from(rawValue: String): IngredientName {
            val displayValue = rawValue.trimUnicodeWhitespace()
            val codePointCount = displayValue.codePointCount(0, displayValue.length)
            if (codePointCount == 0) {
                throw DomainValidationException(DomainErrorCode.NAME_REQUIRED, "Ingredient name is required")
            }
            if (codePointCount > MAX_CODE_POINTS) {
                throw DomainValidationException(DomainErrorCode.NAME_TOO_LONG, "Ingredient name is too long")
            }

            val normalizedValue = Normalizer
                .normalize(displayValue, Normalizer.Form.NFKC)
                .trimUnicodeWhitespace()
            if (normalizedValue.isEmpty()) {
                throw DomainValidationException(DomainErrorCode.NAME_REQUIRED, "Ingredient name is required")
            }
            return IngredientName(displayValue, normalizedValue)
        }
    }
}

private fun String.trimUnicodeWhitespace(): String {
    var start = 0
    var end = length
    while (start < end) {
        val codePoint = codePointAt(start)
        if (!codePoint.isUnicodeWhitespace()) break
        start += Character.charCount(codePoint)
    }
    while (start < end) {
        val codePoint = codePointBefore(end)
        if (!codePoint.isUnicodeWhitespace()) break
        end -= Character.charCount(codePoint)
    }
    return substring(start, end)
}

private fun Int.isUnicodeWhitespace(): Boolean =
    Character.isWhitespace(this) || Character.isSpaceChar(this)
