// HydroKit 纯 Rust WASM 视频切片压制与动图编码器 (hydro-media-wasm)
// 零外部 C/FFmpeg 依赖，编译为 wasm32-unknown-unknown 跨平台运行于 Desktop / Android。

pub mod resize;
pub mod quantize;
pub mod gif;

use std::alloc::{alloc, dealloc, Layout};
use resize::resize_rgba;
use quantize::quantize_rgba;
use gif::{GifEncoder, GifFrame};

#[no_mangle]
pub extern "C" fn hydro_alloc(len: usize) -> *mut u8 {
    let len = if len == 0 { 1 } else { len };
    unsafe { alloc(Layout::from_size_align_unchecked(len, 1)) }
}

#[no_mangle]
pub extern "C" fn hydro_free(ptr: *mut u8, len: usize) {
    if ptr.is_null() { return; }
    let len = if len == 0 { 1 } else { len };
    unsafe { dealloc(ptr, Layout::from_size_align_unchecked(len, 1)) }
}

#[no_mangle]
pub extern "C" fn slice_version() -> i32 {
    1
}

/// 封包装：[u32 length (little-endian)][payload bytes]
fn envelope(bytes: Vec<u8>) -> *mut u8 {
    let total = 4 + bytes.len();
    let p = hydro_alloc(total);
    unsafe {
        std::ptr::copy_nonoverlapping((bytes.len() as u32).to_le_bytes().as_ptr(), p, 4);
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), p.add(4), bytes.len());
    }
    p
}

/// 图像双线性缩放导出门
/// 返回 [u32 len][RGBA 字节]
#[no_mangle]
pub extern "C" fn slice_resize(
    src_ptr: *const u8,
    src_len: usize,
    src_w: u32,
    src_h: u32,
    dst_w: u32,
    dst_h: u32,
) -> *mut u8 {
    if src_ptr.is_null() || src_len == 0 {
        return envelope(Vec::new());
    }
    let src = unsafe { std::slice::from_raw_parts(src_ptr, src_len) };
    let resized = resize_rgba(src, src_w, src_h, dst_w, dst_h);
    envelope(resized)
}

/// 全流程视频帧压制导出门：
/// 输入：拼接的 N 帧原分辨率 RGBA 缓冲 (`src_w * src_h * 4 * frame_count`)
/// 处理：并行降采样至目标尺寸 -> 颜色空间量化 -> GIF 89a 压缩编码
/// 输出：可以直接下发给手表端的标准 GIF 动图二进制 [u32 len][GIF 89a bytes]
#[no_mangle]
pub extern "C" fn slice_video_frames_to_gif(
    rgba_ptr: *const u8,
    rgba_len: usize,
    src_w: u32,
    src_h: u32,
    dst_w: u32,
    dst_h: u32,
    frame_count: u32,
    fps: u32,
) -> *mut u8 {
    if rgba_ptr.is_null() || rgba_len == 0 || frame_count == 0 || dst_w == 0 || dst_h == 0 {
        return envelope(Vec::new());
    }

    let raw = unsafe { std::slice::from_raw_parts(rgba_ptr, rgba_len) };
    let src_frame_bytes = (src_w * src_h * 4) as usize;
    let safe_fps = fps.clamp(1, 60);
    // GIF delay 单位为 1/100 秒 (centiseconds)
    let delay_cs = ((100.0 / safe_fps as f32).round().max(1.0)) as u16;

    let mut encoder = GifEncoder::new(dst_w as u16, dst_h as u16, 0);

    for i in 0..frame_count {
        let offset = (i as usize) * src_frame_bytes;
        if offset + src_frame_bytes > raw.len() {
            break;
        }
        let frame_slice = &raw[offset..offset + src_frame_bytes];

        // 1. 缩放到手表端目标尺寸
        let downsampled = if src_w == dst_w && src_h == dst_h {
            frame_slice.to_vec()
        } else {
            resize_rgba(frame_slice, src_w, src_h, dst_w, dst_h)
        };

        // 2. 调色板颜色量化
        let quantized = quantize_rgba(&downsampled, dst_w, dst_h);

        // 3. 添加到 GIF 动图序列
        let gif_frame = GifFrame {
            width: dst_w as u16,
            height: dst_h as u16,
            delay_centisecs: delay_cs,
            palette: quantized.palette,
            indices: quantized.indices,
            transparent_idx: quantized.transparent_idx,
        };
        encoder.add_frame(&gif_frame);
    }

    let gif_binary = encoder.finish();
    envelope(gif_binary)
}
