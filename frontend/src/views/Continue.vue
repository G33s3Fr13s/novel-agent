<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, nextTick, computed, watch } from 'vue'
import api from '../api'

interface Novel { id: string; title: string; chapterCount: number }
interface Chapter { id: string; chapterNum: number; title: string; charCount: number }

const novels = ref<Novel[]>([])
const selectedNovelId = ref<string | null>(null)
const chapters = ref<Chapter[]>([])
const selectedChapterIds = ref<Set<string>>(new Set())
const chapterCount = ref(1)
const foreword = ref('')
const afterword = ref('')
const prompt = ref('')
const output = ref('')
const generating = ref(false)
const genMsg = ref('')
// 联网搜索站点选择
const SEARCH_SITES = [
  { key: 'qidian', label: '起点中文网' },
  { key: 'fanqie', label: '番茄小说' },
  { key: 'qimao', label: '七猫小说' },
  { key: 'jjwxc', label: '晋江文学城' },
  { key: 'web', label: '全网搜索' },
]
const searchSites = ref<string[]>([])
const searchHint = ref('')
const showSearchMenu = ref(false)

function toggleSite(key: string) {
  const idx = searchSites.value.indexOf(key)
  if (idx >= 0) searchSites.value.splice(idx, 1)
  else searchSites.value.push(key)
}

function closeSearchMenu(e: MouseEvent) {
  const el = (e.target as HTMLElement).closest('.ctn-search-picker')
  if (!el) showSearchMenu.value = false
}

const expandedChapters = ref<number[]>([])
const previewExpanded = ref<number[]>([])
const showConfig = ref(false)
const showPreviewConfig = ref(false)
let abortController: AbortController | null = null

onBeforeUnmount(() => {
  clearPolling()
  document.removeEventListener('click', closeSearchMenu)
  if (abortController) {
    abortController.abort()
    abortController = null
  }
})

// 作业记录
interface ContinueJob { id: string; novelTitle: string; chapterCount: number; chapterIds: string; chapterTitles: string; foreword: string; afterword: string; prompt: string; resultLength: number; resultPreview: string; status: string; createdAt: string }
interface ContinueJobFull extends ContinueJob { novelId: string; chapterIds: string; chapterTitles: string; result: string; status: string; searchHint?: string; searchSites?: string }
const viewingJob = ref<ContinueJobFull | null>(null)
const previewModal = ref<ContinueJobFull | null>(null)

interface ParsedChapter { chapterNum: number; title: string; content: string }

function parseChapters(text: string): ParsedChapter[] {
  if (!text) return []
  const chapters: ParsedChapter[] = []
  const regex = /^\s*(?:#+\s*)?第(\d+)章\s+(.+)$/gm
  let lastIndex = 0
  let match: RegExpExecArray | null
  let lastChapter: ParsedChapter | null = null
  while ((match = regex.exec(text)) !== null) {
    if (lastChapter) {
      lastChapter.content = text.slice(lastIndex, match.index).trim()
    }
    lastChapter = { chapterNum: parseInt(match[1]), title: match[2].trim(), content: '' }
    chapters.push(lastChapter)
    lastIndex = match.index + match[0].length
  }
  if (lastChapter) {
    lastChapter.content = text.slice(lastIndex).trim()
  }
  return chapters
}

const displayChapters = computed(() => parseChapters(viewingJob.value ? viewingJob.value.result : output.value))

const startChapterNum = computed(() => {
  const pool = selectedChapterIds.value.size > 0
    ? chapters.value.filter(c => selectedChapterIds.value.has(c.id))
    : chapters.value
  return pool.length > 0 ? Math.max(...pool.map(c => c.chapterNum)) + 1 : 1
})
const previewChapters = computed(() => previewModal.value ? parseChapters(previewModal.value.result) : [])

watch(() => viewingJob.value?.id, () => { showConfig.value = false; expandedChapters.value = [] })
watch(() => previewModal.value?.id, () => { showPreviewConfig.value = false; previewExpanded.value = [] })

function fmtTime(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  return d.toLocaleString('zh-CN', { month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', second:'2-digit' })
}

function toggleCard(num: number, arr: number[]) {
  if (!arr) return
  const idx = arr.indexOf(num)
  if (idx >= 0) { arr.splice(idx, 1) } else { arr.push(num) }
}

const jobs = ref<ContinueJob[]>([])
const previewOpen = ref(false)
const jobPage = ref(0)
const jobHasMore = ref(false)
const activeJobId = ref<string | null>(null)
const activeJobStatus = ref('')
const streamingJobId = ref<string | null>(null)
const outputTruncated = ref(false)
const outputActualChapters = ref(0)

// 流式生成时实时统计已完成章节数
const generatedCount = computed(() => {
  if (!output.value) return 0
  return parseChapters(output.value).filter(ch => ch.content.length > 100).length
})

const allSelected = computed(() =>
  chapters.value.length > 0 && selectedChapterIds.value.size === chapters.value.length
)

onMounted(async () => {
  document.addEventListener('click', closeSearchMenu)
  try { const { data } = await api.get('/novels'); novels.value = data.data } catch {}
  loadJobs(true)
  checkActiveJob()
})

async function loadJobs(reset = false) {
  if (reset) { jobPage.value = 0; jobs.value = [] }
  try {
    const { data } = await api.get('/continue/jobs', { params: { page: jobPage.value, size: 20 } })
    const pageData = data.data
    if (reset) jobs.value = pageData.items
    else jobs.value.push(...pageData.items)
    jobHasMore.value = pageData.hasMore
    jobPage.value++
  } catch (e) {
    console.error('加载作业记录失败:', e)
  }
}

let pollTimer: ReturnType<typeof setInterval> | null = null

async function checkActiveJob() {
  if (!selectedNovelId.value) { clearPolling(); return }
  try {
    const { data } = await api.get('/continue/active', { params: { novelId: selectedNovelId.value } })
    const job = data.data
    if (job && (job.status === 'GENERATING' || job.status === 'PARTIAL' || job.status === 'PENDING')) {
      activeJobId.value = job.id
      activeJobStatus.value = job.status
      chapterCount.value = job.chapterCount
      // 启动轮询
      if (!pollTimer) {
        pollTimer = setInterval(pollActiveJob, 5000)
      }
    } else {
      // 如果有 job 但不是 GENERATING（可能刚完成），自动加载
      clearPolling()
      if (job && !activeJobId.value) {
        // 刚检测到完成 → 加载结果
        viewJob(job.id)
      }
      activeJobId.value = null
    }
  } catch { clearPolling() }
}

async function pollActiveJob() {
  if (!selectedNovelId.value || !activeJobId.value) { clearPolling(); return }
  try {
    const { data } = await api.get('/continue/active', { params: { novelId: selectedNovelId.value } })
    const job = data.data
    if (!job || (job.status !== 'GENERATING' && job.status !== 'PARTIAL' && job.status !== 'PENDING')) {
      clearPolling()
      activeJobId.value = null
      activeJobStatus.value = ''
      loadJobs(true)
      if (job && job.status === 'COMPLETED') {
        viewJob(job.id)
      }
    } else {
      activeJobStatus.value = job.status
    }
  } catch { clearPolling() }
}

async function doResume() {
  if (!activeJobId.value) return
  const token = localStorage.getItem('accessToken')
  if (!token) return
  try {
    await api.post(`/continue/resume/${activeJobId.value}`)
    // 重新订阅 SSE
    activeJobStatus.value = 'GENERATING'
    output.value = ''
    generating.value = true
    genMsg.value = '正在继续生成...'
    abortController = new AbortController()

    const resp = await fetch(`/api/continue/stream/${activeJobId.value}`, {
      headers: { Authorization: `Bearer ${token}` },
      signal: abortController.signal,
    })
    const reader = resp.body?.getReader()
    if (!reader) throw new Error('无法读取响应流')
    const decoder = new TextDecoder(); let ev = ''
    let firstToken = true
    streamingJobId.value = activeJobId.value

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const chunk = decoder.decode(value, { stream: true })
      for (const line of chunk.split('\n')) {
        if (line.startsWith('event:')) ev = line.slice(6).trim()
        else if (line.startsWith('data:') && ev === 'chapter') {
          try {
            const ci = JSON.parse(line.slice(5).trim())
            genMsg.value = `第${ci.chapter}章完成（${ci.completed}/${ci.total}）`
          } catch {}
        } else if (line.startsWith('data:') && ev === 't') {
          const token = line.slice(5).replace(/\\n/g, '\n').replace(/\\\\/g, '\\')
          if (firstToken) { genMsg.value = ''; firstToken = false }
          output.value += token
        } else if (line.startsWith('data:') && ev === 'done') {
          try {
            const doneData = JSON.parse(line.slice(5).trim())
            outputTruncated.value = doneData.truncated || false
            outputActualChapters.value = doneData.actualChapters || 0
          } catch {}
        } else if (line.startsWith('data:') && ev === 'error') {
          try { output.value += '\n\n[错误] ' + JSON.parse(line.slice(5).trim()) } catch {}
        }
      }
    }
  } catch (e) {
    if ((e as Error).name === 'AbortError') return
  } finally {
    abortController = null
    generating.value = false
    genMsg.value = ''
    streamingJobId.value = null
    activeJobId.value = null
    activeJobStatus.value = ''
    loadJobs(true)
  }
}

function clearPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

async function viewJob(id: string) {
  try {
    const { data } = await api.get(`/continue/jobs/${id}`)
    viewingJob.value = data.data
    outputTruncated.value = false
  } catch {}
}

function closePreview() { viewingJob.value = null }

async function showPreview(id: string) {
  try {
    const { data } = await api.get(`/continue/jobs/${id}`)
    previewModal.value = data.data
    previewOpen.value = true
  } catch {}
}

function closePreviewModal() { previewOpen.value = false; previewModal.value = null }

let restoringConfig = false

async function useConfig(id: string) {
  try {
    const { data } = await api.get(`/continue/jobs/${id}`)
    const job = data.data as ContinueJobFull
    restoringConfig = true
    // 先加载章节，再设 novelId（避免 watcher 清空已选章节）
    const chRes = await api.get(`/novels/${job.novelId}/chapters`)
    chapters.value = chRes.data.data || []
    selectedNovelId.value = job.novelId
    chapterCount.value = job.chapterCount
    foreword.value = job.foreword || ''
    afterword.value = job.afterword || ''
    prompt.value = job.prompt || ''
    searchHint.value = job.searchHint || ''
    if (job.searchSites) {
      searchSites.value = job.searchSites.split(',').filter(Boolean)
    } else {
      searchSites.value = []
    }
    if (job.chapterIds) {
      selectedChapterIds.value = new Set(job.chapterIds.split(',').filter(Boolean))
    } else {
      selectedChapterIds.value = new Set()
    }
    await nextTick()
    restoringConfig = false
  } catch {}
}

watch(selectedNovelId, async (id) => {
  if (restoringConfig) return
  selectedChapterIds.value = new Set()
  if (!id) { chapters.value = []; activeJobId.value = null; return }
  try {
    const { data } = await api.get(`/novels/${id}/chapters`)
    chapters.value = data.data || []
  } catch { chapters.value = [] }
  checkActiveJob()
})

function toggleChapter(id: string) {
  const s = new Set(selectedChapterIds.value)
  if (s.has(id)) { s.delete(id) } else { s.add(id) }
  selectedChapterIds.value = s
}

function toggleAll() {
  if (allSelected.value) { selectedChapterIds.value = new Set() }
  else { selectedChapterIds.value = new Set(chapters.value.map(c => c.id)) }
}

function buildBody() {
  const body: any = { novelId: selectedNovelId.value, chapterCount: chapterCount.value }
  if (selectedChapterIds.value.size > 0) body.chapterIds = [...selectedChapterIds.value]
  if (foreword.value.trim()) body.foreword = foreword.value.trim()
  if (afterword.value.trim()) body.afterword = afterword.value.trim()
  if (prompt.value.trim()) body.prompt = prompt.value.trim()
  if (searchSites.value.length > 0) body.searchSites = searchSites.value
  if (searchHint.value.trim()) body.searchHint = searchHint.value.trim()
  return body
}

async function doGenerate() {
  if (!selectedNovelId.value || generating.value) return
  const token = localStorage.getItem('accessToken')
  if (!token) {
    sessionStorage.setItem('loginRedirect', window.location.pathname + window.location.search)
    window.location.href = '/login'
    return
  }
  output.value = ''
  outputTruncated.value = false
  outputActualChapters.value = 0
  generating.value = true
  genMsg.value = '正在分析剧情大纲...'

  const body = buildBody()

  try {

    genMsg.value = '正在启动生成任务...'

    // 第一步：POST /start → 拿到 jobId
    const startResp = await fetch('/api/continue/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(body),
    })
    if (!startResp.ok) {
      const errText = await startResp.text()
      throw new Error(`HTTP ${startResp.status}: ${errText}`)
    }
    const startData = await startResp.json()
    const jobId = startData.data.jobId
    streamingJobId.value = jobId

    genMsg.value = '正在调用 AI 创作中，你可以离开页面，完成后自动保存到作业记录'

    // 第二步：GET /stream/{jobId} → 订阅 SSE
    abortController = new AbortController()
    const resp = await fetch(`/api/continue/stream/${jobId}`, {
      headers: { Authorization: `Bearer ${token}` },
      signal: abortController.signal,
    })
    if (!resp.ok) {
      const errText = await resp.text()
      throw new Error(`HTTP ${resp.status}: ${errText}`)
    }
    const reader = resp.body?.getReader()
    if (!reader) throw new Error('无法读取响应流')
    const decoder = new TextDecoder(); let ev = ''
    let firstToken = true
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const chunk = decoder.decode(value, { stream: true })
      for (const line of chunk.split('\n')) {
        if (line.startsWith('event:')) ev = line.slice(6).trim()
        else if (line.startsWith('data:') && ev === 'job') {
          try { streamingJobId.value = JSON.parse(line.slice(5).trim()).jobId } catch {}
        } else if (line.startsWith('data:') && ev === 'chapter') {
          try {
            const ci = JSON.parse(line.slice(5).trim())
            genMsg.value = `第${ci.chapter}章完成（${ci.completed}/${ci.total}）`
          } catch {}
        } else if (line.startsWith('data:') && ev === 't') {
          const token = line.slice(5).replace(/\\n/g, '\n').replace(/\\\\/g, '\\')
          if (firstToken) { genMsg.value = ''; firstToken = false }
          output.value += token
        } else if (line.startsWith('data:') && ev === 'done') {
          try {
            const doneData = JSON.parse(line.slice(5).trim())
            outputTruncated.value = doneData.truncated || false
            outputActualChapters.value = doneData.actualChapters || 0
          } catch {}
        } else if (line.startsWith('data:') && ev === 'error') {
          try { output.value += '\n\n[错误] ' + JSON.parse(line.slice(5).trim()) } catch {}
        }
      }
    }
  } catch (e) {
    if ((e as Error).name === 'AbortError') return  // 用户离开页面，静默取消
    const msg = (e as Error).message
    if (msg.includes('401')) {
      sessionStorage.setItem('loginRedirect', window.location.pathname + window.location.search)
      window.location.href = '/login'
      return
    }
    output.value += '\n\n[连接失败] ' + msg
  } finally {
    abortController = null
    generating.value = false
    genMsg.value = ''
    streamingJobId.value = null
    activeJobId.value = null
    loadJobs(true)
  }
}

// 当前上下文的小说 ID（用于章节保存）
const contextNovelId = computed(() => {
  if (viewingJob.value) return viewingJob.value.novelId
  return selectedNovelId.value
})

function fullChapterText(ch: ParsedChapter): string {
  return `第${ch.chapterNum}章 ${ch.title}\n\n${ch.content}`
}

async function copyChapter(ch: ParsedChapter) {
  const text = fullChapterText(ch)
  try { await navigator.clipboard.writeText(text) } catch {
    const ta = document.createElement('textarea'); ta.value = text
    document.body.appendChild(ta); ta.select(); document.execCommand('copy'); document.body.removeChild(ta)
  }
}

async function saveChapter(ch: ParsedChapter, novelId?: string) {
  const nid = novelId || contextNovelId.value
  if (!nid) return
  const fileName = `第${ch.chapterNum}章 ${ch.title}.txt`
  const blob = new Blob([fullChapterText(ch)], { type: 'text/plain; charset=utf-8' })
  const fd = new FormData()
  fd.append('file', blob, fileName)
  try {
    await api.post(`/novels/${nid}/chapters`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    alert(`「第${ch.chapterNum}章 ${ch.title}」已保存到资料库`)
  } catch (e: any) {
    alert('保存失败: ' + (e.response?.data?.message || e.message))
  }
}
</script>

<template>
  <div class="ctn-layout">
    <!-- ====== 左栏：配置 ====== -->
    <aside class="ctn-sidebar">
      <div class="ctn-sb-head">
        <button class="ctn-back-btn" @click="$router.push('/')">← 返回</button>
        <span>续写配置</span>
      </div>

      <div class="ctn-field">
        <label class="ctn-label">选择作品</label>
        <select v-model="selectedNovelId" class="ctn-select">
          <option :value="null" disabled>-- 请选择 --</option>
          <option v-for="n in novels" :key="n.id" :value="n.id">{{ n.title }} ({{ n.chapterCount }}章)</option>
        </select>
      </div>

      <div class="ctn-field" v-if="selectedNovelId">
        <label class="ctn-label">
          参考章节
          <span class="ctn-label-hint">已选 {{ selectedChapterIds.size }}/{{ chapters.length }}</span>
        </label>
        <div class="ctn-chapter-list" v-if="chapters.length > 0">
          <label class="ctn-ch-all" @click="toggleAll">
            <input type="checkbox" :checked="allSelected" @click.stop />
            <span>全选</span>
          </label>
          <label v-for="ch in chapters" :key="ch.id" class="ctn-ch-item"
            :class="{ checked: selectedChapterIds.has(ch.id) }">
            <input type="checkbox" :checked="selectedChapterIds.has(ch.id)" @change="toggleChapter(ch.id)" />
            <span class="ctn-ch-num">第{{ ch.chapterNum }}章</span>
            <span class="ctn-ch-title">{{ ch.title }}</span>
            <span class="ctn-ch-chars">{{ ch.charCount }}字</span>
          </label>
        </div>
        <div v-else class="ctn-empty">暂无章节</div>
      </div>

      <div class="ctn-field">
        <label class="ctn-label">续写章数 <span class="ctn-label-req">必选</span></label>
        <div class="ctn-ch-count">
          <button v-for="n in 5" :key="n" class="ctn-cc-btn" :class="{ active: chapterCount === n }"
            @click="chapterCount = n">{{ n }}</button>
        </div>
      </div>

      <div class="ctn-field">
        <label class="ctn-label">前言（前置背景）</label>
        <textarea v-model="foreword" class="ctn-textarea" rows="3"
          placeholder="例如：上一章讲到林夜突破金丹境，但根基不稳..."></textarea>
      </div>

      <div class="ctn-field">
        <label class="ctn-label">后语（续写方向）</label>
        <textarea v-model="afterword" class="ctn-textarea" rows="3"
          placeholder="例如：下一章写林夜稳固境界后挑战内门长老..."></textarea>
      </div>

      <div class="ctn-field">
        <label class="ctn-label">额外提示词</label>
        <textarea v-model="prompt" class="ctn-textarea" rows="2"
          placeholder="如：多写打斗 / 增加对话 / 保持悬疑..."></textarea>
      </div>

      <!-- 联网参考 -->
      <div class="ctn-field">
        <label class="ctn-label">联网参考 <span class="ctn-label-req">可选</span></label>
        <div class="ctn-search-row">
          <span class="ctn-search-picker" @click.stop="showSearchMenu = !showSearchMenu">
            <span class="ctn-search-toggle" :class="{ active: searchSites.length > 0 }">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
              <span>{{ searchSites.length > 0 ? '已选' + searchSites.length + '站' : '搜索站点' }}</span>
            </span>
            <div class="ctn-search-drop" v-if="showSearchMenu">
              <label v-for="s in SEARCH_SITES" :key="s.key" class="ctn-search-opt" @click.stop="toggleSite(s.key)">
                <input type="checkbox" :checked="searchSites.includes(s.key)" />
                <span>{{ s.label }}</span>
              </label>
            </div>
          </span>
        </div>
        <textarea v-model="searchHint" class="ctn-textarea" rows="2"
          placeholder="联网搜索提示词，如：2024年玄幻小说流行趋势...&#10;留空则自动用前言+后语+提示词拼接"></textarea>
      </div>

      <button class="ctn-btn"
        :disabled="!selectedNovelId || generating || activeJobStatus === 'GENERATING' || activeJobStatus === 'PENDING'"
        @click="activeJobStatus === 'PARTIAL' ? doResume() : doGenerate()">
        {{ generating ? '生成中...' :
           activeJobStatus === 'GENERATING' || activeJobStatus === 'PENDING' ? '后台创作中...' :
           activeJobStatus === 'PARTIAL' ? '继续生成' :
           '开始续写' }}
      </button>
    </aside>

    <!-- ====== 中间：输出 ====== -->
    <main class="ctn-main">
      <!-- 查看历史作业 -->
      <template v-if="viewingJob">
        <div class="ctn-topbar">
          <span class="ctn-topbar-title">{{ viewingJob.novelTitle }} · {{ fmtTime(viewingJob.createdAt) }}</span>
          <div class="ctn-topbar-actions">
            <span class="ctn-meta-tag">{{ viewingJob.chapterCount }}章</span>
            <span class="ctn-meta-tag">{{ viewingJob.resultLength }}字</span>
            <button class="ctn-tb-btn" @click="closePreview">← 返回</button>
          </div>
        </div>
        <div class="ctn-output" style="overflow-y:auto; padding:32px 48px;">
          <!-- 配置摘要：折叠卡片 -->
          <div class="ctn-ch-card config-card" :class="{ open: showConfig }">
            <div class="ctn-ch-card-hd" @click="showConfig = !showConfig">
              <span class="ctn-ch-card-arrow">{{ showConfig ? '▼' : '▶' }}</span>
              <span class="ctn-ch-card-label">续写配置</span>
              <span class="ctn-ch-card-meta">{{ viewingJob.chapterCount }}章 · {{ viewingJob.resultLength }}字</span>
            </div>
            <div v-show="showConfig" class="ctn-ch-card-body">
              <div v-if="viewingJob.chapterTitles" class="cfg-row"><b>参考章节</b></div>
              <div v-if="viewingJob.chapterTitles" class="cfg-chips">
                <span v-for="(t, i) in viewingJob.chapterTitles.split(',')" :key="i" class="cfg-chip">{{ t }}</span>
              </div>
              <div v-if="viewingJob.foreword" class="cfg-row"><b>前言</b> {{ viewingJob.foreword }}</div>
              <div v-if="viewingJob.afterword" class="cfg-row"><b>后语</b> {{ viewingJob.afterword }}</div>
              <div v-if="viewingJob.prompt" class="cfg-row"><b>提示词</b> {{ viewingJob.prompt }}</div>
              <div v-if="!viewingJob.chapterTitles && !viewingJob.foreword && !viewingJob.afterword && !viewingJob.prompt" class="cfg-empty">无额外配置</div>
            </div>
          </div>
          <!-- 多章卡片 -->
          <template v-if="displayChapters.length > 0">
            <div class="ctn-chapters-wrap">
              <div v-for="ch in displayChapters" :key="ch.chapterNum" class="ctn-ch-card"
                :class="{ open: expandedChapters.includes(ch.chapterNum) }">
                <div class="ctn-ch-card-hd" @click="toggleCard(ch.chapterNum, expandedChapters)">
                  <span class="ctn-ch-card-arrow">{{ expandedChapters.includes(ch.chapterNum) ? '▼' : '▶' }}</span>
                  <span class="ctn-ch-card-label">第{{ ch.chapterNum }}章</span>
                  <span class="ctn-ch-card-title-text">{{ ch.title }}</span>
                  <span class="ch-actions" @click.stop>
                    <button class="ch-act-btn" @click="copyChapter(ch)" title="复制本章">📋</button>
                    <button class="ch-act-btn" @click="saveChapter(ch, viewingJob!.novelId)" title="保存到资料库">💾</button>
                  </span>
                </div>
                <div v-show="expandedChapters.includes(ch.chapterNum)" class="ctn-ch-card-body">
                  <div class="ctn-output-text">{{ ch.content }}</div>
                </div>
              </div>
            </div>
          </template>
          <div v-else class="ctn-output-text">{{ viewingJob.result }}</div>
        </div>
      </template>

      <!-- 当前生成 -->
      <template v-else>
        <div class="ctn-topbar">
          <span class="ctn-topbar-title">续写结果</span>
          <div class="ctn-topbar-actions">
            <span v-if="generating && generatedCount > 0" class="ctn-progress-tag">
              已生成 {{ generatedCount }}/{{ chapterCount }} 章
            </span>
            <button v-if="output && !generating" class="ctn-tb-btn" @click="doGenerate">重新生成</button>
          </div>
        </div>
        <div v-if="outputTruncated && !generating" class="ctn-trunc-warn">
          ⚠️ API 返回不完整：预期 {{ chapterCount }} 章，实际生成 {{ outputActualChapters }} 章。可减少续写章数重试。
        </div>
        <div class="ctn-output" ref="outputBox">
          <div v-if="(generating || activeJobId) && !output" class="ctn-loading">
            <div class="ctn-load-icon">✍️</div>
            <div class="ctn-load-title">
              {{ activeJobStatus === 'PARTIAL' ? '创作已暂停' : 'AI 正在创作中' }}
            </div>
            <div class="ctn-load-hint">
              <template v-if="activeJobStatus === 'PARTIAL'">
                上一轮生成中断，点击左侧「继续生成」完成剩余章节
              </template>
              <template v-else>
                生成 {{ chapterCount }} 章内容需要一些时间，你可以离开页面<br/>
                完成后会自动保存并刷新页面
              </template>
            </div>
            <template v-if="activeJobStatus !== 'PARTIAL'">
              <div class="ctn-load-bar-track">
                <div class="ctn-load-bar-fill"></div>
              </div>
              <div class="ctn-gen-msg" v-if="genMsg">{{ genMsg }}</div>
              <!-- 骨架卡片 -->
              <div class="ctn-chapters-wrap ctn-skeleton-list">
                <div v-for="n in chapterCount" :key="n" class="ctn-ch-card ctn-sk-card">
                <div class="ctn-ch-card-hd">
                  <span class="ctn-ch-card-arrow">▶</span>
                  <span class="ctn-ch-card-label">第{{ startChapterNum + n - 1 }}章</span>
                  <span class="ctn-sk-line"></span>
                </div>
              </div>
            </div>
            </template>
          </div>
          <div v-else-if="!output && !generating" class="ctn-placeholder">
            配置好参数，点击「开始续写」
          </div>
          <!-- 多章卡片 -->
          <template v-else-if="displayChapters.length > 0">
            <div class="ctn-chapters-wrap">
              <div v-for="ch in displayChapters" :key="ch.chapterNum" class="ctn-ch-card"
                :class="{ open: expandedChapters.includes(ch.chapterNum) }">
                <div class="ctn-ch-card-hd" @click="toggleCard(ch.chapterNum, expandedChapters)">
                  <span class="ctn-ch-card-arrow">{{ expandedChapters.includes(ch.chapterNum) ? '▼' : '▶' }}</span>
                  <span class="ctn-ch-card-label">第{{ ch.chapterNum }}章</span>
                  <span class="ctn-ch-card-title-text">{{ ch.title }}</span>
                  <span class="ch-actions" @click.stop>
                    <button class="ch-act-btn" @click="copyChapter(ch)" title="复制本章">📋</button>
                    <button class="ch-act-btn" @click="saveChapter(ch)" title="保存到资料库">💾</button>
                  </span>
                </div>
                <div v-show="expandedChapters.includes(ch.chapterNum)" class="ctn-ch-card-body">
                  <div class="ctn-output-text">{{ ch.content }}</div>
                </div>
              </div>
            </div>
          </template>
          <div v-else class="ctn-output-text">{{ output }}</div>
        </div>
      </template>
    </main>

    <!-- ====== 右栏：作业记录 ====== -->
    <aside class="ctn-history" v-if="jobs.length > 0">
      <div class="ctn-hist-head">作业记录</div>
      <div class="ctn-hist-list">
        <div v-for="j in jobs" :key="j.id" class="ctn-hist-card"
          :class="{ active: viewingJob?.id === j.id }">
          <div class="chc-title" @click="viewJob(j.id)">{{ j.novelTitle }}</div>
          <div class="chc-meta" @click="viewJob(j.id)">{{ j.chapterCount }}章 · {{ j.resultLength }}字 · {{ fmtTime(j.createdAt) }}</div>
          <div class="chc-preview" @click="viewJob(j.id)">{{ j.resultPreview }}</div>
          <div class="chc-actions">
            <button class="chc-btn" @click.stop="showPreview(j.id)">预览</button>
            <button class="chc-btn chc-use" @click.stop="useConfig(j.id)">使用配置</button>
          </div>
        </div>
        <div v-if="jobHasMore" class="ctn-hist-more" @click="loadJobs(false)">加载更多...</div>
      </div>
    </aside>

    <!-- 预览弹窗 -->
    <Teleport to="body">
      <div v-if="previewOpen && previewModal" class="pv-overlay" @click.self="closePreviewModal">
        <div class="pv-dialog">
          <div class="pv-head">
            <span class="pv-title">{{ previewModal.novelTitle }}</span>
            <button class="pv-close" @click="closePreviewModal">✕</button>
          </div>
          <div class="pv-body">
            <!-- 配置区：折叠卡片 -->
            <div class="ctn-ch-card config-card" :class="{ open: showPreviewConfig }" style="margin-bottom:20px">
              <div class="ctn-ch-card-hd" @click="showPreviewConfig = !showPreviewConfig">
                <span class="ctn-ch-card-arrow">{{ showPreviewConfig ? '▼' : '▶' }}</span>
                <span class="ctn-ch-card-label">续写配置</span>
                <span class="ctn-ch-card-meta">{{ previewModal.chapterCount }}章 · {{ previewModal.resultLength }}字 · {{ fmtTime(previewModal.createdAt) }}</span>
              </div>
              <div v-show="showPreviewConfig" class="ctn-ch-card-body">
                <div class="cfg-row"><b>续写章数</b> {{ previewModal.chapterCount }} 章</div>
                <div class="cfg-row"><b>结果字数</b> {{ previewModal.resultLength }} 字</div>
                <div class="cfg-row"><b>时间</b> {{ fmtTime(previewModal.createdAt) }}</div>
                <div v-if="previewModal.chapterTitles" class="cfg-row"><b>参考章节</b></div>
                <div v-if="previewModal.chapterTitles" class="cfg-chips">
                  <span v-for="(t, i) in previewModal.chapterTitles.split(',')" :key="i" class="cfg-chip">{{ t }}</span>
                </div>
                <div v-if="previewModal.foreword" class="cfg-row"><b>前言</b> {{ previewModal.foreword }}</div>
                <div v-if="previewModal.afterword" class="cfg-row"><b>后语</b> {{ previewModal.afterword }}</div>
                <div v-if="previewModal.prompt" class="cfg-row"><b>提示词</b> {{ previewModal.prompt }}</div>
              </div>
            </div>
            <!-- 结果区 -->
            <div class="pv-section">
              <div class="pv-section-title">续写结果</div>
              <template v-if="previewChapters.length > 0">
                <div class="ctn-chapters-wrap">
                  <div v-for="ch in previewChapters" :key="ch.chapterNum" class="ctn-ch-card"
                    :class="{ open: previewExpanded.includes(ch.chapterNum) }">
                    <div class="ctn-ch-card-hd" @click="toggleCard(ch.chapterNum, previewExpanded)">
                      <span class="ctn-ch-card-arrow">{{ previewExpanded.includes(ch.chapterNum) ? '▼' : '▶' }}</span>
                      <span class="ctn-ch-card-label">第{{ ch.chapterNum }}章</span>
                      <span class="ctn-ch-card-title-text">{{ ch.title }}</span>
                      <span class="ch-actions" @click.stop>
                        <button class="ch-act-btn" @click="copyChapter(ch)" title="复制本章">📋</button>
                        <button class="ch-act-btn" @click="saveChapter(ch, previewModal!.novelId)" title="保存到资料库">💾</button>
                      </span>
                    </div>
                    <div v-show="previewExpanded.includes(ch.chapterNum)" class="ctn-ch-card-body">
                      <div class="ctn-output-text">{{ ch.content }}</div>
                    </div>
                  </div>
                </div>
              </template>
              <div v-else class="pv-result">{{ previewModal.result }}</div>
            </div>
          </div>
          <div class="pv-foot">
            <button class="ctn-tb-btn primary" @click="useConfig(previewModal.id); closePreviewModal()">使用此配置</button>
            <button class="ctn-tb-btn" @click="closePreviewModal">关闭</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style>
body, button, input, textarea, select {
  font-family: "HarmonyOS Sans", "HarmonyOS Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif;
}
</style>

<style scoped>
.ctn-layout { display: flex; height: 100vh; background: #fafbfc; }

/* ====== 左栏：配置 ====== */
.ctn-sidebar {
  width: 340px; flex-shrink: 0; overflow-y: auto;
  background: #fff; border-right: 1px solid #e8ecf0;
  padding: 24px; display: flex; flex-direction: column; gap: 14px;
}
.ctn-sb-head { font-size: 18px; font-weight: 700; color: #222; margin-bottom: 2px; display: flex; align-items: center; gap: 12px; }
.ctn-back-btn {
  background: none; border: none; color: #4f46e5; cursor: pointer;
  font-size: 14px; font-weight: 500; padding: 0; white-space: nowrap;
}
.ctn-back-btn:hover { color: #4338ca; }
.ctn-field { display: flex; flex-direction: column; gap: 6px; }
.ctn-label { font-size: 13px; font-weight: 600; color: #555; display: flex; justify-content: space-between; }
.ctn-label-hint { font-weight: 400; color: #999; font-size: 12px; }
.ctn-label-req { font-weight: 600; color: #e55; font-size: 11px; background: #fef2f2; padding: 1px 8px; border-radius: 4px; }
.ctn-select {
  width: 100%; padding: 9px 12px; border: 1px solid #ddd; border-radius: 10px;
  font-size: 14px; outline: none; background: #fff; color: #333;
}
.ctn-select:focus { border-color: #4f46e5; }

.ctn-ch-count { display: flex; gap: 8px; }
.ctn-cc-btn {
  width: 42px; height: 38px; border: 2px solid #e0e0e0; border-radius: 10px;
  background: #fff; font-size: 17px; font-weight: 600; color: #888;
  cursor: pointer; transition: all 0.15s;
}
.ctn-cc-btn:hover { border-color: #4f46e5; color: #4f46e5; }
.ctn-cc-btn.active { border-color: #4f46e5; background: #eef2ff; color: #4f46e5; }

.ctn-chapter-list {
  border: 1px solid #e8ecf0; border-radius: 10px; max-height: 180px;
  overflow-y: auto; padding: 4px;
}
.ctn-ch-all {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  cursor: pointer; font-size: 13px; color: #4f46e5; font-weight: 600;
  border-bottom: 1px solid #f0f0f0; user-select: none;
}
.ctn-ch-all input { accent-color: #4f46e5; }
.ctn-ch-item {
  display: flex; align-items: center; gap: 8px; padding: 6px 12px;
  cursor: pointer; font-size: 13px; color: #666; transition: background 0.1s; user-select: none;
}
.ctn-ch-item:hover { background: #f8f9fb; }
.ctn-ch-item.checked { background: #eef2ff; color: #4f46e5; }
.ctn-ch-item input { accent-color: #4f46e5; flex-shrink: 0; }
.ctn-ch-num { font-weight: 500; flex-shrink: 0; min-width: 42px; }
.ctn-ch-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ctn-ch-chars { font-size: 11px; color: #bbb; flex-shrink: 0; }

.ctn-textarea {
  width: 100%; padding: 10px 14px; border: 1px solid #ddd; border-radius: 10px;
  font-size: 13px; outline: none; resize: vertical; font-family: inherit; color: #333;
  box-sizing: border-box;
}
.ctn-textarea:focus { border-color: #4f46e5; box-shadow: 0 0 0 3px rgba(79,70,229,0.08); }
.ctn-empty { text-align: center; color: #bbb; font-size: 13px; padding: 16px; }

.ctn-btn {
  width: 100%; padding: 12px; background: #4f46e5; color: #fff;
  border: none; border-radius: 12px; font-size: 15px; font-weight: 600;
  cursor: pointer; transition: all 0.15s; margin-top: auto;
}
.ctn-btn:hover { background: #4338ca; }
.ctn-btn:disabled { opacity: 0.4; cursor: default; }

/* ====== 中间：输出 ====== */
.ctn-main {
  flex: 1; display: flex; flex-direction: column; min-width: 0;
  background: #f5f4f0;
}
.ctn-topbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 28px; background: #fff; border-bottom: 1px solid #e8ecf0; flex-shrink: 0;
  gap: 12px;
}
.ctn-topbar-title { font-size: 15px; font-weight: 600; color: #333; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ctn-topbar-actions { display: flex; gap: 8px; align-items: center; }
.ctn-meta-tag { font-size: 12px; color: #999; background: #f0f1f3; padding: 2px 10px; border-radius: 6px; }
.ctn-progress-tag { font-size: 13px; color: #4f46e5; background: #eef2ff; padding: 4px 12px; border-radius: 8px; font-weight: 600; }
.ctn-trunc-warn {
  font-size: 13px; color: #b45309; background: #fffbeb;
  padding: 10px 28px; text-align: center; flex-shrink: 0;
  border-bottom: 1px solid #fde68a;
}
.ctn-tb-btn {
  padding: 6px 14px; background: #f0f1f3; border: none; border-radius: 8px;
  cursor: pointer; font-size: 13px; color: #555; transition: background 0.15s; white-space: nowrap;
}
.ctn-tb-btn:hover { background: #e4e5e8; }
.ctn-tb-btn:disabled { opacity: 0.4; cursor: default; }
.ctn-tb-btn.primary { background: #eef2ff; color: #4f46e5; }
.ctn-tb-btn.primary:hover { background: #dde4ff; }

/* 配置摘要条 */
.job-config-bar {
  display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px;
  padding: 12px 16px; background: #f8f9fb; border-radius: 10px;
}
.jcb-item { font-size: 13px; color: #666; }
.jcb-item b { color: #888; font-weight: 500; }

.ctn-output {
  flex: 1; overflow-y: auto; padding: 40px 56px;
}
.ctn-placeholder { color: #bbb; font-size: 15px; padding-top: 120px; }

.ctn-loading {
  display: flex; flex-direction: column; align-items: center;
  padding-top: 64px; padding-bottom: 40px;
}
.ctn-load-icon {
  font-size: 48px; margin-bottom: 8px;
  animation: writing-bounce 2s ease-in-out infinite;
}
@keyframes writing-bounce {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-8px); }
}
.ctn-load-title {
  font-size: 18px; font-weight: 600; color: #333; margin-bottom: 8px;
}
.ctn-load-hint {
  font-size: 13px; color: #999; text-align: center; line-height: 1.8; margin-bottom: 20px;
}
.ctn-load-bar-track {
  width: 260px; height: 3px; background: #e8ecf0; border-radius: 2px;
  overflow: hidden; margin-bottom: 10px;
}
.ctn-load-bar-fill {
  width: 40%; height: 100%; background: #4f46e5; border-radius: 2px;
  animation: load-slide 1.2s ease-in-out infinite;
}
@keyframes load-slide {
  0% { transform: translateX(-60%); }
  100% { transform: translateX(320px); }
}
.ctn-gen-msg { font-size: 13px; color: #aaa; margin-bottom: 4px; }

/* 骨架卡片 */
.ctn-skeleton-list { margin-top: 28px; pointer-events: none; }
.ctn-sk-card { opacity: 0.55; }
.ctn-sk-card .ctn-ch-card-hd { cursor: default; }
.ctn-sk-line {
  flex: 1; height: 14px; background: linear-gradient(90deg, #eee 25%, #f5f5f5 50%, #eee 75%);
  background-size: 200% 100%; border-radius: 4px;
  animation: shimmer 1.5s ease-in-out infinite;
}
@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.ctn-output-text {
  max-width: 680px; width: 100%;
  font-size: 17px; line-height: 2; color: #333;
  white-space: pre-wrap; word-break: break-word;
}

/* ====== 右栏：作业记录 ====== */
.ctn-history {
  width: 260px; flex-shrink: 0; background: #fff;
  border-left: 1px solid #e8ecf0; display: flex; flex-direction: column;
}
.ctn-hist-head {
  font-size: 14px; font-weight: 600; color: #666;
  padding: 14px 16px 10px; border-bottom: 1px solid #f0f0f0;
}
.ctn-hist-list { flex: 1; overflow-y: auto; padding: 8px; }
.ctn-hist-card {
  padding: 12px 14px; border-radius: 10px; cursor: pointer;
  transition: background 0.12s; margin-bottom: 6px;
  border: 1px solid transparent;
}
.ctn-hist-card:hover { background: #f8f9fb; }
.ctn-hist-card.active { background: #eef2ff; border-color: #dde4ff; }
.chc-title {
  font-size: 13px; font-weight: 600; color: #333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.chc-meta { font-size: 11px; color: #bbb; margin: 3px 0 5px; }
.chc-preview {
  font-size: 12px; color: #999; line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  overflow: hidden; white-space: pre-wrap;
  margin-bottom: 8px;
}
.chc-actions { display: flex; gap: 6px; }
.chc-btn {
  flex: 1; padding: 5px 0; border: 1px solid #e0e0e0; border-radius: 6px;
  background: #fff; font-size: 11px; color: #888; cursor: pointer;
  transition: all 0.12s; text-align: center;
}
.chc-btn:hover { border-color: #4f46e5; color: #4f46e5; }
.chc-btn.chc-use { background: #eef2ff; border-color: #dde4ff; color: #4f46e5; }
.chc-btn.chc-use:hover { background: #dde4ff; }

.ctn-active-job-notice {
  font-size: 12px; color: #d97706; background: #fef3c7;
  padding: 8px 12px; border-radius: 8px; margin-bottom: 8px; text-align: center;
}
.ctn-hist-more {
  text-align: center; padding: 10px; font-size: 13px; color: #4f46e5;
  cursor: pointer; border-radius: 8px; transition: background 0.15s;
}
.ctn-hist-more:hover { background: #eef2ff; }

/* 预览弹窗 */
.pv-overlay {
  position: fixed; inset: 0; z-index: 500;
  background: rgba(0,0,0,0.4); display: flex;
  align-items: center; justify-content: center;
}
.pv-dialog {
  background: #fff; border-radius: 16px; box-shadow: 0 24px 64px rgba(0,0,0,0.2);
  width: 780px; max-width: 92vw; max-height: 85vh; display: flex; flex-direction: column;
  overflow: hidden;
}
.pv-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 24px; border-bottom: 1px solid #eee; flex-shrink: 0;
}
.pv-title { font-size: 17px; font-weight: 700; color: #222; }
.pv-close {
  width: 32px; height: 32px; border: none; background: #f0f1f3;
  border-radius: 8px; cursor: pointer; font-size: 16px; color: #999;
  display: flex; align-items: center; justify-content: center;
}
.pv-close:hover { background: #e4e5e8; color: #555; }

.pv-body { flex: 1; overflow-y: auto; padding: 20px 24px; }
.pv-section { margin-bottom: 20px; }
.pv-section-title { font-size: 14px; font-weight: 600; color: #666; margin-bottom: 10px; }
.pv-config-grid { display: flex; gap: 24px; margin-bottom: 10px; }
.pv-label { font-size: 12px; color: #aaa; margin-right: 4px; }
.pv-val { font-size: 13px; color: #555; }
.pv-extra { font-size: 13px; color: #666; line-height: 1.6; }
.pv-extra b { color: #999; font-weight: 500; }
.pv-result {
  font-size: 16px; line-height: 2; color: #333;
  white-space: pre-wrap; word-break: break-word;
  max-height: 45vh; overflow-y: auto;
}

.pv-foot {
  display: flex; justify-content: flex-end; gap: 8px;
  padding: 14px 24px; border-top: 1px solid #eee; flex-shrink: 0;
}

/* ====== 章节卡片 ====== */
.ctn-chapters-wrap {
  max-width: 680px; margin: 0 auto;
  display: flex; flex-direction: column; gap: 12px;
}
.ctn-ch-card {
  border: 1px solid #e0ddd5; border-radius: 12px;
  background: #fffef9; overflow: hidden;
  transition: box-shadow 0.15s;
}
.ctn-ch-card.open {
  box-shadow: 0 2px 12px rgba(0,0,0,0.06);
  border-color: #d5d0c5;
}
.ctn-ch-card-hd {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 20px; cursor: pointer;
  user-select: none; transition: background 0.12s;
}
.ctn-ch-card-hd:hover { background: #faf8f2; }
.ctn-ch-card-arrow {
  font-size: 11px; color: #bbb; width: 14px; flex-shrink: 0;
  transition: transform 0.15s;
}
.ctn-ch-card.open .ctn-ch-card-arrow { color: #888; }
.ctn-ch-card-label {
  font-size: 14px; font-weight: 700; color: #4f46e5; flex-shrink: 0;
}
.ctn-ch-card-title-text {
  font-size: 14px; font-weight: 600; color: #444;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  flex: 1;
}
.ch-actions {
  display: flex; gap: 4px; flex-shrink: 0; opacity: 0;
  transition: opacity 0.15s;
}
.ctn-ch-card-hd:hover .ch-actions { opacity: 1; }
.ch-act-btn {
  width: 30px; height: 30px; border: 1px solid #e0ddd5; border-radius: 6px;
  background: #fffef9; cursor: pointer; font-size: 14px;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.12s; padding: 0;
}
.ch-act-btn:hover { border-color: #4f46e5; background: #eef2ff; }
.ctn-ch-card-body {
  padding: 0 20px 20px; border-top: 1px solid #f0ede5;
}
.ctn-ch-card-body .ctn-output-text {
  padding-top: 16px;
}

/* 配置卡片 */
.config-card { margin-bottom: 20px; border-color: #e8e4db; }
.config-card .ctn-ch-card-hd { padding: 10px 20px; }
.config-card .ctn-ch-card-label { color: #888; font-weight: 500; font-size: 13px; }
.ctn-ch-card-meta { font-size: 12px; color: #bbb; margin-left: auto; }
.cfg-row {
  font-size: 14px; color: #555; line-height: 1.8;
  padding: 4px 0;
}
.cfg-row b { color: #999; font-weight: 500; margin-right: 6px; }
.cfg-empty { font-size: 13px; color: #ccc; padding: 8px 0; }
.cfg-chips {
  display: flex; flex-wrap: wrap; gap: 6px;
  max-height: 120px; overflow-y: auto;
  margin-bottom: 10px; padding: 2px 0;
}
.cfg-chip {
  font-size: 12px; color: #4f46e5; background: #eef2ff;
  padding: 3px 10px; border-radius: 6px;
  white-space: nowrap; font-weight: 500;
}

/* 联网搜索站点选择 */
.ctn-search-row { margin-bottom: 6px; }
.ctn-search-picker { position: relative; display: inline-block; }
.ctn-search-toggle {
  display: flex; align-items: center; gap: 5px; padding: 7px 12px;
  border: 1px solid #ddd; border-radius: 10px; cursor: pointer;
  font-size: 12px; color: #999; background: #fafafa; user-select: none;
  transition: all 0.15s;
}
.ctn-search-toggle:hover { border-color: #4f46e5; color: #4f46e5; }
.ctn-search-toggle.active { background: #eef2ff; border-color: #4f46e5; color: #4f46e5; }
.ctn-search-toggle svg { flex-shrink: 0; }
.ctn-search-drop {
  position: absolute; bottom: calc(100% + 4px); left: 0; z-index: 50;
  background: #fff; border: 1px solid #e0e3e8; border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0,0,0,0.1); padding: 6px; min-width: 160px;
}
.ctn-search-opt {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  border-radius: 8px; cursor: pointer; font-size: 13px; color: #333;
  transition: background 0.12s; user-select: none;
}
.ctn-search-opt:hover { background: #f5f5ff; }
.ctn-search-opt input[type="checkbox"] { accent-color: #4f46e5; width: 15px; height: 15px; margin: 0; }
.ctn-search-opt span { line-height: 1; }
</style>
