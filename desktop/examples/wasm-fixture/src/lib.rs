// HydroKit WASM plugin ABI v1 — minimal zero-dependency fixture.
// Used by the runtime end-to-end test (and as the smallest possible example).
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

/// Envelope: [u32 little-endian length][utf-8 JSON]
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

/// Pure view function: reads the context JSON, returns a tree + effects.
#[no_mangle]
pub extern "C" fn hydro_view(ctx_ptr: *const u8, ctx_len: usize) -> *mut u8 {
    let ctx = if ctx_ptr.is_null() || ctx_len == 0 {
        String::new()
    } else {
        String::from_utf8_lossy(unsafe { std::slice::from_raw_parts(ctx_ptr, ctx_len) }).into_owned()
    };
    // Zero-dependency substring checks (real plugins would use serde_json).
    let surface = pick(&ctx, "\"surface\":\"");
    let has_system = ctx.contains("\"system\"");
    let text = format!("fixture {}/{} sys={} len={}", surface, pick(&ctx, "\"item\":\""), has_system, ctx.len());
    envelope(format!(
        "{{\"root\":{{\"type\":\"text\",\"props\":{{\"text\":\"{}\"}}}},\"refreshMs\":0}}",
        text
    ))
}

/// pick returns the value of `"key":"value"` (very small helper for the fixture).
fn pick(s: &str, key: &str) -> String {
    match s.split(key).nth(1) {
        Some(rest) => match rest.split('"').next() {
            Some(v) => v.to_string(),
            None => String::new(),
        },
        None => String::new(),
    }
}
