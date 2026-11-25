<template>
  <div class="progress-view">
    <!-- 执行记录列表 -->
    <div v-if="!selectedRun" class="run-list">
      <div class="run-list-header">
        <h3><i class="el-icon-time" /> 分析执行记录</h3>
        <span class="run-count">任务 {{ taskId }} · 共 {{ rows.length }} 条记录</span>
      </div>

      <el-table
        v-loading="loading"
        :data="rows"
        stripe
        @row-click="selectRun"
        class="run-table"
        :row-class-name="runRowClass"
      >
        <el-table-column prop="runId" label="执行编号" width="100" />
        <el-table-column label="状态" width="120">
          <template slot-scope="scope">
            <el-tag size="small" :type="statusType(scope.row.status)">
              {{ statusText(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="stage" label="最后阶段" min-width="140" />
        <el-table-column label="进度" width="180">
          <template slot-scope="scope">
            <div class="progress-bar">
              <div class="progress-fill" :style="{ width: stageProgress(scope.row.stage) + '%' }"></div>
              <span class="progress-text">{{ stageProgress(scope.row.stage) }}%</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="模型调用 / Token" width="160">
          <template slot-scope="scope">
            <span class="stat-text">
              {{ scope.row.modelCalls || 0 }} 次调用
            </span>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" min-width="160">
          <template slot-scope="scope">{{ formatTime(scope.row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template slot-scope="scope">
            <el-button type="text" size="small" @click.stop="selectRun(scope.row)">
              查看详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="nextCursor" class="load-more">
        <el-button size="small" :loading="loadingMore" @click="loadMore">
          加载更早记录
        </el-button>
      </div>
    </div>

    <!-- 详细进度视图 -->
    <div v-else class="detail-view">
      <div class="detail-header">
        <el-button
          type="text"
          icon="el-icon-arrow-left"
          @click="selectedRun = null"
          class="back-btn"
        >
          返回列表
        </el-button>
        <h2>执行 #{{ selectedRun.runId }}</h2>
        <el-tag :type="statusType(selectedRun.status)" size="medium">
          {{ statusText(selectedRun.status) }}
        </el-tag>
      </div>

      <div class="detail-content" v-loading="detailLoading">
        <!-- 总体统计卡片 -->
        <div class="stats-cards">
          <div class="stat-card">
            <i class="el-icon-data-analysis stat-icon"></i>
            <div class="stat-content">
              <div class="stat-label">分析范围</div>
              <div class="stat-value">{{ selectionText(selectedRun.selectionJson) }}</div>
            </div>
          </div>
          <div class="stat-card">
            <i class="el-icon-connection stat-icon"></i>
            <div class="stat-content">
              <div class="stat-label">模型调用</div>
              <div class="stat-value">{{ selectedRun.modelCalls || 0 }} 次</div>
            </div>
          </div>
          <div class="stat-card">
            <i class="el-icon-coin stat-icon"></i>
            <div class="stat-content">
              <div class="stat-label">Token 消耗</div>
              <div class="stat-value">{{ formatTokens(totalTokens) }}</div>
            </div>
          </div>
          <div class="stat-card">
            <i class="el-icon-timer stat-icon"></i>
            <div class="stat-content">
              <div class="stat-label">总耗时</div>
              <div class="stat-value">{{ formatDuration(totalDuration) }}</div>
            </div>
          </div>
        </div>

        <!-- 执行进度时间轴 -->
        <div class="timeline-section">
          <h3><i class="el-icon-loading" v-if="selectedRun.status === 'RUNNING'" /> 执行进度</h3>

          <div class="timeline">
            <div
              v-for="(stage, index) in stageTimeline"
              :key="stage.stage"
              :class="['timeline-item', stageStatusClass(stage)]"
            >
              <div class="timeline-marker">
                <i :class="stageIcon(stage)"></i>
              </div>
              <div class="timeline-content">
                <div class="timeline-header">
                  <h4>{{ stageLabel(stage.stage) }}</h4>
                  <el-tag size="mini" :type="stageTagType(stage)">
                    {{ stageStatusText(stage) }}
                  </el-tag>
                </div>
                <div class="timeline-meta">
                  <span v-if="stage.durationMillis">
                    <i class="el-icon-time"></i> {{ stage.durationMillis }}ms
                  </span>
                  <span v-if="stage.modelCalls">
                    <i class="el-icon-connection"></i> {{ stage.modelCalls }} 次调用
                  </span>
                  <span v-if="stage.tokens">
                    <i class="el-icon-coin"></i> {{ formatTokens(stage.tokens) }}
                  </span>
                </div>
                <div v-if="stage.currentActivity" class="timeline-activity">
                  <i class="el-icon-loading"></i> {{ stage.currentActivity }}
                </div>
                <div v-if="stage.failureType" class="timeline-error">
                  <i class="el-icon-warning"></i> {{ stage.failureType }}
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 模型调用详情（可折叠） -->
        <el-collapse v-model="activeCollapse" class="detail-collapse">
          <el-collapse-item title="模型调用详情" name="calls">
            <el-table
              :data="modelCalls"
              size="small"
              stripe
              max-height="400"
            >
              <el-table-column prop="callNumber" label="序号" width="80" />
              <el-table-column label="阶段" min-width="140">
                <template slot-scope="{ row }">{{ stageLabel(row.stage) }}</template>
              </el-table-column>
              <el-table-column label="结果" width="80">
                <template slot-scope="{ row }">
                  <el-tag size="mini" :type="row.status === 'SUCCEEDED' ? 'success' : 'danger'">
                    {{ row.status === 'SUCCEEDED' ? '成功' : '失败' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="durationMillis" label="耗时 (ms)" width="100" />
              <el-table-column label="Token" width="120">
                <template slot-scope="{ row }">
                  {{ row.inputTokens && row.outputTokens ? formatTokens(row.inputTokens + row.outputTokens) : '—' }}
                </template>
              </el-table-column>
              <el-table-column label="开始时间" min-width="160">
                <template slot-scope="{ row }">{{ formatTime(row.startedAt) }}</template>
              </el-table-column>
              <el-table-column prop="failureType" label="异常" min-width="120" show-overflow-tooltip />
            </el-table>
          </el-collapse-item>

          <!-- 执行日志（可折叠） -->
          <el-collapse-item title="执行日志" name="logs">
            <div class="log-viewer">
              <div v-for="(log, index) in executionLogs" :key="index" class="log-entry">
                <span class="log-time">{{ formatTime(log.timestamp) }}</span>
                <span :class="['log-level', 'log-' + log.level]">{{ log.level }}</span>
                <span class="log-message">{{ log.message }}</span>
              </div>
              <div v-if="!executionLogs.length" class="log-empty">
                暂无执行日志
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>
    </div>
  </div>
</template>

<script>
import { getAnalysisModelCalls, getAnalysisRuns, getAnalysisStageTimings } from '@/api/ontology'

export default {
  name: 'AnalysisProgressView',
  props: {
    taskId: [String, Number],
    visible: Boolean
  },
  data() {
    return {
      loading: false,
      loadingMore: false,
      detailLoading: false,
      rows: [],
      nextCursor: null,
      selectedRun: null,
      stageTimings: [],
      modelCalls: [],
      activeCollapse: ['calls'],
      historyRequest: 0,
      detailRequest: 0
    }
  },
  computed: {
    stageTimeline() {
      if (!this.stageTimings.length) {
        // 如果还没有详细数据，从基本信息推断进度
        return this.inferStageTimeline()
      }
      return this.stageTimings.map(stage => ({
        ...stage,
        modelCalls: this.modelCalls.filter(call => call.stage === stage.stage).length,
        tokens: this.modelCalls
          .filter(call => call.stage === stage.stage)
          .reduce((sum, call) => sum + (call.inputTokens || 0) + (call.outputTokens || 0), 0)
      }))
    },
    totalTokens() {
      return this.modelCalls.reduce((sum, call) =>
        sum + (call.inputTokens || 0) + (call.outputTokens || 0), 0)
    },
    totalDuration() {
      return this.stageTimings.reduce((sum, stage) => sum + (stage.durationMillis || 0), 0)
    },
    executionLogs() {
      // 从阶段时间和模型调用生成日志
      const logs = []
      this.stageTimings.forEach(stage => {
        logs.push({
          timestamp: stage.startedAt,
          level: 'INFO',
          message: `开始执行：${this.stageLabel(stage.stage)}`
        })
        if (stage.status === 'SUCCEEDED') {
          logs.push({
            timestamp: stage.finishedAt,
            level: 'SUCCESS',
            message: `完成：${this.stageLabel(stage.stage)} (${stage.durationMillis}ms)`
          })
        } else if (stage.status === 'FAILED') {
          logs.push({
            timestamp: stage.finishedAt,
            level: 'ERROR',
            message: `失败：${this.stageLabel(stage.stage)} - ${stage.failureType}`
          })
        }
      })
      return logs.sort((a, b) => a.timestamp - b.timestamp)
    }
  },
  watch: {
    visible(value) {
      if (value && this.taskId) this.open(this.taskId)
    },
    taskId(value) {
      if (this.visible && value) this.open(value)
    }
  },
  created() {
    if (this.visible && this.taskId) this.open(this.taskId)
  },
  methods: {
    open(taskId) {
      this.taskId = taskId
      this.selectedRun = null
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
    loadMore() {
      this.load(true)
    },
    async selectRun(row) {
      this.selectedRun = row
      this.detailLoading = true
      this.stageTimings = []
      this.modelCalls = []
      const request = ++this.detailRequest
      try {
        const [stagesRes, callsRes] = await Promise.all([
          getAnalysisStageTimings(this.taskId, row.runId),
          getAnalysisModelCalls(this.taskId, row.runId)
        ])
        if (request === this.detailRequest) {
          this.stageTimings = stagesRes.data || []
          this.modelCalls = callsRes.data || []
        }
      } catch (error) {
        if (request === this.detailRequest) {
          this.$modal.msgError('加载执行详情失败')
        }
      } finally {
        if (request === this.detailRequest) {
          this.detailLoading = false
        }
      }
    },
    inferStageTimeline() {
      if (!this.selectedRun) return []
      const allStages = ['SCAN', 'WIKI_DOCUMENTS', 'BUSINESS_RULES', 'GENERATION', 'MODEL_ENRICHMENT', 'NAME_COMPLETION']
      const currentStageIndex = allStages.indexOf(this.selectedRun.stage)
      return allStages.map((stage, index) => ({
        stage,
        status: index < currentStageIndex ? 'SUCCEEDED' :
                index === currentStageIndex ? (this.selectedRun.status === 'RUNNING' ? 'RUNNING' : this.selectedRun.status) :
                'PENDING'
      }))
    },
    runRowClass({ row }) {
      return row.status === 'RUNNING' ? 'running-row' : ''
    },
    stageProgress(stage) {
      const stages = ['SCAN', 'WIKI_DOCUMENTS', 'BUSINESS_RULES', 'GENERATION', 'MODEL_ENRICHMENT', 'NAME_COMPLETION', 'READY']
      const index = stages.indexOf(stage)
      return index >= 0 ? Math.round((index + 1) / stages.length * 100) : 0
    },
    stageStatusClass(stage) {
      return {
        'timeline-succeeded': stage.status === 'SUCCEEDED',
        'timeline-running': stage.status === 'RUNNING',
        'timeline-failed': stage.status === 'FAILED',
        'timeline-pending': stage.status === 'PENDING'
      }
    },
    stageIcon(stage) {
      if (stage.status === 'SUCCEEDED') return 'el-icon-success'
      if (stage.status === 'FAILED') return 'el-icon-error'
      if (stage.status === 'RUNNING') return 'el-icon-loading'
      return 'el-icon-time'
    },
    stageTagType(stage) {
      if (stage.status === 'SUCCEEDED') return 'success'
      if (stage.status === 'FAILED') return 'danger'
      if (stage.status === 'RUNNING') return 'warning'
      return 'info'
    },
    stageStatusText(stage) {
      if (stage.status === 'SUCCEEDED') return '已完成'
      if (stage.status === 'FAILED') return '失败'
      if (stage.status === 'RUNNING') return '进行中'
      return '等待中'
    },
    stageLabel(stage) {
      const labels = {
        SCAN: '数据源结构扫描',
        WIKI_DOCUMENTS: 'Wiki 文档检索',
        BUSINESS_RULES: '业务规则检索',
        GENERATION: '候选生成',
        MODEL_ENRICHMENT: '模型语义补充',
        NAME_COMPLETION: '中文名称补全'
      }
      return labels[stage] || stage
    },
    statusText(status) {
      const texts = {
        RUNNING: '执行中',
        DONE: '已完成',
        FAILED: '失败',
        CANCELLED: '已取消',
        INTERRUPTED: '已中断'
      }
      return texts[status] || status
    },
    statusType(status) {
      const types = {
        RUNNING: 'warning',
        DONE: 'success',
        FAILED: 'danger',
        CANCELLED: 'info',
        INTERRUPTED: 'danger'
      }
      return types[status] || 'info'
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
      if (!value) return '—'
      return new Date(Number(value)).toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
      })
    },
    formatTokens(tokens) {
      if (!tokens) return '0'
      if (tokens >= 1000) return (tokens / 1000).toFixed(1) + 'k'
      return tokens.toString()
    },
    formatDuration(ms) {
      if (!ms) return '—'
      if (ms < 1000) return ms + 'ms'
      const seconds = Math.floor(ms / 1000)
      if (seconds < 60) return seconds + '秒'
      const minutes = Math.floor(seconds / 60)
      const remainingSeconds = seconds % 60
      return `${minutes}分${remainingSeconds}秒`
    }
  }
}
</script>

<style scoped lang="scss">
.progress-view {
  padding: 20px;
  background: #f5f7fa;
  min-height: calc(100vh - 100px);
}

// ============ 执行记录列表 ============
.run-list {
  background: white;
  border-radius: 8px;
  padding: 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.run-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;

  h3 {
    margin: 0;
    font-size: 18px;
    color: #303133;
    font-weight: 600;

    i {
      color: #409eff;
      margin-right: 8px;
    }
  }

  .run-count {
    color: #909399;
    font-size: 14px;
  }
}

.run-table {
  ::v-deep .running-row {
    background-color: #f0f9ff !important;

    &:hover > td {
      background-color: #e6f4ff !important;
    }
  }

  ::v-deep .el-table__row {
    cursor: pointer;
    transition: all 0.2s;

    &:hover {
      transform: translateY(-2px);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
    }
  }
}

.progress-bar {
  position: relative;
  height: 24px;
  background: #f0f2f5;
  border-radius: 12px;
  overflow: hidden;

  .progress-fill {
    position: absolute;
    top: 0;
    left: 0;
    height: 100%;
    background: linear-gradient(90deg, #409eff, #66b1ff);
    border-radius: 12px;
    transition: width 0.3s ease;
  }

  .progress-text {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    font-size: 12px;
    font-weight: 600;
    color: #303133;
    z-index: 1;
  }
}

.stat-text {
  font-size: 13px;
  color: #606266;
  font-weight: 500;
}

.load-more {
  margin-top: 16px;
  text-align: center;
}

// ============ 详细进度视图 ============
.detail-view {
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  overflow: hidden;
}

.detail-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;

  .back-btn {
    color: white;
    font-size: 14px;

    &:hover {
      color: #e6f4ff;
    }
  }

  h2 {
    flex: 1;
    margin: 0;
    font-size: 20px;
    font-weight: 600;
  }
}

.detail-content {
  padding: 24px;
}

// ============ 统计卡片 ============
.stats-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 32px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  background: linear-gradient(135deg, #f5f7fa 0%, #ffffff 100%);
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  transition: all 0.3s;

  &:hover {
    transform: translateY(-4px);
    box-shadow: 0 8px 16px rgba(0, 0, 0, 0.1);
    border-color: #409eff;
  }

  .stat-icon {
    font-size: 32px;
    color: #409eff;
  }

  .stat-content {
    flex: 1;

    .stat-label {
      font-size: 12px;
      color: #909399;
      margin-bottom: 4px;
    }

    .stat-value {
      font-size: 20px;
      font-weight: 600;
      color: #303133;
    }
  }
}

// ============ 时间轴 ============
.timeline-section {
  margin-bottom: 32px;

  h3 {
    margin: 0 0 24px;
    font-size: 16px;
    font-weight: 600;
    color: #303133;

    i {
      color: #409eff;
      margin-right: 8px;
    }
  }
}

.timeline {
  position: relative;
  padding-left: 40px;

  &::before {
    content: '';
    position: absolute;
    left: 15px;
    top: 0;
    bottom: 0;
    width: 2px;
    background: #e4e7ed;
  }
}

.timeline-item {
  position: relative;
  padding-bottom: 32px;

  &:last-child {
    padding-bottom: 0;
  }

  .timeline-marker {
    position: absolute;
    left: -40px;
    top: 0;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    background: white;
    border: 2px solid #e4e7ed;
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1;

    i {
      font-size: 14px;
      color: #909399;
    }
  }

  .timeline-content {
    background: #fafafa;
    border: 1px solid #e4e7ed;
    border-radius: 8px;
    padding: 16px;
    transition: all 0.3s;

    &:hover {
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
    }
  }

  .timeline-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;

    h4 {
      margin: 0;
      font-size: 15px;
      font-weight: 600;
      color: #303133;
    }
  }

  .timeline-meta {
    display: flex;
    gap: 16px;
    font-size: 13px;
    color: #606266;

    span {
      display: flex;
      align-items: center;
      gap: 4px;

      i {
        color: #909399;
      }
    }
  }

  .timeline-activity {
    margin-top: 8px;
    padding: 8px 12px;
    background: #e6f4ff;
    border-left: 3px solid #409eff;
    border-radius: 4px;
    font-size: 13px;
    color: #409eff;

    i {
      margin-right: 6px;
    }
  }

  .timeline-error {
    margin-top: 8px;
    padding: 8px 12px;
    background: #fef0f0;
    border-left: 3px solid #f56c6c;
    border-radius: 4px;
    font-size: 13px;
    color: #f56c6c;

    i {
      margin-right: 6px;
    }
  }

  // 状态样式
  &.timeline-succeeded {
    .timeline-marker {
      border-color: #67c23a;
      background: #f0f9ff;

      i {
        color: #67c23a;
      }
    }

    .timeline-content {
      border-color: #c6e2d4;
      background: #f0f9f4;
    }
  }

  &.timeline-running {
    .timeline-marker {
      border-color: #409eff;
      background: #e6f4ff;
      animation: pulse 2s infinite;

      i {
        color: #409eff;
      }
    }

    .timeline-content {
      border-color: #b3d8ff;
      background: #ecf5ff;
    }
  }

  &.timeline-failed {
    .timeline-marker {
      border-color: #f56c6c;
      background: #fef0f0;

      i {
        color: #f56c6c;
      }
    }

    .timeline-content {
      border-color: #fbc4c4;
      background: #fef0f0;
    }
  }

  &.timeline-pending {
    opacity: 0.6;

    .timeline-marker {
      border-color: #dcdfe6;

      i {
        color: #c0c4cc;
      }
    }
  }
}

@keyframes pulse {
  0%, 100% {
    transform: scale(1);
    box-shadow: 0 0 0 0 rgba(64, 158, 255, 0.4);
  }
  50% {
    transform: scale(1.1);
    box-shadow: 0 0 0 10px rgba(64, 158, 255, 0);
  }
}

// ============ 折叠面板 ============
.detail-collapse {
  border: 1px solid #e4e7ed;
  border-radius: 8px;

  ::v-deep .el-collapse-item__header {
    padding: 16px 20px;
    font-weight: 600;
    font-size: 14px;
    background: #fafafa;
  }

  ::v-deep .el-collapse-item__content {
    padding: 20px;
  }
}

// ============ 日志查看器 ============
.log-viewer {
  background: #1e1e1e;
  border-radius: 4px;
  padding: 16px;
  max-height: 400px;
  overflow-y: auto;
  font-family: 'Monaco', 'Menlo', 'Courier New', monospace;

  .log-entry {
    display: flex;
    gap: 12px;
    padding: 4px 0;
    font-size: 12px;
    line-height: 1.6;

    .log-time {
      color: #858585;
    }

    .log-level {
      font-weight: 600;
      min-width: 60px;
      text-align: center;
    }

    .log-INFO {
      color: #4fc3f7;
    }

    .log-SUCCESS {
      color: #66bb6a;
    }

    .log-ERROR {
      color: #ef5350;
    }

    .log-message {
      color: #d4d4d4;
      flex: 1;
    }
  }

  .log-empty {
    color: #858585;
    text-align: center;
    padding: 40px 0;
  }
}
</style>