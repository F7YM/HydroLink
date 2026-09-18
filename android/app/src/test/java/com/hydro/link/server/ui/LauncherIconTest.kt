package com.hydro.link.server.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自适应图标的静态检查（真机上栽过一次，代价是 App 完全打不开）。
 *
 * 事故：`mipmap-anydpi-v26/ic_launcher.xml` 里写了
 * `<background android:drawable="@mipmap/ic_launcher"/>` —— 图标引用它自己。资源解析成环，
 * 桌面图标直接消失；更糟的是前台服务通知要拿应用图标时抛
 * `BadForegroundServiceNotificationException: Couldn't create icon StatusBarIcon(
 * pkg=com.hydro.link id=0x7f0e0000)`，服务一起来就崩、重启再崩 = 打不开。
 *
 * 这两条断言就是当时缺的那根保险丝。
 */
class LauncherIconTest {

    private val resDir = File("src/main/res")

    @Test
    fun adaptiveIconDoesNotReferenceItself() {
        val files = File(resDir, "mipmap-anydpi-v26").listFiles { f -> f.name.endsWith(".xml") }
        assertTrue("找不到自适应图标 XML", !files.isNullOrEmpty())
        files!!.forEach { f ->
            // 只检查属性，注释里提到这个名字是允许的（注释本身就在讲这个坑）
            val text = f.readText().replace(Regex("(?s)<!--.*?-->"), "")
            assertTrue(
                "${f.name} 自引用 @mipmap/${f.nameWithoutExtension}（资源解析成环 → 图标丢失 + 服务崩溃）",
                !text.contains("@mipmap/${f.nameWithoutExtension}"),
            )
            assertTrue("${f.name} 没有 background 层", text.contains("<background"))
        }
    }

    @Test
    fun adaptiveIconLayersExist() {
        val xml = File(resDir, "mipmap-anydpi-v26/ic_launcher.xml").readText()
        val refs = Regex("""@drawable/([A-Za-z0-9_]+)""").findAll(xml).map { it.groupValues[1] }.toList()
        assertTrue("自适应图标没有 @drawable 层", refs.isNotEmpty())
        for (name in refs) {
            val found = resDir.listFiles()!!.any { d ->
                d.isDirectory && d.name.startsWith("drawable") &&
                    (File(d, "$name.png").isFile || File(d, "$name.xml").isFile)
            }
            assertTrue("自适应图标引用的 drawable/$name 不存在", found)
        }
    }
}
