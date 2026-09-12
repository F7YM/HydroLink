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

    // Clicking the cover toggles playback; the host executes the effects
    // (music control + remembering the action in server-scope storage).
    let mut effects = json!([]);
    if handler == "toggle" {
        effects = json!([
            { "op": "music", "action": "playpause" },
            { "op": "storage", "key": "lastToggleAt", "value": num(&ctx, &["time", "unix"]) }
        ]);
    }

    let background = if has_media && !art_blur.is_empty() {
        art_blur
    } else {
        String::new()
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
    let edge_gap = if screen_w > 0.0 { 16.0 * scale } else { 32.0 }; // 距边缘物理 ~16px
    let radius = cx - edge_gap;
    let stroke = if screen_w > 0.0 { 9.0 * scale } else { 9.0 };

    // 弧条：0°=12 点方向、顺时针；左侧弧以 270°（9 点）为中心，右侧以 90°（3 点）
    let arc = |value: f64, start: i64, color: &str| {
        json!({
            "type": "progress",
            "props": { "percent": value.round(), "variant": "arc" },
            "style": {
                "position": "absolute",
                "left": 0, "top": 0, "width": canvas_w, "height": canvas_h,
                "startAngle": start,
                "totalAngle": 60,
                "radius": radius.round(),
                "centerX": cx,
                "centerY": cy,
                "strokeWidth": stroke.round(),
                "color": color,
                "layerColor": "rgba(255,255,255,0.14)"
            }
        })
    };

    let cover = if has_media && !art_url.is_empty() {
        json!({
            "type": "row",
            "style": { "justifyContent": "center", "marginTop": 18 },
            "children": [{
                "type": "image",
                "props": { "src": art_url },
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

    let tree = json!({
        "refreshMs": 3000,
        "effects": effects,
        "root": {
            "type": "scroll",
            "style": root_style,
            "children": [
                arc(cpu, 240, "#C8CDD8"),
                arc(mem, 60, "#C8CDD8"),
                {
                    "type": "text",
                    "props": { "text": clock },
                    "style": { "fontSize": 44, "fontWeight": 700, "color": "#FFFFFF",
                               "textAlign": "center", "width": "100%" }
                },
                {
                    "type": "text",
                    "props": { "text": if title.is_empty() { "HydroLink".to_string() } else { title } },
                    "style": { "fontSize": 30, "fontWeight": 700, "color": "#FFFFFF",
                               "textAlign": "center", "width": "100%", "marginTop": 10 }
                },
                {
                    "type": "text",
                    "props": { "text": artist },
                    "style": { "fontSize": 22, "color": "#B9C0CC",
                               "textAlign": "center", "width": "100%", "marginTop": 4 }
                },
                cover
            ]
        }
    });
    envelope(tree.to_string())
}
