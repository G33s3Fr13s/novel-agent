<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { marked } from 'marked'

marked.setOptions({ breaks: true, gfm: true })
const route = useRoute()
const auth = useAuthStore()
const novelId = route.params.id as string
const conversationId = ref<string | null>(null)
const messages = ref<{ role: string; content: string }[]>([])
const input = ref('')
const streaming = ref(false)
const chatBox = ref<HTMLElement>()

function chatKey(): string {
  const uid = auth.user?.id
  return uid ? `chat-${novelId}-${uid}` : `chat-${novelId}`
}

onMounted(async () => {
  const saved = localStorage.getItem(chatKey())
  if (saved) messages.value = JSON.parse(saved)
})

async function send() {
  if (!input.value.trim()) return
  const question = input.value
  input.value = ''
  messages.value.push({ role: 'user', content: question })
  messages.value.push({ role: 'assistant', content: '' })
  streaming.value = true
  scrollDown()

  try {
    const token = localStorage.getItem('accessToken')
    const resp = await fetch('http://localhost:8080/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({
        message: question,
        novelId: novelId,
        ...(conversationId.value ? { conversationId: conversationId.value } : {}),
      }),
    })

    const reader = resp.body?.getReader()
    const decoder = new TextDecoder()

    while (reader) {
      const { done, value } = await reader.read()
      if (done) break
      const text = decoder.decode(value, { stream: true })
      const lines = text.split('\n')
      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (data.startsWith('{')) {
            try {
              const json = JSON.parse(data)
              if (json.conversationId) conversationId.value = json.conversationId
            } catch {}
          } else if (data !== '[DONE]') {
            const last = messages.value[messages.value.length - 1]
            if (last && last.role === 'assistant') {
              last.content += data === '\\n' ? '\n' : data.replace(/\\n/g, '\n')
              scrollDown()
            }
          }
        }
      }
    }
  } catch (e) {
    messages.value.push({ role: 'system', content: '连接失败: ' + (e as Error).message })
  } finally {
    streaming.value = false
    localStorage.setItem(chatKey(), JSON.stringify(messages.value))
  }
}

function renderMd(text: string): string {
  if (!text) return ''
  return marked.parse(text) as string
}

function scrollDown() {
  nextTick(() => {
    if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight
  })
}
</script>

<template>
  <div class="chat-page">
    <div class="chat-header">
      <router-link to="/">← 返回</router-link>
      <span>资料库模式 · {{ novelId.slice(0, 8) }}...</span>
    </div>
    <div class="chat-box" ref="chatBox">
      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
        <div class="role-tag">{{ m.role === 'user' ? '你' : m.role === 'assistant' ? 'AI' : '系统' }}</div>
        <div class="content" v-if="m.role === 'assistant'" v-html="renderMd(m.content) || '...'"></div>
        <div class="content" v-else>{{ m.content || (streaming && i === messages.length - 1 ? '...' : '') }}</div>
      </div>
    </div>
    <div class="input-bar">
      <input v-model="input" @keydown.enter="send" :disabled="streaming" placeholder="输入问题，回车发送..." />
      <button @click="send" :disabled="streaming">发送</button>
    </div>
  </div>
</template>

<style scoped>
.chat-page { display: flex; flex-direction: column; height: calc(100vh - 80px); }
.chat-header { display: flex; gap: 16px; padding: 12px 0; color: #888; border-bottom: 1px solid #222; margin-bottom: 16px; }
.chat-header a { color: #7c8cf8; text-decoration: none; }
.chat-box { flex: 1; overflow-y: auto; padding: 8px 0; }
.msg { margin-bottom: 16px; }
.role-tag { font-size: 12px; color: #7c8cf8; margin-bottom: 4px; }
.msg.user .role-tag { color: #4caf50; }
.content { line-height: 1.7; font-size: 15px; white-space: pre-wrap; word-break: break-word; }
.msg.user .content { background: #4f46e5; color: #fff; padding: 12px 18px; border-radius: 16px 16px 6px 16px; display: inline-block; max-width: 80%; }
.msg.assistant .content { background: #f4f5f7; color: #333; padding: 12px 18px; border-radius: 16px 16px 16px 6px; display: inline-block; max-width: 85%; }
.msg.system .content { background: #fff0f0; color: #d33; font-size: 13px; padding: 8px 14px; border-radius: 8px; }
/* markdown styles */
.content :deep(h2) { font-size: 17px; margin: 12px 0 6px; }
.content :deep(h3) { font-size: 15px; margin: 10px 0 4px; }
.content :deep(h4) { font-size: 14px; margin: 8px 0 2px; }
.content :deep(strong) { font-weight: 600; }
.content :deep(ul) { margin: 4px 0; padding-left: 18px; }
.content :deep(li) { margin: 2px 0; line-height: 1.6; }
.content :deep(p) { margin: 4px 0; }
.content :deep(hr) { border: none; border-top: 1px solid #ddd; margin: 12px 0; }
.input-bar { display: flex; gap: 8px; padding: 12px 0; }
.input-bar input { flex: 1; padding: 12px; background: #1a1a2e; border: 1px solid #444; border-radius: 8px; color: #e0e0e0; }
.input-bar button { padding: 12px 24px; background: #7c8cf8; color: #fff; border: none; border-radius: 8px; cursor: pointer; }
.input-bar button:disabled { opacity: 0.5; }
</style>
