// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppShell } from './AppShell'

vi.mock('./Header', () => ({
  Header: () => <div data-testid="global-header">Global header</div>
}))

vi.mock('./NavRail', () => ({
  NavRail: () => <div data-testid="nav-rail">Navigation</div>
}))

vi.mock('../../hooks/useBootstrap', () => ({
  useBootstrap: vi.fn()
}))

describe('AppShell', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  it('keeps the global header on the settings page', async () => {
    await renderShell(root, '/settings')

    expect(document.querySelector('[data-testid="global-header"]')).not.toBeNull()
    expect(document.querySelector('[data-testid="nav-rail"]')).not.toBeNull()
  })

  it('keeps the global header on the short-term page', async () => {
    await renderShell(root, '/short-term')

    expect(document.querySelector('[data-testid="global-header"]')).not.toBeNull()
    expect(document.querySelector('[data-testid="nav-rail"]')).not.toBeNull()
  })

  it('keeps the global header on the default short-term route', async () => {
    await renderShell(root, '/')

    expect(document.querySelector('[data-testid="global-header"]')).not.toBeNull()
    expect(document.querySelector('[data-testid="nav-rail"]')).not.toBeNull()
  })

  it('keeps the global header on other business pages too', async () => {
    await renderShell(root, '/market')

    expect(document.querySelector('[data-testid="global-header"]')).not.toBeNull()
  })
})

async function renderShell(root: Root, path: string) {
  await act(async () => {
    root.render(
      <MemoryRouter
        initialEntries={[path]}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <AppShell />
      </MemoryRouter>
    )
    await Promise.resolve()
  })
}
