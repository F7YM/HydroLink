// HydroKit WASM 视频切片压制胶水 (mediaSlicer.js)
// 宿主端 (Desktop Node.js / Android WebView / 插件 worker) 调用入口。
// 加载 hydro_media_wasm.wasm，直接将多帧视频/图像切片压制为手表端 GifPlayer.ux 可消费的动图分段。

/**
 * 封装 WASM 内存读写与切片调用
 * @param {ArrayBuffer|Uint8Array} wasmBinary hydro_media_wasm.wasm 二进制缓冲
 */
export async function createMediaSlicer(wasmBinary) {
  const mod = await WebAssembly.instantiate(wasmBinary, {});
  const exp = mod.instance.exports;
  const mem = exp.memory;

  function alloc(len) {
    const ptr = exp.hydro_alloc(len);
    if (!ptr) throw new Error('[mediaSlicer] hydro_alloc 失败');
    return ptr;
  }

  function free(ptr, len) {
    if (ptr) exp.hydro_free(ptr, len);
  }

  function readEnvelope(ptr) {
    if (!ptr) return new Uint8Array(0);
    const view = new DataView(mem.buffer);
    const len = view.getUint32(ptr, true);
    const result = new Uint8Array(mem.buffer, ptr + 4, len).slice();
    free(ptr, 4 + len);
    return result;
  }

  return {
    /**
     * 将拼接的多帧 raw RGBA 数组压缩为单个 GIF 二进制
     * @param {Uint8Array} rgbaBuffer 拼接的 RGBA 帧缓冲
     * @param {Object} opts 选项
     * @param {number} opts.srcWidth 原图宽
     * @param {number} opts.srcHeight 原图高
     * @param {number} opts.dstWidth 目标宽度 (手表尺寸，如 240, 160)
     * @param {number} opts.dstHeight 目标高度 (手表尺寸，如 135, 160)
     * @param {number} opts.frameCount 帧数
     * @param {number} opts.fps 帧率 (默认 10)
     * @returns {Uint8Array} GIF 二进制
     */
    encodeFramesToGif(rgbaBuffer, opts) {
      const {
        srcWidth, srcHeight, dstWidth, dstHeight, frameCount, fps = 10
      } = opts;

      const ptr = alloc(rgbaBuffer.length);
      new Uint8Array(mem.buffer, ptr, rgbaBuffer.length).set(rgbaBuffer);

      const outPtr = exp.slice_video_frames_to_gif(
        ptr,
        rgbaBuffer.length,
        srcWidth,
        srcHeight,
        dstWidth,
        dstHeight,
        frameCount,
        fps
      );

      free(ptr, rgbaBuffer.length);
      return readEnvelope(outPtr);
    },

    /**
     * 将视频切片并封装为手表端 GifPlayer.ux 可直接消费的 segments 格式
     * @param {Array<Uint8Array>} frameChunks 数组，每个元素包含一段连续帧的 raw RGBA
     * @param {Object} opts 尺寸与帧率选项
     * @returns {Array<{ src: string, duration: number }>} 手表端分段数组
     */
    generateSegments(frameChunks, opts) {
      const segments = [];
      for (let i = 0; i < frameChunks.length; i++) {
        const chunk = frameChunks[i];
        const count = opts.framesPerChunk || opts.frameCount;
        const gifBytes = this.encodeFramesToGif(chunk, { ...opts, frameCount: count });
        const base64 = typeof Buffer !== 'undefined'
          ? Buffer.from(gifBytes).toString('base64')
          : btoa(String.fromCharCode.apply(null, gifBytes));
        const duration = Math.round((count / (opts.fps || 10)) * 1000);

        segments.push({
          src: 'data:image/gif;base64,' + base64,
          duration,
          bytes: gifBytes.length
        });
      }
      return segments;
    }
  };
}
