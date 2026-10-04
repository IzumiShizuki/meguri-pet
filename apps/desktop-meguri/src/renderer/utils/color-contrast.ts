function linearChannel(value: number) {
  const channel = value / 255
  return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
}

function luminance(hex: string) {
  const normalized = hex.replace('#', '')
  if (!/^[0-9a-f]{6}$/i.test(normalized))
    throw new Error(`Unsupported color: ${hex}`)
  const channels = [0, 2, 4].map(offset => Number.parseInt(normalized.slice(offset, offset + 2), 16))
  return 0.2126 * linearChannel(channels[0]!)
    + 0.7152 * linearChannel(channels[1]!)
    + 0.0722 * linearChannel(channels[2]!)
}

export function contrastRatio(foreground: string, background: string) {
  const [lighter, darker] = [luminance(foreground), luminance(background)].sort((a, b) => b - a)
  return (lighter! + 0.05) / (darker! + 0.05)
}
