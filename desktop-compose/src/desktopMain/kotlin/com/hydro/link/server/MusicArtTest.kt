package com.hydro.link.server

import com.hydro.link.server.store.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import javax.imageio.ImageIO

/**
 * 封面链路回归：`playerctl` 解析 → artUrl → `/api/music/art` → 像素 → JPEG，
 * 以及 `ctx.music.artUrl` 的形状（插件按字段名取值）。
 *
 * 为什么要有这个测试：桌搭的封面在桌面端**一张裂图**，看不出是"宿主没给地址"、
 * "端点 404" 还是"像素处理错了"。这里把每一步都钉住；`?size=466` 的 466×349 是
 * 与 Go/安卓 逐像素对齐的那条几何，改一边不改另一边就会红。
 */

/** 造一张 w×h 的渐变图（真渐变，缩放/模糊才看得出差别）。 */
private fun gradient(w: Int, h: Int): MusicArtProcessor.Pixels {
    val buf = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val r = x * 255 / maxOf(1, w - 1)
            val g = y * 255 / maxOf(1, h - 1)
            buf[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or 0x40
        }
    }
    return MusicArtProcessor.Pixels(buf, w, h)
}

fun main() {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    // ------------------------------------------------------------ 1. MPRIS 输出解析
    val parsed = SystemCapabilities.parseMprisMetadata("标题|||歌手|||Playing|||file:///tmp/cover.jpg")
        ?: error("应解析出媒体信息")
    check(parsed.hasMedia && parsed.title == "标题" && parsed.artist == "歌手" && parsed.playing)
    check(parsed.artUrl == "file:///tmp/cover.jpg")
    check(SystemCapabilities.parseMprisMetadata("") == null) { "空输出 = 没有正在播放的媒体" }
    check(SystemCapabilities.parseMprisMetadata("\n") == null)
    val noArt = SystemCapabilities.parseMprisMetadata("歌|||手|||Paused") ?: error("缺 artUrl 段也应解析")
    check(!noArt.playing && noArt.artUrl.isEmpty()) { "缺段按空处理，playing 只看 ''Playing''" }
    check(SystemCapabilities.parseMprisMetadata("||| |||Playing|||x") == null) { "标题歌手都空 = 没有媒体" }
    println("PASS: playerctl --format 输出解析（含 artUrl 缺失、暂停、空输出）")

    // ------------------------------------------------------------ 2. 像素几何（三端对齐）
    val big = gradient(1200, 900)
    val fit = MusicArtProcessor.fit(big, 466)
    check(fit.width == 466 && fit.height == 349) {
        "1200x900 fit(466) 必须是 466x349（截断取整，与 Go imaging.Fit / 安卓一致），实得 ${fit.width}x${fit.height}"
    }
    check(MusicArtProcessor.fit(gradient(400, 400), 466).let { it.width == 400 && it.height == 400 }) {
        "比 max 小的图不放大"
    }
    check(MusicArtProcessor.fill(big, 466, 466).let { it.width == 466 && it.height == 466 }) {
        "w/h 是 cover 语义：铺满再中心裁剪"
    }
    val white = MusicArtProcessor.Pixels(IntArray(1) { 0xFFFFFFFF.toInt() }, 1, 1)
    check((MusicArtProcessor.dim(white, 0.5).argb[0] and 0xFF) == 127) {
        "dim 用截断（Go 的 uint8(v*dim)），255*0.5 应是 127 不是 128"
    }
    val processed = MusicArtProcessor.process(big, 26.0, 0.3, 466, 0, 0)
    check(processed.width == 466 && processed.height == 349)
    val maxChannel = processed.argb.maxOf { p ->
        maxOf((p ushr 16) and 0xFF, (p ushr 8) and 0xFF, p and 0xFF)
    }
    check(maxChannel <= 77) { "dim=0.3 后通道值不该超过 77（255*0.3 截断），实得 $maxChannel" }
    println("PASS: fit/fill/dim/process 几何（466x349、933 缓存键不变式）")

    // ------------------------------------------------------------ 3. 编解码（ImageIO 真进真出）
    val jpeg = MusicArtCodec.encodeJpeg(gradient(64, 48))
    check(jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "应编出 JPEG" }
    val back = MusicArtCodec.decode(jpeg) ?: error("自己编的 JPEG 应能解回来")
    check(back.width == 64 && back.height == 48)
    check(MusicArtCodec.decode(byteArrayOf(1, 2, 3)) == null) { "垃圾字节解码失败（端点回 415）" }
    println("PASS: JPEG 编解码往返（质量 86）")

    // ------------------------------------------------------------ 4. 取图路径与 MIME 嗅探
    check(AlbumArtFetcher.localPath("file:///tmp/a.jpg") == "/tmp/a.jpg")
    check(AlbumArtFetcher.localPath("file:///C:/dir/a.jpg") == "C:/dir/a.jpg") { "Windows 的 file URL" }
    check(AlbumArtFetcher.localPath("file://server/share/a.jpg") == "//server/share/a.jpg") { "UNC 路径" }
    check(AlbumArtFetcher.localPath("/tmp/a.jpg") == "/tmp/a.jpg") { "裸路径" }
    check(AlbumArtFetcher.localPath("https://example.com/a.jpg") == null) { "http 不是本地文件" }
    check(AlbumArtFetcher.localPath("data:image/png;base64,xxx") == null)
    check(AlbumArtFetcher.sniffMime(jpeg) == "image/jpeg")
    check(
        AlbumArtFetcher.sniffMime(
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a),
        ) == "image/png",
    )
    check(AlbumArtFetcher.sniffMime("RIFFxxxxWEBP".toByteArray()) == "image/webp")
    check(AlbumArtFetcher.sniffMime(byteArrayOf(1)) == "application/octet-stream")
    // 本地文件读取：真写一张 PNG 到磁盘，走 file:// 取回来
    val artFile = Files.createTempFile("hydro-art", ".png").toFile()
    artFile.writeBytes(run {
        val png = MusicArtProcessor.Pixels(gradient(32, 32).argb, 32, 32)
        val img = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, 32, 32, png.argb, 0, 32)
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        out.toByteArray()
    })
    val fromDisk = AlbumArtFetcher.fetch("file://${artFile.absolutePath}")
        ?: error("file:// 本地封面应能取到")
    check(fromDisk.mime == "image/png" && fromDisk.bytes.isNotEmpty())
    check(AlbumArtFetcher.fetch("file:///definitely/not/here.jpg") == null)
    println("PASS: artUrl 路径解析、MIME 嗅探、file:// 真读取")

    // ------------------------------------------------------------ 5. 路由语义（假源）
    val source = MusicArtSource("file:///tmp/cover.jpg", jpeg, "image/jpeg", gradient(1200, 900))
    var loads = 0
    val route = MusicArtRoute(
        currentKey = { "file:///tmp/cover.jpg" },
        loadSource = { loads++; MusicArtLoad.Ok(source) },
        encodeJpeg = { MusicArtCodec.encodeJpeg(it) },
    )
    val rawResult = route.handle(emptyMap())
    check(rawResult.status == 200 && rawResult.mime == "image/jpeg" && rawResult.bytes.contentEquals(jpeg)) {
        "无参数 = 原图字节（与 Go 一致）"
    }
    val sized = route.handle(mapOf("size" to "466"))
    check(sized.status == 200 && sized.mime == "image/jpeg")
    check(ImageIO.read(ByteArrayInputStream(sized.bytes)).let { it.width == 466 && it.height == 349 }) {
        "size=466 出来必须是 466x349"
    }
    check(route.handle(mapOf("size" to "466")).bytes.contentEquals(sized.bytes)) { "同参数重复请求命中缓存" }
    check(loads == 1) { "同一首歌只取图/解码一次（手表每次刷新都会来取），实得 $loads 次" }

    val cover = route.handle(mapOf("size" to "466", "w" to "466", "h" to "466"))
    check(ImageIO.read(ByteArrayInputStream(cover.bytes)).let { it.width == 466 && it.height == 466 }) {
        "w/h 走 cover 裁剪"
    }

    val noKey = MusicArtRoute({ "" }, { MusicArtLoad.Ok(source) }, { MusicArtCodec.encodeJpeg(it) })
    val noKeyResult = noKey.handle(mapOf("size" to "466"))
    check(noKeyResult.status == 404 && String(noKeyResult.bytes).contains("当前曲目没有封面")) {
        "没有封面 → 404 JSON"
    }

    val broken = MusicArtRoute({ "file:///tmp/x.png" }, { MusicArtLoad.Fail(415, "无法解码封面") }, { ByteArray(0) })
    check(broken.handle(mapOf("size" to "466")).status == 415) { "解不开的图 → 415（与 Go 一致）" }
    check(broken.handle(emptyMap()).status == 415) { "无参数路径也要如实报错" }
    println("PASS: 路由语义（原图/处理/缓存/404/415）")

    // ------------------------------------------------------------ 6. ctx.music 形状（插件契约）
    val noArtShape = musicJson(SystemCapabilities.Music(false, "", "", false), "http://127.0.0.1:9000", hasArt = false)
    check(noArtShape["artUrl"]?.jsonPrimitive?.content == "") { "没有封面时必须给空串，别给必然 404 的地址" }
    check(noArtShape["artBlur"]?.jsonPrimitive?.content == "")
    check(noArtShape["hasMedia"]?.jsonPrimitive?.content == "false")
    val artShape = musicJson(
        SystemCapabilities.Music(true, "歌", "手", true, "file:///x.jpg"),
        "http://127.0.0.1:9000",
        hasArt = true,
    )
    check(artShape["artUrl"]?.jsonPrimitive?.content == "http://127.0.0.1:9000/api/music/art")
    check(artShape["artBlur"]?.jsonPrimitive?.content == "http://127.0.0.1:9000/api/music/art?size=466&blur=26&dim=0.3")
    println("PASS: ctx.music 形状（artUrl/artBlur 空串与真地址两种）")

    // ------------------------------------------------------------ 7. 端到端：真 HTTP 服务
    val directory = Files.createTempDirectory("hydro-music-art-test").toFile()
    val store = Store.open(directory)
    val pkg = "com.test.art"
    val pluginDir = File(store.pluginsDir, pkg).apply { mkdirs() }
    File(pluginDir, "manifest.json").writeText(
        """{"id":"$pkg","name":"Art","version":"1.0.0","runtime":"js","entry":"server.js","permissions":["music"],"apps":[{"id":"main","name":"Art"}]}""",
    )
    File(pluginDir, "server.js").writeText("module.exports = {};")
    val server = HydroServer(store)
    try {
        server.start(0)
        val base = "http://127.0.0.1:${server.port}"
        server.baseUrlProvider = { base }
        server.musicArtOverride = source

        fun get(path: String): HttpResponse<ByteArray> = client.send(
            HttpRequest.newBuilder(URI.create("$base$path")).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )

        // 7.1 像手表那样取封面（渲染器直接取 URL，不带任何设备头）
        val rawResp = get("/api/music/art")
        check(rawResp.statusCode() == 200) { "封面端点应 200，实得 ${rawResp.statusCode()}" }
        check(rawResp.body().contentEquals(jpeg)) { "无参数应回原图字节" }
        check(rawResp.headers().firstValue("content-type").orElse("") == "image/jpeg")
        check(rawResp.headers().firstValue("cache-control").orElse("") == "no-store") { "封面必须不缓存（换歌立刻换图）" }

        // 7.2 桌搭背景那条：size+blur+dim
        val blurResp = get("/api/music/art?size=466&blur=26&dim=0.3")
        check(blurResp.statusCode() == 200) { "背景图应 200，实得 ${blurResp.statusCode()}" }
        check(blurResp.headers().firstValue("content-type").orElse("") == "image/jpeg")
        val blurImg = ImageIO.read(ByteArrayInputStream(blurResp.body()))
            ?: error("背景图应是能解码的 JPEG")
        check(blurImg.width == 466 && blurImg.height == 349) {
            "背景图尺寸必须与 Go/安卓 一致（466x349），实得 ${blurImg.width}x${blurImg.height}"
        }

        // 7.3 cover 裁剪
        val coverImg = ImageIO.read(ByteArrayInputStream(get("/api/music/art?size=466&w=466&h=466").body()))
        check(coverImg.width == 466 && coverImg.height == 466)

        // 7.4 插件真实走的那条路：ctx.music.artUrl → 同一个端点
        fun kitMusic(): kotlinx.serialization.json.JsonObject {
            val request = HttpRequest.newBuilder(URI.create("$base/api/hydro/worker/music"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"package":"$pkg","op":"now"}"""))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "kit.music.now 应 200，实得 ${response.statusCode()}: ${response.body()}" }
            return Json.parseToJsonElement(response.body()).jsonObject
        }

        val music = kitMusic()
        val artUrl = music["artUrl"]?.jsonPrimitive?.content.orEmpty()
        check(artUrl == "$base/api/music/art") { "有封面时 artUrl 应是 $base/api/music/art，实得 '$artUrl'" }
        check(music["artBlur"]?.jsonPrimitive?.content == "$base/api/music/art?size=466&blur=26&dim=0.3")
        val pluginArt = client.send(
            HttpRequest.newBuilder(URI.create(artUrl)).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        check(pluginArt.statusCode() == 200 && pluginArt.body().isNotEmpty()) {
            "插件拿到的 artUrl 必须真能取到图（这正是桌搭封面裂图的断点）"
        }

        // 7.5 没有封面：artUrl/artBlur 是空串，端点 404（插件据此退回纯色背景）
        server.musicArtOverride = MusicArtSource("", jpeg, "image/jpeg", gradient(8, 8))
        val musicNoArt = kitMusic()
        check(musicNoArt["artUrl"]?.jsonPrimitive?.content == "") { "没有封面时 artUrl 应是空串" }
        check(musicNoArt["artBlur"]?.jsonPrimitive?.content == "")
        val missing = get("/api/music/art?size=466")
        check(missing.statusCode() == 404) { "没有封面应 404，实得 ${missing.statusCode()}" }
        check(String(missing.body()).contains("当前曲目没有封面")) { "404 文案要能看出原因" }
        println("PASS: 端到端（真 HTTP：原图/背景图/封面裁剪/ctx.music → artUrl 取图/无封面 404）")
    } finally {
        server.stop()
        directory.deleteRecursively()
    }

    println("MusicArtTest 全部通过")
}
