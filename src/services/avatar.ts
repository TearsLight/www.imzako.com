/** Largest accepted source file before compression. */
export const MAX_AVATAR_SOURCE_BYTES = 5 * 1024 * 1024
/** Size of the square the avatar is cropped and scaled to. */
export const AVATAR_SIZE = 256
/** Must stay below the server side MAX_AVATAR_LENGTH guard (300000). */
export const MAX_AVATAR_DATA_LENGTH = 260_000

export class AvatarError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AvatarError'
  }
}

function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const image = new Image()
    image.onload = () => {
      URL.revokeObjectURL(url)
      resolve(image)
    }
    image.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new AvatarError('无法读取该图片，请换一张。'))
    }
    image.src = url
  })
}

/**
 * Center-crops the chosen image to a square and re-encodes it as JPEG so the
 * stored data URI stays small. Keeps the codec at a fixed quality and steps down
 * only when the payload would exceed the server limit.
 */
export async function prepareAvatar(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) {
    throw new AvatarError('请选择图片文件。')
  }
  if (file.size > MAX_AVATAR_SOURCE_BYTES) {
    throw new AvatarError('图片不能超过 5 MB。')
  }

  const image = await loadImage(file)
  const canvas = document.createElement('canvas')
  canvas.width = AVATAR_SIZE
  canvas.height = AVATAR_SIZE
  const context = canvas.getContext('2d')
  if (!context) throw new AvatarError('当前浏览器无法处理图片。')

  const side = Math.min(image.width, image.height)
  if (!side) throw new AvatarError('无法读取该图片，请换一张。')
  context.drawImage(
    image,
    (image.width - side) / 2,
    (image.height - side) / 2,
    side,
    side,
    0,
    0,
    AVATAR_SIZE,
    AVATAR_SIZE,
  )

  for (const quality of [0.85, 0.7, 0.55, 0.4]) {
    const dataUri = canvas.toDataURL('image/jpeg', quality)
    if (dataUri.length <= MAX_AVATAR_DATA_LENGTH) return dataUri
  }
  throw new AvatarError('图片压缩后仍然过大，请换一张更简单的图片。')
}
