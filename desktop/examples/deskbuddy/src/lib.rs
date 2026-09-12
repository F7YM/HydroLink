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
    // Tunables (WebUI writes them into server-scope storage; defaults keep the
    // reference-mock look). gap/stroke are physical-px intents.
    let gap_phys = setting(&ctx, "edgeGap", 16.0).clamp(4.0, 60.0); // 曲线模式的贴边距离
    let arc_span = setting(&ctx, "arcSpan", 60.0).clamp(20.0, 120.0);
    let stroke_phys = setting(&ctx, "stroke", 9.0).clamp(2.0, 24.0);
    let blur = setting(&ctx, "blur", 26.0).clamp(0.0, 60.0);
    let dim = setting(&ctx, "dim", 0.3).clamp(0.05, 1.0);
    let colors = setting_str(&ctx, "colors", "gray");
    // 侧条默认直条（普通 div 渲染，稳定可见）；arc 为可选
    let bars = setting_str(&ctx, "bars", "bar"); // bar（直条）| curve（逐行描出的曲线）
    let font_scale = setting(&ctx, "fontScale", 1.0).clamp(0.6, 2.0);
    let f = |base: f64| (base * font_scale).round();

    let _ = stroke_phys; // 粗细在各自渲染器内定义


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
    // 侧条：**不使用绝对定位**（绝对定位的叶子在容器组件内不渲染）。
    // 结构 = column 轨道（固定宽高、底部对齐）+ box 填充（自下而上）。
    let bar = |value: f64, color: &str| -> Value {
        let bar_w = 14.0;
        let track_h = (canvas_h * 0.52).round();
        let pct = value.clamp(0.0, 100.0);
        let fill_h = ((track_h - 6.0) * pct / 100.0).max(bar_w / 2.0).round();
        json!({
            "type": "column",
            "style": {
                "width": bar_w, "height": track_h,
                "borderRadius": bar_w / 2.0,
                "backgroundColor": "rgba(255,255,255,0.16)",
                "justifyContent": "flex-end",
                "alignItems": "center",
                "padding": 3
            },
            "children": [
                {
                    "type": "box",
                    "style": {
                        "width": bar_w - 6.0, "height": fill_h,
                        "borderRadius": (bar_w - 6.0) / 2.0,
                        "backgroundColor": color
                    }
                }
            ]
        })
    };


    let cover_src = if !server_base.is_empty() {
        let size = if screen_w > 0.0 { screen_w.round() as i64 } else { 466 };
        format!("{}/api/music/art?size={}&k={}",
            server_base.trim_end_matches('/'), size, track_key(&ctx))
    } else {
        art_url.clone()
    };

    // 封面必须是**叶子**（容器页只展开 3 层，L3 上的容器会被当成未知叶子）；
    // 居中由中间列的 alignItems: center 完成
    let cover = if has_media && !cover_src.is_empty() {
        json!({
            "type": "image",
            "props": { "src": cover_src },
            "events": { "tap": "toggle" },
            "style": { "width": 200, "height": 200, "borderRadius": 16, "marginTop": 18 }
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
    let bar_w: f64 = 14.0;
    let gap: f64 = 12.0;
    let center_w = (canvas_w - 2.0 * (bar_w + gap)).round();

    // arc 模式仍用绝对定位（在真机上待验证）；bar 模式为纯 flex 流布局
    // curve：逐行 box 描出的贴边曲线（纯流式，不依赖 progress 组件）
    let curve = |value: f64, side: &str, color: &str| -> Value {
        let thickness = 12.0;
        let radius = cx - gap_phys * (if screen_w > 0.0 { scale } else { 1.0 }) - thickness / 2.0;
        let half = arc_span.to_radians() / 2.0;
        let half_span = radius * half.sin();
        let rows: i64 = 26;
        let row_h = (half_span * 2.0 / rows as f64).max(2.0);
        let max_inset = (cx - radius * half.cos()) + thickness / 2.0;
        let col_w = (max_inset + thickness + 2.0).round();
        let pct = value.clamp(0.0, 100.0);
        let mut kids: Vec<Value> = Vec::new();
        for i in 0..rows {
            // 自下而上编号：dy 为该行中心相对屏幕中心的纵向偏移
            let dy = half_span - (i as f64 + 0.5) * row_h;
            let dx = (radius * radius - dy * dy).max(0.0).sqrt();
            let inset = (cx - dx - thickness / 2.0).max(0.0);
            let filled = (100.0 * (i as f64 + 1.0) / rows as f64) <= pct;
            let left = if side == "left" { inset } else { col_w - thickness - inset };
            kids.push(json!({
                "type": "box",
                "style": {
                    "width": thickness, "height": row_h,
                    "marginLeft": left.round(),
                    "backgroundColor": if filled { color } else { "rgba(255,255,255,0.16)" }
                }
            }));
        }
        json!({
            "type": "column",
            "style": { "width": col_w, "height": (half_span * 2.0).round() },
            "children": kids
        })
    };

    let side_left = if bars == "curve" { curve(cpu, "left", color_left) } else { bar(cpu, color_left) };
    let side_right = if bars == "curve" { curve(mem, "right", color_right) } else { bar(mem, color_right) };

    let mut children: Vec<Value> = vec![json!({
        "type": "row",
        "style": { "alignItems": "center" },
        "children": [
            side_left,
            { "type": "column", "style": { "width": center_w, "alignItems": "center" },
              "children": [] },
            side_right
        ]
    })];
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
    if let Some(row) = children.get_mut(0) {
        if let Some(kids) = row["children"].as_array_mut() {
            kids[1]["children"] = json!(center_kids);
        }
    }

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

    /// 根 row 的三列：[左条, 中间列, 右条]
    fn row_columns(tree: &Value) -> Vec<&Value> {
        tree["root"]["children"][0]["children"]
            .as_array()
            .expect("row children")
            .iter()
            .collect()
    }

    /// 侧条列里的 box 数量（curve 模式 = 描线行数；bar 模式 = 1）
    fn side_box_count(tree: &Value, idx: usize) -> usize {
        row_columns(tree)[idx]["children"].as_array().map(|k| k.len()).unwrap_or(0)
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
        // 默认直条：两侧各一个填充 box，高度按百分比
        assert_eq!(row_columns(&t).len(), 3);
        let all = texts(&t);
        assert!(all.contains(&"09:41".to_string()), "clock missing: {:?}", all);
        assert!(all.contains(&"Feel Something".to_string()));
        assert!(all.contains(&"Jaymes Young".to_string()));
    }

    #[test]
    fn geometry_adapts_to_screen() {
        // 直条：轨道高度按画布（=屏幕换算到 designWidth 480）计算
        let with = view(r#"{"req":{"screen":{"width":466,"height":466}},"music":{"hasMedia":false}}"#);
        let track_h = row_columns(&with)[0]["style"]["height"].as_f64().unwrap();
        assert!((track_h - 250.0).abs() < 2.0, "track height {track_h} (0.52*480)");
        let without = view(r#"{"music":{"hasMedia":false}}"#);
        let track_h2 = row_columns(&without)[0]["style"]["height"].as_f64().unwrap();
        assert!((track_h2 - 250.0).abs() < 2.0, "no screen info -> 480 canvas");
    }

    #[test]
    fn settings_are_applied_and_clamped() {
        let t = view(r#"{"music":{"hasMedia":false},
            "system":{"cpu":80,"memPercent":20},
            "req":{"screen":{"width":466,"height":466}},
            "storage":{"bars":"curve","edgeGap":40,"arcSpan":90,"colors":"split"}}"#);
        let cols = row_columns(&t);
        // 曲线模式：两侧列都是多行 box；配色 split 生效
        assert!(side_box_count(&t, 0) > 10 && side_box_count(&t, 2) > 10);
        let left = cols[0]["children"].as_array().unwrap();
        // 自下而上填充：CPU 80% → 底部行为填充色，顶部行仍为轨道色
        assert_eq!(left[0]["style"]["backgroundColor"], "#BAC3FF");
        assert_eq!(left[left.len() - 1]["style"]["backgroundColor"], "rgba(255,255,255,0.16)");
        // RAM 20%：填充行数明显更少
        let right = cols[2]["children"].as_array().unwrap();
        let filled = |v: &Vec<Value>| v.iter().filter(|b| b["style"]["backgroundColor"] != "rgba(255,255,255,0.16)").count();
        assert!(filled(right) < filled(left), "RAM(20%) should fill fewer rows than CPU(80%)");
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
        let all = texts(&without);
        assert!(all.iter().any(|t| t.contains("无正在播放")), "placeholder missing: {:?}", all);
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
        let a = view(r#"{"music":{"hasMedia":true,"title":"A","artist":"X"},
            "server":{"base":"http://h:9000"},"req":{"screen":{"width":466,"height":466}}}"#);
        let b = view(r#"{"music":{"hasMedia":true,"title":"B","artist":"X"},
            "server":{"base":"http://h:9000"},"req":{"screen":{"width":466,"height":466}}}"#);
        let bg_a = a["root"]["style"]["backgroundImage"].as_str().unwrap_or("");
        let bg_b = b["root"]["style"]["backgroundImage"].as_str().unwrap_or("");
        assert!(bg_a.contains("&k="), "background lacks a cache key: {bg_a}");
        assert_ne!(bg_a, bg_b, "background URL must change when the track changes");
        let cover_a = row_columns(&a)[1]["children"]
            .as_array()
            .and_then(|kids| kids.iter().find(|c| c["type"] == "image"))
            .and_then(|im| im["props"]["src"].as_str().map(|s| s.to_string()))
            .unwrap_or_default();
        assert!(cover_a.contains("&k="), "cover lacks a cache key: {cover_a}");
    }

    #[test]
    fn bars_are_flow_laid_out_and_arc_is_opt_in() {
        let t = view(r#"{"music":{"hasMedia":false},"system":{"cpu":50,"memPercent":50},
            "req":{"screen":{"width":466,"height":466}}}"#);
        let cols = row_columns(&t);
        assert_eq!(cols.len(), 3, "row = [left bar, center, right bar]");
        // 侧条：column 轨道 + 单个 box 填充，且不使用绝对定位
        // canvas_h 480 → 轨道 round(480*0.52)=250，填充 (250-6)*50% = 122
        for (idx, expect_fill) in [(0usize, 122.0_f64), (2usize, 122.0_f64)] {
            let track = cols[idx];
            assert_eq!(track["type"], "column", "bar must be a flow column");
            assert!(track["style"]["position"].is_null(), "bar must not be absolute");
            let fill = &track["children"][0];
            assert_eq!(fill["type"], "box");
            let h = fill["style"]["height"].as_f64().unwrap();
            assert!((h - expect_fill).abs() < 2.0, "fill height {h} for 50%");
        }
        // curve 模式：侧条列由多行 box 描出曲线，且不使用 progress 节点
        let c = view(r#"{"music":{"hasMedia":false},"system":{"cpu":80,"memPercent":20},
            "storage":{"bars":"curve"},"req":{"screen":{"width":466,"height":466}}}"#);
        assert!(side_box_count(&c, 0) > 10, "curve mode needs many rows");
        assert!(side_box_count(&c, 2) > 10);
        assert!(!c.to_string().contains("progress"), "must not use the progress component");
        // 行的左偏移随高度变化 => 形成弧线
        let col = &row_columns(&c)[0];
        let first = col["children"][0]["style"]["marginLeft"].as_f64().unwrap();
        let mid = col["children"][13]["style"]["marginLeft"].as_f64().unwrap();
        assert!(mid < first, "curve must bow toward the edge: first={first} mid={mid}");
    }

    #[test]
    fn depth_never_exceeds_three_levels() {
        // 容器页只展开 3 层：根(0) → L1 → L2 → L3 叶子。深层容器会被渲染成垃圾文本，
        // 这里锁死结构（叶子层不允许再出现容器）。
        let t = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A"},
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
        let base = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A"},"time":{"hour":1,"minute":2}}"#);
        let big = view(r#"{"music":{"hasMedia":true,"title":"T","artist":"A"},"time":{"hour":1,"minute":2},
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
        let idle = view(r#"{"music":{"hasMedia":true,"title":"x"},"req":{"event":{"handler":"other"}}}"#);
        assert_eq!(idle["effects"].as_array().unwrap().len(), 0);
    }
}
