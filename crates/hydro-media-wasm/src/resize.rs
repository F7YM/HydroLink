// 双线性插值图片缩放器 (Bilinear Interpolation Scaler)
// 纯 Rust 实现，零外部依赖，支持将任意分辨率的视频/动图帧降采样至手表适宜分辨率 (如 240x135, 160x160)。

pub fn resize_rgba(
    src: &[u8],
    src_w: u32,
    src_h: u32,
    dst_w: u32,
    dst_h: u32,
) -> Vec<u8> {
    if dst_w == 0 || dst_h == 0 {
        return Vec::new();
    }
    if src_w == dst_w && src_h == dst_h {
        let len = (src_w * src_h * 4) as usize;
        return src[..len.min(src.len())].to_vec();
    }

    let mut dst = vec![0u8; (dst_w * dst_h * 4) as usize];
    let x_ratio = if dst_w > 1 { (src_w - 1) as f32 / (dst_w - 1) as f32 } else { 0.0 };
    let y_ratio = if dst_h > 1 { (src_h - 1) as f32 / (dst_h - 1) as f32 } else { 0.0 };

    for y in 0..dst_h {
        let src_y = y as f32 * y_ratio;
        let y_low = src_y.floor() as u32;
        let y_high = (y_low + 1).min(src_h - 1);
        let y_weight = src_y - y_low as f32;

        for x in 0..dst_w {
            let src_x = x as f32 * x_ratio;
            let x_low = src_x.floor() as u32;
            let x_high = (x_low + 1).min(src_w - 1);
            let x_weight = src_x - x_low as f32;

            let idx_tl = ((y_low * src_w + x_low) * 4) as usize;
            let idx_tr = ((y_low * src_w + x_high) * 4) as usize;
            let idx_bl = ((y_high * src_w + x_low) * 4) as usize;
            let idx_br = ((y_high * src_w + x_high) * 4) as usize;

            let dst_idx = ((y * dst_w + x) * 4) as usize;

            for c in 0..4 {
                let tl = src.get(idx_tl + c).copied().unwrap_or(0) as f32;
                let tr = src.get(idx_tr + c).copied().unwrap_or(0) as f32;
                let bl = src.get(idx_bl + c).copied().unwrap_or(0) as f32;
                let br = src.get(idx_br + c).copied().unwrap_or(0) as f32;

                let top = tl * (1.0 - x_weight) + tr * x_weight;
                let bottom = bl * (1.0 - x_weight) + br * x_weight;
                let pixel = top * (1.0 - y_weight) + bottom * y_weight;

                dst[dst_idx + c] = pixel.round().clamp(0.0, 255.0) as u8;
            }
        }
    }

    dst
}
