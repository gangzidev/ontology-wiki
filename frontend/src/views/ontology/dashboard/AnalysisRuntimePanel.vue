<template>
  <section class="runtime-panel" v-loading="loading">
    <div class="runtime-heading">
      <h2>分析执行器</h2>
      <el-button icon="el-icon-refresh" size="mini" :disabled="loading" @click="refresh">刷新</el-button>
    </div>
    <el-alert v-if="failed" title="读取运行指标失败" type="error" :closable="false" />
    <template v-else-if="metrics">
      <p>当前实例自 {{ new Date(metrics.since).toLocaleString() }} 起统计。正在执行 {{ metrics.activeWorkers }}，排队 {{ metrics.queuedJobs }}，调度失败 {{ metrics.dispatchFailures }} 次。</p>
      <el-table :data="rows" size="small">
        <el-table-column prop="label" label="环节" min-width="120" />
        <el-table-column prop="count" label="次数" />
        <el-table-column prop="failures" label="失败数" />
        <el-table-column label="平均耗时 (ms)" min-width="110"><template slot-scope="{ row }">{{ duration(row.meanMs) }}</template></el-table-column>
        <el-table-column label="最长耗时 (ms)" min-width="110"><template slot-scope="{ row }">{{ duration(row.maxMs) }}</template></el-table-column>
      </el-table>
    </template>
  </section>
</template>

<script>
import { getAnalysisRuntimeMetrics } from '@/api/ontology'

const labels = {
  queueWait: '入队至开始执行', scan: '数据源结构扫描', wikiDocuments: 'Wiki 证据检索',
  businessRules: '业务规则检索', generation: '候选生成', modelEnrichment: '模型语义补充',
  nameCompletion: '中文名称补全', model: '分析模型调用'
}

export default {
  name: 'AnalysisRuntimePanel',
  data: () => ({ metrics: null, loading: false, failed: false }),
  computed: {
    rows() {
      return Object.keys(labels).map(key => ({ label: labels[key], ...this.metrics[key] }))
    }
  },
  created() { this.refresh() },
  methods: {
    duration(value) { return value == null ? '—' : value.toLocaleString(undefined, { maximumFractionDigits: 1 }) },
    async refresh() {
      this.loading = true
      this.failed = false
      try {
        const { data } = await getAnalysisRuntimeMetrics()
        this.metrics = data
      } catch (error) {
        this.failed = true
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style scoped>
.runtime-panel { margin-top: 24px; }
.runtime-heading { display: flex; align-items: center; justify-content: space-between; }
.runtime-heading h2 { margin: 0; font-size: 16px; }
.runtime-panel p { color: #606266; font-size: 13px; }
</style>
