// ─── 移植自 Kyant0/AndroidLiquidGlass 的 catalog 示例组件，Apache-2.0 ───
// 源: https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/Coroutines.kt (androidMain actual 拍平)
// 改动: expect/actual 拍平；其余原样保留（原包名不变）。

package com.kyant.backdrop.catalog.utils

suspend fun awaitFrame() {
    kotlinx.coroutines.android.awaitFrame()
}
