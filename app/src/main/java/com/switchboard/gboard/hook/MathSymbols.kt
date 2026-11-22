package com.switchboard.gboard.hook

/**
 * 数字页运算符映射（移植自 gboardtweak MathSymbols）：
 * 数字页标点栏前 4 项（？！……～）键面与输出改为 + − × ÷。
 */
object MathSymbols {
    const val SLOT_COUNT = 4
    val displaySymbols = listOf("+", "-", "×", "÷")

    /** 原标点（含全/半角变体）→ 运算符 */
    private val operatorMap = mapOf(
        "？" to "+", "?" to "+",
        "！" to "-", "!" to "-",
        "……" to "×", "…" to "×",
        "～" to "÷", "~" to "÷",
    )

    /** KeyLabelHooks 改键面时登记的实际替换映射（原文本 → 新文本） */
    @Volatile
    private var replaceMappings: Map<String, String> = emptyMap()

    fun setReplaceMappings(mappings: Map<String, String>) {
        replaceMappings = mappings
    }

    fun clearReplaceMappings() {
        replaceMappings = emptyMap()
    }

    /** 提交文本改写：先查键面登记的映射，再查静态运算符表 */
    fun transform(text: CharSequence): String? {
        val s = text.toString()
        replaceMappings[s]?.let { return it }
        return operatorMap[s]
    }
}
