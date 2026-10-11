// 纯 Rust GIF 89a 动图编码器 (GIF 89a Multi-Frame Encoder with LZW)
// 零外部依赖，支持循环播放 (Netscape 2.0)、透明通道、可变帧延迟与高压缩比 LZW 编码。

pub struct GifFrame {
    pub width: u16,
    pub height: u16,
    pub delay_centisecs: u16, // 单位为 1/100 秒 (如 10 = 100ms)
    pub palette: Vec<u8>,     // 256 * 3 字节 RGB
    pub indices: Vec<u8>,     // 像素调色板索引
    pub transparent_idx: Option<u8>,
}

pub struct GifEncoder {
    width: u16,
    height: u16,
    loop_count: u16, // 0 为无限循环
    buffer: Vec<u8>,
}

impl GifEncoder {
    pub fn new(width: u16, height: u16, loop_count: u16) -> Self {
        let mut enc = Self {
            width,
            height,
            loop_count,
            buffer: Vec::with_capacity(32768),
        };
        enc.write_header();
        enc
    }

    fn write_header(&mut self) {
        // 1. Header: GIF89a
        self.buffer.extend_from_slice(b"GIF89a");

        // 2. Logical Screen Descriptor
        self.buffer.extend_from_slice(&self.width.to_le_bytes());
        self.buffer.extend_from_slice(&self.height.to_le_bytes());
        // Packed field: 无全局调色板，各帧使用独立局部调色板以保留各帧最佳画质 (0x70)
        self.buffer.push(0x70);
        self.buffer.push(0); // Background color index
        self.buffer.push(0); // Pixel aspect ratio

        // 3. Netscape 2.0 Application Extension (循环播放头)
        self.buffer.extend_from_slice(&[
            0x21, 0xFF, 0x0B, // Extension introducer, App extension, block size 11
            b'N', b'E', b'T', b'S', b'C', b'A', b'P', b'E', b'2', b'.', b'0',
            0x03, 0x01,       // Sub-block size 3, loop sub-block ID 1
        ]);
        self.buffer.extend_from_slice(&self.loop_count.to_le_bytes());
        self.buffer.push(0x00); // Block terminator
    }

    pub fn add_frame(&mut self, frame: &GifFrame) {
        // 1. Graphic Control Extension (图形控制扩展)
        let has_trans = frame.transparent_idx.is_some();
        let packed_gce = if has_trans { 0x09 } else { 0x04 }; // 0x04: 保持不销毁, 0x01: 透明位
        let trans_byte = frame.transparent_idx.unwrap_or(0);

        self.buffer.extend_from_slice(&[
            0x21, 0xF9, 0x04,
            packed_gce,
        ]);
        self.buffer.extend_from_slice(&frame.delay_centisecs.to_le_bytes());
        self.buffer.push(trans_byte);
        self.buffer.push(0x00); // Block terminator

        // 2. Image Descriptor (图像描述符)
        self.buffer.push(0x2C); // ','
        self.buffer.extend_from_slice(&0u16.to_le_bytes()); // Left 0
        self.buffer.extend_from_slice(&0u16.to_le_bytes()); // Top 0
        self.buffer.extend_from_slice(&frame.width.to_le_bytes());
        self.buffer.extend_from_slice(&frame.height.to_le_bytes());
        // Local Color Table Flag: 1, Size of Table: 7 (256 colors) -> 0x87
        self.buffer.push(0x87);

        // 3. Local Color Table (局部调色板 256 * 3 = 768 字节)
        let pal_len = frame.palette.len().min(768);
        self.buffer.extend_from_slice(&frame.palette[..pal_len]);
        if pal_len < 768 {
            self.buffer.resize(self.buffer.len() + (768 - pal_len), 0);
        }

        // 4. LZW Image Data
        let lzw_min_code_size = 8u8;
        self.buffer.push(lzw_min_code_size);

        let compressed_blocks = encode_lzw(lzw_min_code_size, &frame.indices);
        self.buffer.extend_from_slice(&compressed_blocks);
        self.buffer.push(0x00); // Block terminator
    }

    pub fn finish(mut self) -> Vec<u8> {
        // Trailer: 0x3B (';')
        self.buffer.push(0x3B);
        self.buffer
    }
}

// ---- GIF 标准 LZW 变长位流编码器 ----

struct BitWriter {
    data: Vec<u8>,
    current_byte: u32,
    bits_in_byte: u8,
    sub_block: Vec<u8>,
}

impl BitWriter {
    fn new() -> Self {
        Self {
            data: Vec::new(),
            current_byte: 0,
            bits_in_byte: 0,
            sub_block: Vec::with_capacity(255),
        }
    }

    fn write_bits(&mut self, code: u32, code_len: u8) {
        self.current_byte |= code << self.bits_in_byte;
        self.bits_in_byte += code_len;

        while self.bits_in_byte >= 8 {
            let byte = (self.current_byte & 0xFF) as u8;
            self.append_byte(byte);
            self.current_byte >>= 8;
            self.bits_in_byte -= 8;
        }
    }

    fn append_byte(&mut self, b: u8) {
        self.sub_block.push(b);
        if self.sub_block.len() == 255 {
            self.data.push(255);
            self.data.extend_from_slice(&self.sub_block);
            self.sub_block.clear();
        }
    }

    fn flush(mut self) -> Vec<u8> {
        if self.bits_in_byte > 0 {
            let byte = (self.current_byte & 0xFF) as u8;
            self.append_byte(byte);
        }
        if !self.sub_block.is_empty() {
            self.data.push(self.sub_block.len() as u8);
            self.data.extend_from_slice(&self.sub_block);
            self.sub_block.clear();
        }
        self.data
    }
}

fn encode_lzw(min_code_size: u8, indices: &[u8]) -> Vec<u8> {
    let clear_code = 1u32 << min_code_size;
    let end_code = clear_code + 1;

    let mut bit_writer = BitWriter::new();
    let mut code_size = min_code_size + 1;
    let mut next_code = end_code + 1;

    // 前缀哈希表: (prefix_code << 8) | next_char -> code
    // 容量 5003 (素数，防冲突)
    const HASH_SIZE: usize = 5003;
    let mut hash_keys = [0u32; HASH_SIZE];
    let mut hash_values = [0u32; HASH_SIZE];

    let clear_table = |keys: &mut [u32; HASH_SIZE]| {
        keys.fill(0xFFFF_FFFF);
    };

    clear_table(&mut hash_keys);

    // 首先输出 Clear Code
    bit_writer.write_bits(clear_code, code_size);

    if indices.is_empty() {
        bit_writer.write_bits(end_code, code_size);
        return bit_writer.flush();
    }

    let mut prefix = indices[0] as u32;

    for &val in &indices[1..] {
        let c = val as u32;
        let key = (prefix << 8) | c;
        let mut hash = ((key ^ (key >> 12)) as usize) % HASH_SIZE;

        let mut found = false;
        while hash_keys[hash] != 0xFFFF_FFFF {
            if hash_keys[hash] == key {
                prefix = hash_values[hash];
                found = true;
                break;
            }
            hash = (hash + 1) % HASH_SIZE;
        }

        if !found {
            // 输出当前前缀
            bit_writer.write_bits(prefix, code_size);

            if next_code < 4096 {
                hash_keys[hash] = key;
                hash_values[hash] = next_code;
                next_code += 1;

                if next_code > (1 << code_size) && code_size < 12 {
                    code_size += 1;
                }
            } else {
                // 编码字典已满，发送 Clear Code 重置字典
                bit_writer.write_bits(clear_code, code_size);
                code_size = min_code_size + 1;
                next_code = end_code + 1;
                clear_table(&mut hash_keys);
            }

            prefix = c;
        }
    }

    // 输出最后的前缀和 End Code
    bit_writer.write_bits(prefix, code_size);
    bit_writer.write_bits(end_code, code_size);

    bit_writer.flush()
}
