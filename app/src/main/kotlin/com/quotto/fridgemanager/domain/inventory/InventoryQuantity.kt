package com.quotto.fridgemanager.domain.inventory

import java.math.BigDecimal

/** 0〜100、小数部最大2桁の在庫数量。値は末尾ゼロを除いた表現へ正規化する。 */
class InventoryQuantity private constructor(val value: BigDecimal) {
    override fun equals(other: Any?): Boolean =
        other is InventoryQuantity && value.compareTo(other.value) == 0

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value.toPlainString()

    companion object {
        private val MIN = BigDecimal.ZERO
        private val MAX = BigDecimal("100")
        const val MAX_DECIMAL_PLACES = 2

        fun from(rawValue: String): InventoryQuantity {
            val parsed = try {
                BigDecimal(rawValue.trim())
            } catch (_: NumberFormatException) {
                throw DomainValidationException(DomainErrorCode.INVALID_QUANTITY, "Quantity must be numeric")
            }
            return from(parsed)
        }

        fun from(rawValue: BigDecimal): InventoryQuantity {
            // 範囲外の巨大な指数値を10進整数へ展開するとArithmeticExceptionや過大な
            // メモリ消費につながるため、正規化より先に指数表現のまま比較する。
            if (rawValue < MIN || rawValue > MAX) {
                throw DomainValidationException(
                    DomainErrorCode.QUANTITY_OUT_OF_RANGE,
                    "Quantity must be between 0 and 100",
                )
            }
            val normalized = rawValue.stripTrailingZeros().let {
                if (it.scale() < 0) it.setScale(0) else it
            }
            if (normalized.scale() > MAX_DECIMAL_PLACES) {
                throw DomainValidationException(
                    DomainErrorCode.INVALID_QUANTITY,
                    "Quantity may have at most two decimal places",
                )
            }
            return InventoryQuantity(normalized)
        }
    }
}
