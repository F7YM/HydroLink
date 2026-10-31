package com.hydro.link.compose.update

import com.hydro.link.shared.update.AppUpdateHelper
import com.hydro.link.shared.update.AppUpdateInfo
import kotlin.system.exitProcess

fun main() {
    println("[Test] 正在验证桌面端 AppUpdateHelper 逻辑...")

    val sampleJson = """
        {
          "version": "1.2.0",
          "version_code": 12,
          "publish_date": "2026-10-02",
          "title": "HydroLink v1.2.0",
          "changelog": [
            "新增：App 自动检查更新与网盘分发支持",
            "优化：插件市场弹窗规范与按钮强调色",
            "修复：若干已知体验问题"
          ],
          "download_url": "https://www.123pan.com/s/sample",
          "backup_url": "https://wwx.lanzou.com/sample",
          "password": "abcd"
        }
    """.trimIndent()

    // 1. 标准解析测试
    val info = AppUpdateHelper.parse(sampleJson)
    check(info.version == "1.2.0") { "版本号解析失败" }
    check(info.versionCode == 12) { "版本代码解析失败" }
    check(info.publishDate == "2026-10-02") { "发布日期解析失败" }
    check(info.title == "HydroLink v1.2.0") { "标题解析失败" }
    check(info.changelog.size == 3) { "更新日志项数不匹配" }
    check(info.downloadUrl == "https://www.123pan.com/s/sample") { "下载链接解析失败" }
    check(info.backupUrl == "https://wwx.lanzou.com/sample") { "备用链接解析失败" }
    check(info.password == "abcd") { "提取码解析失败" }
    println("  ✓ 标准 app.json 解析成功")

    // 2. 多行纯文本日志解析
    val textLogJson = """
        {
          "version": "1.2.1",
          "changelog": "第一条更新\n第二条更新"
        }
    """.trimIndent()
    val textInfo = AppUpdateHelper.parse(textLogJson)
    check(textInfo.changelog == listOf("第一条更新", "第二条更新")) { "纯文本日志解析失败" }
    println("  ✓ 纯文本多行日志兼容成功")

    // 3. 版本比对
    check(AppUpdateHelper.isNewer("1.2.0", "1.1.0")) { "版本比对失败 1" }
    check(AppUpdateHelper.isNewer("1.0.1", "1.0.0")) { "版本比对失败 2" }
    check(AppUpdateHelper.isNewer("2.0.0", "1.9.9")) { "版本比对失败 3" }
    check(!AppUpdateHelper.isNewer("1.0.0", "1.0.0")) { "版本比对失败 4" }
    check(!AppUpdateHelper.isNewer("1.0.0", "1.0.1")) { "版本比对失败 5" }
    check(!AppUpdateHelper.isNewer("1.0.0-beta", "1.0.0")) { "版本比对失败 6" }
    println("  ✓ 语义化版本比对判定成功")

    // 4. 频控与提醒判定
    val today = "2026-10-02"
    val yesterday = "2026-10-01"
    check(!AppUpdateHelper.shouldAutoCheck(today, today)) { "频控失败 1" }
    check(AppUpdateHelper.shouldAutoCheck(yesterday, today)) { "频控失败 2" }
    check(AppUpdateHelper.shouldAutoCheck("", today)) { "频控失败 3" }

    check(!AppUpdateHelper.shouldPrompt(today, today)) { "稍后提醒失败 1" }
    check(AppUpdateHelper.shouldPrompt(yesterday, today)) { "稍后提醒失败 2" }
    check(AppUpdateHelper.shouldPrompt("", today)) { "稍后提醒失败 3" }
    println("  ✓ 自动检查频控与稍后提醒判定成功")

    // 5. 弹窗正文生成
    val text = AppUpdateHelper.dialogText(info, "1.1.0")
    check(text.contains("最新版本：v1.2.0（当前 v1.1.0）")) { "弹窗文本缺少版本对比" }
    check(text.contains("发布日期：2026-10-02")) { "弹窗文本缺少发布日期" }
    check(text.contains("网盘提取码：abcd（前往时自动复制）")) { "弹窗文本缺少提取码" }
    check(text.contains("1. 新增：App 自动检查更新与网盘分发支持")) { "弹窗文本格式化序号失败" }
    println("  ✓ 弹窗正文排版格式化成功")

    // 6. 分端分类 changelog（手机端、桌面端、手表端）解析与排版
    val multiSectionJson = """
        {
          "version": "1.2.1",
          "changelog": {
            "手机端": [
              "新增：App 自动检查更新与网盘分发支持",
              "修复：若干已知体验问题"
            ],
            "桌面端": [
              "新增：Compose 双引擎更新弹窗"
            ],
            "手表端": [
              "优化：互联通信稳定性"
            ]
          }
        }
    """.trimIndent()
    val multiInfo = AppUpdateHelper.parse(multiSectionJson)
    val multiText = AppUpdateHelper.formatChangelog(multiInfo.changelog)
    check(multiText.contains("【手机端】")) { "缺少手机端标题" }
    check(multiText.contains("1. 新增：App 自动检查更新与网盘分发支持")) { "手机端序号失败" }
    check(multiText.contains("2. 修复：若干已知体验问题")) { "手机端项 2 失败" }
    check(multiText.contains("【桌面端】")) { "缺少桌面端标题" }
    check(multiText.contains("1. 新增：Compose 双引擎更新弹窗")) { "桌面端序号失败" }
    check(multiText.contains("【手表端】")) { "缺少手表端标题" }
    check(multiText.contains("1. 优化：互联通信稳定性")) { "手表端序号失败" }
    println("  ✓ 分端分类日志（手机端、桌面端、手表端）解析与排版成功")

    // 7. 强制更新与最低兼容版本测试
    val forceJson = """
        {
          "version": "1.2.1",
          "force": true,
          "min_version": "1.2.0"
        }
    """.trimIndent()
    val forceInfo = AppUpdateHelper.parse(forceJson)
    check(forceInfo.force) { "force 解析失败" }
    check(forceInfo.minVersion == "1.2.0") { "min_version 解析失败" }
    check(AppUpdateHelper.isForceUpdate(forceInfo, "1.1.0")) { "isForceUpdate 1 失败" }
    check(AppUpdateHelper.isForceUpdate(forceInfo, "1.2.0")) { "isForceUpdate 2 失败" }

    val minOnlyInfo = AppUpdateInfo(version = "1.2.1", force = false, minVersion = "1.2.0")
    check(AppUpdateHelper.isForceUpdate(minOnlyInfo, "1.1.9")) { "isForceUpdate 3 失败" }
    check(!AppUpdateHelper.isForceUpdate(minOnlyInfo, "1.2.0")) { "isForceUpdate 4 失败" }
    check(!AppUpdateHelper.isForceUpdate(minOnlyInfo, "1.2.1")) { "isForceUpdate 5 失败" }
    println("  ✓ 强制更新与最低版本兼容判定成功")

    println("[Test] 桌面端 AppUpdateHelper 所有验证项 PASS！")
    exitProcess(0)
}
