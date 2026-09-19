// 桌搭 (Desk Buddy) — HydroKit WASM plugin (ABI v1).
//
// 两种版式，由**是否开启 SPlayer 歌词集成**决定（看宿主注入的 ctx.music.lyrics 存不存在）：
//
//   · 开启：设计稿版式（466×466 圆屏设计稿等比换算到实际屏幕）——顶部时钟、
//     左侧封面、右侧歌名/歌手/当前歌词，0.5s 一刷跟着播放下走。
//   · 未开启：原来那套居中版式（大时钟 + 居中歌名/歌手 + 居中封面），3s 一刷。
//
// 歌词来自宿主（桌面端接 SPlayer 的 WebSocket，见 desktop/internal/splayer）；
// 安卓端没有这个能力，所以永远走原版式。
use serde_json::{json, Value};
use std::alloc::{alloc, dealloc, Layout};

/// 设计稿画布边长（466×466 圆屏）与其中的关键尺寸。
const DESIGN: f64 = 466.0;
const D_COVER: f64 = 135.0; // 封面边长
// 设计稿的边距（**屏幕坐标**；减掉容器的安全区后才是内容盒坐标）。
// 46/42 正好等于容器圆屏安全区的左右内边距（42），所以减完就是"贴着安全区两端"，
// 再往左就出安全区、会被容器硬裁。
const D_COVER_LEFT: f64 = 46.0; // 封面左边距
const D_RIGHT_PAD: f64 = 42.0; // 文字列右侧安全边距
const D_GAP: f64 = 18.0; // 封面与文字列间距
const D_CLOCK_TOP: f64 = 30.0; // 时钟顶部
const D_RADIUS: f64 = 21.0; // 封面圆角

/// 容器的圆屏安全区内边距（vela/src/pages/hydro/hydro.ux 的 `.safe-circle`）。
///
/// 容器里 `position:absolute` 的原点是**内容盒**而不是屏幕，而且超出内容盒的部分
/// 会被**硬裁**（连省略号一起裁掉）——所以设计稿的屏幕坐标必须先减掉这段内边距，
/// 否则整块内容会右移 42px、被裁在安全区边缘（真机上就表现为"歌名断在半截、
/// 没有省略号"）。方屏/圆角矩形下容器把安全区置零。
const SAFE_INSET_X: f64 = 42.0;
const SAFE_INSET_TOP: f64 = 30.0;

/// 歌词版式的刷新间隔：歌词要跟着播放下走。
const LYRICS_REFRESH_MS: i64 = 500;
/// 原版式的刷新间隔（界面没有随时间变化的内容）。
const CLASSIC_REFRESH_MS: i64 = 3000;

#[no_mangle]
pub extern "C" fn hydro_abi_version() -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn hydro_alloc(len: usize) -> *mut u8 {
    let len = if len == 0 { 1 } else { len };
    unsafe { alloc(Layout::from_size_align_unchecked(len, 1)) }
}

#[no_mangle]
pub extern "C" fn hydro_free(ptr: *mut u8, len: usize) {
    let len = if len == 0 { 1 } else { len };
    unsafe { dealloc(ptr, Layout::from_size_align_unchecked(len, 1)) }
}

fn envelope(json: String) -> *mut u8 {
    let bytes = json.into_bytes();
    let total = 4 + bytes.len();
    let p = hydro_alloc(total);
    unsafe {
        std::ptr::copy_nonoverlapping((bytes.len() as u32).to_le_bytes().as_ptr(), p, 4);
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), p.add(4), bytes.len());
    }
    p
}

fn num(v: &Value, path: &[&str]) -> f64 {
    let mut cur = v;
    for k in path {
        cur = &cur[*k];
        if cur.is_null() {
            return 0.0;
        }
    }
    cur.as_f64().unwrap_or(0.0)
}

/// Setting from the plugin's server-scope storage snapshot (ctx.storage),
/// falling back to a default.
fn setting(ctx: &Value, key: &str, default: f64) -> f64 {
    let v = &ctx["storage"][key];
    v.as_f64().unwrap_or(default)
}

/// 曲目指纹（djb2）：用于给图片 URL 加缓存键，切歌后 URL 变化才能刷新。
fn track_key(ctx: &Value) -> String {
    let seed = format!("{}|{}", text(ctx, &["music", "title"]), text(ctx, &["music", "artist"]));
    let mut h: u64 = 5381;
    for b in seed.as_bytes() {
        h = h.wrapping_mul(33) ^ (*b as u64);
    }
    format!("{:x}", h)
}

fn text(v: &Value, path: &[&str]) -> String {
    let mut cur = v;
    for k in path {
        cur = &cur[*k];
        if cur.is_null() {
            return String::new();
        }
    }
    cur.as_str().unwrap_or("").to_string()
}

/// 单行文本：超出宽度由容器补省略号（Vela text 的 lines + text-overflow）。
///
/// 这里先在插件侧截一次：容器上的 `text-overflow:ellipsis` 在部分固件上只裁不加点
/// （歌名/歌手直接断在半截），估算宽度自己补 `…` 才稳。截得比容器略短，所以容器
/// 那层省略号仍然可以作为兜底。
fn one_line(s: &str, width: f64, size: f64, weight: f64, color: &str) -> Value {
    json!({
        "type": "text",
        "props": { "text": elide(s, width, size) },
        "style": {
            "width": width,
            "fontSize": size,
            "fontWeight": weight,
            "color": color,
            "lines": 1,
            "textOverflow": "ellipsis"
        }
    })
}

/// 估算一个字符占几个字号（CJK/全角算 1，ASCII 算 0.55，空格更窄）。
fn char_width(c: char) -> f64 {
    let cp = c as u32;
    let wide = (0x1100..=0x115F).contains(&cp)      // 谚文字母
        || (0x2E80..=0xA4CF).contains(&cp)          // 部首/假名/中日韩统一表意文字/彝文
        || (0xAC00..=0xD7A3).contains(&cp)          // 谚文音节
        || (0xF900..=0xFAFF).contains(&cp)          // 中日韩兼容表意
        || (0xFE30..=0xFE6F).contains(&cp)          // 中日韩兼容形式
        || (0xFF00..=0xFF60).contains(&cp)          // 全角
        || (0xFFE0..=0xFFE6).contains(&cp)
        || cp >= 0x1F300; // emoji
    if wide {
        1.0
    } else if cp == 0x20 {
        0.3
    } else {
        0.55
    }
}

/// 放不下就截断并补 `…`（留 2px 余量，免得容器再裁掉省略号本身）。
fn elide(s: &str, max_w: f64, font_px: f64) -> String {
    let max_w = max_w - 2.0;
    if font_px <= 0.0 {
        return s.to_string();
    }
    let total: f64 = s.chars().map(|c| char_width(c) * font_px).sum();
    if total <= max_w {
        return s.to_string();
    }
    let ell = char_width('…') * font_px;
    let mut out = String::new();
    let mut used = 0.0;
    for c in s.chars() {
        let w = char_width(c) * font_px;
        if used + w + ell > max_w {
            break;
        }
        used += w;
        out.push(c);
    }
    format!("{}…", out.trim_end())
}

#[no_mangle]
pub extern "C" fn hydro_view(ctx_ptr: *const u8, ctx_len: usize) -> *mut u8 {
    let raw = if ctx_ptr.is_null() || ctx_len == 0 {
        String::new()
    } else {
        String::from_utf8_lossy(unsafe { std::slice::from_raw_parts(ctx_ptr, ctx_len) }).into_owned()
    };
    let ctx: Value = serde_json::from_str(&raw).unwrap_or(Value::Null);

    let hour = num(&ctx, &["time", "hour"]) as i64;
    let minute = num(&ctx, &["time", "minute"]) as i64;
    let clock = format!("{:02}:{:02}", hour, minute);

    let title = text(&ctx, &["music", "title"]);
    let artist = text(&ctx, &["music", "artist"]);
    let has_media = ctx["music"]["hasMedia"].as_bool().unwrap_or(false);
    let art_url = text(&ctx, &["music", "artUrl"]);
    let art_blur = text(&ctx, &["music", "artBlur"]);
    let handler = text(&ctx, &["req", "event", "handler"]);

    // 歌词版式只在**插件开了 SPlayer 歌词集成**时启用：那时宿主总会给 ctx.music.lyrics
    // （哪怕还没连上、这一句为空串）；没开就整个字段都没有 —— 版式跟着它走。
    let lyrics = &ctx["music"]["lyrics"];
    let lyrics_on = !lyrics.is_null();
    let lyric_line = lyrics["line"].as_str().unwrap_or("").to_string();

    // 屏幕尺寸（设计稿等比换算、方屏封面定位、背景图裁切都用它）
    let screen_w = num(&ctx, &["req", "screen", "width"]);
    let screen_h = num(&ctx, &["req", "screen", "height"]);
    let shape = text(&ctx, &["req", "screen", "shape"]);
    let sw = if screen_w > 0.0 { screen_w } else { DESIGN };
    let sh = if screen_h > 0.0 { screen_h } else { DESIGN };
    let s = sw.min(sh) / DESIGN;

    let blur = setting(&ctx, "blur", 26.0).clamp(0.0, 60.0);
    let dim = setting(&ctx, "dim", 0.3).clamp(0.05, 1.0);
    let font_scale = setting(&ctx, "fontScale", 1.0).clamp(0.6, 2.0);
    let f = |base: f64| (base * s * font_scale).round();

    // Clicking the cover toggles playback; the host executes the effects
    // (music control + remembering the action in server-scope storage).
    let mut effects = json!([]);
    if handler == "toggle" {
        effects = json!([
            { "op": "music", "action": "playpause" },
            { "op": "storage", "key": "lastToggleAt", "value": num(&ctx, &["time", "unix"]) }
        ]);
    }

    // 只有"有音乐 **且** 服务端确实拿到封面"时才铺背景/封面，否则请求
    // /api/music/art 会得到 400，界面上就是一块黑。
    let has_art = has_media && !art_url.is_empty();
    let server_base = text(&ctx, &["server", "base"]);
    let background = if !has_art {
        String::new()
    } else if !server_base.is_empty() {
        // 直接要屏幕尺寸的裁切图（w/h=cover 语义），方屏上不会出现上下黑边
        format!(
            "{}/api/music/art?w={}&h={}&size={}&blur={}&dim={}&k={}",
            server_base.trim_end_matches('/'),
            sw.round() as i64,
            sh.round() as i64,
            sw.max(sh).round() as i64,
            blur.round() as i64,
            (dim * 100.0).round() / 100.0,
            track_key(&ctx)
        )
    } else {
        art_blur
    };
    let mut root_style = json!({
        "backgroundColor": "#0b0d12",
        "backgroundSize": "cover",
        "backgroundPosition": "center"
    });
    if !background.is_empty() {
        root_style["backgroundImage"] = json!(background);
    }

    let cover_src = if !has_art {
        String::new()
    } else if !server_base.is_empty() {
        let size = sw.round() as i64;
        format!(
            "{}/api/music/art?size={}&k={}",
            server_base.trim_end_matches('/'),
            size,
            track_key(&ctx)
        )
    } else {
        art_url.clone()
    };

    let title_text = if title.is_empty() { "HydroLink".to_string() } else { title };

    // 显示哪一句：优先当前句；还没唱到第一句（前奏/间奏）时退而显示**下一句**，
    // 颜色压暗一档——真机上"前奏十几秒空白"看起来就像歌词坏了。
    let next_line = text(&ctx, &["music", "lyrics", "next"]);
    let upcoming = lyric_line.is_empty() && !next_line.is_empty();
    let shown_line = if upcoming { next_line } else { lyric_line };

    let (root_type, root_style, children, refresh_ms) = if lyrics_on {
        // ---------- 设计稿版式 ----------
        // 字号沿用原来那套（设计稿的 21/24/17 在真机上太小）：时钟 36、歌名 32、
        // 歌手 22；歌词取二者之间的 26，最多三行（字号大了，两行装不下整句）。
        // 坐标全部按**内容盒**算（设计稿的 46/42 边距恰好等于容器的安全区）。
        let inset_x = if shape == "circle" { SAFE_INSET_X } else { 0.0 };
        let inset_top = if shape == "circle" { SAFE_INSET_TOP } else { 0.0 };
        let canvas_w = sw - 2.0 * inset_x;

        let cover = (D_COVER * s).round();
        let cover_left = (D_COVER_LEFT * s).round() - inset_x;
        let gap = (D_GAP * s).round();
        let text_left = cover_left + cover + gap;
        let text_right = (D_RIGHT_PAD * s).round() - inset_x;
        let text_width = canvas_w - text_left - text_right;
        // 封面垂直居中于屏幕
        let top = ((sh - cover) / 2.0).round() - inset_top;

        let cover_node = if !cover_src.is_empty() {
            json!({
                "type": "image",
                "props": { "src": cover_src },
                "events": { "tap": "toggle" },
                "style": {
                    "width": cover,
                    "height": cover,
                    "borderRadius": (D_RADIUS * s).round()
                }
            })
        } else {
            json!({
                "type": "text",
                "props": { "text": "♪" },
                "style": {
                    "width": cover,
                    "fontSize": (cover * 0.4).round(),
                    "color": "#3a4150",
                    "textAlign": "center",
                    "marginTop": (cover * 0.28).round()
                }
            })
        };

        // 文字列：歌名（恒单行 + 省略号）、歌手、当前歌词
        let mut text_kids = vec![
            one_line(&title_text, text_width, f(32.0), 700.0, "#FFFFFF"),
            one_line(
                if artist.is_empty() && !has_media { "无正在播放的媒体" } else { &artist },
                text_width,
                f(22.0),
                400.0,
                "rgba(255,255,255,0.4)",
            ),
        ];
        if !shown_line.is_empty() {
            text_kids.push(json!({
                "type": "text",
                "props": { "text": shown_line },
                "style": {
                    "width": text_width,
                    "fontSize": f(26.0),
                    "lineHeight": f(32.0),
                    "color": if upcoming { "rgba(255,255,255,0.45)" } else { "#FFFFFF" },
                    "marginTop": (5.0 * s).round(),
                    "lines": 3,
                    "textOverflow": "ellipsis"
                }
            }));
        }

        (
            "column",
            root_style,
            json!([
                {
                    "type": "text",
                    "props": { "text": clock },
                    "style": {
                        "position": "absolute",
                        "top": (D_CLOCK_TOP * s).round() - inset_top,
                        "left": 0,
                        "right": 0,
                        // 显式给整个内容盒宽：容器里的百分比宽度会随包装层收缩，居中就不再可靠
                        "width": canvas_w.round() as i64,
                        "fontSize": f(36.0),
                        "fontWeight": 700,
                        "color": "#FFFFFF",
                        "textAlign": "center"
                    }
                },
                {
                    "type": "row",
                    "style": {
                        "position": "absolute",
                        "top": top,
                        "left": cover_left,
                        "width": cover + gap + text_width,
                        "alignItems": "flex-start"
                    },
                    "children": [
                        cover_node,
                        {
                            "type": "column",
                            "style": {
                                "width": text_width,
                                "marginLeft": gap,
                                "alignItems": "flex-start"
                            },
                            "children": text_kids
                        }
                    ]
                }
            ]),
            LYRICS_REFRESH_MS,
        )
    } else {
        // ---------- 原版式（没开歌词集成 = 和以前一模一样）----------
        // 方屏（rect）：封面放大并绝对居中于屏幕正中；圆屏：流式居中
        let canvas_w = 480.0;
        let canvas_h = if sw > 0.0 && sh > 0.0 { sh * (480.0 / sw) } else { 480.0 };
        let is_rect = shape == "rect";
        let cover_size: f64 = if is_rect { 290.0 } else { 200.0 };
        let mut cover_style = json!({
            "width": cover_size, "height": cover_size, "borderRadius": 16
        });
        if is_rect {
            cover_style["position"] = json!("absolute");
            cover_style["left"] = json!(((canvas_w - cover_size) / 2.0).round());
            // 略低于屏幕中心，避免压住上方的歌手行
            cover_style["top"] = json!(((canvas_h - cover_size) / 2.0 + 26.0).round());
        } else {
            cover_style["marginTop"] = json!(18);
        }
        let cover_node = if !cover_src.is_empty() {
            json!({
                "type": "image",
                "props": { "src": cover_src },
                "events": { "tap": "toggle" },
                "style": cover_style
            })
        } else if has_media {
            // 有音乐但没有封面：给一个中性占位，别显示"无正在播放的媒体"。
            json!({
                "type": "text",
                "props": { "text": "♪" },
                "style": { "fontSize": 64, "color": "#3a4150", "textAlign": "center", "width": "100%", "marginTop": 30 }
            })
        } else {
            json!({
                "type": "text",
                "props": { "text": "无正在播放的媒体" },
                "style": { "fontSize": 22, "color": "#8b93a3", "textAlign": "center", "width": "100%", "marginTop": 40 }
            })
        };

        let mut style = root_style;
        style["padding"] = json!(16);
        style["alignItems"] = json!("center"); // 列内子节点水平居中（封面不再靠左）
        let kids = json!([
            {
                "type": "text",
                "props": { "text": clock },
                "style": { "fontSize": f(36.0), "fontWeight": 700, "color": "#FFFFFF",
                           "textAlign": "center", "width": "100%" }
            },
            {
                "type": "text",
                "props": { "text": title_text },
                "style": { "fontSize": f(32.0), "fontWeight": 700, "color": "#FFFFFF",
                           "textAlign": "center", "width": "100%", "marginTop": 10 }
            },
            {
                "type": "text",
                "props": { "text": artist },
                "style": { "fontSize": f(22.0), "color": "rgba(255,255,255,0.4)",
                           "textAlign": "center", "width": "100%", "marginTop": 4 }
            },
            cover_node
        ]);
        ("scroll", style, kids, CLASSIC_REFRESH_MS)
    };

    let tree = json!({
        "refreshMs": refresh_ms,
        "effects": effects,
        "root": {
            "type": root_type,
            "style": root_style,
            "children": children
        }
    });
    envelope(tree.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Calls the pure ABI function with a context JSON and parses the envelope.
    fn view(ctx: &str) -> Value {
        let bytes = ctx.as_bytes();
        let ptr = hydro_view(bytes.as_ptr(), bytes.len());
        assert!(!ptr.is_null(), "hydro_view returned null");
        let len = unsafe { std::ptr::read_unaligned(ptr as *const u32) } as usize;
        let payload = unsafe { std::slice::from_raw_parts(ptr.add(4), len) };
        serde_json::from_slice(payload).expect("envelope payload is not valid JSON")
    }

    /// 开了歌词集成的上下文（那时宿主总会带上 ctx.music.lyrics）。
    /// 标题/歌手用短串，免得先被省略号截掉。
    fn with_lyrics(line: &str) -> String {
        format!(
            r#"{{"music":{{"hasMedia":true,"title":"Song","artist":"Artist",
                "artUrl":"file:///tmp/c.jpg","lyrics":{{"source":"splayer","line":"{line}","next":"下一句"}}}},
                "req":{{"screen":{{"shape":"circle","width":466,"height":466}}}}}}"#
        )
    }

    /// 收集树里所有 text 节点的文本
    fn texts(tree: &Value) -> Vec<String> {
        fn walk(n: &Value, out: &mut Vec<String>) {
            if n["type"] == "text" {
                if let Some(t) = n["props"]["text"].as_str() {
                    out.push(t.to_string());
                }
            }
            if let Some(kids) = n["children"].as_array() {
                for k in kids {
                    walk(k, out);
                }
            }
        }
        let mut out = Vec::new();
        walk(&tree["root"], &mut out);
        out
    }

    fn find_text<'a>(tree: &'a Value, want: &str) -> &'a Value {
        fn walk<'a>(n: &'a Value, want: &str) -> Option<&'a Value> {
            if n["type"] == "text" && n["props"]["text"].as_str() == Some(want) {
                return Some(n);
            }
            if let Some(kids) = n["children"].as_array() {
                for k in kids {
                    if let Some(f) = walk(k, want) {
                        return Some(f);
                    }
                }
            }
            None
        }
        walk(&tree["root"], want).unwrap_or_else(|| panic!("text {want} not found in {:?}", texts(tree)))
    }

    /// 设计稿版式的内容行（根下唯一的 row 容器）
    fn board(tree: &Value) -> Value {
        tree["root"]["children"]
            .as_array()
            .expect("root children")
            .iter()
            .find(|c| c["type"] == "row")
            .expect("content row")
            .clone()
    }

    #[test]
    fn lyrics_turn_on_the_design_layout() {
        let t = view(&with_lyrics("Make me feel something, something"));
        assert_eq!(t["refreshMs"], 500, "歌词版式 0.5s 一刷");
        assert_eq!(t["root"]["type"], "column");
        // 坐标是**内容盒**坐标：容器圆屏内边距 42/30，设计稿的屏幕坐标要减掉它
        // （覆盖 28…438 会被容器的安全区硬裁，省略号一起消失）
        let row = board(&t);
        assert_eq!(row["style"]["left"].as_f64().unwrap(), D_COVER_LEFT - SAFE_INSET_X);
        assert_eq!(row["style"]["top"].as_f64().unwrap(), 136.0, "封面垂直居中 (466-135)/2-30");
        let cover = &row["children"][0];
        assert_eq!(cover["type"], "image");
        assert_eq!(cover["style"]["width"].as_f64().unwrap(), 135.0);
        assert_eq!(cover["style"]["borderRadius"].as_f64().unwrap(), 21.0);
        let col = &row["children"][1];
        assert_eq!(col["style"]["marginLeft"].as_f64().unwrap(), 18.0);
        assert_eq!(col["style"]["width"].as_f64().unwrap(), 225.0);
        // 整行必须落在内容盒里：46…424（容器只给 42…424，越界会被硬裁）
        assert_eq!(row["style"]["width"].as_f64().unwrap(), 378.0);
        assert_eq!(row["style"]["left"].as_f64().unwrap() + 42.0, 46.0, "封面落在设计稿的位置");
        assert_eq!(row["style"]["left"].as_f64().unwrap() + 42.0 + 378.0, 424.0, "右边界=安全区右缘");
    }

    #[test]
    fn design_layout_uses_the_big_typography() {
        // 真机上设计稿的 21/24/17 太小：字号沿用原来那套
        let t = view(&with_lyrics("L"));
        let clock = find_text(&t, "00:00");
        assert_eq!(clock["style"]["fontSize"].as_f64().unwrap(), 36.0);
        assert_eq!(clock["style"]["textAlign"], "center");
        // 时钟铺满整个内容盒（382）→ 居中即屏幕中心 233
        assert_eq!(clock["style"]["width"].as_f64().unwrap(), 466.0 - 2.0 * SAFE_INSET_X);
        assert_eq!(clock["style"]["top"].as_f64().unwrap(), 0.0);
        assert_eq!(find_text(&t, "Song")["style"]["fontSize"].as_f64().unwrap(), 32.0);
        assert_eq!(find_text(&t, "Artist")["style"]["fontSize"].as_f64().unwrap(), 22.0);
        let lyric = find_text(&t, "L");
        assert_eq!(lyric["style"]["fontSize"].as_f64().unwrap(), 26.0);
        assert_eq!(lyric["style"]["lineHeight"].as_f64().unwrap(), 32.0);
        assert_eq!(lyric["style"]["lines"].as_f64().unwrap(), 3.0, "歌词最多三行");
        assert_eq!(lyric["style"]["textOverflow"], "ellipsis");
    }

    #[test]
    fn design_layout_scales_to_other_screens() {
        // 方屏没有安全区内边距：坐标就是屏幕坐标
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/c.jpg",
            "lyrics":{"line":"L"}},"req":{"screen":{"shape":"rect","width":432,"height":514}}}"#);
        let row = board(&t);
        let left = row["style"]["left"].as_f64().unwrap();
        let width = row["style"]["width"].as_f64().unwrap();
        assert!(left > 0.0 && left + width < 432.0, "内容越界: {left}+{width}");
        let top = row["style"]["top"].as_f64().unwrap();
        let cover = 135.0 * (432.0 / 466.0);
        assert!((top - (514.0 - cover) / 2.0).abs() <= 1.0, "封面未垂直居中: {top}");
    }

    #[test]
    fn intro_shows_the_upcoming_line_dimmed() {
        // 前奏/间奏（还没唱到第一句）：显示下一句并压暗，而不是留一块空白
        let t = view(r#"{"music":{"hasMedia":true,"title":"Song","artist":"Artist",
            "artUrl":"file:///tmp/c.jpg","lyrics":{"source":"splayer","line":"","next":"I don't care if it hurts"}},
            "req":{"screen":{"shape":"circle","width":466,"height":466}}}"#);
        let up = find_text(&t, "I don't care if it hurts");
        assert_eq!(up["style"]["color"], "rgba(255,255,255,0.45)", "前奏里的下一句要压暗");
        assert_eq!(up["style"]["lines"].as_f64().unwrap(), 3.0);
        // 正在唱的那句是纯白
        let playing = view(&with_lyrics("正在唱"));
        assert_eq!(find_text(&playing, "正在唱")["style"]["color"], "#FFFFFF");
    }

    #[test]
    fn long_title_and_artist_are_elided_in_the_plugin() {
        // 容器上的 text-overflow 在部分固件上只裁不加点，所以插件自己先截；
        // 这里同时钉住"短文本不动"。
        let long_title = "这是一个非常非常长的歌曲名字用来验证省略号是否生效 A Very Long Song Title";
        let long_artist = "一个同样非常非常长的歌手名字用来验证省略号 A Very Long Artist Name Here";
        let t = view(&format!(
            r#"{{"music":{{"hasMedia":true,"title":"{long_title}","artist":"{long_artist}",
                "artUrl":"file:///tmp/c.jpg","lyrics":{{"line":"L"}}}},
                "req":{{"screen":{{"width":466,"height":466}}}}}}"#
        ));
        let col = &board(&t)["children"][1];
        let title = &col["children"][0];
        let artist = &col["children"][1];
        for (node, full) in [(title, long_title), (artist, long_artist)] {
            let shown = node["props"]["text"].as_str().unwrap();
            assert!(shown.ends_with('…'), "没补省略号: {shown}");
            assert!(shown.chars().count() < full.chars().count(), "没有截短: {shown}");
            assert_eq!(node["style"]["lines"].as_f64().unwrap(), 1.0);
            assert_eq!(node["style"]["textOverflow"], "ellipsis");
            assert!(node["style"]["width"].as_f64().unwrap() > 0.0);
        }
        // 短的照旧原样显示
        let short = view(&with_lyrics("L"));
        assert_eq!(find_text(&short, "Song")["props"]["text"], "Song");
        assert_eq!(find_text(&short, "Artist")["props"]["text"], "Artist");
    }

    #[test]
    fn elide_measures_cjk_as_full_width() {
        // 22px 高、220px 宽的一行：中文 10 个字符放不下，ASCII 可以放 18 个
        assert_eq!(elide("短", 220.0, 22.0), "短");
        assert_eq!(elide("一二三四五六七八九十", 220.0, 22.0), "一二三四五六七八九…");
        assert_eq!(elide("abcdefghijklmnop", 220.0, 22.0), "abcdefghijklmnop");
        // 放不下就截到能放下 + 省略号为止（"abc def" = 6 个半角 ≈ 79px，再加 … 刚好）
        assert_eq!(elide("abc defghijklmnopqrst", 100.0, 22.0), "abc def…");
    }

    #[test]
    fn empty_lyric_line_still_keeps_the_design_layout() {
        // 开了集成但还没连上 / 还没开唱：版式已经是设计稿那套，只是不渲染歌词行
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/c.jpg",
            "lyrics":{"source":"splayer","line":""}},"req":{"screen":{"width":466,"height":466}}}"#);
        assert_eq!(t["refreshMs"], 500);
        assert_eq!(board(&t)["children"][1]["children"].as_array().unwrap().len(), 2,
            "只有歌名与歌手");
        assert_eq!(find_text(&t, "T")["style"]["fontSize"].as_f64().unwrap(), 32.0);
    }

    #[test]
    fn without_the_integration_the_classic_layout_stays() {
        // 没开歌词集成 = 完全回到以前的版式（连刷新间隔都一样）
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/c.jpg"},
            "req":{"screen":{"shape":"circle","width":466,"height":466}}}"#);
        assert_eq!(t["refreshMs"], 3000);
        assert_eq!(t["root"]["type"], "scroll");
        assert_eq!(t["root"]["style"]["alignItems"], "center");
        assert_eq!(t["root"]["style"]["padding"].as_f64().unwrap(), 16.0);
        assert!(!t.to_string().contains("\"row\""), "原版式没有内容行容器");
        let kids = t["root"]["children"].as_array().unwrap();
        assert_eq!(kids.len(), 4);
        assert_eq!(kids[0]["style"]["fontSize"].as_f64().unwrap(), 36.0);
        assert_eq!(kids[1]["style"]["fontSize"].as_f64().unwrap(), 32.0);
        assert_eq!(kids[2]["style"]["fontSize"].as_f64().unwrap(), 22.0);
        assert_eq!(kids[3]["style"]["width"].as_f64().unwrap(), 200.0, "圆屏封面 200");
        assert_eq!(kids[3]["style"]["marginTop"].as_f64().unwrap(), 18.0);
        assert!(kids[1]["style"]["textOverflow"].is_null(), "原版式不裁歌名");
    }

    #[test]
    fn classic_layout_keeps_the_rect_cover_centring() {
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/cover.jpg"},
            "req":{"screen":{"shape":"rect","width":432,"height":514}}}"#);
        let cover = t["root"]["children"].as_array().unwrap().iter()
            .find(|c| c["type"] == "image").expect("cover").clone();
        let style = &cover["style"];
        assert_eq!(style["position"], "absolute");
        // canvas: 480 x (514*480/432=571.1)；290 的封面 -> left 95 / top 141 + 26
        assert_eq!(style["left"].as_f64().unwrap(), 95.0);
        assert_eq!(style["top"].as_f64().unwrap(), 167.0);
        assert_eq!(style["width"].as_f64().unwrap(), 290.0);
    }

    #[test]
    fn background_follows_settings_and_media_state() {
        let with = view(r#"{"music":{"hasMedia":true,"title":"x","artUrl":"file:///tmp/cover.jpg"},
            "server":{"base":"http://host:9000"},
            "req":{"screen":{"width":466,"height":466}},
            "storage":{"blur":12,"dim":0.5}}"#);
        let bg = with["root"]["style"]["backgroundImage"].as_str().unwrap_or("");
        assert!(bg.starts_with("http://host:9000/api/music/art?"), "bg: {bg}");
        assert!(bg.contains("w=466") && bg.contains("h=466"), "bg must carry screen size: {bg}");
        assert!(bg.contains("blur=12") && bg.contains("dim=0.5"), "bg: {bg}");
        let without = view(r#"{"music":{"hasMedia":false},"server":{"base":"http://host:9000"}}"#);
        assert!(without["root"]["style"]["backgroundImage"].is_null(), "no media -> no background image");
        let all = texts(&without);
        assert!(all.iter().any(|t| t.contains("无正在播放")), "placeholder missing: {:?}", all);
    }

    #[test]
    fn background_and_cover_are_blurred_screen_sized_art() {
        // 回归：切歌必须换 URL，否则封面/背景会被 WebView 缓存住不刷新
        let mk = |title: &str| view(&format!(
            r#"{{"music":{{"hasMedia":true,"title":"{title}","artist":"X","artUrl":"file:///tmp/cover.jpg",
                "lyrics":{{"line":"L"}}}},"server":{{"base":"http://h:9000"}},
                "req":{{"screen":{{"width":466,"height":466}}}}}}"#
        ));
        let a = mk("A");
        let b = mk("B");
        let bg_a = a["root"]["style"]["backgroundImage"].as_str().unwrap_or("");
        let bg_b = b["root"]["style"]["backgroundImage"].as_str().unwrap_or("");
        assert!(bg_a.contains("&k="), "background lacks a cache key: {bg_a}");
        assert_ne!(bg_a, bg_b, "background URL must change when the track changes");
        let cover_a = board(&a)["children"][0]["props"]["src"].as_str().unwrap_or("").to_string();
        assert!(cover_a.contains("&k="), "cover lacks a cache key: {cover_a}");
        assert_ne!(cover_a, board(&b)["children"][0]["props"]["src"].as_str().unwrap_or(""));
    }

    #[test]
    fn media_without_cover_emits_no_art_nodes() {
        // macOS 的 Apple Music / Windows 上无缩略图的曲目：hasMedia=true 但没有
        // artUrl。此时若仍请求 /api/music/art，服务端返回 400，界面上就是白块。
        let v = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","lyrics":{"line":"L"}},
            "server":{"base":"http://host:9000"},"req":{"screen":{"width":466,"height":466}}}"#);
        let dump = v.to_string();
        assert!(!dump.contains("/api/music/art"), "无封面时不应请求封面接口: {dump}");
        assert!(v["root"]["style"]["backgroundImage"].is_null(), "无封面时不应设置背景图");
        assert!(!dump.contains("无正在播放的媒体"), "有音乐时不应显示空状态文案: {dump}");
        assert!(dump.contains("♪"), "应显示中性占位: {dump}");
        assert_eq!(board(&v)["children"][0]["type"], "text", "占位替代封面图");
    }

    #[test]
    fn font_scale_setting_scales_all_text() {
        let mk = |scale: &str| view(&format!(
            r#"{{"music":{{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/c.jpg",
                "lyrics":{{"line":"L"}}}}{scale},"req":{{"screen":{{"width":466,"height":466}}}}}}"#
        ));
        let size_of = |t: &Value, txt: &str| -> f64 {
            find_text(t, txt)["style"]["fontSize"].as_f64().unwrap()
        };
        let base = mk("");
        let big = mk(r#","storage":{"fontScale":1.5}"#);
        assert_eq!(size_of(&base, "00:00"), 36.0);
        assert_eq!(size_of(&big, "00:00"), 54.0);
        assert_eq!(size_of(&base, "T"), 32.0);
        assert_eq!(size_of(&big, "T"), 48.0);
        assert_eq!(size_of(&base, "A"), 22.0);
        assert_eq!(size_of(&big, "A"), 33.0);
        assert_eq!(size_of(&base, "L"), 26.0);
        assert_eq!(size_of(&big, "L"), 39.0);
    }

    #[test]
    fn depth_never_exceeds_three_levels() {
        // 容器页只展开 3 层：根(0) → L1 → L2 → L3 叶子。深层容器会被渲染成垃圾文本，
        // 这里锁死两种版式的结构（叶子层不允许再出现容器）。
        fn leafish(n: &Value) -> bool {
            matches!(n["type"].as_str().unwrap_or(""), "text" | "image" | "icon" | "box"
                | "badge" | "spinner" | "button" | "switch" | "input" | "progress" | "nav")
        }
        fn walk(n: &Value, depth: usize) {
            if let Some(kids) = n["children"].as_array() {
                assert!(depth <= 2, "container at depth {depth} would be clipped by the 3-level unroll");
                for k in kids {
                    if k.get("children").is_some() {
                        walk(k, depth + 1);
                    } else {
                        assert!(leafish(k), "unexpected leaf type {:?}", k["type"]);
                    }
                }
            }
        }
        walk(&view(&with_lyrics("L"))["root"], 0);
        walk(&view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A"},"time":{"hour":1,"minute":2}}"#)["root"], 0);
    }

    #[test]
    fn cover_tap_emits_music_and_storage_effects() {
        let t = view(r#"{"time":{"unix":1700000000},"music":{"hasMedia":true,"title":"x"},
            "req":{"event":{"handler":"toggle"}}}"#);
        let e = t["effects"].as_array().expect("effects");
        assert_eq!(e.len(), 2);
        assert_eq!(e[0]["op"], "music");
        assert_eq!(e[0]["action"], "playpause");
        assert_eq!(e[1]["op"], "storage");
        assert_eq!(e[1]["key"], "lastToggleAt");
        // unrelated events emit nothing
        let idle = view(r#"{"music":{"hasMedia":true,"title":"x","artUrl":"file:///tmp/cover.jpg"},"req":{"event":{"handler":"other"}}}"#);
        assert_eq!(idle["effects"].as_array().unwrap().len(), 0);
    }
}
