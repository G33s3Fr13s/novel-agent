<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../api'

interface DashboardStats {
  totalUsers: number; totalNovels: number; totalChapters: number
  totalChunks: number; totalEntities: number; totalRelations: number
  latestEval: { context_precision: number; context_recall: number; vector_recall: number; graphrag_gain: number; created_at: string } | null
}
interface EvalTrend { context_precision: number; context_recall: number; vector_recall: number; graphrag_gain: number; total_questions: number; created_at: string }

const stats = ref<DashboardStats | null>(null)
const trends = ref<EvalTrend[]>([])
const error = ref('')

onMounted(async () => {
  try {
    const [dRes, tRes] = await Promise.all([
      api.get('/admin/dashboard'),
      api.get('/admin/eval-trend'),
    ])
    stats.value = dRes.data.data
    trends.value = tRes.data.data
  } catch (e: any) {
    error.value = e.response?.data?.message || e.message
    if (error.value.includes('管理')) window.location.href = '/'
  }
})

function pct(v: number) { return (v * 100).toFixed(1) + '%' }
function time(s: string) { return s ? new Date(s).toLocaleString('zh-CN') : '' }
function gainColor(v: number) { return v > 0 ? '#16a34a' : v < -0.01 ? '#dc2626' : '#999' }
function gainIcon(v: number) { return v > 0 ? '▲' : v < -0.01 ? '▼' : '─' }
</script>

<template>
  <div class="admin-page">
    <div class="admin-head">
      <h1>📊 管理仪表盘</h1>
      <button @click="$router.push('/')">← 返回首页</button>
    </div>
    <p v-if="error" class="admin-err">{{ error }}</p>

    <template v-if="stats">
      <!-- 系统概览 -->
      <section class="admin-section">
        <h2>系统概览</h2>
        <div class="stat-cards">
          <div class="stat-card"><span class="stat-num">{{ stats.totalUsers }}</span><span class="stat-label">用户</span></div>
          <div class="stat-card"><span class="stat-num">{{ stats.totalNovels }}</span><span class="stat-label">小说</span></div>
          <div class="stat-card"><span class="stat-num">{{ stats.totalChapters }}</span><span class="stat-label">章节</span></div>
          <div class="stat-card"><span class="stat-num">{{ stats.totalChunks }}</span><span class="stat-label">语义块</span></div>
          <div class="stat-card"><span class="stat-num">{{ stats.totalEntities }}</span><span class="stat-label">图谱实体</span></div>
          <div class="stat-card"><span class="stat-num">{{ stats.totalRelations }}</span><span class="stat-label">实体关系</span></div>
        </div>
      </section>

      <!-- 最新评测 -->
      <section class="admin-section" v-if="stats.latestEval">
        <h2>最新评测结果</h2>
        <div class="eval-latest">
          <div class="eval-metric eval-tip" data-tip="检索准不准：召回的 chunk 里有多少是真正相关的。Precision = 命中数 / 召回总数">
            <span class="eval-value">{{ pct(stats.latestEval.context_precision) }}</span>
            <span class="eval-label">Precision <span class="tip-icon">?</span></span>
          </div>
          <div class="eval-metric eval-tip" data-tip="双路融合 (语义+图谱 RRF) 检索找全了多少。Recall = 命中数 / 所有应找回的相关chunk">
            <span class="eval-value">{{ pct(stats.latestEval.context_recall) }}</span>
            <span class="eval-label">Recall (fused) <span class="tip-icon">?</span></span>
          </div>
          <div class="eval-metric eval-tip" data-tip="纯语义向量检索找全了多少，不加图谱。作为 baseline 对比">
            <span class="eval-value">{{ pct(stats.latestEval.vector_recall) }}</span>
            <span class="eval-label">Recall (vector) <span class="tip-icon">?</span></span>
          </div>
          <div class="eval-metric eval-tip" data-tip="图谱有没有用？&#10;正数 ▲ = 图谱帮上忙了，找回了语义搜不到但实体关系能找到的 chunk&#10;零 = 图谱没贡献，语义搜索已经够好&#10;负数 ▼ = 图谱带进了噪音，把好的挤掉了">
            <span class="eval-value" :style="{ color: gainColor(stats.latestEval.graphrag_gain) }">
              {{ gainIcon(stats.latestEval.graphrag_gain) }} {{ pct(Math.abs(stats.latestEval.graphrag_gain)) }}
            </span>
            <span class="eval-label">GraphRAG 增益 <span class="tip-icon">?</span></span>
          </div>
          <div class="eval-metric">
            <span class="eval-value" style="font-size:13px;color:#999">{{ time(stats.latestEval.created_at) }}</span>
            <span class="eval-label">评测时间</span>
          </div>
        </div>
      </section>

      <!-- 历史趋势 -->
      <section class="admin-section" v-if="trends.length > 0">
        <h2>评测历史 (最近 30 次)</h2>
        <table class="eval-table">
          <thead>
            <tr>
              <th>时间</th><th>题数</th>
              <th><span class="th-tip" title="检索准不准：命中 / 召回总数">Precision <span class="tip-icon">?</span></span></th>
              <th><span class="th-tip" title="双路融合 Recall：开了图谱后找全了多少">Recall(fused) <span class="tip-icon">?</span></span></th>
              <th><span class="th-tip" title="纯语义 baseline Recall：不开图谱找全了多少">Recall(vector) <span class="tip-icon">?</span></span></th>
              <th><span class="th-tip" title="fused - vector：正=有增益，负=拖后腿">GraphRAG 增益 <span class="tip-icon">?</span></span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in trends" :key="t.created_at">
              <td>{{ time(t.created_at) }}</td>
              <td>{{ t.total_questions }}</td>
              <td>{{ pct(t.context_precision) }}</td>
              <td>{{ pct(t.context_recall) }}</td>
              <td>{{ pct(t.vector_recall) }}</td>
              <td :style="{ color: gainColor(t.graphrag_gain), fontWeight: 600 }">
                {{ gainIcon(t.graphrag_gain) }} {{ pct(Math.abs(t.graphrag_gain)) }}
              </td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </div>
</template>

<style scoped>
.admin-page { max-width: 960px; margin: 0 auto; padding: 40px 24px; min-height: 100vh; background: #fafbfc; }
.admin-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 32px; }
.admin-head h1 { font-size: 24px; font-weight: 700; color: #222; margin: 0; }
.admin-head button {
  padding: 8px 16px; border: 1px solid #ddd; border-radius: 8px;
  background: #fff; cursor: pointer; font-size: 13px; color: #666;
}
.admin-head button:hover { background: #f5f5f5; }
.admin-err { color: #e55; background: #fef2f2; padding: 12px 16px; border-radius: 8px; margin-bottom: 16px; }

.admin-section { margin-bottom: 32px; }
.admin-section h2 { font-size: 16px; font-weight: 600; color: #555; margin: 0 0 16px; }

.stat-cards { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.stat-card {
  background: #fff; border: 1px solid #e8ecf0; border-radius: 12px;
  padding: 20px 16px; text-align: center; display: flex; flex-direction: column; gap: 8px;
}
.stat-num { font-size: 28px; font-weight: 700; color: #4f46e5; }
.stat-label { font-size: 13px; color: #999; }

.eval-latest {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px;
}
.eval-metric {
  background: #fff; border: 1px solid #e8ecf0; border-radius: 12px;
  padding: 20px 16px; text-align: center; display: flex; flex-direction: column; gap: 6px;
}
.eval-value { font-size: 22px; font-weight: 700; color: #333; }
.eval-label { font-size: 12px; color: #999; }

.eval-table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 12px; overflow: hidden; border: 1px solid #e8ecf0; }
.eval-table th { background: #f8f9fb; padding: 12px 14px; font-size: 12px; font-weight: 600; color: #888; text-align: left; }
.eval-table td { padding: 12px 14px; font-size: 13px; color: #555; border-top: 1px solid #f0f0f0; }
.eval-table tr:hover td { background: #fafbfd; }

/* tooltips */
.eval-tip { position: relative; cursor: help; }
.eval-tip:hover::after {
  content: attr(data-tip);
  position: absolute; bottom: calc(100% + 8px); left: 50%;
  transform: translateX(-50%);
  background: #1e293b; color: #fff;
  padding: 10px 14px; border-radius: 8px;
  font-size: 12px; line-height: 1.6;
  white-space: pre-line; min-width: 220px; max-width: 300px;
  z-index: 100; pointer-events: none;
  text-align: left; font-weight: 400;
}
.tip-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 15px; height: 15px; border-radius: 50%;
  background: #e0e3e8; color: #888; font-size: 10px; font-weight: 700;
  cursor: help; vertical-align: middle; margin-left: 2px;
}
.th-tip { cursor: help; border-bottom: 1px dotted #bbb; }
</style>
