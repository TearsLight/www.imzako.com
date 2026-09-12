import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import ThemeToggle from '../components/ThemeToggle.vue'

enableAutoUnmount(afterEach)

function mockSystemTheme(dark: boolean) {
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: dark }))
}

beforeEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  document.documentElement.style.removeProperty('color-scheme')
  const themeColor = document.createElement('meta')
  themeColor.name = 'theme-color'
  themeColor.content = '#f9f8f5'
  document.head.append(themeColor)
  mockSystemTheme(false)
})

afterEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  document.documentElement.style.removeProperty('color-scheme')
  document.querySelector('meta[name="theme-color"]')?.remove()
  vi.unstubAllGlobals()
})

describe('global theme toggle', () => {
  it('switches between light and dark mode and remembers the selection', async () => {
    const wrapper = mount(ThemeToggle)
    const toggle = wrapper.get('[role="switch"]')

    expect(document.documentElement.dataset.theme).toBe('light')
    expect(toggle.attributes('aria-checked')).toBe('false')
    await toggle.trigger('click')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.documentElement.style.colorScheme).toBe('dark')
    expect(localStorage.getItem('imzako:theme')).toBe('dark')
    expect(toggle.attributes('aria-checked')).toBe('true')
    expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe(
      '#080808',
    )

    await toggle.trigger('click')
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(localStorage.getItem('imzako:theme')).toBe('light')
  })

  it('restores a saved dark theme', () => {
    localStorage.setItem('imzako:theme', 'dark')
    const wrapper = mount(ThemeToggle)
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(wrapper.get('[role="switch"]').attributes('aria-checked')).toBe('true')
  })

  it('follows the system preference when no theme has been saved', () => {
    mockSystemTheme(true)
    const wrapper = mount(ThemeToggle)
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(wrapper.get('[role="switch"]').attributes('aria-checked')).toBe('true')
    expect(localStorage.getItem('imzako:theme')).toBeNull()
  })
})
