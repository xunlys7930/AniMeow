/**
 * Desktop Studio 实时视频超分引擎（WebGL2）
 *
 * 与 Android 端 `PlaybackUpscale.kt` 保持同款管线：
 * - `off`：关闭 · 原画
 * - `spatial`：清晰锐化放大（可分离 Lanczos-3 卷积，上限 4K 边界）
 * - `ai-s`：AI 超清 · 效率（Anime4K restore-S → upscale-S，1080p 及以下 2× 放大）
 * - `ai-m`：AI 超清 · 质量（Anime4K restore-M → upscale-M，含 skip connections，1080p 及以下 2× 放大）
 */

export type VideoUpscaleMode = 'off' | 'spatial' | 'ai-s' | 'ai-m'

export interface VideoUpscaleOption {
  value: VideoUpscaleMode
  label: string
  shortLabel: string
  description: string
}

export interface VideoUpscaleRenderResult {
  status: string
  rendered: boolean
  /** true 表示当前帧真正经过了锐化放大或 Anime4K AI 超分处理 */
  enhancing: boolean
  /** true 表示处于跳过/回退原画状态（如超 1080p、受跨域限制或显卡能力不足） */
  fallback: boolean
}

export const VIDEO_UPSCALE_OPTIONS: VideoUpscaleOption[] = [
  {
    value: 'off',
    label: '关闭 · 原画',
    shortLabel: '原画',
    description: '按原始视频画质播放'
  },
  {
    value: 'spatial',
    label: '清晰锐化放大',
    shortLabel: '锐化放大',
    description: 'Lanczos-3 平滑锐化放大，低占用，适合日常观看'
  },
  {
    value: 'ai-s',
    label: 'AI 超清 · 效率 (S)',
    shortLabel: 'AI 效率',
    description: 'Anime4K 轻量线条修复与 2× 放大（1080p 及以下），兼顾流畅与画质'
  },
  {
    value: 'ai-m',
    label: 'AI 超清 · 质量 (M)',
    shortLabel: 'AI 质量',
    description: 'Anime4K 精细线条修复与 2× 放大（1080p 及以下），效果更好但显卡负载更高'
  }
]

const STORAGE_KEY = 'animeow_desktop_upscale_mode'

export function loadSavedUpscaleMode(): VideoUpscaleMode {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (raw === 'off' || raw === 'spatial' || raw === 'ai-s' || raw === 'ai-m') {
      return raw
    }
  } catch {
    // ignore storage errors
  }
  return 'off'
}

export function saveUpscaleMode(mode: VideoUpscaleMode): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, mode)
  } catch {
    // ignore storage errors
  }
}

export function computeSpatialUpscaleSize(width: number, height: number): [number, number] {
  if (width <= 0 || height <= 0) return [width, height]
  const maxSide = Math.max(width, height)
  const minSide = Math.min(width, height)
  const scale = Math.max(1.0, Math.min(2.0, Math.min(3840.0 / maxSide, 2160.0 / minSide)))
  return [Math.round(width * scale), Math.round(height * scale)]
}

interface ManifestInput {
  name: string
  source: number
  unit: number
  size: boolean
}

interface ManifestPass {
  shader: string
  inputs: ManifestInput[]
}

interface ModelManifest {
  version: number
  scale: number
  passes: ManifestPass[]
}

const RAW_SHADERS = import.meta.glob('../assets/playback-upscale/*.glsl', {
  query: '?raw',
  import: 'default',
  eager: true
}) as Record<string, string>

const RAW_MANIFESTS = import.meta.glob('../assets/playback-upscale/*.json', {
  import: 'default',
  eager: true
}) as Record<string, ModelManifest>

function getShaderSource(fileName: string): string {
  const src = RAW_SHADERS[`../assets/playback-upscale/${fileName}`]
  if (!src) throw new Error(`缺少着色器资源：${fileName}`)
  return src
}

function getModelManifest(modelName: string): ModelManifest {
  const manifest = RAW_MANIFESTS[`../assets/playback-upscale/${modelName}.json`]
  if (!manifest) throw new Error(`缺少超分模型清单：${modelName}.json`)
  return manifest
}

const VERTEX_SHADER_SRC = `
attribute vec2 aFramePosition;
varying vec2 vTexCoord;
void main() {
  gl_Position = vec4(aFramePosition, 0.0, 1.0);
  vTexCoord = aFramePosition * 0.5 + 0.5;
}
`

const PASSTHROUGH_FRAG_SRC = `
precision highp float;
uniform sampler2D uTexture0;
varying vec2 vTexCoord;
void main() {
  gl_FragColor = texture2D(uTexture0, vTexCoord);
}
`

const LANCZOS3_FRAG_SRC = `
precision highp float;
uniform sampler2D uTexture0;
uniform vec2 uSize0;
uniform vec2 uDirection;
varying vec2 vTexCoord;

const float PI = 3.14159265358979323846;

float sinc(float x) {
  if (abs(x) < 1e-5) return 1.0;
  float px = PI * x;
  return sin(px) / px;
}

float lanczos3(float x) {
  float ax = abs(x);
  if (ax >= 3.0) return 0.0;
  return sinc(x) * sinc(x / 3.0);
}

void main() {
  vec2 coord = vTexCoord * uSize0 - 0.5;
  vec2 base = floor(coord);
  vec2 frac = coord - base;
  float f = dot(frac, uDirection);
  vec2 baseUv = (base + 0.5) / uSize0;
  vec2 stepUv = uDirection / uSize0;

  vec4 sum = vec4(0.0);
  float weightSum = 0.0;

  for (int i = -2; i <= 3; i++) {
    float fi = float(i);
    float w = lanczos3(fi - f);
    vec2 sampleUv = clamp(baseUv + stepUv * fi, vec2(0.5) / uSize0, vec2(1.0) - vec2(0.5) / uSize0);
    sum += texture2D(uTexture0, sampleUv) * w;
    weightSum += w;
  }

  vec4 color = weightSum > 0.0 ? (sum / weightSum) : texture2D(uTexture0, vTexCoord);
  gl_FragColor = vec4(clamp(color.rgb, 0.0, 1.0), 1.0);
}
`

interface CompiledPass {
  program: WebGLProgram
  posLoc: number
  inputs: {
    source: number
    unit: number
    texLoc: WebGLUniformLocation | null
    sizeLoc: WebGLUniformLocation | null
    needsSize: boolean
  }[]
}

interface CompiledStage {
  scale: number
  passes: CompiledPass[]
  textures: WebGLTexture[]
  framebuffers: WebGLFramebuffer[]
  outputTexture: WebGLTexture | null
  outputFramebuffer: WebGLFramebuffer | null
}

function compileShader(gl: WebGL2RenderingContext, type: number, source: string): WebGLShader {
  const shader = gl.createShader(type)
  if (!shader) throw new Error('无法创建 WebGL 着色器')
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(shader) || '着色器编译失败'
    gl.deleteShader(shader)
    throw new Error(log)
  }
  return shader
}

function linkProgram(gl: WebGL2RenderingContext, fragSrc: string): WebGLProgram {
  const vs = compileShader(gl, gl.VERTEX_SHADER, VERTEX_SHADER_SRC)
  const fs = compileShader(gl, gl.FRAGMENT_SHADER, fragSrc)
  const program = gl.createProgram()
  if (!program) {
    gl.deleteShader(vs)
    gl.deleteShader(fs)
    throw new Error('无法创建 WebGL 程序')
  }
  gl.attachShader(program, vs)
  gl.attachShader(program, fs)
  gl.linkProgram(program)
  gl.deleteShader(vs)
  gl.deleteShader(fs)
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const log = gl.getProgramInfoLog(program) || '着色器链接失败'
    gl.deleteProgram(program)
    throw new Error(log)
  }
  return program
}

function createFboTexture(
  gl: WebGL2RenderingContext,
  width: number,
  height: number,
  halfFloat: boolean
): { texture: WebGLTexture; framebuffer: WebGLFramebuffer } {
  const texture = gl.createTexture()
  const framebuffer = gl.createFramebuffer()
  if (!texture || !framebuffer) {
    if (texture) gl.deleteTexture(texture)
    if (framebuffer) gl.deleteFramebuffer(framebuffer)
    throw new Error('无法分配超分显存缓冲')
  }
  gl.bindTexture(gl.TEXTURE_2D, texture)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)

  if (halfFloat) {
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, width, height, 0, gl.RGBA, gl.HALF_FLOAT, null)
  } else {
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null)
  }

  gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer)
  gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0)
  const status = gl.checkFramebufferStatus(gl.FRAMEBUFFER)
  gl.bindFramebuffer(gl.FRAMEBUFFER, null)
  gl.bindTexture(gl.TEXTURE_2D, null)

  if (status !== gl.FRAMEBUFFER_COMPLETE) {
    gl.deleteFramebuffer(framebuffer)
    gl.deleteTexture(texture)
    throw new Error(`FBO 不完整 (0x${status.toString(16)})`)
  }
  return { texture, framebuffer }
}

export class WebGlVideoUpscaler {
  private readonly canvas: HTMLCanvasElement
  private readonly gl: WebGL2RenderingContext
  private readonly quadBuffer: WebGLBuffer
  private readonly videoTexture: WebGLTexture
  private readonly passthroughProgram: WebGLProgram
  private readonly passthroughPosLoc: number
  private readonly passthroughTexLoc: WebGLUniformLocation | null
  private readonly lanczosProgram: WebGLProgram
  private readonly lanczosPosLoc: number
  private readonly lanczosTexLoc: WebGLUniformLocation | null
  private readonly lanczosSizeLoc: WebGLUniformLocation | null
  private readonly lanczosDirLoc: WebGLUniformLocation | null
  private readonly supportsHalfFloatFbo: boolean

  private mode: VideoUpscaleMode = 'off'
  private inputWidth = 0
  private inputHeight = 0
  private outputWidth = 0
  private outputHeight = 0
  private passthroughOnly = false
  private fallbackActive = false
  private statusText = '原画模式'

  private spatialTempTex: WebGLTexture | null = null
  private spatialTempFbo: WebGLFramebuffer | null = null
  private aiStages: CompiledStage[] = []
  private readonly stageCache = new Map<string, { scale: number; passes: CompiledPass[] }>()

  constructor(canvas: HTMLCanvasElement) {
    const gl = canvas.getContext('webgl2', {
      alpha: false,
      antialias: false,
      depth: false,
      stencil: false,
      preserveDrawingBuffer: true,
      powerPreference: 'high-performance'
    })
    if (!gl) {
      throw new Error('当前环境不支持 WebGL2，无法启用实时画质增强')
    }
    this.canvas = canvas
    this.gl = gl

    // Anime4K CNN 中间层特征含负值，必须启用半精度浮点渲染目标
    const colorFloatExt = gl.getExtension('EXT_color_buffer_float')
    const colorHalfFloatExt = gl.getExtension('EXT_color_buffer_half_float')
    this.supportsHalfFloatFbo = Boolean(colorFloatExt || colorHalfFloatExt)

    const quadBuffer = gl.createBuffer()
    const videoTexture = gl.createTexture()
    if (!quadBuffer || !videoTexture) {
      throw new Error('初始化 WebGL 缓冲失败')
    }
    this.quadBuffer = quadBuffer
    this.videoTexture = videoTexture

    gl.bindBuffer(gl.ARRAY_BUFFER, quadBuffer)
    gl.bufferData(
      gl.ARRAY_BUFFER,
      new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]),
      gl.STATIC_DRAW
    )

    gl.bindTexture(gl.TEXTURE_2D, videoTexture)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)

    this.passthroughProgram = linkProgram(gl, PASSTHROUGH_FRAG_SRC)
    this.passthroughPosLoc = gl.getAttribLocation(this.passthroughProgram, 'aFramePosition')
    this.passthroughTexLoc = gl.getUniformLocation(this.passthroughProgram, 'uTexture0')

    this.lanczosProgram = linkProgram(gl, LANCZOS3_FRAG_SRC)
    this.lanczosPosLoc = gl.getAttribLocation(this.lanczosProgram, 'aFramePosition')
    this.lanczosTexLoc = gl.getUniformLocation(this.lanczosProgram, 'uTexture0')
    this.lanczosSizeLoc = gl.getUniformLocation(this.lanczosProgram, 'uSize0')
    this.lanczosDirLoc = gl.getUniformLocation(this.lanczosProgram, 'uDirection')
  }

  public getStatus(): string {
    return this.statusText
  }

  public setMode(mode: VideoUpscaleMode): string {
    if (this.mode !== mode) {
      this.mode = mode
      if (this.inputWidth > 0 && this.inputHeight > 0) {
        this.configure(this.inputWidth, this.inputHeight)
      }
    }
    return this.statusText
  }

  private getCompiledStageTemplate(modelName: string): { scale: number; passes: CompiledPass[] } {
    const cached = this.stageCache.get(modelName)
    if (cached) return cached
    const gl = this.gl
    const manifest = getModelManifest(modelName)
    const passes: CompiledPass[] = manifest.passes.map((pass) => {
      const program = linkProgram(gl, getShaderSource(pass.shader))
      const posLoc = gl.getAttribLocation(program, 'aFramePosition')
      const inputs = pass.inputs.map((inp) => ({
        source: inp.source,
        unit: inp.unit,
        texLoc: gl.getUniformLocation(program, `uTexture${inp.unit}`),
        sizeLoc: inp.size ? gl.getUniformLocation(program, `uSize${inp.unit}`) : null,
        needsSize: inp.size
      }))
      return { program, posLoc, inputs }
    })
    const entry = { scale: manifest.scale, passes }
    this.stageCache.set(modelName, entry)
    return entry
  }

  private releaseDynamicBuffers(): void {
    const gl = this.gl
    if (this.spatialTempFbo) {
      gl.deleteFramebuffer(this.spatialTempFbo)
      this.spatialTempFbo = null
    }
    if (this.spatialTempTex) {
      gl.deleteTexture(this.spatialTempTex)
      this.spatialTempTex = null
    }
    for (const stage of this.aiStages) {
      for (const fb of stage.framebuffers) gl.deleteFramebuffer(fb)
      for (const tex of stage.textures) gl.deleteTexture(tex)
      if (stage.outputFramebuffer) gl.deleteFramebuffer(stage.outputFramebuffer)
      if (stage.outputTexture) gl.deleteTexture(stage.outputTexture)
    }
    this.aiStages = []
  }

  private configure(width: number, height: number): void {
    this.releaseDynamicBuffers()
    this.inputWidth = width
    this.inputHeight = height
    this.passthroughOnly = false
    this.fallbackActive = false

    if (this.mode === 'off') {
      this.outputWidth = width
      this.outputHeight = height
      this.passthroughOnly = true
      this.fallbackActive = false
      this.statusText = '关闭 · 原画'
      this.resizeCanvas(width, height)
      return
    }

    if (this.mode === 'spatial') {
      const [outW, outH] = computeSpatialUpscaleSize(width, height)
      this.outputWidth = outW
      this.outputHeight = outH
      this.resizeCanvas(outW, outH)
      if (outW === width && outH === height) {
        this.passthroughOnly = true
        this.fallbackActive = true
        this.statusText = '无需放大：当前画面已达 4K 边界，保留原画'
        return
      }
      try {
        const temp = createFboTexture(this.gl, outW, height, false)
        this.spatialTempTex = temp.texture
        this.spatialTempFbo = temp.framebuffer
        this.statusText = `清晰锐化放大 · ${width}×${height} → ${outW}×${outH}`
      } catch (err) {
        this.passthroughOnly = true
        this.fallbackActive = true
        this.statusText = `清晰锐化放大初始化失败，已保留原画（${String(err)}）`
      }
      return
    }

    // AI 模式（ai-s / ai-m）：与手机端口径一致，1080p 及以下启用 restore + upscale 2×
    const maxSide = Math.max(width, height)
    const minSide = Math.min(width, height)
    if (maxSide > 1920 || minSide > 1080) {
      this.outputWidth = width
      this.outputHeight = height
      this.passthroughOnly = true
      this.fallbackActive = true
      this.resizeCanvas(width, height)
      this.statusText = '视频已超过 1080p，无需放大，已保留原画'
      return
    }

    if (!this.supportsHalfFloatFbo) {
      this.outputWidth = width
      this.outputHeight = height
      this.passthroughOnly = true
      this.fallbackActive = true
      this.resizeCanvas(width, height)
      this.statusText = '当前显卡不支持半精度渲染目标，AI 超分已回退原画'
      return
    }

    const suffix = this.mode === 'ai-m' ? 'M' : 'S'
    const label = this.mode === 'ai-m' ? 'AI 质量 (M)' : 'AI 效率 (S)'
    try {
      const stageNames = [`restore-${suffix}`, `upscale-${suffix}`]
      let curW = width
      let curH = height
      const builtStages: CompiledStage[] = []

      for (let sIdx = 0; sIdx < stageNames.length; sIdx++) {
        const template = this.getCompiledStageTemplate(stageNames[sIdx])
        const textures: WebGLTexture[] = []
        const framebuffers: WebGLFramebuffer[] = []
        for (let i = 0; i < template.passes.length - 1; i++) {
          const created = createFboTexture(this.gl, curW, curH, true)
          textures.push(created.texture)
          framebuffers.push(created.framebuffer)
        }
        const nextW = curW * template.scale
        const nextH = curH * template.scale
        let outputTexture: WebGLTexture | null = null
        let outputFramebuffer: WebGLFramebuffer | null = null
        if (sIdx < stageNames.length - 1) {
          const out = createFboTexture(this.gl, nextW, nextH, false)
          outputTexture = out.texture
          outputFramebuffer = out.framebuffer
        }
        builtStages.push({
          scale: template.scale,
          passes: template.passes,
          textures,
          framebuffers,
          outputTexture,
          outputFramebuffer
        })
        curW = nextW
        curH = nextH
      }

      this.aiStages = builtStages
      this.outputWidth = curW
      this.outputHeight = curH
      this.resizeCanvas(curW, curH)
      this.statusText = `${label} 已启用 · ${width}×${height} → ${curW}×${curH}`
    } catch (err) {
      this.releaseDynamicBuffers()
      this.outputWidth = width
      this.outputHeight = height
      this.passthroughOnly = true
      this.fallbackActive = true
      this.resizeCanvas(width, height)
      this.statusText = `${label} 初始化失败，已保留原画（${String(err)}）`
    }
  }

  private resizeCanvas(width: number, height: number): void {
    if (this.canvas.width !== width) this.canvas.width = width
    if (this.canvas.height !== height) this.canvas.height = height
  }

  private drawQuad(posLoc: number): void {
    const gl = this.gl
    gl.bindBuffer(gl.ARRAY_BUFFER, this.quadBuffer)
    gl.enableVertexAttribArray(posLoc)
    gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 0, 0)
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
  }

  public renderFrame(video: HTMLVideoElement): VideoUpscaleRenderResult {
    if (video.readyState < 2 || video.videoWidth <= 0 || video.videoHeight <= 0) {
      return {
        status: this.statusText,
        rendered: false,
        enhancing: false,
        fallback: this.fallbackActive
      }
    }
    const vw = video.videoWidth
    const vh = video.videoHeight
    if (vw !== this.inputWidth || vh !== this.inputHeight) {
      this.configure(vw, vh)
    }

    const gl = this.gl
    gl.bindTexture(gl.TEXTURE_2D, this.videoTexture)
    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true)
    try {
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, video)
    } catch {
      this.fallbackActive = true
      this.statusText = '当前视频流受跨域限制，无法读取帧画面，已回退原生播放'
      return {
        status: this.statusText,
        rendered: false,
        enhancing: false,
        fallback: true
      }
    }

    if (this.passthroughOnly) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, null)
      gl.viewport(0, 0, this.outputWidth, this.outputHeight)
      gl.useProgram(this.passthroughProgram)
      gl.activeTexture(gl.TEXTURE0)
      gl.bindTexture(gl.TEXTURE_2D, this.videoTexture)
      gl.uniform1i(this.passthroughTexLoc, 0)
      this.drawQuad(this.passthroughPosLoc)
      return {
        status: this.statusText,
        rendered: true,
        enhancing: false,
        fallback: this.fallbackActive
      }
    }

    if (this.mode === 'spatial' && this.spatialTempFbo && this.spatialTempTex) {
      // Pass 1: 水平 Lanczos-3 (vw x vh -> outW x vh)
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.spatialTempFbo)
      gl.viewport(0, 0, this.outputWidth, this.inputHeight)
      gl.useProgram(this.lanczosProgram)
      gl.activeTexture(gl.TEXTURE0)
      gl.bindTexture(gl.TEXTURE_2D, this.videoTexture)
      gl.uniform1i(this.lanczosTexLoc, 0)
      gl.uniform2f(this.lanczosSizeLoc, this.inputWidth, this.inputHeight)
      gl.uniform2f(this.lanczosDirLoc, 1.0, 0.0)
      this.drawQuad(this.lanczosPosLoc)

      // Pass 2: 垂直 Lanczos-3 (outW x vh -> outW x outH)
      gl.bindFramebuffer(gl.FRAMEBUFFER, null)
      gl.viewport(0, 0, this.outputWidth, this.outputHeight)
      gl.activeTexture(gl.TEXTURE0)
      gl.bindTexture(gl.TEXTURE_2D, this.spatialTempTex)
      gl.uniform1i(this.lanczosTexLoc, 0)
      gl.uniform2f(this.lanczosSizeLoc, this.outputWidth, this.inputHeight)
      gl.uniform2f(this.lanczosDirLoc, 0.0, 1.0)
      this.drawQuad(this.lanczosPosLoc)
      return {
        status: this.statusText,
        rendered: true,
        enhancing: true,
        fallback: false
      }
    }

    // AI 多阶段 CNN 管线：restore -> upscale
    let stageInputTex: WebGLTexture = this.videoTexture
    let stageW = this.inputWidth
    let stageH = this.inputHeight

    for (const stage of this.aiStages) {
      const lastIdx = stage.passes.length - 1
      for (let i = 0; i < stage.passes.length; i++) {
        const pass = stage.passes[i]
        const isFinalPass = i === lastIdx
        const targetFbo = isFinalPass ? stage.outputFramebuffer : stage.framebuffers[i]
        const vpW = isFinalPass ? stageW * stage.scale : stageW
        const vpH = isFinalPass ? stageH * stage.scale : stageH

        gl.bindFramebuffer(gl.FRAMEBUFFER, targetFbo)
        gl.viewport(0, 0, vpW, vpH)
        gl.useProgram(pass.program)

        for (const inp of pass.inputs) {
          const tex = inp.source === -1 ? stageInputTex : stage.textures[inp.source]
          gl.activeTexture(gl.TEXTURE0 + inp.unit)
          gl.bindTexture(gl.TEXTURE_2D, tex)
          if (inp.texLoc) gl.uniform1i(inp.texLoc, inp.unit)
          if (inp.needsSize && inp.sizeLoc) {
            gl.uniform2f(inp.sizeLoc, stageW, stageH)
          }
        }
        this.drawQuad(pass.posLoc)
      }
      if (stage.outputTexture) {
        stageInputTex = stage.outputTexture
      }
      stageW *= stage.scale
      stageH *= stage.scale
    }

    return {
      status: this.statusText,
      rendered: true,
      enhancing: true,
      fallback: false
    }
  }

  public dispose(): void {
    this.releaseDynamicBuffers()
    const gl = this.gl
    for (const entry of this.stageCache.values()) {
      for (const pass of entry.passes) {
        gl.deleteProgram(pass.program)
      }
    }
    this.stageCache.clear()
    gl.deleteProgram(this.passthroughProgram)
    gl.deleteProgram(this.lanczosProgram)
    gl.deleteTexture(this.videoTexture)
    gl.deleteBuffer(this.quadBuffer)
  }
}
