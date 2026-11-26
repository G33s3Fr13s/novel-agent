import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '../api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<{ id: string; username: string; role: string } | null>(null)

  const isAdmin = computed(() => user.value?.role === 'ADMIN')

  async function login(username: string, password: string) {
    const { data } = await api.post('/auth/login', { username, password })
    localStorage.setItem('accessToken', data.data.accessToken)
    user.value = data.data.user
  }

  async function register(username: string, email: string, password: string) {
    const { data } = await api.post('/auth/register', { username, email, password })
    localStorage.setItem('accessToken', data.data.accessToken)
    user.value = data.data.user
  }

  async function fetchMe() {
    try {
      const { data } = await api.get('/auth/me')
      user.value = data.data
    } catch {
      user.value = null
    }
  }

  async function logout() {
    await api.post('/auth/logout')
    // 清除所有 localStorage 中的聊天记录（防止下一个登录用户看到）
    const keys = Object.keys(localStorage)
    for (const key of keys) {
      if (key.startsWith('chat-') || key.startsWith('chat-user-')) {
        localStorage.removeItem(key)
      }
    }
    localStorage.removeItem('accessToken')
    user.value = null
  }

  return { user, isAdmin, login, register, fetchMe, logout }
})
