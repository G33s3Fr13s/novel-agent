<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import api from '../api'

const route = useRoute()
const novelId = route.query.novelId as string
const tab = ref<'entities' | 'plot'>('entities')
const entities = ref<any[]>([])
const relations = ref<any[]>([])
const plotEvents = ref<any[]>([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  const [eRes, pRes] = await Promise.all([
    api.get(`/novels/${novelId}/graph`).catch(() => null),
    api.get(`/novels/${novelId}/plot`).catch(() => null),
  ])
  if (eRes?.data?.data) {
    entities.value = eRes.data.data.entities || []
    relations.value = eRes.data.data.relations || []
  }
  if (pRes?.data?.data) {
    plotEvents.value = pRes.data.data
  }
  loading.value = false
})

async function rebuild(type: 'graph' | 'plot') {
  await api.post(`/novels/${novelId}/${type}`)
  alert('后台构建中，稍后刷新页面查看')
}
</script>

<template>
  <div>
    <div class="graph-header">
      <router-link to="/">← 返回</router-link>
      <div class="tabs">
        <button :class="{ active: tab === 'entities' }" @click="tab = 'entities'">人物关系图</button>
        <button :class="{ active: tab === 'plot' }" @click="tab = 'plot'">剧情大纲图</button>
      </div>
      <button @click="rebuild(tab === 'entities' ? 'graph' : 'plot')" class="rebuild">🔄 重建</button>
    </div>

    <div v-if="loading" class="msg">加载中...</div>

    <!-- 人物关系图 -->
    <div v-if="tab === 'entities' && !loading" class="graph-area">
      <h3>实体 ({{ entities.length }})</h3>
      <div class="entity-grid">
        <div v-for="e in entities" :key="e.name" class="entity-node" :class="e.type.toLowerCase()">
          <div class="e-name">{{ e.name }}</div>
          <div class="e-type">{{ e.type }}</div>
          <div class="e-aliases" v-if="e.aliases?.length">{{ e.aliases.join(' / ') }}</div>
        </div>
      </div>
      <h3 style="margin-top:24px">关系 ({{ relations.length }})</h3>
      <div class="rel-list">
        <div v-for="r in relations" :key="r.source + r.target + r.relationType" class="rel-item">
          <span class="from">{{ r.source }}</span>
          <span class="arrow">→{{ r.relationType }}→</span>
          <span class="to">{{ r.target }}</span>
          <span class="desc" v-if="r.description">{{ r.description }}</span>
        </div>
      </div>
    </div>

    <!-- 剧情大纲图 -->
    <div v-if="tab === 'plot' && !loading">
      <h3>剧情弧线 ({{ plotEvents.length }})</h3>
      <div class="arc-list">
        <div v-for="arc in plotEvents" :key="arc.eventIndex" class="arc-card">
          <div class="arc-idx">{{ arc.eventIndex + 1 }}</div>
          <div class="arc-body">
            <div class="arc-summary">{{ arc.summary }}</div>
            <div class="arc-entities" v-if="arc.keyEntities?.length">
              {{ arc.keyEntities.join(' · ') }}
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.graph-header { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; }
.graph-header a { color: #7c8cf8; text-decoration: none; }
.tabs { display: flex; gap: 4px; background: #1a1a2e; border-radius: 8px; padding: 4px; }
.tabs button { padding: 6px 16px; background: transparent; color: #888; border: none; border-radius: 6px; cursor: pointer; }
.tabs button.active { background: #7c8cf8; color: #fff; }
.rebuild { padding: 6px 14px; background: #2a2a4e; color: #aaa; border: 1px solid #555; border-radius: 6px; cursor: pointer; font-size: 13px; }
.msg { text-align: center; color: #888; margin: 48px; }
.entity-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 8px; }
.entity-node { background: #1a1a2e; padding: 12px; border-radius: 8px; border-left: 4px solid #555; }
.entity-node.person { border-left-color: #7c8cf8; }
.entity-node.location { border-left-color: #4caf50; }
.entity-node.item { border-left-color: #ff9800; }
.entity-node.concept { border-left-color: #9c27b0; }
.e-name { font-weight: bold; }
.e-type { font-size: 12px; color: #888; }
.e-aliases { font-size: 12px; color: #666; margin-top: 4px; }
.rel-list { display: flex; flex-direction: column; gap: 6px; }
.rel-item { background: #1a1a2e; padding: 10px 16px; border-radius: 6px; display: flex; align-items: center; gap: 8px; font-size: 14px; }
.from, .to { font-weight: bold; }
.arrow { color: #7c8cf8; font-size: 12px; }
.desc { color: #888; font-size: 13px; }
.arc-list { display: flex; flex-direction: column; gap: 12px; }
.arc-card { display: flex; gap: 16px; background: #1a1a2e; padding: 16px; border-radius: 8px; }
.arc-idx { font-size: 24px; font-weight: bold; color: #7c8cf8; min-width: 40px; }
.arc-body { flex: 1; }
.arc-summary { line-height: 1.5; font-size: 15px; }
.arc-entities { margin-top: 8px; color: #888; font-size: 13px; }
</style>
