package com.quotto.fridgemanager.domain.inventory

/** UI文言に依存せず、入力元を問わず共通利用する検証エラー種別。 */
enum class DomainErrorCode {
    NAME_REQUIRED,
    NAME_TOO_LONG,
    INVALID_QUANTITY,
    QUANTITY_OUT_OF_RANGE,
    UNKNOWN_UNIT,
    BATCH_TOO_LARGE,
    DUPLICATE_NAME,
}

open class DomainValidationException(
    val code: DomainErrorCode,
    message: String,
) : IllegalArgumentException(message)

data class DuplicateGroup(
    val normalizedName: String,
    val indices: List<Int>,
)

class DuplicateIngredientException(
    val duplicateGroups: List<DuplicateGroup>,
) : DomainValidationException(
    code = DomainErrorCode.DUPLICATE_NAME,
    message = "Duplicate ingredient names: ${duplicateGroups.joinToString { it.normalizedName }}",
) {
    val normalizedNames: List<String> = duplicateGroups.map(DuplicateGroup::normalizedName)
}
