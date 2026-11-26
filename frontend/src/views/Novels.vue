<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'

interface Novel { id: string; title: string; author: string; description: string; chapterCount: number }
interface Chapter { id: string; chapterNum: number; title: string; charCount: number }

const router = useRouter()
const novels = ref<Novel[]>([])
const chapters = ref<Chapter[]>([])
const selectedNovel = ref<string | null>(null)
const uploading = ref(false)

onMounted(async () => { await loadNovels() })

async function loadNovels() {
  const { data } = await api.get('/novels')
  novels.value = data.data
}

const newTitle = ref('')
const newAuthor = ref('')
const newDesc = ref('')
async function createNovel() {
  await api.post('/novels', { title: newTitle.value, author: newAuthor.value, description: newDesc.value })
  newTitle.value = newAuthor.value = newDesc.value = ''
  await loadNovels()
}

async function onNovelClick(n: Novel) {
  selectedNovel.value = n.id
  const { data } = await api.get(`/novels/${n.id}/chapters`)
  chapters.value = data.data
}

async function onUpload(file: File) {
  if (!selectedNovel.value) return
  uploading.value = true
  const fd = new FormData()
  fd.append('file', file)
  try {
    const { data } = await api.post(`/novels/${selectedNovel.value}/chapters`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    console.log('Upload result:', data.data)
    await onNovelClick({ id: selectedNovel.value } as Novel)
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div>
    <!-- 创建小说 -->
    <div class="create-bar">
      <input v-model="newTitle" placeholder="小说名" />
      <input v-model="newAuthor" placeholder="作者（选填）" />
      <input v-model="newDesc" placeholder="简介（选填）" />
      <button @click="createNovel">+ 创建小说</button>
    </div>

    <div class="layout">
      <!-- 小说列表 -->
      <div class="novel-list">
        <h2>我的小说</h2>
        <div v-for="n in novels" :key="n.id" class="novel-card" :class="{ active: selectedNovel === n.id }" @click="onNovelClick(n)">
          <h3>{{ n.title }}</h3>
          <p>{{ n.chapterCount }} 章 · {{ n.author || '未署名' }}</p>
          <div class="actions">
            <button class="sm" @click.stop="router.push(`/novels/${n.id}/chat`)">💬 对话</button>
            <button class="sm" @click.stop="router.push(`/novels/${n.id}/graph`)">🔗 图谱</button>
          </div>
        </div>
        <p v-if="novels.length === 0" class="empty">还没有小说，创建一部吧</p>
      </div>

      <!-- 章节管理 -->
      <div class="chapter-panel" v-if="selectedNovel">
        <h2>章节</h2>
        <label class="upload-btn">
          📤 {{ uploading ? '上传中...' : '上传章节 (.txt/.md/.docx)' }}
          <input type="file" accept=".txt,.md,.docx" @change="(e:any) => onUpload(e.target.files?.[0])" hidden />
        </label>
        <div class="chapter-list">
          <div v-for="ch in chapters" :key="ch.id" class="ch-item">
            <span>第{{ ch.chapterNum }}章 {{ ch.title }}</span>
            <span class="chars">{{ ch.charCount }} 字</span>
          </div>
          <p v-if="chapters.length === 0" class="empty">还没有章节</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.create-bar { display: flex; gap: 8px; margin-bottom: 24px; }
.create-bar input { flex: 1; padding: 8px; background: #222; border: 1px solid #444; border-radius: 6px; color: #e0e0e0; }
.create-bar button { background: #7c8cf8; color: #fff; border: none; padding: 8px 16px; border-radius: 6px; cursor: pointer; white-space: nowrap; }
.layout { display: flex; gap: 24px; }
.novel-list { flex: 1; }
.novel-card { background: #1a1a2e; padding: 16px; border-radius: 8px; margin-bottom: 8px; cursor: pointer; border: 1px solid transparent; }
.novel-card.active { border-color: #7c8cf8; }
.novel-card h3 { margin: 0 0 4px; }
.novel-card p { color: #888; font-size: 14px; margin: 0 0 8px; }
.actions { display: flex; gap: 8px; }
button.sm { padding: 4px 12px; font-size: 13px; }
.chapter-panel { flex: 1; }
.upload-btn { display: inline-block; background: #2a2a4e; color: #7c8cf8; padding: 8px 16px; border-radius: 6px; cursor: pointer; margin-bottom: 12px; border: 1px dashed #7c8cf8; }
.chapter-list { max-height: 500px; overflow-y: auto; }
.ch-item { display: flex; justify-content: space-between; padding: 8px; border-bottom: 1px solid #222; }
.chars { color: #888; font-size: 13px; }
.empty { color: #666; font-style: italic; }
</style>
