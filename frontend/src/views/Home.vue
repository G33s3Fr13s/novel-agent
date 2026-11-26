<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { marked } from 'marked'
import api from '../api'

marked.setOptions({ breaks: true, gfm: true })

interface Novel { id: string; title: string; chapterCount: number }
interface Chapter { id: string; chapterNum: number; title: string; charCount: number }
interface Msg { role: string; content: string }

const router = useRouter()
const auth = useAuthStore()

const novels = ref<Novel[]>([])
const selectedNovel = ref<Novel | null>(null)
const selectedChapterId = ref<string | null>(null)
const conversationId = ref<string | null>(null)
const messages = ref<Msg[]>([])
const input = ref('')
const inputEl = ref<HTMLTextAreaElement>()
const streaming = ref(false)

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() }
}

function autoResize() {
  const el = inputEl.value; if (!el) return
  el.style.height = 'auto'
  el.style.height = el.scrollHeight + 'px'
}
// 联网搜索站点选择
const SEARCH_SITES = [
  { key: 'qidian', label: '起点中文网' },
  { key: 'fanqie', label: '番茄小说' },
  { key: 'qimao', label: '七猫小说' },
  { key: 'jjwxc', label: '晋江文学城' },
]
const searchSites = ref<string[]>([])
const showSearchMenu = ref(false)

function toggleSite(key: string) {
  const idx = searchSites.value.indexOf(key)
  if (idx >= 0) searchSites.value.splice(idx, 1)
  else searchSites.value.push(key)
}

function closeSearchMenu(e: MouseEvent) {
  const el = (e.target as HTMLElement).closest('.search-picker')
  if (!el) showSearchMenu.value = false
}
const chatBox = ref<HTMLElement>()
const sidebarCollapsed = ref(false)

const activeNovelId = ref<string | null>(null)
const chapters = ref<Chapter[]>([])
const uploading = ref(false)
const uploadProgress = ref('')

const readingChapter = ref<{ novel: Novel; chapter: Chapter } | null>(null)
const chapterText = ref('')

const showCreateNovel = ref(false)
const newNovelTitle = ref('')
const page = ref<'chat' | 'books'>('chat')
const conversations = ref<any[]>([])
const searchResults = ref<any[]>([])
const searchQuery = ref('')
const showSearch = ref(false)
const searchInputEl = ref<HTMLInputElement>()
const menuConvId = ref<string | null>(null)
const menuAnchorEl = ref<HTMLElement | null>(null)
const novelMenuId = ref<string | null>(null)
const novelMenuEl = ref<HTMLElement | null>(null)
const modalInputEl = ref<HTMLInputElement>()

// 自定义弹窗
const modal = ref({ show: false, title: '', message: '', input: '', type: 'confirm' as 'confirm' | 'prompt' })
let modalResolve: ((v: any) => void) | null = null
function showConfirm(msg: string, title = '确认操作'): Promise<boolean> {
  return new Promise(resolve => {
    modal.value = { show: true, title, message: msg, input: '', type: 'confirm' }
    modalResolve = resolve
  })
}
function showPrompt(msg: string, title = '请输入'): Promise<string | null> {
  return new Promise(resolve => {
    modal.value = { show: true, title, message: msg, input: '', type: 'prompt' }
    modalResolve = resolve
    nextTick(() => modalInputEl.value?.focus())
  })
}
function modalOk() {
  modal.value.show = false
  modalResolve?.(modal.value.type === 'prompt' ? modal.value.input : true)
  modalResolve = null
}
function modalCancel() {
  modal.value.show = false
  modalResolve?.(modal.value.type === 'prompt' ? null : false)
  modalResolve = null
}

onMounted(async () => { await loadNovels(); loadChatHistory(); loadConversations() })
document.addEventListener('click', closeSearchMenu)

async function loadNovels() {
  try { const { data } = await api.get('/novels'); novels.value = data.data } catch {}
}

function chatLocalKey(): string {
  // 已登录用户按 userId 隔离 localStorage，避免同浏览器不同用户看到对方聊天记录
  const uid = auth.user?.id
  if (selectedNovel.value) {
    const base = `chat-${selectedNovel.value.id}`
    return selectedChapterId.value ? `${base}-${selectedChapterId.value}-${uid}` : `${base}-${uid}`
  }
  return uid ? `chat-user-${uid}` : 'chat-default'
}

function loadChatHistory() {
  const saved = localStorage.getItem(chatLocalKey())
  messages.value = saved ? JSON.parse(saved) : []
}

async function createNovel() {
  if (!newNovelTitle.value.trim()) return
  await api.post('/novels', { title: newNovelTitle.value, author: '', description: '' })
  newNovelTitle.value = ''; showCreateNovel.value = false; await loadNovels()
}

async function toggleNovel(n: Novel) {
  if (activeNovelId.value === n.id) { activeNovelId.value = null; return }
  activeNovelId.value = n.id
  const { data } = await api.get(`/novels/${n.id}/chapters`)
  chapters.value = data.data
}

const ALLOWED = ['.txt', '.md', '.docx']

async function uploadFiles(novelId: string, event: Event) {
  const files = (event.target as HTMLInputElement).files
  if (!files || files.length === 0) return
  // 前端拦截不支持的文件格式
  const rejected: string[] = []
  const validFiles: File[] = []
  for (const f of Array.from(files)) {
    const ext = '.' + f.name.split('.').pop()?.toLowerCase()
    if (ALLOWED.includes(ext)) validFiles.push(f)
    else rejected.push(f.name)
  }
  if (rejected.length > 0) {
    alert(`不支持的文件格式: ${rejected.join(', ')}\n仅支持 .txt .md .docx`)
    ;(event.target as HTMLInputElement).value = ''
    if (validFiles.length === 0) return
  }
  uploading.value = true
  let done = 0

  for (const file of validFiles) {
    uploadProgress.value = `上传 ${++done}/${validFiles.length}: ${file.name}`
    const fd = new FormData(); fd.append('file', file)
    try {
      await api.post(`/novels/${novelId}/chapters`, fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    } catch (e: any) {
      uploadProgress.value = `${file.name} 上传失败: ${e.response?.data?.message || e.message}`
      await new Promise(r => setTimeout(r, 2000))
    }
  }

  uploadProgress.value = `✅ ${validFiles.length} 个章节上传完成`
  ;(event.target as HTMLInputElement).value = ''
  // 刷新章节列表，保持展开
  const { data } = await api.get(`/novels/${novelId}/chapters`)
  chapters.value = data.data
  await loadNovels()
  setTimeout(() => { uploadProgress.value = ''; uploading.value = false }, 2000)
}

async function readChapter(n: Novel, ch: Chapter) {
  const { data } = await api.get(`/novels/${n.id}/chapters/${ch.chapterNum}`)
  readingChapter.value = { novel: n, chapter: ch }
  chapterText.value = data.data.content
}

function closeReader() { readingChapter.value = null }

async function buildPlot(novelId: string) {
  try {
    await api.post(`/novels/${novelId}/plot`)
    alert('剧情脉络提取任务已启动，稍后刷新即可')
  } catch (e: any) { alert('构建失败: ' + (e.response?.data?.message || e.message)) }
}

async function buildGraph(novelId: string) {
  try {
    await api.post(`/novels/${novelId}/graph`)
    alert('人物关系提取任务已启动，稍后刷新即可')
  } catch (e: any) { alert('构建失败: ' + (e.response?.data?.message || e.message)) }
}

async function downloadExport(url: string, filename: string) {
  try {
    const resp = await api.get(url, { responseType: 'blob' })
    const blobUrl = URL.createObjectURL(new Blob([resp.data], { type: 'text/markdown' }))
    const a = document.createElement('a')
    a.href = blobUrl; a.download = filename; a.click()
    URL.revokeObjectURL(blobUrl)
  } catch (e: any) {
    alert('导出失败: ' + (e.response?.data?.message || e.message))
  }
}

function openRagChat(n: Novel) {
  readingChapter.value = null; selectedNovel.value = n; selectedChapterId.value = null
  conversationId.value = null
  loadChatHistory(); page.value = 'chat'
}

function openChapterChat(n: Novel, ch: Chapter) {
  readingChapter.value = null; selectedNovel.value = n; selectedChapterId.value = ch.id
  conversationId.value = null
  loadChatHistory()
  page.value = 'chat'
}

async function loadConversations() {
  try { const { data } = await api.get('/chat/conversations'); conversations.value = data.data } catch {}
}

let searchTimer: any = null
async function searchConversations() {
  if (!searchQuery.value.trim()) { searchResults.value = []; return }
  clearTimeout(searchTimer)
  searchTimer = setTimeout(async () => {
    try {
      const { data } = await api.get('/chat/search', { params: { q: searchQuery.value } })
      searchResults.value = data.data
    } catch {}
  }, 300)
}

async function startNewChat() {
  selectedNovel.value = null; selectedChapterId.value = null; conversationId.value = null
  messages.value = []
  page.value = 'chat'
}

async function renameConv(id: string) {
  const title = await showPrompt('请输入新名称', '重命名对话')
  if (!title) return
  await api.put(`/chat/conversations/${id}`, { title })
  menuConvId.value = null
  await loadConversations()
}

async function delConv(id: string) {
  if (!await showConfirm('确认删除此对话？此操作不可恢复。')) return
  await api.delete(`/chat/conversations/${id}`)
  menuConvId.value = null
  if (conversationId.value === id) startNewChat()
  await loadConversations()
}

async function deleteNovel(id: string) {
  if (!await showConfirm('确认删除该作品及其所有章节、图谱数据？此操作不可恢复。', '删除作品')) return
  await api.delete(`/novels/${id}`)
  if (activeNovelId.value === id) { activeNovelId.value = null; chapters.value = [] }
  if (selectedNovel.value?.id === id) { selectedNovel.value = null; selectedChapterId.value = null; loadChatHistory() }
  await loadNovels()
}

async function deleteChapter(novelId: string, chapterId: string) {
  if (!await showConfirm('确认删除该章节？其分块和剧情事件将被一并清除。', '删除章节')) return
  await api.delete(`/novels/${novelId}/chapters/${chapterId}`)
  if (selectedChapterId.value === chapterId) { selectedChapterId.value = null; loadChatHistory() }
  // 刷新章节列表
  const { data } = await api.get(`/novels/${novelId}/chapters`)
  chapters.value = data.data
  await loadNovels()
}

async function openConversation(conv: any) {
  readingChapter.value = null; conversationId.value = conv.id
  selectedNovel.value = null; selectedChapterId.value = null
  const { data } = await api.get(`/chat/conversations/${conv.id}`)
  messages.value = data.data.map((m: any) => ({ role: m.role, content: m.content }))
  page.value = 'chat'
}

function openNormalChat() {
  readingChapter.value = null; selectedNovel.value = null; selectedChapterId.value = null
  loadChatHistory(); page.value = 'chat'
}

async function send() {
  if (!input.value.trim() || streaming.value) return
  const q = input.value; input.value = ''
  nextTick(() => autoResize())
  messages.value.push({ role: 'user', content: q })
  messages.value.push({ role: 'assistant', content: '' })
  streaming.value = true; scrollDown()

  const body: any = { message: q, searchSites: searchSites.value.length > 0 ? searchSites.value : null }
  if (selectedNovel.value) body.novelId = selectedNovel.value.id
  if (selectedChapterId.value) body.chapterId = selectedChapterId.value
  if (conversationId.value) body.conversationId = conversationId.value

  try {
    const token = localStorage.getItem('accessToken')
    const resp = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
    })
    const reader = resp.body?.getReader()
    const decoder = new TextDecoder(); let ev = ''
    while (reader) {
      const { done, value } = await reader.read()
      if (done) break
      for (const line of decoder.decode(value, { stream: true }).split('\n')) {
        if (line.startsWith('event:')) ev = line.slice(6).trim()
        else if (line.startsWith('data:') && ev === 'token') {
          const last = messages.value[messages.value.length - 1]
          if (last?.role === 'assistant') { last.content += line.slice(5).trim().replace(/\\n/g, '\n'); scrollDown() }
        } else if (line.startsWith('data:') && ev === 'done') {
          try { const j = JSON.parse(line.slice(5).trim()); if (j.conversationId) conversationId.value = j.conversationId } catch {}
        }
      }
    }
  } catch (e) {
    messages.value.push({ role: 'system', content: '连接失败: ' + (e as Error).message })
  } finally {
    streaming.value = false
    localStorage.setItem(chatLocalKey(), JSON.stringify(messages.value))
  }
}

function renderMd(text: string): string {
  if (!text) return ''
  return marked.parse(text) as string
}

function scrollDown() { nextTick(() => { if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight }) }
</script>

<template>
  <div class="app">
    <!-- ====== 侧边栏 ====== -->
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <div class="sb-head">
        <span v-if="!sidebarCollapsed" class="sb-logo" @click="openNormalChat">📖</span>
        <span v-if="!sidebarCollapsed" class="sb-title" @click="openNormalChat">Novel Agent</span>
        <span class="sb-head-actions">
          <span v-if="!showSearch" class="sb-head-icon" @click="showSearch = true; searchQuery = ''; searchResults = []; nextTick(() => searchInputEl?.focus())" title="搜索">🔍</span>
          <button class="sb-collapse-btn" @click="sidebarCollapsed = !sidebarCollapsed" :title="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'">
            <span class="sb-collapse-icon">
              <span class="sb-collapse-bar"></span>
            </span>
          </button>
        </span>
      </div>
      <template v-if="!sidebarCollapsed">

      <nav class="sb-tabs">
        <div class="sb-tab" :class="{ active: page === 'chat' && !selectedNovel }" @click="openNormalChat">
          💬 对话
        </div>
        <div class="sb-tab" :class="{ active: page === 'books' }" @click="page = 'books'">
          📚 作品 ({{ novels.length }})
        </div>
        <div class="sb-tab" @click="router.push('/continue')">
          ✍️ 续写
        </div>
      </nav>

      <!-- 对话页 -->
      <div class="sb-list" v-if="page === 'chat'">
        <div class="sb-label">当前对话</div>
        <div class="sb-new-chat" @click="startNewChat">＋ 新对话</div>

        <!-- 搜索结果 / 对话列表 -->
        <div class="sb-conv-list" v-if="!showSearch || !searchQuery">
          <div v-for="c in conversations" :key="c.id"
            class="sb-conv-item" :class="{ active: conversationId === c.id && !selectedNovel }"
            @click="openConversation(c)">
            <span class="sb-conv-title">{{ c.title || '新对话' }}</span>
            <span class="sb-conv-date">{{ c.updatedAt?.slice(0, 10) }}</span>
            <span class="sb-conv-menu" @click.stop :ref="el => { if (menuConvId === c.id) menuAnchorEl = el as HTMLElement }">
              <span class="sb-conv-dots" @click="menuConvId = menuConvId === c.id ? null : c.id">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>
              </span>
            </span>
          </div>
          <div v-if="conversations.length === 0 && !searchQuery" class="sb-empty">暂无对话记录</div>
        </div>
      </div>

      <!-- 作品页 — 管理 -->
      <div class="sb-list" v-if="page === 'books'">
        <div class="sb-label">作品管理</div>
        <div v-for="n in novels" :key="n.id" class="sb-novel">
          <div class="sb-novel-row" @click="toggleNovel(n)">
            <svg class="sb-chevron" :class="{ open: activeNovelId === n.id }" width="16" height="16" viewBox="0 0 16 16">
              <path d="M6 4l4 4-4 4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span class="sb-novel-name">{{ n.title }}</span>
            <span class="sb-novel-meta">{{ n.chapterCount }} 章</span>
            <span class="sb-novel-menu-wrap" @click.stop :ref="el => { if (novelMenuId === n.id) novelMenuEl = el as HTMLElement }">
              <span class="sb-novel-dots" @click="novelMenuId = novelMenuId === n.id ? null : n.id">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>
              </span>
            </span>
          </div>
          <div v-if="activeNovelId === n.id" class="sb-novel-children">
            <div class="sb-chapters-scroll">
            <div v-for="ch in chapters" :key="ch.id" class="sb-chapter" @click="readChapter(n, ch)">
              <span class="sb-ch-num">{{ ch.chapterNum }}</span>
              <span class="sb-ch-title">{{ ch.title }}</span>
              <span class="sb-ch-chars">{{ ch.charCount }}字</span>
              <span class="sb-ch-chat" @click.stop="openChapterChat(n, ch)" title="基于本章对话">💬</span>
              <span class="sb-ch-del" @click.stop="deleteChapter(n.id, ch.id)" title="删除章节">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>
              </span>
            </div>
            <p v-if="chapters.length === 0" class="sb-empty-sm">暂无章节</p>
            </div>
            <div v-if="uploading && activeNovelId === n.id" class="sb-progress">{{ uploadProgress }}</div>
          </div>
        </div>
        <button class="sb-create" @click="showCreateNovel = !showCreateNovel">＋ 新建作品</button>
        <div v-if="showCreateNovel" class="sb-create-form">
          <input v-model="newNovelTitle" placeholder="作品名称" @keydown.enter="createNovel" />
          <button @click="createNovel">创建</button>
        </div>
      </div>

      <div class="sb-footer">
        <span>👤 {{ auth.user?.username }}</span>
        <button @click="auth.logout().then(() => router.push('/login'))">退出</button>
      </div>
      </template>
      <div v-if="sidebarCollapsed" class="sb-collapsed-icons">
        <div class="sb-ci-item" :class="{ active: !selectedNovel }" @click="openNormalChat()" title="对话">💬</div>
        <div class="sb-ci-item" title="作品" @click="page = 'books'; sidebarCollapsed = false">📚</div>
        <div class="sb-ci-item" title="续写" @click="router.push('/continue')">✍️</div>
        <div v-for="n in novels" :key="n.id" class="sb-ci-item" :class="{ active: selectedNovel?.id === n.id }" @click="openRagChat(n)" :title="n.title">📘</div>
      </div>
    </aside>

    <!-- ====== 搜索弹窗 ====== -->
    <Teleport to="body">
      <div v-if="showSearch" class="search-overlay" @click.self="showSearch = false; searchQuery = ''; searchResults = []">
        <div class="search-modal" @click.stop>
          <div class="sm-bar">
            <span class="sm-icon">🔍</span>
            <input ref="searchInputEl" v-model="searchQuery" class="sm-input" placeholder="搜索对话标题或内容..."
              @input="searchConversations" />
            <span class="sm-close" @click="showSearch = false; searchQuery = ''; searchResults = []">✕</span>
          </div>
          <div class="sm-results" v-if="searchQuery">
            <div v-for="c in searchResults" :key="c.conversationId"
              class="sm-result" @click="openConversation({id: c.conversationId}); showSearch = false; searchQuery = ''; searchResults = []">
              <div class="sm-title">{{ c.title }}</div>
              <div class="sm-snippet">{{ c.snippet }}</div>
            </div>
            <div v-if="searchResults.length === 0" class="sm-empty">未找到匹配的对话</div>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- ====== 主区域 ====== -->
    <main class="main">
      <!-- 阅读器 -->
      <template v-if="readingChapter">
        <div class="topbar">
          <button class="tb-back" @click="closeReader">← 返回对话</button>
          <span>{{ readingChapter.novel.title }} / 第{{ readingChapter.chapter.chapterNum }}章</span>
        </div>
        <div class="reader">
          <h2>第{{ readingChapter.chapter.chapterNum }}章 {{ readingChapter.chapter.title }}</h2>
          <div class="reader-text">{{ chapterText }}</div>
        </div>
      </template>

      <!-- 聊天 -->
      <template v-else>
        <div class="topbar">
          <span class="tb-title">{{ selectedNovel ? selectedNovel.title : 'AI 写作助手' }}</span>
          <span v-if="selectedChapterId" class="tb-chapter">· 第{{ chapters.find(c => c.id === selectedChapterId)?.chapterNum }}章</span>
          <span class="tb-badge rag" v-if="selectedNovel">RAG</span>
          <span class="tb-badge chap" v-if="selectedChapterId">单章</span>
          <span class="tb-badge" v-if="!selectedNovel">通用</span>
        </div>
        <div class="chat-box" ref="chatBox">
          <div v-if="messages.length === 0" class="welcome">
            <div class="w-icon">✨</div>
            <div class="w-title">开始写作</div>
            <div class="w-sub" v-if="selectedNovel">基于「{{ selectedNovel.title }}」提问，AI 检索小说内容回答</div>
            <div class="w-sub" v-else>左侧选择作品进入 RAG 模式，或直接开始对话</div>
          </div>
          <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
            <div class="msg-tag">{{ m.role === 'user' ? '你' : m.role === 'system' ? '系统' : 'AI' }}</div>
            <div class="msg-bubble" v-if="m.role === 'assistant'" v-html="renderMd(m.content) || '...'"></div>
            <div class="msg-bubble" v-else>{{ m.content || (streaming && i === messages.length - 1 ? '...' : '') }}</div>
          </div>
        </div>
        <div class="input-bar">
          <div class="input-wrap">
            <textarea v-model="input" @keydown="onKeydown" @input="autoResize" :disabled="streaming"
              :placeholder="selectedNovel ? '基于作品提问...' : '输入问题或续写需求...'"
              rows="2" ref="inputEl"
            ></textarea>
            <div class="input-actions">
              <span class="search-picker" @click.stop="showSearchMenu = !showSearchMenu">
                <span class="search-toggle" :class="{ active: searchSites.length > 0 }">
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y2="11"/></svg>
                  <span class="search-label">联网</span>
                </span>
                <div class="search-drop" v-if="showSearchMenu">
                  <label v-for="s in SEARCH_SITES" :key="s.key" class="search-site-opt" @click.stop="toggleSite(s.key)">
                    <input type="checkbox" :checked="searchSites.includes(s.key)" />
                    <span>{{ s.label }}</span>
                  </label>
                </div>
              </span>
              <div class="input-spacer"></div>
              <button class="send-btn" @click="send" :disabled="streaming || !input.trim()">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></svg>
              </button>
            </div>
          </div>
        </div>
      </template>
    </main>
  </div>

  <!-- 对话菜单下拉（Teleport 到 body，不被侧边栏裁剪） -->
  <Teleport to="body">
    <div v-if="menuConvId" class="conv-menu-portal"
      :style="{ top: (menuAnchorEl?.getBoundingClientRect().bottom ?? 0) + 4 + 'px', left: (menuAnchorEl?.getBoundingClientRect().left ?? 0) + 'px' }">
      <div @click="renameConv(menuConvId!)" class="conv-menu-item">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
        <span>重命名</span>
      </div>
      <div @click="delConv(menuConvId!)" class="conv-menu-item danger">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>
        <span>删除</span>
      </div>
    </div>
    <div v-if="menuConvId" class="conv-menu-mask" @click="menuConvId = null"></div>

    <!-- 作品操作菜单 -->
    <div v-if="novelMenuId" class="conv-menu-portal"
      :style="{ top: (novelMenuEl?.getBoundingClientRect().bottom ?? 0) + 4 + 'px', left: (novelMenuEl?.getBoundingClientRect().left ?? 0) + 'px' }">
      <label class="conv-menu-item">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
        <span>上传章节</span>
        <span class="conv-menu-hint">.txt .md .docx</span>
        <input type="file" accept="text/plain,.txt,.md,application/vnd.openxmlformats-officedocument.wordprocessingml.document,.docx" multiple hidden @change="uploadFiles(novelMenuId, $event); novelMenuId = null" />
      </label>
      <div class="conv-menu-item" @click="openRagChat(novels.find(n => n.id === novelMenuId)!); novelMenuId = null">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
        <span>基于作品对话</span>
      </div>
      <div class="conv-menu-sep"></div>
      <div class="conv-menu-item" @click="buildPlot(novelMenuId); novelMenuId = null">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
        <span>分析剧情结构</span>
      </div>
      <div class="conv-menu-item" @click="buildGraph(novelMenuId); novelMenuId = null">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
        <span>分析人物关系</span>
      </div>
      <div class="conv-menu-sep"></div>
      <div class="conv-menu-item" @click="downloadExport(`/novels/${novelMenuId}/export/plot`, `${novels.find(n => n.id === novelMenuId)?.title}_剧情脉络.md`); novelMenuId = null">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        <span>导出剧情脉络</span>
      </div>
      <div class="conv-menu-item" @click="downloadExport(`/novels/${novelMenuId}/export/chars`, `${novels.find(n => n.id === novelMenuId)?.title}_人物关系.md`); novelMenuId = null">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        <span>导出人物关系</span>
      </div>
      <div class="conv-menu-sep"></div>
      <div class="conv-menu-item danger" @click="deleteNovel(novelMenuId); novelMenuId = null">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>
        <span>删除作品</span>
      </div>
    </div>
    <div v-if="novelMenuId" class="conv-menu-mask" @click="novelMenuId = null"></div>
  </Teleport>

  <!-- 自定义弹窗 -->
  <Teleport to="body">
    <div v-if="modal.show" class="modal-overlay" @click.self="modalCancel">
      <div class="modal-box">
        <div class="modal-title">{{ modal.title }}</div>
        <div class="modal-msg">{{ modal.message }}</div>
        <input v-if="modal.type === 'prompt'" v-model="modal.input" class="modal-input"
          placeholder="输入新名称..." @keydown.enter="modalOk" ref="modalInputEl" />
        <div class="modal-btns">
          <button class="modal-btn cancel" @click="modalCancel">取消</button>
          <button class="modal-btn ok" @click="modalOk"
            :disabled="modal.type === 'prompt' && !modal.input.trim()">确定</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.app { display: flex; height: 100vh; }

/* ====== 侧边栏 ====== */
.sidebar {
  width: 260px; background: #fafbfc; border-right: 1px solid #e8ecf0;
  display: flex; flex-direction: column; flex-shrink: 0;
  font-size: 14px; color: #333; transition: width 0.2s;
}
.sidebar.collapsed { width: 56px; }
.sb-head {
  display: flex; align-items: center; gap: 10px; padding: 14px 12px;
  cursor: pointer; user-select: none;
}
.sidebar.collapsed .sb-head { justify-content: center; padding: 14px 0; }
.sb-head-actions { display: flex; align-items: center; gap: 2px; margin-left: auto; }
.sb-head-icon {
  width: 32px; height: 32px; display: flex; align-items: center; justify-content: center;
  border-radius: 8px; cursor: pointer; font-size: 15px; transition: background 0.15s;
}
.sb-head-icon:hover { background: #eef1f5; }
.sb-collapse-btn {
  background: none; border: none; cursor: pointer;
  padding: 6px; border-radius: 8px; display: flex; align-items: center;
  transition: background 0.15s;
}
.sb-collapse-btn:hover { background: #eef1f5; }
.sidebar.collapsed .sb-collapse-btn { margin-left: 0; }
.sb-collapse-icon {
  display: flex; width: 18px; height: 18px; border: 2px solid #999;
  border-radius: 4px; align-items: center; justify-content: center;
  transition: border-color 0.15s;
}
.sb-collapse-btn:hover .sb-collapse-icon { border-color: #555; }
.sb-collapse-bar {
  width: 2px; height: 10px; background: #999; border-radius: 1px;
  margin-left: -6px; transition: background 0.15s;
}
.sb-collapse-btn:hover .sb-collapse-bar { background: #555; }

.sb-collapsed-icons {
  flex: 1; display: flex; flex-direction: column; align-items: center;
  padding: 8px 0; gap: 4px;
}
.sb-ci-item {
  width: 40px; height: 40px; display: flex; align-items: center; justify-content: center;
  border-radius: 10px; cursor: pointer; font-size: 20px; transition: background 0.15s;
}
.sb-ci-item:hover { background: #eef1f5; }
.sb-ci-item.active { background: #eef2ff; }
.sb-logo { font-size: 22px; }
.sb-title { font-size: 17px; font-weight: 700; }

.sb-tabs { display: flex; padding: 0 12px 10px; gap: 4px; border-bottom: 1px solid #e8ecf0; margin-bottom: 4px; }
.sb-tab {
  flex: 1; text-align: center; padding: 8px 0; font-size: 13px; font-weight: 500;
  color: #888; border-radius: 8px; cursor: pointer; transition: all 0.15s;
}
.sb-tab:hover { background: #eef1f5; color: #555; }
.sb-tab.active { background: #fff; color: #4f46e5; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }

.sb-list { flex: 1; overflow-y: auto; padding: 8px 12px; }
.sb-label { font-size: 11px; font-weight: 600; color: #aaa; text-transform: uppercase; letter-spacing: 0.5px; padding: 8px 8px 6px; }

/* 对话页 — 作品列表 */
.sb-book {
  display: flex; align-items: center; gap: 10px; padding: 11px 14px;
  border-radius: 10px; cursor: pointer; transition: background 0.15s;
}
.sb-book:hover { background: #eef1f5; }
.sb-book.active { background: #eef2ff; }
.sb-book-icon { font-size: 18px; flex-shrink: 0; }
.sb-book-name { flex: 1; font-weight: 500; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sb-book-badge { color: #4f46e5; font-size: 8px; flex-shrink: 0; }
.sb-book-actions { display: flex; gap: 2px; opacity: 0; transition: opacity 0.15s; flex-shrink: 0; }
.sb-book:hover .sb-book-actions { opacity: 1; }
.sb-book-act { font-size: 15px; cursor: pointer; padding: 3px 6px; border-radius: 6px; }
.sb-book-act:hover { background: #eef2ff; }

/* 作品管理页 */
.sb-novel { margin-bottom: 2px; }
.sb-novel-row {
  display: flex; align-items: center; gap: 6px; padding: 11px 12px;
  border-radius: 10px; cursor: pointer; transition: background 0.15s; user-select: none;
}
.sb-novel-row:hover { background: #eef1f5; }
.sb-chevron {
  flex-shrink: 0; color: #999; transition: transform 0.2s;
}
.sb-chevron.open { transform: rotate(90deg); }
.sb-novel-name { flex: 1; font-weight: 500; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sb-novel-meta { font-size: 12px; color: #aaa; flex-shrink: 0; }
.sb-novel-menu-wrap { position: relative; flex-shrink: 0; }
.sb-novel-dots {
  width: 28px; height: 28px; display: flex; align-items: center; justify-content: center;
  cursor: pointer; border-radius: 6px; color: #aaa; opacity: 0; transition: all 0.15s;
}
.sb-novel-row:hover .sb-novel-dots { opacity: 1; }
.sb-novel-dots:hover { background: #e8ecf2; color: #555; }

.sb-novel-children { padding-left: 28px; margin: 2px 0 6px; display: flex; flex-direction: column; max-height: 360px; }
.sb-chapters-scroll { flex: 1; overflow-y: auto; }
.sb-chapter {
  display: flex; align-items: center; gap: 6px; padding: 7px 8px 7px 0;
  border-radius: 6px; cursor: pointer; font-size: 13px; color: #666;
  transition: all 0.15s;
}
.sb-chapter:hover { color: #333; background: #f0f2f5; }
.sb-ch-num { color: #4f46e5; font-weight: 600; font-size: 11px; flex-shrink: 0; min-width: 24px; text-align: right; }
.sb-ch-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.sb-ch-chars { font-size: 10px; color: #bbb; flex-shrink: 0; }
.sb-ch-chat { font-size: 12px; cursor: pointer; flex-shrink: 0; padding: 2px 4px; border-radius: 5px; opacity: 0; transition: opacity 0.15s; }
.sb-chapter:hover .sb-ch-chat { opacity: 1; }
.sb-ch-chat:hover { background: #eef2ff; }

.sb-ch-del { cursor: pointer; flex-shrink: 0; padding: 2px 4px; border-radius: 5px; opacity: 0; transition: opacity 0.15s; color: #ccc; display: flex; align-items: center; }
.sb-chapter:hover .sb-ch-del { opacity: 1; }
.sb-ch-del:hover { background: #fef2f2; color: #e55; }

.sb-novel-actions { display: flex; gap: 8px; padding: 6px 0 4px; flex-shrink: 0; }
.sb-progress {
  padding: 6px 8px; font-size: 12px; color: #4f46e5; background: #eef2ff;
  border-radius: 6px; text-align: center; margin-bottom: 4px; animation: pulse 1.5s infinite;
}
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.6; } }

.sb-empty, .sb-empty-sm { color: #bbb; font-size: 13px; text-align: center; padding: 16px 0; }

.sb-new-chat {
  padding: 8px 14px; border: 1px dashed #d0d0d0; border-radius: 8px;
  cursor: pointer; font-size: 13px; color: #888; text-align: center; margin-bottom: 8px;
  transition: all 0.15s;
}
.sb-new-chat:hover { border-color: #4f46e5; color: #4f46e5; }

.sb-conv-list { max-height: 240px; overflow-y: auto; }
.sb-conv-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 9px 14px; border-radius: 8px; cursor: pointer; font-size: 13px;
  transition: background 0.15s;
}
.sb-conv-item:hover { background: #eef1f5; }
.sb-conv-item.active { background: #eef2ff; }
.sb-conv-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #333; }
.sb-conv-date { font-size: 11px; color: #bbb; flex-shrink: 0; margin-left: 8px; }
.sb-conv-menu { position: relative; flex-shrink: 0; }
.sb-conv-dots {
  width: 28px; height: 28px; display: flex; align-items: center; justify-content: center;
  cursor: pointer; border-radius: 6px; color: #aaa; opacity: 0; transition: all 0.15s;
}
.sb-conv-item:hover .sb-conv-dots { opacity: 1; }
.sb-conv-dots:hover { background: #e8ecf2; color: #555; }

.conv-menu-portal {
  position: fixed; z-index: 200;
  background: #fff; border: 1px solid #e8ecf0; border-radius: 10px;
  box-shadow: 0 4px 16px rgba(0,0,0,0.12); padding: 4px; min-width: 120px;
}
.conv-menu-item {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; font-size: 13px; cursor: pointer; border-radius: 6px;
  transition: background 0.1s; white-space: nowrap; color: #555;
}
.conv-menu-item:hover { background: #f5f5f5; }
.conv-menu-item.danger { color: #e55; }
.conv-menu-item.danger:hover { background: #fef2f2; }
.conv-menu-hint { font-size: 10px; color: #bbb; margin-left: auto; }
.conv-menu-sep { height: 1px; background: #e8ecf0; margin: 4px 8px; }
.conv-menu-mask { position: fixed; inset: 0; z-index: 199; }

.sb-create {
  width: 100%; padding: 10px; background: none; border: 2px dashed #ddd;
  color: #999; border-radius: 10px; cursor: pointer; font-size: 14px; font-weight: 500;
  margin-top: 6px; transition: all 0.15s;
}
.sb-create:hover { border-color: #4f46e5; color: #4f46e5; }
.sb-create-form { display: flex; gap: 6px; margin-top: 8px; }
.sb-create-form input { flex: 1; padding: 8px 10px; border: 1px solid #ddd; border-radius: 8px; font-size: 13px; outline: none; }
.sb-create-form input:focus { border-color: #4f46e5; }
.sb-create-form button { padding: 8px 14px; background: #4f46e5; color: #fff; border: none; border-radius: 8px; cursor: pointer; font-size: 13px; }

.sb-footer {
  padding: 12px 16px; border-top: 1px solid #e8ecf0;
  display: flex; align-items: center; gap: 8px; font-size: 13px; color: #888;
}
.sb-footer span { flex: 1; }
.sb-footer button { padding: 4px 10px; background: none; border: 1px solid #ddd; color: #999; border-radius: 6px; cursor: pointer; font-size: 12px; }
.sb-footer button:hover { color: #e55; border-color: #e55; }

/* ====== 主区域 ====== */
.main { flex: 1; display: flex; flex-direction: column; background: #fff; min-width: 0; }

.topbar {
  display: flex; align-items: center; gap: 10px; padding: 14px 32px;
  border-bottom: 1px solid #eef0f3; font-size: 14px; color: #666; flex-shrink: 0;
}
.tb-back { background: none; border: none; color: #4f46e5; cursor: pointer; font-size: 14px; font-weight: 500; }
.tb-title { font-weight: 600; color: #333; }
.tb-badge { font-size: 11px; padding: 2px 10px; border-radius: 8px; background: #f0f0f0; color: #888; }
.tb-badge.rag { background: #eef2ff; color: #4f46e5; }
.tb-badge.chap { background: #fef3c7; color: #d97706; }
.tb-chapter { font-size: 13px; color: #d97706; }

/* 阅读器 */
.reader { flex: 1; overflow-y: auto; padding: 40px 64px; max-width: 860px; margin: 0 auto; width: 100%; }
.reader h2 { font-size: 24px; font-weight: 700; color: #222; margin-bottom: 32px; text-align: center; }
.reader-text { line-height: 2.2; font-size: 16px; color: #444; white-space: pre-wrap; }

/* 聊天 */
.chat-box { flex: 1; overflow-y: auto; padding: 24px 40px 0; }
.welcome { text-align: center; padding: 80px 0; }
.w-icon { font-size: 48px; margin-bottom: 12px; }
.w-title { font-size: 20px; font-weight: 700; color: #333; margin-bottom: 8px; }
.w-sub { font-size: 14px; color: #999; max-width: 420px; margin: 0 auto; line-height: 1.6; }

.msg { margin-bottom: 24px; }
.msg.user { display: flex; flex-direction: column; align-items: flex-end; }
.msg.assistant { display: flex; flex-direction: column; align-items: flex-start; }
.msg.system { display: flex; flex-direction: column; align-items: flex-start; }
.msg-tag { font-size: 11px; color: #bbb; margin-bottom: 4px; padding: 0 2px; }
.msg-bubble { line-height: 1.7; font-size: 15px; max-width: 80%; padding: 12px 18px; border-radius: 16px; white-space: pre-wrap; word-break: break-word; }
.msg.user .msg-bubble { background: #4f46e5; color: #fff; border-bottom-right-radius: 6px; }
.msg.assistant .msg-bubble { background: #f4f5f7; color: #333; border-bottom-left-radius: 6px; }
.msg.system .msg-bubble { background: #fff0f0; color: #d33; font-size: 13px; padding: 8px 14px; border-radius: 8px; }
/* Markdown rendered content */
.msg-bubble h2 { font-size: 17px; margin: 12px 0 6px; }
.msg-bubble h3 { font-size: 15px; margin: 10px 0 4px; }
.msg-bubble h4 { font-size: 14px; margin: 8px 0 2px; }
.msg-bubble strong { font-weight: 600; }
.msg-bubble ul { margin: 4px 0; padding-left: 18px; }
.msg-bubble li { margin: 2px 0; line-height: 1.6; }
.msg-bubble p { margin: 4px 0; }
.msg.assistant .msg-bubble h2,
.msg.assistant .msg-bubble h3,
.msg.assistant .msg-bubble h4 { color: #222; }
.msg.user .msg-bubble strong { color: #fff; }

.input-bar { display: flex; padding: 16px 40px 24px; flex-shrink: 0; }
.input-wrap {
  flex: 1; display: flex; flex-direction: column;
  padding: 12px 12px 10px 16px;
  background: #f7f8fa; border: 1px solid #e0e3e8;
  border-radius: 20px; transition: border-color 0.15s, box-shadow 0.15s;
}
.input-wrap:focus-within { border-color: #4f46e5; box-shadow: 0 1px 8px rgba(79,70,229,0.12); background: #fff; }
.input-wrap textarea {
  flex: 1; padding: 0; background: transparent; border: none; resize: none;
  font-size: 15px; line-height: 1.6; outline: none; color: #333;
  font-family: inherit; min-height: 48px;
}
.input-wrap textarea::placeholder { color: #bbb; }

.input-actions {
  display: flex; align-items: center; margin-top: 6px; gap: 8px;
}
.input-spacer { flex: 1; }

.send-btn {
  width: 34px; height: 34px; display: flex; align-items: center; justify-content: center;
  background: #4f46e5; color: #fff; border: none; border-radius: 50%;
  cursor: pointer; flex-shrink: 0; transition: background 0.15s, transform 0.15s;
}
.send-btn:hover { background: #4338ca; transform: scale(1.05); }
.send-btn:disabled { opacity: 0.3; cursor: default; transform: none; }
.search-toggle {
  display: flex; align-items: center; gap: 4px; padding: 8px 12px;
  border: 1px solid #e0e3e8; border-radius: 10px; cursor: pointer;
  font-size: 12px; color: #999; background: #f7f8fa; user-select: none;
  transition: all 0.15s; flex-shrink: 0;
}
.search-toggle:hover { border-color: #4f46e5; color: #4f46e5; }
.search-toggle.active { background: #eef2ff; border-color: #4f46e5; color: #4f46e5; }
.search-label { font-weight: 500; }
.search-picker { position: relative; flex-shrink: 0; }
.search-drop {
  position: absolute; bottom: calc(100% + 6px); left: 0; z-index: 50;
  background: #fff; border: 1px solid #e0e3e8; border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0,0,0,0.1); padding: 6px; min-width: 140px;
}
.search-site-opt {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  border-radius: 8px; cursor: pointer; font-size: 13px; color: #333;
  transition: background 0.12s; user-select: none;
}
.search-site-opt:hover { background: #f5f5ff; }
.search-site-opt input[type="checkbox"] { accent-color: #4f46e5; width: 15px; height: 15px; margin: 0; }
.search-site-opt span { line-height: 1; }

/* 搜索弹窗 */
.search-overlay {
  position: fixed; inset: 0; z-index: 100; background: rgba(0,0,0,0.35);
  display: flex; justify-content: center; padding-top: 15vh;
}
.search-modal {
  width: 560px; max-width: 90vw; max-height: 60vh;
  background: #fff; border-radius: 16px; box-shadow: 0 20px 60px rgba(0,0,0,0.15);
  display: flex; flex-direction: column; overflow: hidden;
}
.sm-bar {
  display: flex; align-items: center; gap: 10px;
  padding: 16px 20px; border-bottom: 1px solid #eee;
}
.sm-icon { font-size: 18px; flex-shrink: 0; }
.sm-input { flex: 1; border: none; outline: none; font-size: 16px; background: transparent; }
.sm-close {
  font-size: 18px; color: #bbb; cursor: pointer; padding: 4px 8px;
  border-radius: 6px; flex-shrink: 0;
}
.sm-close:hover { background: #f0f0f0; color: #666; }
.sm-results { flex: 1; overflow-y: auto; padding: 8px; }
.sm-result {
  padding: 12px 16px; border-radius: 10px; cursor: pointer; transition: background 0.1s;
}
.sm-result:hover { background: #f5f5f5; }
.sm-title { font-size: 15px; font-weight: 500; color: #333; margin-bottom: 4px; }
.sm-snippet { font-size: 13px; color: #999; line-height: 1.4; }
.sm-empty { text-align: center; color: #bbb; padding: 32px; font-size: 14px; }

/* 自定义弹窗 */
.modal-overlay {
  position: fixed; inset: 0; z-index: 300;
  background: rgba(0,0,0,0.4); display: flex;
  align-items: center; justify-content: center;
}
.modal-box {
  background: #fff; border-radius: 16px; box-shadow: 0 20px 60px rgba(0,0,0,0.18);
  padding: 28px 32px 20px; width: 400px; max-width: 90vw;
}
.modal-title { font-size: 17px; font-weight: 600; color: #222; margin-bottom: 8px; }
.modal-msg { font-size: 14px; color: #666; line-height: 1.6; margin-bottom: 20px; }
.modal-input {
  width: 100%; padding: 10px 14px; border: 1px solid #ddd; border-radius: 10px;
  font-size: 14px; outline: none; margin-bottom: 20px; box-sizing: border-box;
}
.modal-input:focus { border-color: #4f46e5; box-shadow: 0 0 0 3px rgba(79,70,229,0.1); }
.modal-btns { display: flex; justify-content: flex-end; gap: 10px; }
.modal-btn {
  padding: 8px 22px; border-radius: 8px; font-size: 14px; font-weight: 500;
  cursor: pointer; border: none; transition: all 0.15s;
}
.modal-btn.cancel { background: #f0f1f3; color: #666; }
.modal-btn.cancel:hover { background: #e4e5e8; }
.modal-btn.ok { background: #4f46e5; color: #fff; }
.modal-btn.ok:hover { background: #4338ca; }
.modal-btn.ok:disabled { opacity: 0.4; cursor: default; }
</style>
