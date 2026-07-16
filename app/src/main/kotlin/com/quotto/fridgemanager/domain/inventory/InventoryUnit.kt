package com.quotto.fridgemanager.domain.inventory

enum class InventoryUnit(val symbol: String) {
    GRAM("g"),
    KILOGRAM("kg"),
    MILLILITER("ml"),
    LITER("L"),
    PIECE("個"),
    BOTTLE("本"),
    SHEET("枚"),
    BAG("袋"),
    PACK("パック"),
    BOX("箱"),
    CAN("缶"),
    JAR("瓶"),
    BUNCH("束"),
    PLANT("株"),
    BULB("玉"),
    TOFU("丁"),
    FISH("尾"),
    SLICE("切れ"),
    CLUSTER("房"),
    GO("合"),
    MEAL("食"),
    ;

    companion object {
        fun fromSymbol(symbol: String): InventoryUnit = entries.firstOrNull { it.symbol == symbol }
            ?: throw DomainValidationException(DomainErrorCode.UNKNOWN_UNIT, "Unknown inventory unit")
    }
}
