package com.hydro.link.compose.validation

/**
 * 验证程序的可执行入口（`./gradlew validation` 跑的就是这里）。
 *
 * Compose Desktop 的 `application { }` 必须由一个真的 `fun main` 调用，
 * 而验证逻辑本身要能被当作库函数复用，所以入口单独放一个文件。
 */
fun main() = runValidation()
