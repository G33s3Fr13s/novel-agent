<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

const router = useRouter()
const auth = useAuthStore()

onMounted(async () => {
  await auth.fetchMe()
  if (!auth.user && router.currentRoute.value.path !== '/login') {
    router.push('/login')
  }
})
</script>

<template>
  <div class="app-container">
    <router-view />
  </div>
</template>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
.app-container { min-height: 100vh; background: #111; color: #e0e0e0; }
</style>
