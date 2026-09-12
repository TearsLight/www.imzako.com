import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '../views/HomePage.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomePage,
      meta: { title: 'imzako — 站点导航' },
    },
    {
      path: '/blog',
      name: 'blog',
      component: () => import('../views/BlogPage.vue'),
      meta: { title: '博客 — imzako' },
    },
    {
      path: '/blog/posts/:slug(.*)',
      name: 'blog-post',
      component: () => import('../views/BlogPostPage.vue'),
      meta: { title: '文章 — imzako' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginPage.vue'),
      meta: { title: '用户登录 — imzako' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../views/RegisterPage.vue'),
      meta: { title: '用户注册 — imzako' },
    },
    { path: '/about', redirect: '/' },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('../views/NotFoundPage.vue'),
      meta: { title: '页面不存在 — imzako' },
    },
  ],
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})

router.afterEach((to) => {
  document.title = typeof to.meta.title === 'string' ? to.meta.title : 'imzako'
})

export default router
