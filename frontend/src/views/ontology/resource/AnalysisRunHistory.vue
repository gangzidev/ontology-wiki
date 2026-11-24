<template>
  <div>
  <section v-if="embedded" class="analysis-history" v-loading="loading">
    <h3>执行记录</h3>
    <el-table :data="rows" border size="small" max-height="240" empty-text="暂无执行记录">
      <el-table-column prop="runId" label="编号" width="76" />
      <el-table-column label="状态" width="90"><template slot-scope="scope"><el-tag size="mini" :type="statusType(scope.row.status)">{{ statusText(scope.row.status) }}</el-tag></template></el-table-column>
      <el-table-column prop="stage" label="最后阶段" min-width="110" />
      <el-table-column prop="modelCalls" label="模型调用" width="90" />
      <el-table-column label="数据范围" min-width="130"><template slot-scope="scope">{{ selectionText(scope.row.selectionJson) }}</template></el-table-column>
      <el-table-column label="开始时间" min-width="145"><template slot-scope="scope">{{ formatTime(scope.row.startedAt) }}</template></el-table-column>
      <el-table-column label="追踪" width="140"><template slot-scope="scope"><el-button type="text" @click="openDetails(scope.row, 'stages')">阶段耗时</el-button><el-button type="text" @click="openDetails(scope.row, 'calls')">模型调用</el-button></template></el-table-column>
    </el-table>
    <el-button v-if="nextCursor" size="mini" :loading="loadingMore" class="history-more" @click="loadMore">加载更早记录</el-button>
    <el-dialog :title="detailTitle" :visible.sync="detailsVisible" width="min(760px, 94vw)" append-to-body>
      <el-alert v-if="detailError" title="读取追踪记录失败" type="error" :closable="false" />
      <el-table v-else v-loading="detailLoading" :data="details" border size="small" empty-text="暂无追踪记录">
        <el-table-column v-if="detailKind === 'calls'" prop="callNumber" label="序号" width="65" />
        <el-table-column label="阶段" min-width="120"><template slot-scope="{ row }">{{ stageLabel(row.stage) }}</template></el-table-column>
        <el-table-column label="结果" width="65"><template slot-scope="{ row }">{{ row.status === 'SUCCEEDED' ? '成功' : '失败' }}</template></el-table-column>
        <el-table-column prop="failureType" label="异常类型" min-width="110" show-overflow-tooltip />
        <el-table-column prop="durationMillis" label="耗时 (ms)" width="90" />
        <el-table-column label="开始时间" min-width="155"><template slot-scope="{ row }">{{ formatTime(row.startedAt) }}</template></el-table-column>
      </el-table>
    </el-dialog>
  </section>
  <el-dialog v-else title="分析执行记录" :visible.sync="visible" width="min(1040px, 94vw)" append-to-body>
    <el-table v-loading="loading" :data="rows" border size="small">
      <el-table-column prop="runId" label="执行编号" width="100" />
      <el-table-column label="执行方式" width="110">
        <template slot-scope="scope">{{ scope.row.runKind === 'RESUME' ? '检查点恢复' : '开始分析' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template slot-scope="scope">
          <el-tag size="mini" :type="statusType(scope.row.status)">{{ statusText(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="stage" label="最后阶段" min-width="110" />
      <el-table-column prop="modelCalls" label="模型调用" width="90" />
      <el-table-column prop="proposalId" label="草案编号" width="90" />
      <el-table-column label="输入范围" min-width="145">
        <template slot-scope="scope">{{ selectionText(scope.row.selectionJson) }}</template>
      </el-table-column>
      <el-table-column label="数据范围" min-width="145">
        <template slot-scope="scope">数据源 {{ scope.row.sourceId || '—' }} · 空间 {{ scope.row.wikiSpaceId || '—' }}</template>
      </el-table-column>
      <el-table-column label="开始时间" min-width="155">
        <template slot-scope="scope">{{ formatTime(scope.row.startedAt) }}</template>
      </el-table-column>
      <el-table-column label="追踪" width="145">
        <template slot-scope="scope">
          <el-button type="text" @click="openDetails(scope.row, 'stages')">阶段耗时</el-button>
          <el-button type="text" @click="openDetails(scope.row, 'calls')">模型调用</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !rows.length" description="暂无执行记录" :image-size="56" />
    <div class="history-footer">
      <span>任务 {{ taskId }}</span>
      <el-button v-if="nextCursor" size="mini" :loading="loadingMore" @click="loadMore">加载更早记录</el-button>
    </div>
    <el-dialog :title="detailTitle" :visible.sync="detailsVisible" width="min(760px, 94vw)" append-to-body>
      <el-alert v-if="detailError" title="读取追踪记录失败" type="error" :closable="false" />
      <el-table v-else v-loading="detailLoading" :data="details" border size="small" empty-text="暂无追踪记录">
        <el-table-column v-if="detailKind === 'calls'" prop="callNumber" label="序号" width="65" />
        <el-table-column label="阶段" min-width="120">
          <template slot-scope="{ row }">{{ stageLabel(row.stage) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="65">
          <template slot-scope="{ row }">{{ row.status === 'SUCCEEDED' ? '成功' : '失败' }}</template>
        </el-table-column>
        <el-table-column prop="failureType" label="异常类型" min-width="110" show-overflow-tooltip />
        <el-table-column prop="durationMillis" label="耗时 (ms)" width="90" />
        <el-table-column label="开始时间" min-width="155">
          <template slot-scope="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </el-dialog>
  </div>
</template>

<script>
import { getAnalysisModelCalls, getAnalysisRuns, getAnalysisStageTimings } from '@/api/ontology'

export default {
  name: 'AnalysisRunHistory',
  props: { embedded: Boolean, taskId: [String, Number], visible: Boolean },
  data() {
    return { loading: false, loadingMore: false, rows: [], nextCursor: null,
      historyRequest: 0, detailRequest: 0, detailKind: '', detailRunId: null, detailsVisible: false,
      detailLoading: false, detailError: false, details: [] }
  },
  computed: {
    detailTitle() {
      const label = this.detailKind === 'calls' ? '模型调用' : '阶段耗时'
      return `执行 #${this.detailRunId} · ${label}`
    }
  },
  watch: {
    visible(value) { if (value && this.taskId) this.open(this.taskId) },
    taskId(value) { if (this.visible && value) this.open(value) }
  },
  created() { if (this.embedded && this.visible && this.taskId) this.open(this.taskId) },
  methods: {
    open(taskId) {
      this.detailRequest++
      this.detailsVisible = false
      this.loading = false
      this.loadingMore = false
      this.taskId = taskId
      this.rows = []
      this.nextCursor = null
      this.load(false)
    },
    load(more) {
      if (!this.taskId || this.loading || this.loadingMore) return
      if (more) this.loadingMore = true
      else this.loading = true
      const request = ++this.historyRequest
      const params = { limit: 20 }
      if (more && this.nextCursor) params.before = this.nextCursor
      getAnalysisRuns(this.taskId, params).then(({ data }) => {
        if (request !== this.historyRequest) return
        const page = data || {}
        this.rows = more ? this.rows.concat(page.rows || []) : (page.rows || [])
        this.nextCursor = page.nextCursor || null
      }).catch(() => {
        if (request === this.historyRequest) this.$modal.msgError('执行记录加载失败')
      }).finally(() => {
        if (request !== this.historyRequest) return
        this.loading = false
        this.loadingMore = false
      })
    },
    loadMore() { this.load(true) },
    async openDetails(row, kind) {
      const request = ++this.detailRequest
      this.detailRunId = row.runId
      this.detailKind = kind
      this.details = []
      this.detailsVisible = true
      this.detailError = false
      this.detailLoading = true
      const fetchDetails = kind === 'calls' ? getAnalysisModelCalls : getAnalysisStageTimings
      try {
        const { data } = await fetchDetails(this.taskId, row.runId)
        if (request === this.detailRequest) this.details = data || []
      } catch (error) {
        if (request === this.detailRequest) this.detailError = true
      } finally {
        if (request === this.detailRequest) this.detailLoading = false
      }
    },
    stageLabel(stage) {
      return { SCAN: '数据源结构扫描', WIKI_DOCUMENTS: 'Wiki 文档检索', BUSINESS_RULES: '业务规则检索',
        GENERATION: '候选生成', MODEL_ENRICHMENT: '模型语义补充', NAME_COMPLETION: '中文名称补全',
        GENERATED: '模型语义补充', NAMING: '中文名称补全' }[stage] || stage
    },
    statusText(status) {
      return { RUNNING: '执行中', DONE: '已完成', FAILED: '失败', CANCELLED: '已取消', INTERRUPTED: '已中断' }[status] || status
    },
    statusType(status) {
      return { RUNNING: 'warning', DONE: 'success', FAILED: 'danger', CANCELLED: 'info', INTERRUPTED: 'danger' }[status] || 'info'
    },
    selectionText(value) {
      try {
        const selection = JSON.parse(value || '{}')
        const tables = selection.tables || []
        const scope = tables.length ? `${tables.length} 张指定表` : '全数据源'
        const mode = selection.mode === 'INCREMENTAL' ? '增量' : (selection.mode ? '完整' : '')
        return [scope, mode].filter(Boolean).join(' · ')
      } catch (error) {
        return '范围记录不可用'
      }
    },
    formatTime(value) {
      return value ? new Date(Number(value)).toLocaleString() : '—'
    }
  }
}
</script>

<style scoped>
.history-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; color: #909399; }
</style>
