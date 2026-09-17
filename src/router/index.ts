import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '../views/HomePage.vue'
import { loadBlogPostView } from './blogView'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomePage,
      meta: { title: '站点导航' },
    },
    {
      path: '/blog',
      name: 'blog',
      component: () => import('../views/BlogPage.vue'),
      meta: { title: '博客' },
    },
    {
      path: '/blog/posts/:slug(.*)',
      name: 'blog-post',
      component: loadBlogPostView,
      meta: { title: '文章' },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginPage.vue'),
      meta: { title: '用户登录' },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../views/RegisterPage.vue'),
      meta: { title: '用户注册' },
    },
    {
      path: '/account',
      name: 'account',
      component: () => import('../views/AccountPage.vue'),
      meta: { title: '用户管理' },
    },
    { path: '/about', redirect: '/' },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('../views/NotFoundPage.vue'),
      meta: { title: '页面不存在' },
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
