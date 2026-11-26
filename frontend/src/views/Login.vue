<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const isLogin = ref(true)
const username = ref('')
const email = ref('')
const password = ref('')
const error = ref('')
const success = ref('')
const submitting = ref(false)

async function submit() {
  error.value = ''
  success.value = ''
  if (submitting.value) return
  submitting.value = true
  try {
    if (isLogin.value) {
      await auth.login(username.value, password.value)
    } else {
      await auth.register(username.value, email.value, password.value)
    }
    // 如果是从 token 过期跳转过来的，登录后回到之前的页面
    const redirect = sessionStorage.getItem('loginRedirect')
    sessionStorage.removeItem('loginRedirect')
    if (isLogin.value) {
      router.push(redirect || '/')
    } else {
      // 注册成功给明确反馈，短暂停留后再跳转
      success.value = '注册成功，已自动登录，正在跳转…'
      setTimeout(() => router.push(redirect || '/'), 1200)
    }
  } catch (e: any) {
    error.value = e.response?.data?.message || e.message || '操作失败，请重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <span class="login-logo">📖</span>
        <h1>Novel Agent</h1>
        <p class="login-sub">{{ isLogin ? '欢迎回来' : '创建账号' }}</p>
      </div>
      <form @submit.prevent="submit">
        <div class="field">
          <label>用户名</label>
          <input v-model="username" placeholder="请输入用户名" required />
        </div>
        <div class="field" v-if="!isLogin">
          <label>邮箱</label>
          <input v-model="email" placeholder="请输入邮箱" type="email" required />
        </div>
        <div class="field">
          <label>密码</label>
          <input v-model="password" placeholder="请输入密码（至少6位）" type="password" required minlength="6" />
        </div>
        <p v-if="error" class="error">{{ error }}</p>
        <p v-if="success" class="success">{{ success }}</p>
        <button type="submit" class="login-btn" :disabled="submitting">{{ isLogin ? '登录' : '注册' }}</button>
      </form>
      <p class="switch">
        {{ isLogin ? '还没有账号？' : '已有账号？' }}
        <a @click="isLogin = !isLogin; error = ''">{{ isLogin ? '立即注册' : '去登录' }}</a>
      </p>
      <p class="back-home">
        <a @click="router.push('/')">← 返回首页</a>
      </p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex; justify-content: center; align-items: center;
  min-height: 100vh; background: #f5f4f0;
}

.login-header {
  text-align: center; margin-bottom: 32px;
}
.login-logo { font-size: 40px; }
.login-header h1 { font-size: 22px; font-weight: 700; color: #222; margin: 8px 0 4px; }
.login-sub { font-size: 14px; color: #999; margin: 0; }

.login-card {
  background: #fff; padding: 48px 40px; border-radius: 16px;
  width: 400px; box-shadow: 0 4px 24px rgba(0,0,0,0.06);
}

.field { margin-bottom: 18px; }
.field label {
  display: block; font-size: 13px; font-weight: 600; color: #555;
  margin-bottom: 6px;
}
.field input {
  width: 100%; padding: 11px 14px;
  background: #f7f8fa; border: 1px solid #e0e3e8;
  border-radius: 10px; font-size: 14px; color: #333;
  outline: none; transition: border-color 0.15s, box-shadow 0.15s;
  box-sizing: border-box;
}
.field input:focus {
  border-color: #4f46e5; background: #fff;
  box-shadow: 0 0 0 3px rgba(79,70,229,0.08);
}
.field input::placeholder { color: #bbb; }

.error {
  color: #e55; font-size: 13px; margin: -4px 0 12px;
  padding: 8px 12px; background: #fef2f2; border-radius: 8px;
}

.success {
  color: #16a34a; font-size: 13px; margin: -4px 0 12px;
  padding: 8px 12px; background: #f0fdf4; border-radius: 8px;
}

.login-btn:disabled {
  opacity: 0.6; cursor: not-allowed;
}

.login-btn {
  width: 100%; padding: 12px;
  background: #4f46e5; color: #fff; border: none;
  border-radius: 12px; cursor: pointer; font-size: 15px; font-weight: 600;
  transition: background 0.15s;
}
.login-btn:hover { background: #4338ca; }

.switch {
  margin-top: 20px; color: #999; text-align: center; font-size: 13px;
}
.switch a { color: #4f46e5; cursor: pointer; }

.back-home {
  margin-top: 16px; text-align: center;
}
.back-home a {
  color: #bbb; cursor: pointer; font-size: 13px; transition: color 0.15s;
}
.back-home a:hover { color: #4f46e5; }
</style>
