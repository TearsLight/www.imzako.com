import './assets/main.css'

import { createApp } from 'vue'

import App from './App.vue'
import { initializeAuth } from './composables/useAuth'
import { initializeTheme } from './composables/useTheme'
import router from './router'

initializeTheme()
void initializeAuth()

const app = createApp(App)

app.use(router)

app.mount('#app')
