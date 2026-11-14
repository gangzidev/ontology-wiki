<template>
  <div class="dq-shell">

    <!-- ══════════════════════════════════════════════════════════
         顶栏：配置选择 + 状态徽章 + 评测入口
    ══════════════════════════════════════════════════════════ -->
    <header class="dq-header">
      <div class="dq-header-brand">
        <span class="dq-logo-icon"><i class="el-icon-connection" /></span>
        <div>
          <p class="dq-eyebrow">ONTOLOGY QUERY</p>
          <h1 class="dq-title">用业务语言，直接看数据</h1>
          <p class="dq-subtitle">已审核的指标和关系驱动每一次查询，结果可追溯、可复核。</p>
        </div>
      </div>

      <div class="dq-header-controls">
        <el-select
          v-model="selectedProposalId"
          filterable
          placeholder="选择本体配置…"
          size="small"
          class="dq-proposal-select"
          :loading="proposalsLoading"
          @change="onProposalChange"
        >
          <el-option
            v-for="item in proposals"
            :key="item.proposal_id"
            :value="String(item.proposal_id)"
            :label="proposalLabel(item)"
          />
        </el-select>

        <div v-if="readiness" class="dq-status-wrap">
          <el-popover placement="bottom-end" width="300" trigger="hover" popper-class="dq-readiness-popover">
            <div class="dq-readiness-popover-content">
              <strong>{{ readiness.queryEnabled ? '查询已开放' : readiness.eligibleForEvaluation ? '完成评测后开放查询' : '暂不可查询' }}</strong>
              <ul v-if="readinessReasons.length">
                <li v-for="reason in readinessReasons" :key="reason">{{ readinessReasonText(reason) }}</li>
              </ul>
              <p v-else>请先完善本体配置中的指标、维度、关系和标准答案评测。</p>
            </div>
            <span slot="reference" class="dq-status-badge" :class="readiness.queryEnabled ? 'badge-ready' : readiness.eligibleForEvaluation ? 'badge-pending' : 'badge-off'">
              <i :class="readiness.queryEnabled ? 'el-icon-success' : 'el-icon-warning-outline'" />
              {{ readiness.queryEnabled ? '可执行' : readiness.eligibleForEvaluation ? '待评测' : '未就绪' }}
              <i v-if="!readiness.queryEnabled" class="el-icon-question dq-status-help" />
            </span>
          </el-popover>
          <small v-if="!readiness.queryEnabled" class="dq-status-detail">{{ readinessReasonSummary }}</small>
          <button v-if="!readiness.queryEnabled" type="button" class="dq-status-action" @click="showReadinessGuide">为什么不能查询？</button>
        </div>
        <el-button
          v-if="readiness && readiness.eligibleForEvaluation && !readiness.queryEnabled"
          class="dq-evaluate-btn"
          size="small"
          type="primary"
          icon="el-icon-odometer"
          @click="openEvaluation"
        >开始标准问题评测</el-button>
      </div>
    </header>

    <div class="dq-workspace">
      <!-- 左侧语义目录：树节点点击后加入问题输入框。 -->
      <aside class="dq-catalog-panel">
        <div class="dq-catalog-panel-head">
          <div>
            <strong><i class="el-icon-price-tag" /> 语义目录</strong>
            <small>点击指标或维度加入问题</small>
          </div>
          <span v-if="catalog" class="dq-catalog-count">{{ catalogItemCount }}</span>
        </div>
        <el-input v-model="catalogSearch" clearable size="small" prefix-icon="el-icon-search" placeholder="搜索名称或编号" class="dq-catalog-search" />
        <el-tree
          v-if="catalogItemCount"
          ref="catalogTree"
          class="dq-catalog-tree"
          :data="catalogTreeData"
          node-key="key"
          :props="catalogTreeProps"
          :filter-node-method="filterTreeNode"
          default-expand-all
          highlight-current
          @node-click="onCatalogNodeClick"
        >
          <span slot-scope="{ node, data }" class="dq-tree-node">
            <i :class="data.kind === 'metric' ? 'el-icon-data-analysis' : data.kind === 'dimension' ? 'el-icon-s-operation' : data.kind === 'timeDimension' ? 'el-icon-date' : 'el-icon-folder-opened'" />
            <span class="dq-tree-label">{{ node.label }}</span>
          </span>
        </el-tree>
        <div v-else class="dq-catalog-empty"><i :class="catalogLoading ? 'el-icon-loading' : 'el-icon-folder-opened'" /><span>{{ catalogLoading ? '正在加载语义目录…' : '暂无语义目录' }}</span></div>
      </aside>

      <main class="dq-main">
        <!-- ══════════════════════════════════════════════════════════
             对话区
        ══════════════════════════════════════════════════════════ -->
        <div ref="feed" class="dq-feed">

      <!-- 空态引导 -->
      <div v-if="!messages.length" class="dq-empty">
        <div class="dq-empty-icon"><i class="el-icon-chat-line-round" /></div>
        <h2>从一个业务问题开始</h2>
        <p>指标、维度和关系来自已审核的本体配置，模型只在白名单内工作。</p>
        <div v-if="!selectedProposalId" class="dq-empty-hint">
          <i class="el-icon-arrow-up" /> 先选择一个本体配置
        </div>
        <div v-else-if="exampleQuestions.length" class="dq-example-grid">
          <button
            v-for="(ex, i) in exampleQuestions"
            :key="i"
            type="button"
            class="dq-example-btn"
            @click="sendQuestion(ex)"
          >
            <i class="el-icon-chat-dot-round" />
            <span>{{ ex }}</span>
          </button>
        </div>
        <div v-if="recentQuestions.length" class="dq-recent-questions">
          <span>最近查询</span>
          <button v-for="(question, i) in recentQuestions" :key="i" type="button" @click="inputText = question">{{ question }}</button>
        </div>
      </div>

      <!-- 消息列表 -->
      <template v-for="(msg, idx) in messages">

        <!-- 用户气泡 -->
        <div :key="'q-' + idx" class="dq-bubble dq-bubble-user">
          <span>{{ msg.question }}</span>
        </div>

        <!-- 助手回答卡片 -->
        <div :key="'a-' + idx" class="dq-answer-card">

          <!-- 加载中 -->
          <div v-if="msg.loading" class="dq-answer-loading">
            <span class="dq-dot-anim" /><span class="dq-dot-anim dq-dot-2" /><span class="dq-dot-anim dq-dot-3" />
            <span>{{ queryStageText }}<small v-if="queryElapsed"> · 已等待 {{ queryElapsed }} 秒</small></span>
          </div>

          <!-- 追问/澄清 -->
          <div v-else-if="msg.type === 'clarification'" class="dq-clarification">
            <i class="el-icon-question" />
            <p>{{ msg.clarification }}</p>
          </div>

          <!-- 预览（未评测） -->
          <div v-else-if="msg.type === 'preview'" class="dq-preview-block">
            <div class="dq-preview-notice">
              <i class="el-icon-warning-outline" />
              <div>
                <strong>尚未通过标准问答评测，仅展示查询计划</strong>
                <p>{{ (msg.readinessReasons || []).map(readinessReasonText).join('；') }}</p>
              </div>
            </div>
            <div class="dq-plan-chips">
              <span v-for="m in (msg.plan && msg.plan.metrics || [])" :key="'pm-'+m.id" class="dq-plan-chip chip-metric">
                <i class="el-icon-data-analysis" />{{ m.id }}
              </span>
              <span v-for="d in (msg.plan && msg.plan.dimensions || [])" :key="'pd-'+d.id" class="dq-plan-chip chip-dim">
                <i class="el-icon-s-operation" />{{ d.id }}
              </span>
            </div>
            <el-collapse class="dq-sql-collapse">
              <el-collapse-item name="sql">
                <template slot="title"><i class="el-icon-document" /> SQL 预览</template>
                <pre>{{ msg.sql }}</pre>
              </el-collapse-item>
            </el-collapse>
          </div>

          <!-- 正常结果 -->
          <template v-else-if="msg.type === 'result'">

            <!-- 语义证据条 -->
            <div v-if="msg.semanticEvidence" class="dq-evidence-bar">
              <span v-if="msg.semanticEvidence.metric"><i class="el-icon-data-analysis" />{{ msg.semanticEvidence.metric }}</span>
              <span v-if="msg.semanticEvidence.dimension"><i class="el-icon-s-operation" />{{ msg.semanticEvidence.dimension }}</span>
              <span class="dq-evidence-rows">{{ msg.rowCount }} 行</span>
              <button v-if="msg.type === 'result' && msg.rows && msg.rows.length" type="button" class="dq-export-btn" @click="exportRows(msg)"><i class="el-icon-download" /> 导出 CSV</button>
            </div>

            <div v-if="msg.plan" class="dq-plan-summary">
              <span v-for="m in (msg.plan.metrics || [])" :key="'summary-m-' + m.id"><b>指标</b>{{ semanticName('metrics', m.id) }}</span>
              <span v-for="d in (msg.plan.dimensions || [])" :key="'summary-d-' + d.id"><b>维度</b>{{ semanticName('dimensions', d.id) }}</span>
              <span v-if="msg.plan.timeRange"><b>时间</b>{{ msg.plan.timeRange.from }} 至 {{ msg.plan.timeRange.to }}</span>
              <span v-if="msg.plan.comparison"><b>对比</b>{{ comparisonLabel(msg.plan.comparison.type) }}</span>
            </div>

            <!-- 图表 / 表格切换 -->
            <div class="dq-view-toggle">
              <button
                type="button"
                :class="['dq-toggle-btn', { active: msg.viewMode === 'chart' }]"
                :disabled="!canChart(msg)"
                @click="setViewMode(msg, 'chart')"
              >
                <i class="el-icon-data-analysis" /> 图表
              </button>
              <button
                type="button"
                :class="['dq-toggle-btn', { active: msg.viewMode === 'table' }]"
                @click="setViewMode(msg, 'table')"
              >
                <i class="el-icon-s-grid" /> 表格
              </button>
            </div>

            <!-- stat 数值卡片 -->
            <div v-if="msg.viewMode === 'chart' && msg.chartHint && msg.chartHint.type === 'stat'" class="dq-stat-card">
              <div class="dq-stat-value">{{ statValue(msg) }}</div>
              <div class="dq-stat-label">{{ msg.chartHint.label || '' }}</div>
            </div>

            <!-- ECharts 图表容器 -->
            <div
              v-else-if="msg.viewMode === 'chart' && canChart(msg)"
              :ref="'chart-' + idx"
              class="dq-chart-container"
            />

            <!-- 表格 -->
            <el-table
              v-else
              :data="msg.rows"
              stripe
              size="small"
              max-height="360"
              class="dq-result-table"
              empty-text="查询完成，没有匹配结果"
            >
              <el-table-column
                v-for="col in (msg.columns || [])"
                :key="col"
                :prop="col"
                :label="col"
                min-width="120"
                show-overflow-tooltip
              />
              <el-table-column v-if="msg.plan && msg.plan.dimensions && msg.plan.dimensions.length && hasDrillTarget(msg)" label="分析" width="104" fixed="right">
                <template slot-scope="scope">
                  <el-button type="text" size="mini" :disabled="sending" @click="drilldown(msg, scope.row)">继续下钻</el-button>
                </template>
              </el-table-column>
            </el-table>

            <div v-if="msg.plan && msg.plan.dimensions && msg.plan.dimensions.length && availableDrillDimensions(msg).length" class="dq-drill-bar">
              <span><i class="el-icon-share" /> 选择下一级维度</span>
              <el-select v-model="msg.nextDimensionId" size="mini" placeholder="选择维度" class="dq-drill-select">
                <el-option v-for="dimension in availableDrillDimensions(msg)" :key="dimension.id" :label="dimension.name || dimension.id" :value="dimension.id" />
              </el-select>
              <small>切换到表格后，点击一行即可沿用当前筛选继续分析。</small>
            </div>

            <!-- SQL 折叠 -->
            <el-collapse class="dq-sql-collapse">
              <el-collapse-item name="sql">
                <template slot="title">
                  <i class="el-icon-document" /> SQL 依据
                  <small style="margin-left:auto;color:#94a3b8;font-size:11px">{{ msg.configHash }}</small>
                </template>
                <pre>{{ msg.sql }}</pre>
              </el-collapse-item>
            </el-collapse>
          </template>

          <!-- 错误 -->
          <div v-else-if="msg.type === 'error'" class="dq-error-block">
            <i class="el-icon-warning" />{{ msg.error }}
          </div>

        </div>
      </template>

      <!-- 底部锚点 -->
      <div ref="feedBottom" />
        </div>

        <!-- ══════════════════════════════════════════════════════════
             输入区
        ══════════════════════════════════════════════════════════ -->
        <el-alert v-if="readiness && !readiness.queryEnabled" class="dq-readiness-guide" type="warning" :closable="false" show-icon>
          <strong>{{ readinessGuideTitle }}</strong>
          <ol>
            <li v-for="reason in readinessReasons" :key="`guide-${reason}`">{{ readinessReasonText(reason) }}</li>
          </ol>
          <p>{{ readinessGuideHint }}</p>
          <el-button v-if="!readiness.eligibleForEvaluation" size="mini" type="primary" plain icon="el-icon-chat-dot-round" @click="goConfigureSemantics">去分析问答补充指标和维度</el-button>
          <el-button v-else-if="!readiness.queryEnabled" size="mini" type="primary" icon="el-icon-odometer" @click="openEvaluation">进入标准问题评测</el-button>
        </el-alert>
        <div class="dq-composer">
          <div v-if="selectedProposalId && catalog" class="dq-composer-tools">
            <span class="dq-tool-label"><i class="el-icon-magic-stick" /> 快速补充</span>
            <el-dropdown trigger="click" @command="appendQuestion">
              <button type="button" class="dq-tool-btn">时间范围 <i class="el-icon-arrow-down" /></button>
              <el-dropdown-menu slot="dropdown">
                <el-dropdown-item v-for="item in timeShortcuts" :key="item.label" :command="item.value">{{ item.label }}</el-dropdown-item>
              </el-dropdown-menu>
            </el-dropdown>
            <el-dropdown trigger="click" @command="appendQuestion">
              <button type="button" class="dq-tool-btn">对比 <i class="el-icon-arrow-down" /></button>
              <el-dropdown-menu slot="dropdown">
                <el-dropdown-item command="同比">同比</el-dropdown-item>
                <el-dropdown-item command="环比">环比</el-dropdown-item>
              </el-dropdown-menu>
            </el-dropdown>
            <span class="dq-tool-hint">也可以点击左侧目录快速插入</span>
          </div>
          <div class="dq-composer-inner">
        <el-input
          v-model="inputText"
          type="textarea"
          :rows="1"
          :autosize="{ minRows: 1, maxRows: 5 }"
          resize="none"
          :placeholder="selectedProposalId ? '输入业务问题，Ctrl+Enter 发送…' : '请先选择本体配置'"
          :disabled="!selectedProposalId || sending"
          maxlength="2000"
          class="dq-input"
          @keydown.ctrl.enter.native.prevent="submit"
          @keydown.meta.enter.native.prevent="submit"
        />
        <button
          type="button"
          class="dq-send-btn"
          :disabled="!canSend"
          @click="submit"
        >
          <i v-if="sending" class="el-icon-loading" />
          <i v-else class="el-icon-s-promotion" />
        </button>
      </div>
          <p class="dq-composer-hint">Ctrl + Enter 发送 &nbsp;·&nbsp; 结果来自已审核的本体配置</p>
        </div>
      </main>
    </div>

    <el-dialog
      title="标准问题评测"
      :visible.sync="evaluationDialog.visible"
      width="780px"
      append-to-body
      custom-class="dq-evaluation-dialog"
      :close-on-click-modal="false"
    >
      <div class="dq-evaluation-intro">
        <i class="el-icon-info" />
        <div><strong>评测通过后才会开放真实数据查询</strong><p>为配置添加真实业务问题，核对系统生成的查询计划，并填写业务确认过的标准结果。所有用例通过后，右上角状态会变为“可执行”。</p></div>
      </div>
      <el-form label-width="92px" size="small">
        <el-form-item label="评测版本"><el-input v-model="evaluationDialog.version" maxlength="100" placeholder="例如 v1" /></el-form-item>
        <div v-for="(item, index) in evaluationCases" :key="item.key" class="dq-evaluation-case">
          <div class="dq-evaluation-case-head"><strong>标准问题 {{ index + 1 }}</strong><el-button v-if="evaluationCases.length > 1" type="text" icon="el-icon-delete" @click="removeEvaluationCase(index)">删除</el-button></div>
          <el-form-item label="业务问题"><div class="dq-evaluation-question"><el-input v-model="item.question" placeholder="例如：按地区统计近7天订单数量" /><el-button size="mini" :loading="item.planning" @click="buildEvaluationPlan(item)">生成查询计划</el-button></div></el-form-item>
          <el-form-item label="查询计划"><el-input v-model="item.planText" type="textarea" :rows="6" placeholder="先点击“生成查询计划”，再核对或修正 JSON" /></el-form-item>
          <el-form-item label="标准结果"><el-input v-model="item.expectedRowsText" type="textarea" :rows="5" placeholder="填写业务人员确认过的结果 JSON 数组；不要直接复制模型生成的答案" /></el-form-item>
        </div>
        <el-button type="text" icon="el-icon-plus" @click="addEvaluationCase">添加标准问题</el-button>
      </el-form>
      <span slot="footer">
        <el-button @click="evaluationDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="evaluationDialog.submitting" @click="runEvaluation">运行评测</el-button>
      </span>
    </el-dialog>

  </div>
</template>

<script>
import { listResource, getDataQueryReadiness, planDataQuery, evaluateDataQuery, askDataQuery, getDataQueryCatalog, drilldownDataQuery } from '@/api/ontology'
import * as echarts from 'echarts'

export default {
  name: 'OntologyDataQuery',
  data() {
    return {
      proposals: [],
      proposalsLoading: false,
      selectedProposalId: '',
      readiness: null,
      catalog: null,
      catalogLoading: false,
      inputText: '',
      catalogSearch: '',
      recentQuestions: [],
      messages: [],
      sending: false,
      chartInstances: {},
      queryElapsed: 0,
      queryTimer: null,
      evaluationDialog: { visible: false, version: 'v1', submitting: false },
      evaluationCases: [],
      catalogTreeProps: { children: 'children', label: 'label' }
    }
  },
  computed: {
    canSend() {
      return this.selectedProposalId && this.inputText.trim() && !this.sending
    },
    exampleQuestions() {
      if (!this.catalog) return []
      const metrics = this.catalog.metrics || []
      const dims = this.catalog.dimensions || []
      const qs = []
      if (metrics[0] && dims[0]) qs.push(`按${dims[0].name || dims[0].id}统计${metrics[0].name || metrics[0].id}`)
      if (metrics[0]) qs.push(`总${metrics[0].name || metrics[0].id}`)
      if (metrics[0] && dims[0]) qs.push(`${metrics[0].name || metrics[0].id}最高的${dims[0].name || dims[0].id}`)
      return qs.slice(0, 4)
    },
    catalogItemCount() {
      if (!this.catalog) return 0
      return ['metrics', 'dimensions', 'timeDimensions'].reduce((total, key) => total + (this.catalog[key] || []).length, 0)
    },
    catalogTreeData() {
      if (!this.catalog) return []
      const groups = [
        { key: 'metrics', label: '指标', kind: 'group', items: this.catalog.metrics, childKind: 'metric' },
        { key: 'dimensions', label: '维度', kind: 'group', items: this.catalog.dimensions, childKind: 'dimension' },
        { key: 'timeDimensions', label: '时间维度', kind: 'group', items: this.catalog.timeDimensions, childKind: 'timeDimension' }
      ]
      return groups.filter(group => group.items && group.items.length).map(group => ({
        key: group.key,
        label: `${group.label}（${group.items.length}）`,
        kind: group.kind,
        children: group.items.map(item => ({
          key: `${group.key}-${item.id}`,
          id: item.id,
          name: item.name || item.label || item.id,
          label: item.name || item.label || item.id,
          kind: group.childKind,
          synonyms: item.synonyms || []
        }))
      }))
    },
    queryStageText() {
      return this.queryElapsed < 5 ? '正在处理查询…' : '查询仍在处理中…'
    },
    timeShortcuts() {
      return [{ label: '近 7 天', value: '近7天' }, { label: '本月', value: '本月' }, { label: '上月', value: '上月' }, { label: '今年', value: '今年' }]
    },
    readinessReasons() {
      return this.readiness && Array.isArray(this.readiness.reasons) ? this.readiness.reasons : []
    },
    readinessReasonSummary() {
      if (!this.readiness) return ''
      if (this.readiness.eligibleForEvaluation) return '指标和维度已具备，请先运行标准问题评测'
      return this.readinessReasonText(this.readinessReasons[0]) || '请先完善查询语义配置'
    },
    readinessGuideTitle() {
      if (!this.readiness) return '查询准入状态读取中'
      if (this.readinessReasons.includes('配置草案尚未完成审核')) return '请先完成配置草案审核'
      if (this.readinessReasons.some(reason => /querySemantics\.(metrics|dimensions)/.test(reason))) return '草案已完成，指标或维度还未配置完整'
      if (this.readiness.eligibleForEvaluation) return '指标、维度和草案审核已就绪，还需通过标准问题评测'
      return '数据问答尚未就绪'
    },
    readinessGuideHint() {
      if (this.readiness && this.readiness.eligibleForEvaluation) return '系统还需要用标准问题核对查询计划，评测通过后才会读取业务数据。'
      if (this.readinessReasons.includes('配置草案尚未完成审核')) return '草案结构修改后会回到待修正状态；请到配置草案列表重新点击“草案完成”。'
      return '指标是“统计什么”（如订单金额、订单数量），维度是“按什么分组”（如地区、门店、状态）。请在分析问答的“语义与维度”页签查看或补充，再保存并重新完成草案。'
    }
  },
  created() {
    this.loadProposals()
  },
  watch: {
    catalogSearch(value) {
      if (this.$refs.catalogTree) this.$refs.catalogTree.filter(value)
    }
  },
  beforeDestroy() {
    this.destroyCharts()
    this.stopQueryTimer()
  },
  methods: {
    proposalLabel(item) {
      return `#${item.proposal_id} · ${item.proposal_name || '未命名草案'} · ${this.statusLabel(item.status)}`
    },
    statusLabel(s) {
      return ({ DRAFT: '待修正', COMPLETED: '草案完成', IMPORTED: '已导入' })[s] || s || '未知'
    },
    loadProposals() {
      this.proposalsLoading = true
      listResource('proposals', { pageNum: 1, pageSize: 100 }).then(res => {
        this.proposals = res.rows || []
        const req = String(this.$route.query.proposalId || '')
        if (req && this.proposals.some(i => String(i.proposal_id) === req)) {
          this.selectedProposalId = req
        } else if (this.proposals.length) {
          this.selectedProposalId = String(this.proposals[0].proposal_id)
        }
        if (this.selectedProposalId) this.onProposalChange()
      }).catch(() => {}).finally(() => { this.proposalsLoading = false })
    },
    onProposalChange() {
      this.readiness = null
      this.catalog = null
      this.catalogLoading = !!this.selectedProposalId
      this.catalogSearch = ''
      this.messages = []
      this.evaluationCases = []
      this.evaluationDialog.visible = false
      this.destroyCharts()
      if (!this.selectedProposalId) return
      Promise.all([
        getDataQueryReadiness(this.selectedProposalId).then(({ data }) => { this.readiness = data || null }).catch(() => {}),
        getDataQueryCatalog(this.selectedProposalId).then(({ data }) => { this.catalog = data || null }).catch(() => {}).finally(() => { this.catalogLoading = false })
      ])
    },
    onCatalogNodeClick(data) {
      if (data && data.kind !== 'group') this.appendQuestion(data.name || data.id)
    },
    filterTreeNode(value, data) {
      if (!value) return true
      if (data.kind === 'group') return (data.children || []).some(child => this.filterTreeNode(value, child))
      return `${data.name || ''} ${data.id || ''} ${(data.synonyms || []).join(' ')}`.toLowerCase().includes(String(value).trim().toLowerCase())
    },
    appendQuestion(text) {
      this.inputText = this.inputText ? `${this.inputText} ${text}` : text
    },
    readinessReasonText(reason) {
      const text = String(reason || '')
      if (text === '配置草案尚未完成审核') return '先到“配置草案”检查对象、字段和关系，并点击“草案完成”。'
      if (/querySemantics\.metrics 不能为空/.test(text)) return '还没有可统计指标，例如订单金额、订单数量。请在分析问答中补充“指标”。'
      if (/querySemantics\.dimensions 不能为空/.test(text)) return '还没有可分组维度，例如地区、门店、订单状态。请在分析问答中补充“维度”。'
      if (text === '尚未通过标准问答评测') return '还没有用标准问题核对结果；评测全部通过后才开放只读查询。'
      if (/querySemantics\.metrics\[\d+\]/.test(text)) return '有指标没有绑定有效字段或聚合方式，请回到分析问答检查指标口径。'
      if (/querySemantics\.dimensions\[\d+\]/.test(text)) return '有维度没有绑定有效字段，请回到分析问答检查字段映射。'
      return text || '请先完善查询语义配置。'
    },
    showReadinessGuide() {
      this.$alert((this.readinessReasons || []).map(reason => this.readinessReasonText(reason)).join('\n') || '请先完善查询语义配置。', '查询尚未开放', { confirmButtonText: '知道了' })
    },
    openEvaluation() {
      if (!this.readiness || !this.readiness.eligibleForEvaluation) {
        this.showReadinessGuide()
        return
      }
      this.evaluationDialog.visible = true
      if (!this.evaluationCases.length) this.addEvaluationCase()
    },
    addEvaluationCase() {
      this.evaluationCases.push({ key: `case-${Date.now()}-${this.evaluationCases.length}`, question: '', planText: '', expectedRowsText: '[]', planning: false })
    },
    removeEvaluationCase(index) {
      this.evaluationCases.splice(index, 1)
    },
    buildEvaluationPlan(item) {
      if (!item.question || item.planning) return
      item.planning = true
      planDataQuery(this.selectedProposalId, item.question).then(({ data }) => {
        if (!data || !data.plan) throw new Error((data && data.clarification) || '问题还不完整，请补充指标、维度或过滤条件')
        item.planText = JSON.stringify(data.plan, null, 2)
      }).catch(error => this.$modal.msgWarning((error && error.message) || '查询计划生成失败')).finally(() => { item.planning = false })
    },
    parseEvaluationJson(value, label) {
      try { return JSON.parse(value || '') } catch (error) { throw new Error(`${label}不是有效 JSON`) }
    },
    runEvaluation() {
      if (this.evaluationDialog.submitting) return
      if (!this.evaluationDialog.version.trim()) { this.$modal.msgWarning('请填写评测版本'); return }
      if (!this.evaluationCases.length) { this.$modal.msgWarning('至少添加一条标准问题'); return }
      if (this.evaluationCases.length > 100) { this.$modal.msgWarning('标准问题最多添加 100 条'); return }
      if (this.evaluationCases.some(item => !item.question.trim())) { this.$modal.msgWarning('请为每条评测用例填写业务问题'); return }
      let cases
      try {
        cases = this.evaluationCases.map((item, index) => ({
          id: `case-${index + 1}`,
          plan: this.parseEvaluationJson(item.planText, `标准问题 ${index + 1} 的查询计划`),
          expectedRows: this.parseEvaluationJson(item.expectedRowsText, `标准问题 ${index + 1} 的标准结果`)
        }))
        if (cases.some(item => !item.plan || typeof item.plan !== 'object' || Array.isArray(item.plan) || !Array.isArray(item.expectedRows))) throw new Error('查询计划必须是 JSON 对象，标准结果必须是 JSON 数组')
      } catch (error) {
        this.$modal.msgWarning(error.message)
        return
      }
      this.evaluationDialog.submitting = true
      evaluateDataQuery(this.selectedProposalId, { benchmarkVersion: this.evaluationDialog.version.trim(), cases }).then(({ data }) => {
        if (!data || !data.passed) {
          const failed = (data && data.details || []).filter(item => !item.passed).map(item => `${item.caseId}: ${item.error || '结果不一致'}`).join('；')
          throw new Error(`评测未全部通过（${data && data.passedCount || 0}/${data && data.caseCount || cases.length}）${failed ? `：${failed}` : ''}`)
        }
        this.$modal.msgSuccess(`标准问题评测通过，共 ${data.passedCount} 条`)
        this.evaluationDialog.visible = false
        this.onProposalChange()
      }).catch(error => this.$modal.msgError((error && error.message) || '标准问题评测失败')).finally(() => { this.evaluationDialog.submitting = false })
    },
    goConfigureSemantics() {
      const proposal = this.proposals.find(item => String(item.proposal_id) === String(this.selectedProposalId))
      if (!proposal || !proposal.task_id) {
        this.$modal.msgWarning('当前配置没有关联分析任务，请先从配置草案详情进入分析问答')
        return
      }
      this.$router.push({ path: '/knowledge/chat', query: { scopeType: 'ANALYSIS', scopeId: String(proposal.task_id), proposalId: String(proposal.proposal_id), tab: 'querySemantics' } })
    },
    semanticName(type, id) {
      const item = (this.catalog && this.catalog[type] || []).find(entry => String(entry.id) === String(id))
      return item ? (item.name || item.id) : id
    },
    comparisonLabel(type) {
      return ({ YOY: '同比', MOM: '环比', QOQ: '季比', WOW: '周比' })[String(type || '').toUpperCase()] || type
    },
    availableDrillDimensions(msg) {
      const selected = (msg.plan && msg.plan.dimensions || []).map(item => item.id)
      if (msg.plan && msg.plan.drilldown && msg.plan.drilldown.nextDimensionId) selected.push(msg.plan.drilldown.nextDimensionId)
      return (this.catalog && this.catalog.dimensions || []).filter(item => !selected.includes(item.id))
    },
    hasDrillTarget(msg) {
      return this.availableDrillDimensions(msg).length > 0 && !!msg.nextDimensionId
    },
    rowValue(row, key) {
      if (!row || !key) return null
      const candidates = [key, String(key).toLowerCase(), String(key).toUpperCase()]
      for (const candidate of candidates) {
        if (Object.prototype.hasOwnProperty.call(row, candidate)) return row[candidate]
      }
      const found = Object.keys(row).find(name => String(name).toLowerCase() === String(key).toLowerCase())
      return found ? row[found] : null
    },
    dimensionLabel(id) {
      const item = (this.catalog && this.catalog.dimensions || []).find(entry => String(entry.id) === String(id))
      return item ? (item.name || item.id) : id
    },
    drilldown(msg, row) {
      const next = msg.nextDimensionId || (this.availableDrillDimensions(msg)[0] || {}).id
      const dimensions = (msg.plan && msg.plan.dimensions || []).map(item => item.id)
      const previous = msg.plan && msg.plan.drilldown
      const currentId = previous && previous.nextDimensionId ? previous.nextDimensionId : dimensions[dimensions.length - 1]
      const currentValue = this.rowValue(row, currentId)
      if (!next || !currentId || currentValue == null) return
      const plan = JSON.parse(JSON.stringify(msg.plan))
      if (previous) {
        const previousDimension = (this.catalog.dimensions || []).find(item => item.id === previous.dimensionId)
        if (!previousDimension || !previousDimension.propertyId) { this.$modal.msgWarning('语义目录没有该下钻维度的字段映射'); return }
        plan.filters = plan.filters || []
        plan.filters.push({ propertyId: previousDimension.propertyId, operator: '=', value: previous.value })
        if (!dimensions.includes(previous.nextDimensionId)) plan.dimensions.push({ id: previous.nextDimensionId })
      }
      plan.drilldown = { dimensionId: currentId, value: currentValue, nextDimensionId: next }
      this.sending = true
      drilldownDataQuery(this.selectedProposalId, plan).then(({ data }) => {
        const result = Object.assign({}, data || {}, { question: `继续分析：${this.dimensionLabel(currentId)} = ${currentValue}`, viewMode: 'table', nextDimensionId: next })
        this.messages.push(result)
      }).catch(error => this.$modal.msgError((error && error.message) || '下钻失败')).finally(() => {
        this.sending = false
        this.$nextTick(this.scrollToBottom)
      })
    },
    exportRows(msg) {
      const rows = msg.rows || []
      if (!rows.length) return
      const columns = msg.columns || Object.keys(rows[0])
      const escape = value => `"${String(value == null ? '' : value).replace(/"/g, '""')}"`
      const csv = [columns.map(escape).join(','), ...rows.map(row => columns.map(column => escape(row[column])).join(','))].join('\n')
      const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = `ontology-query-${Date.now()}.csv`
      link.click()
      URL.revokeObjectURL(link.href)
    },
    submit() {
      if (!this.canSend) return
      this.sendQuestion(this.inputText.trim())
      this.inputText = ''
    },
    sendQuestion(question) {
      if (!this.selectedProposalId || this.sending) return
      this.sending = true
      this.startQueryTimer()
      this.recentQuestions = [question, ...this.recentQuestions.filter(item => item !== question)].slice(0, 4)
      const msg = { question, type: 'loading', loading: true, viewMode: 'chart' }
      this.messages.push(msg)
      this.$nextTick(this.scrollToBottom)
      const idx = this.messages.length - 1
      askDataQuery(this.selectedProposalId, question).then(({ data }) => {
        Object.assign(msg, data || { type: 'error', error: '未收到有效响应' })
        msg.loading = false
        if (msg.type === 'result' && !Object.prototype.hasOwnProperty.call(msg, 'nextDimensionId')) this.$set(msg, 'nextDimensionId', '')
        if (msg.type === 'result' && msg.chartHint && msg.chartHint.type !== 'table') {
          msg.viewMode = 'chart'
          this.$nextTick(() => this.renderChart(idx, msg))
        } else {
          msg.viewMode = 'table'
        }
      }).catch(err => {
        msg.loading = false
        msg.type = 'error'
        msg.error = (err && err.message) || '查询失败，请检查网络或权限'
      }).finally(() => {
        this.sending = false
        this.stopQueryTimer()
        this.$nextTick(this.scrollToBottom)
      })
    },
    startQueryTimer() {
      this.stopQueryTimer()
      this.queryElapsed = 0
      const started = Date.now()
      this.queryTimer = setInterval(() => { this.queryElapsed = Math.floor((Date.now() - started) / 1000) }, 1000)
    },
    stopQueryTimer() {
      if (this.queryTimer) clearInterval(this.queryTimer)
      this.queryTimer = null
    },
    canChart(msg) {
      return msg.chartHint && msg.chartHint.type && msg.chartHint.type !== 'table'
    },
    setViewMode(msg, mode) {
      msg.viewMode = mode
      if (mode === 'chart' && this.canChart(msg)) {
        this.$nextTick(() => {
          const idx = this.messages.indexOf(msg)
          if (idx >= 0) this.renderChart(idx, msg)
        })
      } else if (mode === 'table') {
        const idx = this.messages.indexOf(msg)
        if (this.chartInstances[idx]) {
          this.chartInstances[idx].dispose()
          delete this.chartInstances[idx]
        }
      }
    },
    renderChart(idx, msg) {
      if (!this.canChart(msg)) return
      const refEl = this.$refs['chart-' + idx]
      if (!refEl || !refEl.length) return
      const el = refEl[0]
      if (this.chartInstances[idx]) this.chartInstances[idx].dispose()
      const chart = echarts.init(el, null, { renderer: 'canvas' })
      this.chartInstances[idx] = chart
      chart.setOption(this.buildOption(msg))
    },
    buildOption(msg) {
      const hint = msg.chartHint
      const rows = msg.rows || []
      const type = hint.type
      const baseColor = '#76b900'
      const palette = ['#76b900', '#000000', '#5a8d00', '#757575', '#bff230', '#5e5e5e']
      const common = {
        grid: { top: 36, right: 24, bottom: 40, left: 56 },
        textStyle: { fontFamily: 'Inter, Arial, sans-serif', fontSize: 12, color: '#1a1a1a' },
        tooltip: { trigger: type === 'pie' ? 'item' : 'axis', backgroundColor: '#fff', borderColor: '#cccccc', textStyle: { color: '#1a1a1a', fontSize: 12 } },
        color: palette
      }
      if (type === 'stat') {
        return {}
      }
      if (hint.multiMetric) {
        const row = rows[0] || {}
        const keys = Object.keys(row)
        return {
          ...common,
          xAxis: { type: 'category', data: keys, axisLine: { lineStyle: { color: '#cbd5e1' } }, axisLabel: { color: '#94a3b8', fontSize: 11, rotate: keys.length > 5 ? 30 : 0 } },
          yAxis: { type: 'value', axisLine: { show: false }, splitLine: { lineStyle: { color: '#f1f5f9' } }, axisLabel: { color: '#94a3b8', fontSize: 11 } },
          series: [{ type: 'bar', data: keys.map(key => Number(row[key] ?? 0)), itemStyle: { color: baseColor }, barMaxWidth: 40 }]
        }
      }
      if (type === 'pie') {
        return {
          ...common,
          tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
          legend: { bottom: 8, textStyle: { fontSize: 11, color: '#64748b' } },
          series: [{
            type: 'pie',
            radius: ['42%', '68%'],
            center: ['50%', '45%'],
            avoidLabelOverlap: true,
            itemStyle: { borderColor: '#fff', borderWidth: 2 },
            label: { show: true, fontSize: 11, color: '#64748b' },
            data: rows.map(r => ({ name: String(r[hint.nameKey] ?? ''), value: Number(r[hint.valueKey] ?? 0) }))
          }]
        }
      }
      const xData = rows.map(r => String(r[hint.xKey] ?? ''))
      const yData = rows.map(r => Number(r[hint.yKey] ?? 0))
      const baseSeries = { type: type === 'line' ? 'line' : 'bar', data: yData, smooth: type === 'line', itemStyle: { color: baseColor }, barMaxWidth: 40 }
      if (type === 'line') {
        baseSeries.areaStyle = { color: 'rgba(118,185,0,0.12)' }
      }
      return {
        ...common,
        xAxis: {
          type: 'category',
          data: xData,
          axisLine: { lineStyle: { color: '#cbd5e1' } },
          axisLabel: { color: '#94a3b8', fontSize: 11, rotate: xData.length > 6 ? 30 : 0 }
        },
        yAxis: {
          type: 'value',
          name: hint.yLabel || '',
          axisLine: { show: false },
          splitLine: { lineStyle: { color: '#f1f5f9' } },
          axisLabel: { color: '#94a3b8', fontSize: 11 }
        },
        series: [baseSeries]
      }
    },
    statValue(msg) {
      if (!msg.rows || !msg.rows.length || !msg.chartHint) return '—'
      const row = msg.rows[0]
      const key = msg.chartHint.valueKey
      const val = row[key] ?? row[Object.keys(row)[0]]
      if (val == null) return '—'
      const num = Number(val)
      if (isNaN(num)) return String(val)
      return num % 1 === 0 ? num.toLocaleString() : num.toLocaleString(undefined, { maximumFractionDigits: 2 })
    },
    scrollToBottom() {
      const el = this.$refs.feed
      if (el) el.scrollTop = el.scrollHeight
    },
    destroyCharts() {
      Object.keys(this.chartInstances).forEach(k => {
        this.chartInstances[k].dispose()
        delete this.chartInstances[k]
      })
    }
  }
}
</script>

<style scoped>
.dq-shell {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 84px);
  padding: 12px 16px;
  background: #f6f5f4;
}

/* ═══ 顶栏 ═══ */
.dq-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex: 0 0 auto;
  margin-bottom: 10px;
}
.dq-header-brand { display: flex; align-items: center; gap: 12px; }
.dq-logo-icon {
  display: grid;
  place-items: center;
  width: 44px; height: 44px;
  border-radius: 12px;
  background: linear-gradient(135deg, #2563eb, #4f46e5);
  color: #fff;
  font-size: 21px;
  flex-shrink: 0;
}
.dq-title { margin: 0; font-size: 20px; font-weight: 700; color: #1e293b; letter-spacing: -0.3px; }
.dq-subtitle { margin: 2px 0 0; font-size: 12px; color: #64748b; }
.dq-header-controls { display: flex; align-items: center; gap: 10px; }
.dq-proposal-select { width: 320px; }
.dq-status-badge {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 5px 10px; border-radius: 9999px; font-size: 12px; font-weight: 600; white-space: nowrap;
}
.dq-status-badge i { font-size: 13px; }
.dq-status-wrap { display:flex; flex-direction:column; align-items:flex-end; gap:4px; min-width:0; }
.dq-status-help { margin-left:2px; font-size:11px !important; }
.dq-status-detail { max-width:280px; overflow:hidden; color:#b3261e; font-size:11px; text-overflow:ellipsis; white-space:nowrap; }
.dq-readiness-popover-content { color:#31302e; font-size:12px; line-height:1.6; }
.dq-readiness-popover-content strong { display:block; margin-bottom:5px; font-size:13px; }
.dq-readiness-popover-content ul { margin:0; padding-left:18px; color:#615d59; }
.dq-readiness-popover-content p { margin:0; color:#615d59; }
.badge-ready { background: #ecfdf5; color: #059669; border: 1px solid #a7f3d0; }
.badge-pending { background: #fffbeb; color: #d97706; border: 1px solid #fde68a; }
.badge-off { background: #f1f5f9; color: #94a3b8; border: 1px solid #e2e8f0; }

/* ═══ 语义目录条 ═══ */
/* ═══ 对话区 ═══ */
.dq-feed {
  flex: 1 1 0; min-height: 0;
  overflow-y: auto; overflow-x: hidden;
  padding: 0 4px 10px;
  display: flex; flex-direction: column; gap: 14px;
  scroll-behavior: smooth;
}
.dq-feed::-webkit-scrollbar { width: 6px; }
.dq-feed::-webkit-scrollbar-thumb { background: #cbd5e1; border-radius: 3px; }

/* ═══ 空态 ═══ */
.dq-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; flex: 1; text-align: center; }
.dq-empty-icon {
  display: grid; place-items: center;
  width: 64px; height: 64px; border-radius: 18px;
  background: #eff6ff; color: #2563eb; font-size: 30px; margin-bottom: 16px;
}
.dq-empty h2 { margin: 0 0 6px; font-size: 18px; color: #1e293b; }
.dq-empty p { margin: 0; font-size: 13px; color: #64748b; max-width: 380px; line-height: 1.6; }
.dq-empty-hint { margin-top: 14px; font-size: 13px; color: #2563eb; animation: dq-bounce 1.2s ease-in-out infinite; }
@keyframes dq-bounce { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-4px); } }
.dq-example-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; margin-top: 18px; max-width: 460px; }
.dq-example-btn {
  display: flex; align-items: center; gap: 8px; text-align: left;
  padding: 11px 14px; border: 1px solid #e6e8ec; border-radius: 10px;
  background: #fff; color: #334155; font-size: 13px; cursor: pointer;
  transition: all 0.15s;
}
.dq-example-btn:hover { border-color: #2563eb; background: #eff6ff; color: #1d4ed8; }
.dq-example-btn i { color: #2563eb; font-size: 14px; flex-shrink: 0; }

/* ═══ 用户气泡 ═══ */
.dq-bubble-user {
  align-self: flex-end;
  max-width: 68%;
  padding: 10px 16px;
  border-radius: 18px 18px 4px 18px;
  background: linear-gradient(135deg, #2563eb, #4f46e5);
  color: #fff;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  box-shadow: 0 2px 8px rgba(37,99,235,0.2);
}

/* ═══ 助手答案卡片 ═══ */
.dq-answer-card {
  align-self: flex-start;
  width: 100%;
  max-width: 820px;
  border: 1px solid rgba(0,0,0,0.08);
  border-radius: 14px;
  background: #fff;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04), 0 4px 12px rgba(0,0,0,0.03);
}

/* 加载动画 */
.dq-answer-loading {
  display: flex; align-items: center; gap: 8px;
  padding: 18px 20px; color: #64748b; font-size: 13px;
}
.dq-dot-anim {
  display: inline-block; width: 7px; height: 7px;
  border-radius: 50%; background: #2563eb;
  animation: dq-pulse 1.1s ease-in-out infinite;
}
.dq-dot-2 { animation-delay: 0.18s; }
.dq-dot-3 { animation-delay: 0.36s; }
@keyframes dq-pulse { 0%,80%,100% { transform:scale(0.6); opacity:0.4; } 40% { transform:scale(1); opacity:1; } }

/* 澄清 */
.dq-clarification { display:flex; gap:10px; padding:18px 20px; color:#92400e; }
.dq-clarification i { font-size:18px; color:#f59e0b; flex-shrink:0; margin-top:2px; }
.dq-clarification p { margin:0; font-size:14px; line-height:1.6; }

/* 预览未评测 */
.dq-preview-block { padding: 16px 20px; }
.dq-preview-notice {
  display:flex; gap:10px; padding:12px 14px; margin-bottom:14px;
  border:1px solid #fde68a; border-radius:8px; background:#fffbeb; color:#92400e;
}
.dq-preview-notice i { font-size:18px; color:#f59e0b; flex-shrink:0; margin-top:2px; }
.dq-preview-notice strong { display:block; font-size:13px; margin-bottom:3px; }
.dq-preview-notice p { margin:0; font-size:12px; color:#b45309; }
.dq-plan-chips { display:flex; flex-wrap:wrap; gap:6px; margin-bottom:12px; }
.dq-plan-chip {
  display:inline-flex; align-items:center; gap:4px;
  padding:4px 10px; border-radius:6px; font-size:12px;
}
.chip-metric { background:#eff6ff; color:#2563eb; }
.chip-dim { background:#f5f3ff; color:#6366f1; }

/* 语义证据条 */
.dq-evidence-bar {
  display:flex; align-items:center; gap:10px;
  padding:10px 20px; border-bottom:1px solid #f1f5f9;
  font-size:12px; color:#64748b;
}
.dq-evidence-bar i { color:#2563eb; margin-right:3px; }
.dq-evidence-rows { margin-left:auto; font-weight:600; color:#1e293b; }

/* 视图切换 */
.dq-view-toggle {
  display:flex; gap:4px; padding:8px 12px; border-bottom:1px solid #f1f5f9;
}
.dq-toggle-btn {
  display:inline-flex; align-items:center; gap:5px;
  padding:5px 12px; border:1px solid #e2e8f0; border-radius:6px;
  background:#f8fafc; color:#64748b; font-size:12px; cursor:pointer;
  transition: all 0.15s;
}
.dq-toggle-btn.active { border-color:#2563eb; background:#eff6ff; color:#2563eb; font-weight:600; }
.dq-toggle-btn:disabled { opacity:0.4; cursor:not-allowed; }

/* stat 数值卡片 */
.dq-stat-card {
  display:flex; flex-direction:column; align-items:center; justify-content:center;
  padding:32px 20px; text-align:center;
}
.dq-stat-value { font-size:46px; font-weight:700; color:#1e293b; letter-spacing:-1px; line-height:1; }
.dq-stat-label { margin-top:8px; font-size:14px; color:#64748b; }

/* ECharts 容器 */
.dq-chart-container { width:100%; height:280px; padding:8px 12px; box-sizing:border-box; }

/* 表格 */
.dq-result-table { padding:12px; }

/* SQL 折叠 */
.dq-sql-collapse { border-top: 1px solid #f1f5f9; }
.dq-sql-collapse ::v-deep .el-collapse-item__header {
  height:40px; padding:0 20px; background:#f8fafc;
  color:#64748b; font-size:12px; font-weight:600;
  border-bottom:0;
}
.dq-sql-collapse ::v-deep .el-collapse-item__header i.el-icon-document { color:#2563eb; margin-right:6px; }
.dq-sql-collapse ::v-deep .el-collapse-item__content { padding:0; border-bottom:0; }
.dq-sql-collapse pre {
  margin:0; padding:14px 20px;
  background:#1e293b; color:#e2e8f0;
  font:12px/1.7 'SFMono-Regular',Consolas,monospace;
  white-space:pre-wrap; word-break:break-word;
  overflow:auto;
}

/* 错误 */
.dq-error-block { display:flex; align-items:center; gap:8px; padding:16px 20px; color:#dc2626; font-size:13px; }
.dq-error-block i { font-size:16px; }

/* ═══ 输入区 ═══ */
.dq-composer {
  flex: 0 0 auto;
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px solid rgba(0,0,0,0.08);
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 -1px 6px rgba(0,0,0,0.03);
}
.dq-composer-inner { display:flex; align-items:flex-end; gap:10px; }
.dq-input { flex:1; }
.dq-input ::v-deep .el-textarea__inner {
  border:none; padding:0; font-size:14px; line-height:1.7;
  resize:none; color:#1e293b; background:transparent;
}
.dq-input ::v-deep .el-textarea__inner:focus { box-shadow:none; }
.dq-send-btn {
  flex-shrink:0;
  display:grid; place-items:center;
  width:40px; height:40px;
  border-radius:10px;
  background: linear-gradient(135deg, #2563eb, #4f46e5);
  color:#fff; font-size:18px; cursor:pointer;
  border:none;
  transition: all 0.15s;
}
.dq-send-btn:hover:not(:disabled) { transform:scale(1.06); box-shadow:0 4px 12px rgba(37,99,235,0.35); }
.dq-send-btn:disabled { opacity:0.4; cursor:not-allowed; }
.dq-composer-hint { margin:6px 0 0; font-size:11px; color:#94a3b8; text-align:center; }

/* ═══ 响应式 ═══ */
@media (max-width: 768px) {
  .dq-shell { padding: 8px 10px; }
  .dq-header { flex-wrap: wrap; }
  .dq-proposal-select { width: 100%; }
  .dq-title { font-size: 17px; }
  .dq-example-grid { grid-template-columns: 1fr; max-width: 100%; }
  .dq-bubble-user { max-width: 85%; }
  .dq-chart-container { height: 220px; }
}

/* 与系统其余页面统一：白色卡片、暖灰画布、蓝色主色。 */
.dq-shell { height:calc(100vh - 126px); max-width:1440px; margin:0 auto; padding:18px 24px 22px; overflow-y:auto; overflow-x:hidden; background:var(--aw-warm); color:var(--aw-ink); font-family:inherit; }
.dq-header { min-height:74px; margin:0 0 14px; padding:16px 20px; border:1px solid var(--aw-border); border-radius:12px; background:#fff; color:var(--aw-ink); box-shadow:var(--aw-card-shadow); }
.dq-logo-icon { width:42px; height:42px; border:1px solid #dcecff; border-radius:10px; background:#edf5ff; color:var(--aw-blue); }
.dq-eyebrow { color:var(--aw-blue); }
.dq-title { color:var(--aw-ink); }
.dq-subtitle { color:var(--aw-muted); }
.dq-proposal-select { width:320px; }
.dq-proposal-select ::v-deep .el-input__inner { height:36px; border:1px solid #ddd9d5; border-radius:6px; background:#fff; color:var(--aw-ink); }
.dq-status-badge { min-height:30px; padding:0 9px; border-radius:6px; }
.dq-status-action { display:block; margin-top:4px; padding:0; border:0; background:transparent; color:var(--aw-blue); font-size:11px; cursor:pointer; text-align:left; }
.dq-status-action:hover { text-decoration:underline; }
.badge-ready { border-color:#b9dfc1; background:#eef6ee; color:#1a7a3a; }
.badge-pending { border-color:#f5d97e; background:#fff8e6; color:#8a6d00; }
.badge-off { border-color:#f0c8c4; background:#fdf0ef; color:#b3261e; }
.dq-answer-card, .dq-composer { border-color:var(--aw-border); border-radius:10px; box-shadow:none; }
.dq-empty-icon { border-radius:12px; background:#edf5ff; color:var(--aw-blue); }
.dq-example-btn { border-radius:8px; color:var(--aw-ink); }
.dq-example-btn:hover { border-color:#cfe2fb; background:#edf5ff; color:var(--aw-blue); }
.dq-bubble-user { border:1px solid var(--aw-blue); border-left:4px solid var(--aw-blue); border-radius:8px 8px 2px 8px; background:var(--aw-blue); box-shadow:none; }
.dq-answer-card { background:#fff; }
.dq-composer { border-top:3px solid var(--aw-blue); background:#fff; }
.dq-readiness-guide { margin:10px 0 0; border:1px solid #f0d797; border-radius:10px; background:#fff9e9; color:var(--aw-ink); }
.dq-readiness-guide strong { color:#7b5d00; }
.dq-readiness-guide ol { margin:6px 0 4px 18px; padding:0; line-height:1.7; }
.dq-readiness-guide p { margin:6px 0 0; color:var(--aw-muted); line-height:1.6; }
.dq-send-btn { border-radius:6px; background:var(--aw-blue); color:#fff; }
.dq-send-btn:hover:not(:disabled) { background:var(--aw-blue-dark); box-shadow:none; transform:none; }
.dq-composer-hint { color:var(--aw-soft); }
.dq-workspace { display:flex; flex:1 1 0; min-height:0; gap:14px; }
.dq-main { display:flex; flex:1 1 0; min-width:0; min-height:0; flex-direction:column; }
.dq-catalog-panel { display:flex; flex:0 0 252px; min-height:0; flex-direction:column; padding:14px; border:1px solid var(--aw-border); border-radius:10px; background:#fff; }
.dq-catalog-panel-head { display:flex; align-items:flex-start; justify-content:space-between; gap:8px; margin-bottom:12px; }
.dq-catalog-panel-head strong { display:block; color:var(--aw-ink); font-size:13px; }
.dq-catalog-panel-head strong i { margin-right:5px; color:var(--aw-blue); }
.dq-catalog-panel-head small { display:block; margin-top:4px; color:var(--aw-muted); font-size:11px; }
.dq-catalog-count { min-width:22px; height:22px; padding:0 6px; border-radius:11px; background:#edf5ff; color:var(--aw-blue); font-size:11px; line-height:22px; text-align:center; }
.dq-catalog-search { margin-bottom:10px; }
.dq-catalog-tree { flex:1; min-height:0; overflow:auto; margin:0 -6px; }
.dq-catalog-tree ::v-deep .el-tree-node__content { height:34px; padding-right:6px; border-radius:6px; }
.dq-catalog-tree ::v-deep .el-tree-node__content:hover, .dq-catalog-tree ::v-deep .is-current > .el-tree-node__content { background:#edf5ff; }
.dq-catalog-tree ::v-deep .el-tree-node__expand-icon { color:#94a3b8; }
.dq-tree-node { display:flex; align-items:center; min-width:0; width:100%; gap:6px; font-size:12px; }
.dq-tree-node > i { flex:0 0 auto; color:var(--aw-blue); }
.dq-tree-label { overflow:hidden; color:var(--aw-ink); text-overflow:ellipsis; white-space:nowrap; }
.dq-catalog-empty { display:flex; flex:1; align-items:center; justify-content:center; flex-direction:column; gap:8px; color:var(--aw-soft); font-size:12px; }
.dq-catalog-empty i { font-size:24px; }
.dq-evaluate-btn { flex-shrink:0; border-radius:6px; }
.dq-status-action { white-space:nowrap; }
.dq-composer-tools { display:flex; align-items:center; gap:8px; min-height:30px; padding-bottom:8px; border-bottom:1px solid #eef1f5; }
.dq-tool-label { color:var(--aw-ink); font-size:12px; font-weight:600; }
.dq-tool-label i { margin-right:4px; color:var(--aw-blue); }
.dq-tool-btn { padding:4px 8px; border:1px solid var(--aw-border); border-radius:5px; background:#fff; color:var(--aw-muted); font-size:11px; cursor:pointer; }
.dq-tool-btn:hover { border-color:#cfe2fb; color:var(--aw-blue); background:#edf5ff; }
.dq-tool-hint { margin-left:auto; color:var(--aw-soft); font-size:11px; }
.dq-evaluation-intro { display:flex; gap:10px; margin-bottom:16px; padding:12px 14px; border:1px solid #cfe2fb; border-radius:8px; background:#f4f8ff; color:var(--aw-ink); }
.dq-evaluation-intro > i { flex:0 0 auto; margin-top:2px; color:var(--aw-blue); font-size:17px; }
.dq-evaluation-intro strong { font-size:13px; }
.dq-evaluation-intro p { margin:4px 0 0; color:var(--aw-muted); font-size:12px; line-height:1.6; }
.dq-evaluation-case { margin:0 0 14px; padding:12px 14px 4px; border:1px solid var(--aw-border); border-radius:8px; background:#fafbfc; }
.dq-evaluation-case-head { display:flex; align-items:center; justify-content:space-between; margin-bottom:8px; color:var(--aw-ink); font-size:13px; }
.dq-evaluation-case .el-form-item { margin-bottom:12px; }
.dq-evaluation-case .el-textarea__inner, .dq-evaluation-case .el-input__inner { font-size:12px; }
.dq-evaluation-question { display:flex; gap:8px; }
.dq-evaluation-question .el-input { flex:1; }
.dq-evaluation-question .el-button { flex:0 0 auto; }
.dq-evaluation-dialog ::v-deep .el-dialog__body { max-height:68vh; overflow:auto; }
.dq-answer-loading small { color:var(--aw-soft); font-size:11px; }
@media (max-width:768px) {
  .dq-shell { height:calc(100vh - 104px); padding:12px 12px 16px; }
  .dq-header { margin:0 0 10px; padding:14px 12px; }
  .dq-header-controls { width:100%; flex-wrap:wrap; align-items:flex-start; }
  .dq-proposal-select { flex:1 1 220px; width:auto; }
  .dq-evaluate-btn { width:100%; }
  .dq-workspace { display:block; }
  .dq-catalog-panel { max-height:220px; margin-bottom:10px; }
  .dq-main { min-height:520px; }
  .dq-tool-hint { display:none; }
  .dq-composer-tools { flex-wrap:wrap; }
}
</style>
