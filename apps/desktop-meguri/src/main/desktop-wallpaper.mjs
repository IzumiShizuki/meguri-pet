import { lstatSync, readFileSync } from 'node:fs'
import { extname, isAbsolute } from 'node:path'
import { maxWallpaperBytes, validatedWallpaperDataUrl } from './desktop-preferences.mjs'

const imageTypes = { '.png': 'png', '.jpg': 'jpeg', '.jpeg': 'jpeg', '.webp': 'webp' }

/** Only main calls this with a path selected by the native file dialog. */
export function loadDesktopWallpaper(path, decodeImage) {
  try {
    if (typeof path !== 'string' || !isAbsolute(path) || /^\\\\/.test(path))
      return { ok: false, error: '请选择本机图片文件。' }
    const type = imageTypes[extname(path).toLowerCase()]
    if (!type) return { ok: false, error: '壁纸支持 PNG、JPEG 和 WebP。' }
    const stat = lstatSync(path)
    if (!stat.isFile() || stat.isSymbolicLink()) return { ok: false, error: '请选择普通图片文件。' }
    if (!stat.size || stat.size > maxWallpaperBytes)
      return { ok: false, error: '壁纸大小须在 3 MB 以内。' }
    const bytes = readFileSync(path)
    const dataUrl = `data:image/${type};base64,${bytes.toString('base64')}`
    if (!validatedWallpaperDataUrl(dataUrl)) return { ok: false, error: '图片格式与文件内容不一致。' }
    const size = decodeImage(bytes)
    if (!size || size.width < 1 || size.height < 1 || size.width > 4096 || size.height > 4096)
      return { ok: false, error: '图片无法读取，或尺寸超过 4096 × 4096。' }
    return { ok: true, dataUrl }
  }
  catch {
    return { ok: false, error: '无法读取该图片，请选择另一张本机图片。' }
  }
}
