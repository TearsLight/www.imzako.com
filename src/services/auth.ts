export interface LoginCredentials {
  username: string
  password: string
}

export interface RegisterCredentials {
  email: string
  username: string
  password: string
  gender: 'male' | 'female' | 'other' | 'undisclosed'
  birthday: string | null
}

export interface AuthUser {
  id: string | number
  username: string
  displayName?: string
  email?: string
  gender?: string
  birthday?: string | null
  roles?: string[]
}

export interface LoginResponse {
  user: AuthUser
}

export class AuthError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
  ) {
    super(message)
    this.name = 'AuthError'
  }
}

const loginEndpoint = import.meta.env.VITE_LOGIN_ENDPOINT || '/api/auth/login'
const registerEndpoint = import.meta.env.VITE_REGISTER_ENDPOINT || '/api/auth/register'

function normalizeResponse(data: unknown, action: '登录' | '注册'): LoginResponse {
  const user = (data as Partial<LoginResponse> | null)?.user
  if (
    !user ||
    (typeof user.id !== 'string' && typeof user.id !== 'number') ||
    typeof user.username !== 'string' ||
    !user.username.trim() ||
    (user.displayName !== undefined && typeof user.displayName !== 'string')
  ) {
    throw new AuthError(`未能确认${action}结果，请稍后重试。`)
  }
  return {
    user: {
      id: user.id,
      username: user.username,
      displayName: user.displayName,
      email: typeof user.email === 'string' ? user.email : undefined,
      gender: typeof user.gender === 'string' ? user.gender : undefined,
      birthday:
        typeof user.birthday === 'string' || user.birthday === null ? user.birthday : undefined,
      roles: Array.isArray(user.roles)
        ? user.roles.filter((role): role is string => typeof role === 'string')
        : undefined,
    },
  }
}

// Backend integration point: POST JSON, with an HttpOnly session cookie in the response.
export async function login(
  credentials: LoginCredentials,
  signal?: AbortSignal,
): Promise<LoginResponse> {
  let response: Response
  try {
    response = await fetch(loginEndpoint, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(credentials),
      signal,
    })
  } catch {
    throw new AuthError(
      signal?.aborted ? '登录请求已超时，请重试。' : '暂时无法连接，请检查网络后重试。',
    )
  }

  if (!response.ok) {
    const messages: Record<number, string> = {
      400: '请检查账号和密码后重试。',
      401: '账号或密码不正确，请重试。',
      403: '暂时无法登录，请刷新页面后重试。',
      404: '登录服务暂未开放，请稍后再试。',
      429: '尝试次数较多，请稍后再试。',
      501: '登录服务暂未开放，请稍后再试。',
    }
    throw new AuthError(
      messages[response.status] ?? '登录服务暂时不可用，请稍后重试。',
      response.status,
    )
  }

  let data: unknown
  try {
    data = await response.json()
  } catch {
    throw new AuthError('未能确认登录结果，请稍后重试。')
  }
  return normalizeResponse(data, '登录')
}

// Public registration creates a regular USER account and establishes the same session as login.
export async function register(
  credentials: RegisterCredentials,
  signal?: AbortSignal,
): Promise<LoginResponse> {
  let response: Response
  try {
    response = await fetch(registerEndpoint, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(credentials),
      signal,
    })
  } catch {
    throw new AuthError(
      signal?.aborted ? '注册请求已超时，请重试。' : '暂时无法连接，请检查网络后重试。',
    )
  }

  if (!response.ok) {
    const messages: Record<number, string> = {
      400: '请检查注册信息后重试。',
      403: '暂时无法注册，请刷新页面后重试。',
      404: '注册服务暂未开放，请稍后再试。',
      409: '用户名或邮箱已被注册，请直接登录或更换信息。',
      429: '注册尝试较多，请稍后再试。',
      501: '注册服务暂未开放，请稍后再试。',
      503: '账号服务暂时不可用，请稍后再试。',
    }
    throw new AuthError(
      messages[response.status] ?? '注册服务暂时不可用，请稍后重试。',
      response.status,
    )
  }

  let data: unknown
  try {
    data = await response.json()
  } catch {
    throw new AuthError('未能确认注册结果，请稍后重试。')
  }
  return normalizeResponse(data, '注册')
}
