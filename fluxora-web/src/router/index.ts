import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import DocsView from '../views/DocsView.vue'
import ConsoleView from '../views/ConsoleView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView },
    { path: '/docs', component: DocsView },
    { path: '/console/:section?', component: ConsoleView },
  ],
})
