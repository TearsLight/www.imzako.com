import { computed, ref } from 'vue'
import { fetchCurrentUser, logout as requestLogout, type AuthUser } from '../services/auth'

const currentUser = ref<AuthUser | null>(null)

export function accountName(user: AuthUser) {
  const name = user.displayName?.trim() || user.username.trim()
  return name || String(user.id)
}

/** First character of the account name, used when no avatar image is set. */
export function accountInitial(user: AuthUser) {
  return accountName(user).trim().charAt(0).toUpperCase() || '?'
}

/**
 * Restores the session created by login or registration. A missing backend, an
 * expired session or a development server without the API simply mean that
 * nobody is signed in yet.
 */
export async function initializeAuth() {
  const user = await fetchCurrentUser()
  if (user) currentUser.value = user
}

export function useAuth() {
  const isAuthenticated = computed(() => currentUser.value !== null)
  const greeting = computed(() => (currentUser.value ? `欢迎${accountName(currentUser.value)}` : ''))

  const avatar = computed(() => currentUser.value?.avatar ?? null)
  const initial = computed(() => (currentUser.value ? accountInitial(currentUser.value) : ''))

  const setUser = (user: AuthUser | null) => {
    currentUser.value = user
  }

  /** Reloads the signed-in user, e.g. after a profile change in another tab. */
  const refresh = async () => {
    const user = await fetchCurrentUser()
    if (user) currentUser.value = user
  }

  const signOut = async () => {
    try {
      await requestLogout()
    } catch {
      // Clear the local view even when the request could not reach the server.
    }
    currentUser.value = null
  }

  return {
    currentUser,
    isAuthenticated,
    greeting,
    avatar,
    initial,
    setUser,
    refresh,
    signOut,
  }
}
