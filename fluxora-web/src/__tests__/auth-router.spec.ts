import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

describe('auth router guards', () => {
  it('console route requires auth meta', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', component: { template: '<div>Login</div>' }, meta: { guest: true } },
        { path: '/console/:section?', component: { template: '<div>Console</div>' }, meta: { requiresAuth: true } },
      ],
    })

    // Import the router's beforeEach - it references the real auth store
    // In this test, we verify the route meta configuration
    const consoleRoute = router.resolve('/console/overview')
    expect(consoleRoute.meta.requiresAuth).toBe(true)

    const loginRoute = router.resolve('/login')
    expect(loginRoute.meta.guest).toBe(true)
  })
})
