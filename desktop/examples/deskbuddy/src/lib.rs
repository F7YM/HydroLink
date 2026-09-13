// 桌搭 (Desk Buddy) — HydroKit WASM plugin (ABI v1).
//
// Now-playing screen with system load: blurred album art background, clock,
// track info, cover art, and two arc progress bars (CPU left, RAM right).
use serde_json::{json, Value};
use std::alloc::{alloc, dealloc, Layout};

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

    // 屏幕信息：用于背景图尺寸与方屏封面定位（designWidth 480 换算）
    let screen_w = num(&ctx, &["req", "screen", "width"]);
    let screen_h = num(&ctx, &["req", "screen", "height"]);
    let shape = text(&ctx, &["req", "screen", "shape"]);
    let canvas_w = 480.0;
    let canvas_h = if screen_w > 0.0 && screen_h > 0.0 { screen_h * (480.0 / screen_w) } else { 480.0 };
    // Tunables (WebUI writes them into server-scope storage; defaults keep the
    // reference-mock look). gap/stroke are physical-px intents.
    let blur = setting(&ctx, "blur", 26.0).clamp(0.0, 60.0);
    let dim = setting(&ctx, "dim", 0.3).clamp(0.05, 1.0);
    // 侧条默认直条（普通 div 渲染，稳定可见）；arc 为可选
    let font_scale = setting(&ctx, "fontScale", 1.0).clamp(0.6, 2.0);
    let f = |base: f64| (base * font_scale).round();



    // Clicking the cover toggles playback; the host executes the effects
    // (music control + remembering the action in server-scope storage).
    let mut effects = json!([]);
    if handler == "toggle" {
        effects = json!([
            { "op": "music", "action": "playpause" },
            { "op": "storage", "key": "lastToggleAt", "value": num(&ctx, &["time", "unix"]) }
        ]);
    }

    // 只有"有音乐 **且** 服务端确实拿到封面"时才铺背景/封面。
    // 否则（macOS 的 Apple Music、Windows 上无缩略图的曲目）请求 /api/music/art
    // 会得到 400，手表那边背景变黑/空白。
    let has_art = has_media && !art_url.is_empty();

    let server_base = text(&ctx, &["server", "base"]);
    let background = if !has_art {
        String::new()
    } else if !server_base.is_empty() {
        // 直接要屏幕尺寸的裁切图（w/h=cover 语义），方屏上不会出现上下黑边
        let (sw, sh) = if screen_w > 0.0 && screen_h > 0.0 {
            (screen_w.round() as i64, screen_h.round() as i64)
        } else {
            (466, 466)
        };
        // size=<长边> 作为兼容：旧版服务端（不认 w/h）仍会缩放到屏幕尺寸，
        // 新版优先使用 w/h 做 cover 裁切。
        format!(
            "{}/api/music/art?w={}&h={}&size={}&blur={}&dim={}&k={}",
            server_base.trim_end_matches('/'),
            sw,
            sh,
            sw.max(sh),
            blur.round() as i64,
            // keep two decimals for dim
            (dim * 100.0).round() / 100.0,
            track_key(&ctx)
        )
    } else {
        art_blur
    };
    let mut root_style = json!({
        "padding": 16,
        "alignItems": "center", // 列内子节点水平居中（封面不再靠左）
        "backgroundColor": "#0b0d12",
        "backgroundSize": "cover",
        "backgroundPosition": "center"
    });
    if !background.is_empty() {
        root_style["backgroundImage"] = json!(background);
    }


    // 弧条：0°=12 点方向、顺时针；左侧弧以 270°（9 点）为中心，右侧以 90°（3 点）


    let cover_src = if !has_art {
        String::new()
    } else if !server_base.is_empty() {
        let size = if screen_w > 0.0 { screen_w.round() as i64 } else { 466 };
        format!("{}/api/music/art?size={}&k={}",
            server_base.trim_end_matches('/'), size, track_key(&ctx))
    } else {
        art_url.clone()
    };

    // 封面必须是**叶子**（容器页只展开 3 层，L3 上的容器会被当成未知叶子）；
    // 居中由中间列的 alignItems: center 完成
    // 方屏（rect）：封面放大并**绝对居中于屏幕正中**；
    // 圆屏：保持流式布局（居中由根节点 alignItems 完成）
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

    let cover = if !cover_src.is_empty() {
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

    // 侧边进度条功能已移除：树只有中间一列内容
    let mut children: Vec<Value> = Vec::new();
    let mut center_kids: Vec<Value> = Vec::new();
    center_kids.push(json!({
        "type": "text",
        "props": { "text": clock },
        "style": { "fontSize": f(36.0), "fontWeight": 700, "color": "#FFFFFF",
                   "textAlign": "center", "width": "100%" }
    }));
    center_kids.push(json!({
        "type": "text",
        "props": { "text": if title.is_empty() { "HydroLink".to_string() } else { title } },
        "style": { "fontSize": f(32.0), "fontWeight": 700, "color": "#FFFFFF",
                   "textAlign": "center", "width": "100%", "marginTop": 10 }
    }));
    center_kids.push(json!({
        "type": "text",
        "props": { "text": artist },
        "style": { "fontSize": f(22.0), "color": "rgba(255,255,255,0.4)",
                   "textAlign": "center", "width": "100%", "marginTop": 4 }
    }));
    center_kids.push(cover);
    children.extend(center_kids);

    let tree = json!({
        "refreshMs": 3000,
        "effects": effects,
        "root": {
            "type": "scroll",
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

    #[test]
    fn renders_clock_and_track_info() {
        let t = view(r#"{
            "time":{"hour":9,"minute":41},
            "screen":{},
            "system":{"cpu":12.5,"memPercent":63.2},
            "music":{"hasMedia":true,"title":"Feel Something","artist":"Jaymes Young",
                     "artUrl":"http://s/api/music/art"},
            "server":{"base":"http://192.168.1.5:9000"},
            "req":{"screen":{"width":466,"height":466},"event":{}}
        }"#);
        assert_eq!(t["refreshMs"], 3000);
        assert!(t["root"]["children"].as_array().unwrap().len() >= 3);
        let all = texts(&t);
        assert!(all.contains(&"09:41".to_string()), "clock missing: {:?}", all);
        assert!(all.contains(&"Feel Something".to_string()));
        assert!(all.contains(&"Jaymes Young".to_string()));
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
        assert!(bg.contains("size=466"), "bg keeps size= as a fallback for older servers: {bg}");
        assert!(bg.contains("blur=12") && bg.contains("dim=0.5"), "bg: {bg}");
        let without = view(r#"{"music":{"hasMedia":false},"server":{"base":"http://host:9000"}}"#);
        assert!(without["root"]["style"]["backgroundImage"].is_null(), "no media -> no background image");
        let all = texts(&without);
        assert!(all.iter().any(|t| t.contains("无正在播放")), "placeholder missing: {:?}", all);
    }

    #[test]
    fn rect_screen_centres_and_enlarges_the_cover() {
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/cover.jpg"},
            "server":{"base":"http://h:9000"},
            "req":{"screen":{"shape":"rect","width":432,"height":514}}}"#);
        let cover = t["root"]["children"].as_array().unwrap().iter()
            .find(|c| c["type"] == "image").expect("cover").clone();
        let style = &cover["style"];
        assert_eq!(style["position"], "absolute", "rect cover must be absolutely centred");
        // canvas: 480 x (514*480/432=571.1)；290 的封面 → left 95 / top 141
        assert_eq!(style["left"].as_f64().unwrap(), 95.0);
        assert_eq!(style["top"].as_f64().unwrap(), 167.0); // 中心 141 + 26
        assert_eq!(style["width"].as_f64().unwrap(), 290.0);
        // 圆心与画布中心一致
        let cx = style["left"].as_f64().unwrap() + style["width"].as_f64().unwrap() / 2.0;
        assert!((cx - 240.0).abs() < 1.0, "cover not horizontally centred: {cx}");

        // 圆屏保持流式（无绝对定位、尺寸较小）
        let c = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/cover.jpg"},
            "server":{"base":"http://h:9000"},
            "req":{"screen":{"shape":"circle","width":466,"height":466}}}"#);
        let cc = c["root"]["children"].as_array().unwrap().iter()
            .find(|x| x["type"] == "image").expect("cover").clone();
        assert!(cc["style"]["position"].is_null(), "circle cover stays in flow");
        assert_eq!(cc["style"]["width"].as_f64().unwrap(), 200.0);
    }

    #[test]
    fn cover_is_centred_by_the_root_column() {
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/cover.jpg"},
            "server":{"base":"http://h:9000"},"req":{"screen":{"width":432,"height":514}}}"#);
        assert_eq!(t["root"]["style"]["alignItems"], "center",
            "column children (cover) must be centred");
        let cover = t["root"]["children"].as_array().unwrap().iter()
            .find(|c| c["type"] == "image")
            .expect("cover image missing");
        assert!(cover["style"]["width"].as_f64().unwrap() > 0.0);
    }

    #[test]
    fn no_progress_or_box_nodes() {
        // 进度条功能已移除：树里不允许再出现 box / progress 节点
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/cover.jpg"},
            "system":{"cpu":50,"memPercent":50},"req":{"screen":{"width":466,"height":466}}}"#);
        let dump = t.to_string();
        assert!(!dump.contains("\"box\""), "no box nodes expected: {dump}");
        assert!(!dump.contains("progress"), "no progress nodes expected: {dump}");
    }

    #[test]
    fn typography_matches_the_spec() {
        let t = view(r#"{"time":{"hour":9,"minute":41},
            "music":{"hasMedia":true,"title":"T","artist":"A"},"req":{"screen":{"width":466,"height":466}}}"#);
        let clock = find_text(&t, "09:41");
        assert_eq!(clock["style"]["fontSize"].as_f64().unwrap(), 36.0);
        assert_eq!(clock["style"]["fontWeight"].as_f64().unwrap(), 700.0);
        let title = find_text(&t, "T");
        assert_eq!(title["style"]["fontSize"].as_f64().unwrap(), 32.0);
        assert_eq!(title["style"]["fontWeight"].as_f64().unwrap(), 700.0);
        let artist = find_text(&t, "A");
        assert_eq!(artist["style"]["fontSize"].as_f64().unwrap(), 22.0);
        assert_eq!(artist["style"]["color"].as_str().unwrap(), "rgba(255,255,255,0.4)");
    }

    #[test]
    fn art_urls_change_with_the_track() {
        // 回归：切歌必须换 URL，否则封面/背景会被 WebView 缓存住不刷新
        let a = view(r#"{"music":{"hasMedia":true,"title":"A","artist":"X","artUrl":"file:///tmp/cover.jpg"},
            "server":{"base":"http://h:9000"},"req":{"screen":{"width":466,"height":466}}}"#);
        let b = view(r#"{"music":{"hasMedia":true,"title":"B","artist":"X","artUrl":"file:///tmp/cover.jpg"},
            "server":{"base":"http://h:9000"},"req":{"screen":{"width":466,"height":466}}}"#);
        let bg_a = a["root"]["style"]["backgroundImage"].as_str().unwrap_or("");
        let bg_b = b["root"]["style"]["backgroundImage"].as_str().unwrap_or("");
        assert!(bg_a.contains("&k="), "background lacks a cache key: {bg_a}");
        assert_ne!(bg_a, bg_b, "background URL must change when the track changes");
        let cover_a = a["root"]["children"]
            .as_array()
            .and_then(|kids| kids.iter().find(|c| c["type"] == "image"))
            .and_then(|im| im["props"]["src"].as_str().map(|s| s.to_string()))
            .unwrap_or_default();
        assert!(cover_a.contains("&k="), "cover lacks a cache key: {cover_a}");
    }

    #[test]
    fn media_without_cover_emits_no_art_nodes() {
        // macOS 的 Apple Music / Windows 上无缩略图的曲目：hasMedia=true 但没有
        // artUrl。此时若仍请求 /api/music/art，服务端返回 400，手表上背景变黑、
        // 封面空白 —— 所以必须完全不产出 art 节点。
        let v = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A"},
            "server":{"base":"http://host:9000"},"req":{"screen":{"width":466,"height":466}}}"#);
        let dump = v.to_string();
        assert!(!dump.contains("/api/music/art"), "无封面时不应请求封面接口: {dump}");
        assert!(v["root"]["style"]["backgroundImage"].is_null(), "无封面时不应设置背景图");
        assert!(!dump.contains("无正在播放的媒体"), "有音乐时不应显示空状态文案: {dump}");
        assert!(dump.contains("\u{266a}") || dump.contains("♪"), "应显示中性占位: {dump}");
    }

    #[test]
    fn depth_never_exceeds_three_levels() {
        // 容器页只展开 3 层：根(0) → L1 → L2 → L3 叶子。深层容器会被渲染成垃圾文本，
        // 这里锁死结构（叶子层不允许再出现容器）。
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/cover.jpg"},
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
    fn font_scale_setting_scales_all_text() {
        let base = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/cover.jpg"},"time":{"hour":1,"minute":2}}"#);
        let big = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A","artUrl":"file:///tmp/cover.jpg"},"time":{"hour":1,"minute":2},
            "storage":{"fontScale":1.5}}"#);
        let size_of = |t: &Value, txt: &str| -> f64 {
            find_text(t, txt)["style"]["fontSize"].as_f64().unwrap()
        };
        assert_eq!(size_of(&base, "01:02"), 36.0);
        assert_eq!(size_of(&big, "01:02"), 54.0);
        assert_eq!(size_of(&base, "A"), 22.0);
        assert_eq!(size_of(&big, "A"), 33.0);
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
