// 桌搭 (Desk Buddy) — HydroKit WASM plugin (ABI v1).
//
// 正在播放的圆屏界面：模糊封面铺底，顶部时钟，左侧封面，右侧歌名/歌手/歌词。
// 布局按设计稿（466×466 圆屏）等比换算到实际屏幕，圆屏方屏共用一套结构。
//
// 歌词来自宿主注入的 ctx.music.lyrics（桌面端接 SPlayer 的 WebSocket，
// 见 desktop/internal/splayer）；宿主没有这个能力时（例如安卓端）不渲染歌词行。
use serde_json::{json, Value};
use std::alloc::{alloc, dealloc, Layout};

/// 设计稿画布边长（466×466 圆屏，见设计稿）。
const DESIGN: f64 = 466.0;
/// 设计稿里的关键尺寸。
const D_COVER: f64 = 135.0; // 封面边长
const D_COVER_LEFT: f64 = 46.0; // 封面左边距
const D_RIGHT_PAD: f64 = 42.0; // 文字列右侧安全边距（圆屏弧边）
const D_GAP: f64 = 18.0; // 封面与文字列间距
const D_CLOCK_TOP: f64 = 26.0; // 时钟顶部
const D_RADIUS: f64 = 21.0; // 封面圆角

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
fn one_line(s: &str, width: f64, size: f64, weight: f64, color: &str) -> Value {
    json!({
        "type": "text",
        "props": { "text": s },
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
    // 宿主注入的实时歌词（桌面端接 SPlayer；没有就留空，歌词行不渲染）
    let lyric = text(&ctx, &["music", "lyrics", "line"]);
    let handler = text(&ctx, &["req", "event", "handler"]);

    // 屏幕尺寸：设计稿坐标等比换算（圆屏方屏共用）
    let screen_w = num(&ctx, &["req", "screen", "width"]);
    let screen_h = num(&ctx, &["req", "screen", "height"]);
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

    // ---- 版面（全部按设计稿等比换算）----
    let cover = (D_COVER * s).round();
    let cover_left = (D_COVER_LEFT * s).round();
    let gap = (D_GAP * s).round();
    let text_left = cover_left + cover + gap;
    let text_width = (sw - text_left - D_RIGHT_PAD * s).round();
    // 封面垂直居中于屏幕
    let content_top = ((sh - cover) / 2.0).round();

    // 封面：有封面就放图（点击暂停/播放），否则给个中性占位
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
    let title_text = if title.is_empty() { "HydroLink".to_string() } else { title };
    let mut text_kids = vec![
        one_line(&title_text, text_width, f(24.0), 700.0, "#FFFFFF"),
        one_line(
            if artist.is_empty() && !has_media { "无正在播放的媒体" } else { &artist },
            text_width,
            f(17.0),
            400.0,
            "rgba(255,255,255,0.4)",
        ),
    ];
    if !lyric.is_empty() {
        // 歌词最多两行，超出补省略号（设计稿里的一句歌词会自然折成两行）
        text_kids.push(json!({
            "type": "text",
            "props": { "text": lyric },
            "style": {
                "width": text_width,
                "fontSize": f(20.0),
                "lineHeight": f(25.0),
                "color": "#FFFFFF",
                "marginTop": (5.0 * s).round(),
                "lines": 2,
                "textOverflow": "ellipsis"
            }
        }));
    }

    let children = json!([
        {
            "type": "text",
            "props": { "text": clock },
            "style": {
                "position": "absolute",
                "top": (D_CLOCK_TOP * s).round(),
                "left": 0,
                "right": 0,
                // 显式给整屏宽：容器里的百分比宽度会随包装层收缩，居中就不再可靠
                "width": sw.round() as i64,
                "fontSize": f(21.0),
                "fontWeight": 700,
                "color": "#FFFFFF",
                "textAlign": "center"
            }
        },
        {
            "type": "row",
            "style": {
                "position": "absolute",
                "top": content_top,
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
    ]);

    // 1s 一刷：歌词要跟着播放下走
    let tree = json!({
        "refreshMs": 1000,
        "effects": effects,
        "root": {
            "type": "column",
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

    /// 版面：466 圆屏上，封面/文字列的位置与设计稿一致，且都在屏内。
    fn board(tree: &Value) -> Value {
        let kids = tree["root"]["children"].as_array().expect("root children");
        kids.iter().find(|c| c["type"] == "row").expect("content row").clone()
    }

    #[test]
    fn layout_follows_the_design_on_a_round_screen() {
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/c.jpg"},
            "req":{"screen":{"shape":"circle","width":466,"height":466}}}"#);
        let row = board(&t);
        assert_eq!(row["style"]["left"].as_f64().unwrap(), 46.0, "封面前的安全边距");
        assert_eq!(row["style"]["top"].as_f64().unwrap(), 166.0, "封面垂直居中 (466-135)/2");
        let cover = &row["children"][0];
        assert_eq!(cover["type"], "image");
        assert_eq!(cover["style"]["width"].as_f64().unwrap(), 135.0);
        assert_eq!(cover["style"]["height"].as_f64().unwrap(), 135.0);
        assert_eq!(cover["style"]["borderRadius"].as_f64().unwrap(), 21.0);
        // 文字列紧接封面右侧，右边留出圆屏安全边距
        let col = &row["children"][1];
        assert_eq!(col["style"]["marginLeft"].as_f64().unwrap(), 18.0);
        assert_eq!(col["style"]["width"].as_f64().unwrap(), 225.0);
        assert_eq!(row["style"]["width"].as_f64().unwrap(), 378.0); // 46…424
        // 时钟整行居中
        let clock = find_text(&t, "00:00");
        assert_eq!(clock["style"]["textAlign"], "center");
        assert_eq!(clock["style"]["fontSize"].as_f64().unwrap(), 21.0);
        assert_eq!(clock["style"]["width"].as_f64().unwrap(), 466.0, "时钟占满整屏宽才能居中");
        assert_eq!(clock["style"]["top"].as_f64().unwrap(), 26.0);
    }

    #[test]
    fn layout_scales_to_other_screens() {
        // 方屏 432×514：等比缩放，纵向按实际高度居中，仍在屏内
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/c.jpg"},
            "req":{"screen":{"shape":"rect","width":432,"height":514}}}"#);
        let row = board(&t);
        let left = row["style"]["left"].as_f64().unwrap();
        let width = row["style"]["width"].as_f64().unwrap();
        assert!(left > 0.0 && left + width < 432.0, "内容越界: {left}+{width}");
        let top = row["style"]["top"].as_f64().unwrap();
        let cover = 135.0 * (432.0 / 466.0);
        assert!((top - (514.0 - cover) / 2.0).abs() <= 1.0, "封面未垂直居中: {top}");
    }

    #[test]
    fn title_is_single_line_with_ellipsis() {
        let long = "这是一个非常非常长的歌曲名字用来验证省略号是否生效 A Very Long Song Title";
        let t = view(&format!(
            r#"{{"music":{{"hasMedia":true,"title":"{long}","artist":"A","artUrl":"file:///tmp/c.jpg"}},
                "req":{{"screen":{{"width":466,"height":466}}}}}}"#
        ));
        let title = find_text(&t, long);
        assert_eq!(title["style"]["lines"].as_f64().unwrap(), 1.0, "标题必须单行");
        assert_eq!(title["style"]["textOverflow"], "ellipsis");
        // 宽度固定，容器才知道该在哪截断
        assert!(title["style"]["width"].as_f64().unwrap() > 0.0);
        // 歌手行同样单行省略（设计稿里它只有一行）
        let artist = find_text(&t, "A");
        assert_eq!(artist["style"]["lines"].as_f64().unwrap(), 1.0);
    }

    #[test]
    fn lyrics_come_from_the_host_context() {
        let t = view(r#"{"music":{"hasMedia":true,"title":"Feel Something","artist":"Jaymes Young",
            "artUrl":"file:///tmp/c.jpg","lyrics":{"source":"splayer","line":"Make me feel something, something","next":"下一句"}},
            "req":{"screen":{"width":466,"height":466}}}"#);
        let lyric = find_text(&t, "Make me feel something, something");
        assert_eq!(lyric["style"]["lines"].as_f64().unwrap(), 2.0, "歌词最多两行");
        assert_eq!(lyric["style"]["textOverflow"], "ellipsis");
        assert_eq!(lyric["style"]["fontSize"].as_f64().unwrap(), 20.0);
        assert_eq!(lyric["style"]["lineHeight"].as_f64().unwrap(), 25.0);
        // 字体缩放同样作用于歌词
        let big = view(r#"{"music":{"hasMedia":true,"lyrics":{"line":"L"}},"storage":{"fontScale":1.5},
            "req":{"screen":{"width":466,"height":466}}}"#);
        assert_eq!(find_text(&big, "L")["style"]["fontSize"].as_f64().unwrap(), 30.0);
    }

    #[test]
    fn no_lyrics_no_lyric_node() {
        // 安卓端 / 没开 SPlayer 集成 / 纯音乐：不能出现空的歌词行
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/c.jpg"},
            "req":{"screen":{"width":466,"height":466}}}"#);
        let col = &board(&t)["children"][1];
        assert_eq!(col["children"].as_array().unwrap().len(), 2, "只有歌名与歌手: {col}");
        let dump = t.to_string();
        assert!(!dump.contains("lyrics"), "没有歌词时不应引用 lyrics: {dump}");
    }

    #[test]
    fn refreshes_once_per_second() {
        let t = view(r#"{"music":{"hasMedia":true,"title":"T"}}"#);
        assert_eq!(t["refreshMs"], 1000, "歌词要跟着播放下走，1s 一刷");
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
            r#"{{"music":{{"hasMedia":true,"title":"{title}","artist":"X","artUrl":"file:///tmp/cover.jpg"}},
                "server":{{"base":"http://h:9000"}},"req":{{"screen":{{"width":466,"height":466}}}}}}"#
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
        let v = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A"},
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
        let base = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/c.jpg"},"time":{"hour":1,"minute":2}}"#);
        let big = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/c.jpg"},"time":{"hour":1,"minute":2},
            "storage":{"fontScale":1.5}}"#);
        let size_of = |t: &Value, txt: &str| -> f64 {
            find_text(t, txt)["style"]["fontSize"].as_f64().unwrap()
        };
        assert_eq!(size_of(&base, "01:02"), 21.0);
        assert_eq!(size_of(&big, "01:02"), 32.0);
        assert_eq!(size_of(&base, "T"), 24.0);
        assert_eq!(size_of(&big, "T"), 36.0);
        assert_eq!(size_of(&base, "A"), 17.0);
        assert_eq!(size_of(&big, "A"), 26.0);
    }

    #[test]
    fn depth_never_exceeds_three_levels() {
        // 容器页只展开 3 层：根(0) → L1 → L2 → L3 叶子。深层容器会被渲染成垃圾文本，
        // 这里锁死结构（叶子层不允许再出现容器）。
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","lyrics":{"line":"L"},"artUrl":"file:///tmp/c.jpg"},
            "req":{"screen":{"width":466,"height":466}},"time":{"hour":1,"minute":2}}"#);
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
        walk(&t["root"], 0);
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
