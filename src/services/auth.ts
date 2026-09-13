export interface LoginCredentials {
  /** Account email or numeric UID. Usernames are not unique and cannot sign in. */
  identity: string
  password: string
  /** Supplied only when the server demanded a captcha. */
  captchaId?: string
  captchaAnswer?: string
}

export interface RegisterCredentials {
  email: string
  username: string
  password: string
  avatar?: string | null
}

export interface AuthUser {
  id: string | number
  username: string
  displayName?: string
  email?: string
  avatar?: string | null
  roles?: string[]
}

export interface AuthResponse {
  user: AuthUser | null
  /** True when the address still needs the emailed code before a session exists. */
  verificationRequired: boolean
  /** Where the code was sent, partly hidden. */
  maskedEmail: string | null
}

export class AuthError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
    /** Server error code such as CAPTCHA_REQUIRED or EMAIL_NOT_VERIFIED. */
    public readonly code?: string,
  ) {
    super(message)
    this.name = 'AuthError'
  }
}

const loginEndpoint = import.meta.env.VITE_LOGIN_ENDPOINT || '/api/auth/login'
const registerEndpoint = import.meta.env.VITE_REGISTER_ENDPOINT || '/api/auth/register'

/** Shapes returned by the account endpoints that answer with a user or a verification prompt. */
function normalizeAuthResponse(data: unknown, action: string): AuthResponse {
  const payload = (data ?? {}) as Partial<AuthResponse>
  const raw = payload.user

  if (payload.verificationRequired) {
    return {
      user: null,
      verificationRequired: true,
      maskedEmail: typeof payload.maskedEmail === 'string' ? payload.maskedEmail : null,
    }
  }

  if (
    !raw ||
    (typeof raw.id !== 'string' && typeof raw.id !== 'number') ||
    typeof raw.username !== 'string' ||
    !raw.username.trim() ||
    (raw.displayName !== undefined && typeof raw.displayName !== 'string')
  ) {
    throw new AuthError(`未能确认${action}结果，请稍后重试。`)
  }

  return {
    user: {
      id: raw.id,
      username: raw.username,
      displayName: raw.displayName,
      email: typeof raw.email === 'string' ? raw.email : undefined,
      avatar: typeof raw.avatar === 'string' ? raw.avatar : null,
      roles: Array.isArray(raw.roles)
        ? raw.roles.filter((role): role is string => typeof role === 'string')
        : undefined,
    },
    verificationRequired: false,
    maskedEmail: null,
  }
}

interface ApiErrorBody {
  message?: unknown
  code?: unknown
  fields?: Record<string, unknown>
}

/** Reads the server's own message and code so the UI can react to specific failures. */
async function readError(
  response: Response,
): Promise<{ message: string | null; code: string | null }> {
  try {
    const body = (await response.json()) as ApiErrorBody
    return {
      message: typeof body?.message === 'string' && body.message.trim() ? body.message : null,
      code: typeof body?.code === 'string' ? body.code : null,
    }
  } catch {
    return { message: null, code: null }
  }
}

function networkMessage(action: string, signal?: AbortSignal) {
  return signal?.aborted ? `${action}请求已超时，请重试。` : '暂时无法连接，请检查网络后重试。'
}

async function send(
  path: string,
  method: 'POST' | 'PUT',
  body: unknown,
  action: string,
  messages: Record<number, string>,
  fallback: string,
  signal?: AbortSignal,
): Promise<AuthResponse> {
  let response: Response
  try {
    response = await fetch(path, {
      method,
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body),
      signal,
    })
  } catch {
    throw new AuthError(networkMessage(action, signal))
  }

  if (!response.ok) {
    const { message, code } = await readError(response)
    throw new AuthError(
      message ?? messages[response.status] ?? fallback,
      response.status,
      code ?? undefined,
    )
  }
  if (response.status === 204) {
    return { user: null, verificationRequired: false, maskedEmail: null }
  }

  let data: unknown
  try {
    data = await response.json()
  } catch {
    throw new AuthError(`未能确认${action}结果，请稍后重试。`)
  }
  return normalizeAuthResponse(data, action)
}

/**
 * Signs in. Throws an AuthError with code CAPTCHA_REQUIRED (428) when a captcha is
 * needed, or EMAIL_NOT_VERIFIED (428) when the address still has to be confirmed.
 */
export async function login(
  credentials: LoginCredentials,
  signal?: AbortSignal,
): Promise<AuthResponse> {
  return send(
    loginEndpoint,
    'POST',
    credentials,
    '登录',
    {
      400: '请检查账号和密码后重试。',
      401: '账号或密码不正确，请重试。',
      403: '暂时无法登录，请刷新页面后重试。',
      404: '登录服务暂未开放，请稍后再试。',
      429: '尝试次数较多，请稍后再试。',
      501: '登录服务暂未开放，请稍后再试。',
    },
    '登录服务暂时不可用，请稍后重试。',
    signal,
  )
}

/** Creates the account. The response asks for the emailed code instead of signing in. */
export async function register(
  credentials: RegisterCredentials,
  signal?: AbortSignal,
): Promise<AuthResponse> {
  return send(
    registerEndpoint,
    'POST',
    credentials,
    '注册',
    {
      400: '请检查注册信息后重试。',
      403: '暂时无法注册，请刷新页面后重试。',
      404: '注册服务暂未开放，请稍后再试。',
      409: '邮箱已被注册，请直接登录或更换邮箱。',
      429: '注册尝试较多，请稍后再试。',
      501: '注册服务暂未开放，请稍后再试。',
      503: '账号服务暂时不可用，请稍后再试。',
    },
    '注册服务暂时不可用，请稍后重试。',
    signal,
  )
}

/** Confirms the emailed code and signs in. */
export async function verifyRegistration(
  identity: string,
  code: string,
  signal?: AbortSignal,
): Promise<AuthResponse> {
  return send(
    '/api/auth/register/verify',
    'POST',
    { identity, code },
    '验证',
    { 400: '验证码不正确或已过期。' },
    '验证失败，请稍后重试。',
    signal,
  )
}

/** Asks for a new code. Answers 204 even for unknown addresses. */
export async function resendVerification(identity: string, signal?: AbortSignal): Promise<void> {
  await send(
    '/api/auth/register/resend',
    'POST',
    { identity },
    '重发',
    { 429: '验证码发送过于频繁，请稍后再试。' },
    '重发失败，请稍后重试。',
    signal,
  )
}

/** Reads the user stored in the current session. Returns null when signed out. */
export async function fetchCurrentUser(signal?: AbortSignal): Promise<AuthUser | null> {
  let response: Response
  try {
    response = await fetch('/api/auth/me', {
      credentials: 'include',
      headers: { Accept: 'application/json' },
      signal,
    })
  } catch {
    return null
  }
  if (!response.ok) return null

  try {
    return normalizeAuthResponse(await response.json(), '登录').user
  } catch {
    return null
  }
}

/** Invalidates the server session. The caller clears the local view. */
export async function logout(signal?: AbortSignal): Promise<void> {
  await fetch('/api/auth/logout', {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'application/json' },
    signal,
  })
}

/** Usernames may repeat across accounts, so this cannot fail with a conflict. */
export async function updateUsername(
  username: string,
  signal?: AbortSignal,
): Promise<AuthResponse> {
  return send(
    '/api/auth/username',
    'PUT',
    { username },
    '修改',
    { 400: '用户名不符合要求。' },
    '修改用户名失败，请稍后重试。',
    signal,
  )
}

/** Pass an image data URI, or null to remove the current avatar. */
export async function updateAvatar(
  avatar: string | null,
  signal?: AbortSignal,
): Promise<AuthResponse> {
  return send(
    '/api/auth/avatar',
    'PUT',
    { avatar },
    '修改',
    { 400: '头像不符合要求。' },
    '修改头像失败，请稍后重试。',
    signal,
  )
}

export async function updateEmail(
  currentPassword: string,
  email: string,
  signal?: AbortSignal,
): Promise<AuthResponse> {
  return send(
    '/api/auth/email',
    'PUT',
    { currentPassword, email },
    '修改',
    { 400: '当前密码不正确或邮箱无效。', 409: '邮箱已被注册。' },
    '改绑邮箱失败，请稍后重试。',
    signal,
  )
}

/** Confirms a pending email change and returns the updated signed-in user. */
export async function verifyEmailChange(code: string, signal?: AbortSignal): Promise<AuthResponse> {
  return send(
    '/api/auth/email/verify',
    'POST',
    { code },
    '验证',
    { 400: '验证码不正确或已过期。', 409: '该邮箱已被其他账号使用。' },
    '邮箱验证失败，请稍后重试。',
    signal,
  )
}

/** Changes the password. The server ends the session, so sign in again afterwards. */
export async function updatePassword(
  currentPassword: string,
  newPassword: string,
  signal?: AbortSignal,
): Promise<void> {
  await send(
    '/api/auth/password',
    'PUT',
    { currentPassword, newPassword },
    '修改',
    { 400: '当前密码不正确或新密码不符合要求。' },
    '修改密码失败，请稍后重试。',
    signal,
  )
}

/** Fetches a fresh captcha image plus the id that must accompany the answer. */
export async function fetchCaptcha(signal?: AbortSignal): Promise<{ id: string; image: string }> {
  let response: Response
  try {
    response = await fetch('/api/auth/captcha', {
      credentials: 'include',
      headers: { Accept: 'image/png' },
      signal,
      cache: 'no-store',
    })
  } catch {
    throw new AuthError('暂时无法连接，请检查网络后重试。')
  }
  const id = response.headers.get('X-Captcha-Id')
  if (!response.ok || !id) throw new AuthError('无法获取图形验证码，请稍后重试。')

  const blob = await response.blob()
  return { id, image: await blobToDataUrl(blob) }
}

/** Encodes a blob as a data URI without relying on FileReader timing. */
async function blobToDataUrl(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer())
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return `data:${blob.type || 'image/png'};base64,${btoa(binary)}`
}
