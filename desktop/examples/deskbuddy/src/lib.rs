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

fn setting_str(ctx: &Value, key: &str, default: &str) -> String {
    let v = &ctx["storage"][key];
    match v.as_str() {
        Some(s) if !s.is_empty() => s.to_string(),
        _ => default.to_string(),
    }
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

    let cpu = num(&ctx, &["system", "cpu"]);
    let mem = num(&ctx, &["system", "memPercent"]);
    let title = text(&ctx, &["music", "title"]);
    let artist = text(&ctx, &["music", "artist"]);
    let has_media = ctx["music"]["hasMedia"].as_bool().unwrap_or(false);
    let art_url = text(&ctx, &["music", "artUrl"]);
    let art_blur = text(&ctx, &["music", "artBlur"]);
    let handler = text(&ctx, &["req", "event", "handler"]);

    // 屏幕自适应几何：Vela 的 px 相对 designWidth(480) 缩放，故用
    // req.screen.width（物理像素）换算回设计像素，保证任何屏型上弧条
    // 距屏幕边缘的物理间距一致。
    let screen_w = num(&ctx, &["req", "screen", "width"]);
    let screen_h = num(&ctx, &["req", "screen", "height"]);
    let scale = if screen_w > 0.0 { 480.0 / screen_w } else { 1.0 };
    let canvas_w = 480.0;
    let canvas_h = if screen_h > 0.0 { screen_h * scale } else { 480.0 };
    let cx = canvas_w / 2.0;
    let cy = canvas_h / 2.0;
    // Tunables (WebUI writes them into server-scope storage; defaults keep the
    // reference-mock look). gap/stroke are physical-px intents.
    let gap_phys = setting(&ctx, "edgeGap", 16.0).clamp(4.0, 60.0);
    let arc_span = setting(&ctx, "arcSpan", 60.0).clamp(20.0, 120.0);
    let stroke_phys = setting(&ctx, "stroke", 9.0).clamp(2.0, 24.0);
    let blur = setting(&ctx, "blur", 26.0).clamp(0.0, 60.0);
    let dim = setting(&ctx, "dim", 0.3).clamp(0.05, 1.0);
    let colors = setting_str(&ctx, "colors", "gray");
    let bars = setting_str(&ctx, "bars", "arc"); // arc（贴边弧线）| bar（直条备选）

    let edge_gap = if screen_w > 0.0 { gap_phys * scale } else { gap_phys * 2.0 };
    let radius = cx - edge_gap;
    let stroke = if screen_w > 0.0 { stroke_phys * scale } else { stroke_phys };

    // Clicking the cover toggles playback; the host executes the effects
    // (music control + remembering the action in server-scope storage).
    let mut effects = json!([]);
    if handler == "toggle" {
        effects = json!([
            { "op": "music", "action": "playpause" },
            { "op": "storage", "key": "lastToggleAt", "value": num(&ctx, &["time", "unix"]) }
        ]);
    }

    let server_base = text(&ctx, &["server", "base"]);
    let background = if !has_media {
        String::new()
    } else if !server_base.is_empty() {
        let size = if screen_w > 0.0 { screen_w.round() as i64 } else { 466 };
        format!(
            "{}/api/music/art?size={}&blur={}&dim={}&k={}",
            server_base.trim_end_matches('/'),
            size,
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
        "backgroundColor": "#0b0d12",
        "backgroundSize": "cover",
        "backgroundPosition": "center"
    });
    if !background.is_empty() {
        root_style["backgroundImage"] = json!(background);
    }


    // 弧条：0°=12 点方向、顺时针；左侧弧以 270°（9 点）为中心，右侧以 90°（3 点）
    // 直条备选：轨道 + 填充两个 box（不依赖 progress 组件）
    let bar = |value: f64, side: &str, color: &str| -> Vec<Value> {
        let (left, right) = if side == "left" { (12.0, 0.0) } else { (0.0, 12.0) };
        let pct = value.clamp(0.0, 100.0).round();
        vec![
            json!({
                "type": "box",
                "style": {
                    "position": "absolute", "top": 120, "bottom": 120,
                    "left": left, "right": right, "width": 10,
                    "borderRadius": 5, "backgroundColor": "rgba(255,255,255,0.14)"
                }
            }),
            json!({
                "type": "box",
                "style": {
                    "position": "absolute", "bottom": 120, "left": left, "right": right,
                    "width": 10, "height": format!("{}%", pct),
                    "borderRadius": 5, "backgroundColor": color
                }
            })
        ]
    };

    let arc = |value: f64, start: i64, color: &str| {
        json!({
            "type": "progress",
            "props": { "percent": value.round(), "variant": "arc" },
            "style": {
                "position": "absolute",
                "left": 0, "top": 0, "width": canvas_w, "height": canvas_h,
                "startAngle": start,
                "totalAngle": arc_span.round(),
                "radius": radius.round(),
                "centerX": cx,
                "centerY": cy,
                "strokeWidth": stroke.round(),
                "color": color,
                "layerColor": "rgba(255,255,255,0.14)"
            }
        })
    };

    let cover_src = if !server_base.is_empty() {
        let size = if screen_w > 0.0 { screen_w.round() as i64 } else { 466 };
        format!("{}/api/music/art?size={}&k={}",
            server_base.trim_end_matches('/'), size, track_key(&ctx))
    } else {
        art_url.clone()
    };

    let cover = if has_media && !cover_src.is_empty() {
        json!({
            "type": "row",
            "style": { "justifyContent": "center", "marginTop": 18 },
            "children": [{
                "type": "image",
                "props": { "src": cover_src },
                "events": { "tap": "toggle" },
                "style": { "width": 200, "height": 200, "borderRadius": 16 }
            }]
        })
    } else {
        json!({
            "type": "text",
            "props": { "text": "无正在播放的媒体" },
            "style": { "fontSize": 22, "color": "#8b93a3", "textAlign": "center", "width": "100%", "marginTop": 40 }
        })
    };

    let color_left = if colors == "split" { "#BAC3FF" } else { "#C8CDD8" };
    let color_right = if colors == "split" { "#7ADFA0" } else { "#C8CDD8" };
    let mut sides: Vec<Value> = Vec::new();
    if bars == "bar" {
        sides.extend(bar(cpu, "left", color_left));
        sides.extend(bar(mem, "right", color_right));
    } else {
        sides.push(arc(cpu, 240, color_left));
        sides.push(arc(mem, 60, color_right));
    }

    let mut children: Vec<Value> = sides;
    children.push(json!({
        "type": "text",
        "props": { "text": clock },
        "style": { "fontSize": 44, "fontWeight": 700, "color": "#FFFFFF",
                   "textAlign": "center", "width": "100%" }
    }));
    children.push(json!({
        "type": "text",
        "props": { "text": if title.is_empty() { "HydroLink".to_string() } else { title } },
        "style": { "fontSize": 30, "fontWeight": 700, "color": "#FFFFFF",
                   "textAlign": "center", "width": "100%", "marginTop": 10 }
    }));
    children.push(json!({
        "type": "text",
        "props": { "text": artist },
        "style": { "fontSize": 22, "color": "#B9C0CC",
                   "textAlign": "center", "width": "100%", "marginTop": 4 }
    }));
    children.push(cover);

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

    fn arcs(tree: &Value) -> Vec<&Value> {
        tree["root"]["children"]
            .as_array()
            .expect("children")
            .iter()
            .filter(|c| c["props"]["variant"] == "arc")
            .collect()
    }

    #[test]
    fn renders_clock_track_and_two_arcs() {
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
        let a = arcs(&t);
        assert_eq!(a.len(), 2, "expected CPU + RAM arcs");
        assert_eq!(a[0]["props"]["percent"].as_f64().unwrap(), 13.0); // cpu rounded
        assert_eq!(a[1]["props"]["percent"].as_f64().unwrap(), 63.0); // mem rounded
        let texts: Vec<String> = t["root"]["children"].as_array().unwrap().iter()
            .filter_map(|c| c["props"]["text"].as_str().map(|s| s.to_string()))
            .collect();
        assert!(texts.contains(&"09:41".to_string()), "clock missing: {:?}", texts);
        assert!(texts.contains(&"Feel Something".to_string()));
        assert!(texts.contains(&"Jaymes Young".to_string()));
    }

    #[test]
    fn geometry_adapts_and_defaults_without_screen_info() {
        let with = view(r#"{"req":{"screen":{"width":466,"height":466}},"music":{"hasMedia":false}}"#);
        let a = arcs(&with);
        assert_eq!(a[0]["style"]["radius"].as_f64().unwrap(), 224.0, "466px circle -> radius 224");
        assert_eq!(a[0]["style"]["height"].as_f64().unwrap(), 480.0, "canvas maps to designWidth 480");
        let without = view(r#"{"music":{"hasMedia":false}}"#);
        assert_eq!(arcs(&without)[0]["style"]["radius"].as_f64().unwrap(), 208.0);
        assert_eq!(arcs(&without)[0]["style"]["width"].as_f64().unwrap(), 480.0);
    }

    #[test]
    fn settings_are_applied_and_clamped() {
        let t = view(r#"{"music":{"hasMedia":false},
            "req":{"screen":{"width":466,"height":466}},
            "storage":{"edgeGap":40,"arcSpan":90,"stroke":30,"colors":"split"}}"#);
        let a = arcs(&t);
        assert_eq!(a[0]["style"]["radius"].as_f64().unwrap(), 199.0); // 240 - 40*480/466
        assert_eq!(a[0]["style"]["totalAngle"].as_f64().unwrap(), 90.0);
        // stroke is clamped to ≤24 physical px, then scaled to design px
        assert_eq!(a[0]["style"]["strokeWidth"].as_f64().unwrap(), 25.0); // round(24*480/466)
        assert_eq!(a[0]["style"]["color"], "#BAC3FF");
        assert_eq!(a[1]["style"]["color"], "#7ADFA0");
    }

    #[test]
    fn background_follows_settings_and_media_state() {
        let with = view(r#"{"music":{"hasMedia":true,"title":"x"},
            "server":{"base":"http://host:9000"},
            "req":{"screen":{"width":466,"height":466}},
            "storage":{"blur":12,"dim":0.5}}"#);
        let bg = with["root"]["style"]["backgroundImage"].as_str().unwrap_or("");
        assert!(bg.starts_with("http://host:9000/api/music/art?"), "bg: {bg}");
        assert!(bg.contains("size=466") && bg.contains("blur=12") && bg.contains("dim=0.5"), "bg: {bg}");
        let without = view(r#"{"music":{"hasMedia":false},"server":{"base":"http://host:9000"}}"#);
        assert!(without["root"]["style"]["backgroundImage"].is_null(), "no media -> no background image");
        let texts: Vec<String> = without["root"]["children"].as_array().unwrap().iter()
            .filter_map(|c| c["props"]["text"].as_str().map(|s| s.to_string()))
            .collect();
        assert!(texts.iter().any(|t| t.contains("无正在播放")), "placeholder missing: {:?}", texts);
    }

    #[test]
    fn art_urls_change_with_the_track() {
        // 回归：切歌必须换 URL，否则封面/背景会被 WebView 缓存住不刷新
        let a = view(r#"{"music":{"hasMedia":true,"title":"A","artist":"X"},
            "server":{"base":"http://h:9000"},"req":{"screen":{"width":466,"height":466}}}"#);
        let b = view(r#"{"music":{"hasMedia":true,"title":"B","artist":"X"},
            "server":{"base":"http://h:9000"},"req":{"screen":{"width":466,"height":466}}}"#);
        let bg_a = a["root"]["style"]["backgroundImage"].as_str().unwrap_or("");
        let bg_b = b["root"]["style"]["backgroundImage"].as_str().unwrap_or("");
        assert!(bg_a.contains("&k="), "background lacks a cache key: {bg_a}");
        assert_ne!(bg_a, bg_b, "background URL must change when the track changes");
        let cover_a = a["root"]["children"].as_array().unwrap().iter()
            .find_map(|c| c["children"].as_array().and_then(|k| k.first())
                .and_then(|im| im["props"]["src"].as_str().map(|s| s.to_string())))
            .unwrap_or_default();
        assert!(cover_a.contains("&k="), "cover lacks a cache key: {cover_a}");
    }

    #[test]
    fn bars_setting_switches_to_box_bars() {
        let t = view(r#"{"music":{"hasMedia":false},"storage":{"bars":"bar"},
            "req":{"screen":{"width":466,"height":466}}}"#);
        let kids = t["root"]["children"].as_array().unwrap();
        assert_eq!(arcs(&t).len(), 0, "no arc nodes in bar mode");
        let boxes = kids.iter().filter(|c| c["type"] == "box").count();
        assert_eq!(boxes, 4, "expect 2 tracks + 2 fills");
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
        let idle = view(r#"{"music":{"hasMedia":true,"title":"x"},"req":{"event":{"handler":"other"}}}"#);
        assert_eq!(idle["effects"].as_array().unwrap().len(), 0);
    }
}
