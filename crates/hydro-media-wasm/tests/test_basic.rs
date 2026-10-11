#[cfg(test)]
mod tests {
    use hydro_media_wasm::{slice_video_frames_to_gif, hydro_free};

    #[test]
    fn test_resize_and_gif_generation() {
        // 创建 3 帧 100x100 的纯色/渐变测试帧
        let src_w = 100u32;
        let src_h = 100u32;
        let frame_count = 3u32;
        let mut frames = Vec::with_capacity((src_w * src_h * 4 * frame_count) as usize);

        for f in 0..frame_count {
            for y in 0..src_h {
                for x in 0..src_w {
                    frames.push((x * 255 / src_w) as u8);         // R
                    frames.push((y * 255 / src_h) as u8);         // G
                    frames.push(((f + 1) * 80) as u8);            // B
                    frames.push(255);                             // A
                }
            }
        }

        let dst_w = 50u32;
        let dst_h = 50u32;
        let ptr = slice_video_frames_to_gif(
            frames.as_ptr(),
            frames.len(),
            src_w,
            src_h,
            dst_w,
            dst_h,
            frame_count,
            10, // 10 fps
        );

        assert!(!ptr.is_null());
        unsafe {
            let len = std::ptr::read_unaligned(ptr as *const u32) as usize;
            assert!(len > 100, "GIF 数据长度应大于 100 字节，实得 {}", len);

            let data = std::slice::from_raw_parts(ptr.add(4), len);
            // 校验 GIF89a 头部
            assert_eq!(&data[0..6], b"GIF89a", "必须以 GIF89a 开头");
            // 校验尺寸 (50x50, little-endian)
            let w = u16::from_le_bytes([data[6], data[7]]);
            let h = u16::from_le_bytes([data[8], data[9]]);
            assert_eq!(w, 50);
            assert_eq!(h, 50);
            // 校验文件尾 0x3B
            assert_eq!(data[len - 1], 0x3B, "GIF 文件尾必须为 0x3B");

            hydro_free(ptr, 4 + len);
        }
    }
}
