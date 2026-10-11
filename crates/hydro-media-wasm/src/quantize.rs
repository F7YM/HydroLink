// 调色板颜色量化器 (Color Palette Quantizer)
// 纯 Rust 实现，零外部依赖。
// 将 32 位 RGBA 帧像素转换为 8 位 256 色索引，支持透明通道映射与颜色空间聚类。

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Color {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

impl Color {
    pub fn dist_sq(&self, r: u8, g: u8, b: u8) -> u32 {
        let dr = self.r as i32 - r as i32;
        let dg = self.g as i32 - g as i32;
        let db = self.b as i32 - b as i32;
        (dr * dr + dg * dg + db * db) as u32
    }
}

pub struct QuantizedFrame {
    pub palette: Vec<u8>,       // 256 * 3 字节 (RGB)
    pub indices: Vec<u8>,       // 每个像素对应的调色板索引
    pub transparent_idx: Option<u8>,
}

#[derive(Clone)]
struct Box3D {
    colors: Vec<[u8; 3]>,
}

impl Box3D {
    fn new(colors: Vec<[u8; 3]>) -> Self {
        Self { colors }
    }

    fn split(mut self) -> (Box3D, Box3D) {
        if self.colors.len() <= 1 {
            return (self.clone(), self);
        }

        let mut min_r = 255; let mut max_r = 0;
        let mut min_g = 255; let mut max_g = 0;
        let mut min_b = 255; let mut max_b = 0;

        for c in &self.colors {
            min_r = min_r.min(c[0]); max_r = max_r.max(c[0]);
            min_g = min_g.min(c[1]); max_g = max_g.max(c[1]);
            min_b = min_b.min(c[2]); max_b = max_b.max(c[2]);
        }

        let range_r = max_r - min_r;
        let range_g = max_g - min_g;
        let range_b = max_b - min_b;

        let channel = if range_r >= range_g && range_r >= range_b {
            0
        } else if range_g >= range_r && range_g >= range_b {
            1
        } else {
            2
        };

        self.colors.sort_by_key(|c| c[channel]);
        let mid = self.colors.len() / 2;
        let right = self.colors.split_off(mid);
        (Box3D::new(self.colors), Box3D::new(right))
    }

    fn average_color(&self) -> Color {
        if self.colors.is_empty() {
            return Color { r: 0, g: 0, b: 0 };
        }
        let mut sum_r = 0u64;
        let mut sum_g = 0u64;
        let mut sum_b = 0u64;
        for c in &self.colors {
            sum_r += c[0] as u64;
            sum_g += c[1] as u64;
            sum_b += c[2] as u64;
        }
        let n = self.colors.len() as u64;
        Color {
            r: (sum_r / n) as u8,
            g: (sum_g / n) as u8,
            b: (sum_b / n) as u8,
        }
    }
}

pub fn quantize_rgba(rgba: &[u8], width: u32, height: u32) -> QuantizedFrame {
    let pixel_count = (width * height) as usize;
    let mut sampled_colors = Vec::with_capacity(pixel_count.min(8192));
    let mut has_transparency = false;

    // 采样像素 (如果图片过大，按步长采样以提升速度)
    let step = if pixel_count > 16384 { 4 } else { 1 };
    for i in (0..pixel_count).step_by(step) {
        let idx = i * 4;
        if idx + 3 < rgba.len() {
            let a = rgba[idx + 3];
            if a < 128 {
                has_transparency = true;
            } else {
                sampled_colors.push([rgba[idx], rgba[idx + 1], rgba[idx + 2]]);
            }
        }
    }

    // Median Cut 空间中位切分聚类
    let max_colors = if has_transparency { 255 } else { 256 };
    let mut boxes = vec![Box3D::new(sampled_colors)];

    while boxes.len() < max_colors {
        // 找到包含样本最多且能够切分的盒子
        let mut max_idx = None;
        let mut max_len = 0;
        for (i, b) in boxes.iter().enumerate() {
            if b.colors.len() > 1 && b.colors.len() > max_len {
                max_len = b.colors.len();
                max_idx = Some(i);
            }
        }

        match max_idx {
            Some(i) => {
                let target = boxes.remove(i);
                let (b1, b2) = target.split();
                boxes.push(b1);
                boxes.push(b2);
            }
            None => break,
        }
    }

    let mut palette_colors: Vec<Color> = boxes.into_iter().map(|b| b.average_color()).collect();
    if palette_colors.is_empty() {
        palette_colors.push(Color { r: 0, g: 0, b: 0 });
    }

    let transparent_idx = if has_transparency {
        let t_idx = palette_colors.len() as u8;
        palette_colors.push(Color { r: 0, g: 0, b: 0 }); // 透明色占位
        Some(t_idx)
    } else {
        None
    };

    // 导出 256 色的 RGB 调色板数组
    let mut palette_bytes = Vec::with_capacity(256 * 3);
    for c in &palette_colors {
        palette_bytes.push(c.r);
        palette_bytes.push(c.g);
        palette_bytes.push(c.b);
    }
    while palette_bytes.len() < 256 * 3 {
        palette_bytes.push(0);
    }

    // 将原始 RGBA 映射到调色板索引
    let mut indices = Vec::with_capacity(pixel_count);
    for i in 0..pixel_count {
        let idx = i * 4;
        if idx + 3 < rgba.len() {
            let a = rgba[idx + 3];
            if a < 128 && transparent_idx.is_some() {
                indices.push(transparent_idx.unwrap());
                continue;
            }

            let r = rgba[idx];
            let g = rgba[idx + 1];
            let b = rgba[idx + 2];

            let mut best_idx = 0u8;
            let mut min_dist = u32::MAX;

            for (p_idx, pc) in palette_colors.iter().enumerate() {
                if Some(p_idx as u8) == transparent_idx {
                    continue;
                }
                let dist = pc.dist_sq(r, g, b);
                if dist < min_dist {
                    min_dist = dist;
                    best_idx = p_idx as u8;
                    if dist == 0 {
                        break;
                    }
                }
            }
            indices.push(best_idx);
        } else {
            indices.push(0);
        }
    }

    QuantizedFrame {
        palette: palette_bytes,
        indices,
        transparent_idx,
    }
}
