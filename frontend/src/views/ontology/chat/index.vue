<template>
  <div class="app-container wiki-assistant">
    <header class="page-toolbar">
      <div class="title-block">
        <h1>{{ scopeType === 'ANALYSIS' ? '分析结果问答' : '业务知识问答' }}</h1>
        <span>{{ scopeType === 'ANALYSIS' ? '分析校对助手' : '基于已整理的业务知识回答' }}</span>
      </div>

      <div class="scope-controls">
        <span class="scope-label">知识范围</span>
        <el-select v-model="scopeType" size="small" :disabled="sending" @change="changeScopeType">
          <el-option label="全部 Wiki 资料" value="ALL" />
          <el-option label="行业知识空间" value="SPACE" />
          <el-option label="单个 Wiki 资产" value="ASSET" />
          <el-option label="分析任务" value="ANALYSIS" />
        </el-select>
        <el-select v-if="scopeType === 'SPACE'" v-model="scopeId" size="small" filterable :disabled="sending" placeholder="选择知识空间" @change="changeScopeTarget">
          <el-option v-for="item in spaces" :key="item.space_id" :label="item.space_name" :value="String(item.space_id)" />
        </el-select>
        <el-select
          v-if="scopeType === 'ASSET' || scopeType === 'ANALYSIS'"
          v-model="scopeId"
          size="small"
          filterable
          remote
          clearable
          :disabled="sending"
          :remote-method="searchScopeOptions"
          :loading="scopeOptionsLoading"
          no-data-text="没有匹配项"
          :placeholder="scopeType === 'ASSET' ? '搜索 Wiki 资产' : '搜索分析任务'"
          @visible-change="scopeDropdownChanged"
          @change="changeScopeTarget"
        >
          <el-option v-for="item in scopeOptions" :key="scopeOptionKey(item)" :label="scopeOptionLabel(item)" :value="String(scopeOptionKey(item))" />
        </el-select>
        <el-tooltip
          :content="scopeType === 'ANALYSIS' ? '仅围绕当前分析草案问答；不检索 Wiki 页面，也不执行本体逻辑或动作。' : '仅使用所选范围内的 Wiki 资料；不查询实时数据库，也不执行本体逻辑或动作。'"
          placement="bottom"
        >
          <el-button class="help-button" type="text" icon="el-icon-question" aria-label="查看使用说明" />
        </el-tooltip>
        <span v-if="scopeOptionsError" class="scope-error">{{ scopeOptionsError }}</span>
        <el-button v-if="scopeType !== 'ANALYSIS'" class="start-analysis" type="text" icon="el-icon-data-analysis" @click="goAnalysis">开始本体分析</el-button>
      </div>

      <div class="toolbar-actions">
        <template v-if="scopeType === 'ANALYSIS'">
          <el-tag v-if="analysisChanges.length" class="save-state" size="small" type="warning">{{ analysisChanges.length }} 项待保存</el-tag>
          <el-tag v-else-if="analysisSaved" class="save-state" size="small" type="success">已保存</el-tag>
          <el-button size="small" type="primary" icon="el-icon-collection-tag" :loading="analysisAnswerSaving" @click="saveAnalysisAnswer">保存分析</el-button>
        </template>
        <el-button size="small" icon="el-icon-time" @click="historyOpen = true">历史会话</el-button>
        <el-button class="icon-button" circle size="small" icon="el-icon-plus" title="新建问答" aria-label="新建问答" :disabled="sending" @click="newChat" />
      </div>
    </header>

    <div v-if="scopeType === 'ANALYSIS'" class="mobile-workspace-tabs" role="tablist" aria-label="分析问答工作区">
      <button type="button" :class="{ active: mobilePanel === 'structure' }" @click="mobilePanel = 'structure'">分析结构</button>
      <button type="button" :class="{ active: mobilePanel === 'assistant' }" @click="mobilePanel = 'assistant'">分析助手</button>
    </div>

    <main ref="workspaceShell" :class="['workspace-shell', { 'has-analysis': scopeType === 'ANALYSIS' }]">
      <section
        v-if="scopeType === 'ANALYSIS'"
        ref="structureColumn"
        :class="['structure-column', { 'mobile-hidden': mobilePanel !== 'structure' }]"
      >
        <div ref="structureHeader" class="structure-heading">
          <div>
            <strong>分析结构</strong>
            <span>点击字段行后可带入对话</span>
          </div>
          <div class="structure-metrics">
            <span>{{ (analysisConfig.objects || []).length }} 对象</span>
            <span>{{ (analysisConfig.properties || []).length }} 字段</span>
            <span>{{ (analysisConfig.relations || []).length }} 关系</span>
            <span>{{ ((analysisConfig.logics || []).length + (analysisConfig.actions || []).length) }} 逻辑 / 动作</span>
          </div>
        </div>

        <div v-if="selectedAnalysisField" class="selected-field-toolbar">
          <span>已选：{{ selectedFieldLabel }}</span>
          <el-button size="mini" type="primary" plain icon="el-icon-chat-dot-round" @click="askAboutSelectedField">询问此字段</el-button>
        </div>

        <el-tabs ref="structureTabs" v-model="analysisStructureTab" class="structure-tabs" @tab-click="onStructureTabChange">
          <el-tab-pane label="对象" name="objects">
            <el-table ref="table-objects" :data="analysisConfig.objects || []" :height="analysisTableHeight" size="mini" :row-class-name="row => analysisRowClass('objects', row)" empty-text="暂无对象候选">
              <el-table-column label="对象名称" min-width="150">
                <template slot-scope="s">
                  <el-tag v-if="analysisRowChanged('objects', s.row)" class="corrected-tag" size="mini" type="success">已修正</el-tag>
                  {{ analysisObjectLabel(s.row) }}
                </template>
              </el-table-column>
              <el-table-column prop="table" label="来源表" min-width="150" />
              <el-table-column prop="description" label="业务说明" min-width="220" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="字段" name="properties">
            <el-table ref="table-properties" :data="analysisConfig.properties || []" :height="analysisTableHeight" size="mini" :row-class-name="row => analysisRowClass('properties', row)" empty-text="暂无字段候选" @row-click="selectAnalysisField">
              <el-table-column label="字段名称" min-width="140">
                <template slot-scope="s">{{ analysisPropertyLabel(s.row) }}</template>
              </el-table-column>
              <el-table-column label="所属对象" min-width="140">
                <template slot-scope="s">{{ analysisObjectName(s.row.objectId) }}</template>
              </el-table-column>
              <el-table-column prop="column" label="物理字段" min-width="120" />
              <el-table-column label="数据类型" width="130">
                <template slot-scope="s">
                  <el-tag v-if="analysisFieldChanged('properties', s.row, 'dataType')" class="corrected-tag" size="mini" type="success">{{ s.row.dataType }} · 已修正</el-tag>
                  <span v-else>{{ s.row.dataType }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="description" label="字段说明" min-width="220" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="关系" name="relations">
            <el-table ref="table-relations" :data="analysisConfig.relations || []" :height="analysisTableHeight" size="mini" :row-class-name="row => analysisRowClass('relations', row)" empty-text="暂无关系候选">
              <el-table-column label="关系名称" min-width="140">
                <template slot-scope="s">
                  <el-tag v-if="analysisRowChanged('relations', s.row)" class="corrected-tag" size="mini" type="success">已修正</el-tag>
                  {{ s.row.name || '未命名关系' }}
                </template>
              </el-table-column>
              <el-table-column label="源对象" min-width="130"><template slot-scope="s">{{ analysisObjectName(s.row.sourceObjectId) }}</template></el-table-column>
              <el-table-column label="目标对象" min-width="130"><template slot-scope="s">{{ analysisObjectName(s.row.targetObjectId) }}</template></el-table-column>
              <el-table-column prop="sourceColumn" label="源字段" min-width="110" />
              <el-table-column prop="targetColumn" label="目标字段" min-width="110" />
              <el-table-column label="关系基数" width="100"><template slot-scope="s">{{ analysisCardinalityText(s.row.cardinality) }}</template></el-table-column>
              <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="业务逻辑" name="logics">
            <el-table ref="table-logics" :data="analysisConfig.logics || []" :height="analysisTableHeight" size="mini" :row-class-name="row => analysisRowClass('logics', row)" empty-text="暂无业务逻辑候选">
              <el-table-column prop="name" label="逻辑名称" min-width="150" />
              <el-table-column prop="conditionText" label="规则内容" min-width="240" show-overflow-tooltip />
              <el-table-column label="风险" width="90"><template slot-scope="s">{{ analysisRiskText(s.row.risk) }}</template></el-table-column>
              <el-table-column label="状态" width="100"><template slot-scope="s">{{ analysisStatusText(s.row.status) }}</template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="业务动作" name="actions">
            <el-table ref="table-actions" :data="analysisConfig.actions || []" :height="analysisTableHeight" size="mini" :row-class-name="row => analysisRowClass('actions', row)" empty-text="暂无业务动作候选">
              <el-table-column prop="name" label="动作名称" min-width="150" />
              <el-table-column label="操作" width="100"><template slot-scope="s">{{ analysisOperationText(s.row.operation) }}</template></el-table-column>
              <el-table-column label="所属对象" min-width="140"><template slot-scope="s">{{ analysisObjectName(s.row.objectId) }}</template></el-table-column>
              <el-table-column label="风险" width="90"><template slot-scope="s">{{ analysisRiskText(s.row.risk) }}</template></el-table-column>
              <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="服务流" name="serviceFlows">
            <el-table ref="table-serviceFlows" :data="analysisConfig.serviceFlows || []" :height="analysisTableHeight" size="mini" :row-class-name="row => analysisRowClass('serviceFlows', row)" empty-text="暂无服务流候选">
              <el-table-column prop="name" label="服务流名称" min-width="150" />
              <el-table-column label="触发方式" width="130"><template slot-scope="s">{{ analysisTriggerText(s.row.trigger) }}</template></el-table-column>
              <el-table-column label="状态" width="100"><template slot-scope="s">{{ analysisStatusText(s.row.status) }}</template></el-table-column>
              <el-table-column prop="steps" label="步骤" min-width="180" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="权限候选" name="permissions">
            <el-table ref="table-permissionCandidates" :data="analysisConfig.permissionCandidates || []" :height="analysisTableHeight" size="mini" :row-class-name="row => analysisRowClass('permissionCandidates', row)" empty-text="暂无权限候选">
              <el-table-column label="资源对象" min-width="150"><template slot-scope="s">{{ analysisObjectName(s.row.resourceId) }}</template></el-table-column>
              <el-table-column label="操作" width="90"><template slot-scope="s">{{ analysisOperationText(s.row.operation) }}</template></el-table-column>
              <el-table-column label="主体类型" width="100"><template slot-scope="s">{{ analysisSubjectTypeText(s.row.subjectType) }}</template></el-table-column>
              <el-table-column prop="suggestedSubjects" label="建议主体" min-width="150" />
              <el-table-column label="行级过滤" width="100"><template slot-scope="s">{{ analysisBooleanText(s.row.rowFilterRequired) }}</template></el-table-column>
              <el-table-column label="状态" width="100"><template slot-scope="s">{{ analysisStatusText(s.row.status) }}</template></el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </section>

      <section :class="['conversation-panel', { 'mobile-hidden': scopeType === 'ANALYSIS' && mobilePanel !== 'assistant' }]">
        <div class="conversation-heading">
          <strong>{{ scopeType === 'ANALYSIS' ? '分析助手' : '知识问答' }}</strong>
          <span>{{ scopeDescription }}</span>
        </div>

        <div ref="messages" class="messages">
          <div v-for="(item, index) in messages" :key="item.message_id || item.tempId || index" :class="['message', item.role, { failed: item.failed }]">
            <div class="role">{{ item.role === 'user' ? '你' : '业务知识助手' }}</div>
            <div v-if="item.questionContextLabel" class="message-context">{{ item.questionContextLabel }}</div>
            <div class="content"><i v-if="item.pending" class="el-icon-loading wait-spinner" /><span>{{ item.content }}</span></div>
            <el-button v-if="item.failed" type="text" size="mini" @click="retryAnswer(item, index)">重新回答</el-button>
            <div v-if="item.sources && item.sources.length" class="citations">
              <span>参考资料</span>
              <el-button v-for="source in item.sources" :key="source.assetId" type="text" size="mini" @click="viewSource(source)">{{ source.title }}</el-button>
            </div>
            <div v-if="item.savedToWiki" class="saved-note"><i class="el-icon-circle-check" /> 已保存为 Wiki 页面：{{ item.pageTitle }}</div>
            <div v-if="item.role === 'assistant' && item.analysisUpdatedChanges && item.analysisUpdatedChanges.length" class="message-actions">
              <el-button type="text" size="mini" @click="locateAnalysisChange(item.analysisUpdatedChanges[0])">查看修改位置</el-button>
              <el-button v-if="item.undoState && !item.undone" type="text" size="mini" @click="undoAnalysisChanges(item)">撤销</el-button>
              <span v-else-if="item.undone" class="undo-done">已撤销</span>
            </div>
          </div>
          <el-empty v-if="!messages.length && !loadingMessages" :image-size="64" :description="scopeType === 'ANALYSIS' ? '围绕当前分析结构提问或提出修正' : '从已整理的业务知识中找答案'" />
          <el-skeleton v-if="loadingMessages" :rows="4" animated />
        </div>

        <div v-if="!messages.length && !loadingMessages" class="examples">
          <span>可以这样问</span>
          <el-button v-for="example in (scopeType === 'ANALYSIS' ? analysisExamples : examples)" :key="example" size="mini" round @click="askExample(example)">{{ example }}</el-button>
        </div>

        <div class="composer">
          <el-checkbox v-if="scopeType !== 'ANALYSIS'" v-model="saveAnswer" class="save-answer-option">将本次回答保存为 Wiki 页面</el-checkbox>
          <div v-if="questionContext" class="question-context">
            <span>引用字段</span>
            <el-tag size="mini" closable @close="clearQuestionContext">{{ questionContextLabel }}</el-tag>
          </div>
          <div class="input-wrap">
            <el-input
              ref="questionInput"
              v-model="question"
              type="textarea"
              :rows="3"
              maxlength="4000"
              resize="none"
              placeholder="输入问题；Enter 发送，Shift + Enter 换行"
              @keydown.native="onKeydown"
            />
            <el-button class="send-button" type="primary" icon="el-icon-position" :loading="sending" :disabled="!question.trim() || !scopeReady" aria-label="发送问题" @click="send">发送</el-button>
          </div>
          <div class="composer-footer">
            <span>Enter 发送 · Shift + Enter 换行</span>
            <span>{{ question.length }} / 4000</span>
          </div>
        </div>
      </section>
    </main>

    <el-drawer
      title="历史会话"
      :visible.sync="historyOpen"
      direction="rtl"
      size="360px"
      append-to-body
      custom-class="wiki-history-drawer"
    >
      <div class="history-list">
        <button v-for="item in sessions" :key="item.sessionId" type="button" :disabled="sending" :class="['session-item', { active: item.sessionId === sessionId }]" @click="openSession(item)">
          <span>{{ item.title || '新建问答' }}</span>
          <small>{{ sessionScope(item) }} · {{ formatTime(item.updatedAt) }}</small>
        </button>
        <el-empty v-if="!sessions.length" :image-size="48" description="还没有问答记录" />
      </div>
    </el-drawer>

    <el-dialog title="引用资料" :visible.sync="sourcePreviewOpen" width="820px" append-to-body>
      <div class="source-heading"><strong>{{ sourcePreview.asset_name }}</strong><el-tag size="mini">{{ assetTypeText(sourcePreview.asset_type) }}</el-tag></div>
      <p v-if="sourcePreview.description" class="source-description">{{ sourcePreview.description }}</p>
      <pre class="source-content">{{ sourcePreview.content || '暂无正文' }}</pre>
      <span slot="footer"><el-button @click="sourcePreviewOpen = false">关闭</el-button></span>
    </el-dialog>
  </div>
</template>

<script>
import { askWiki, getWikiMessages, getResource, listResource, listWikiSessions, saveAnalysisAnswer, getAnalysisContext } from '@/api/ontology'

export default {
  name: 'OntologyWikiChat',
  data() {
    return {
      question: '',
      sessionId: '',
      scopeType: 'ALL',
      scopeId: '',
      spaceId: '',
      spaces: [],
      scopeOptions: [],
      scopeOptionsLoading: false,
      scopeOptionsError: '',
      scopeSearchSeq: 0,
      sessions: [],
      messages: [],
      historyOpen: false,
      mobilePanel: 'assistant',
      questionContext: null,
      selectedAnalysisField: null,
      locatedAnalysisChange: null,
      analysisTableHeight: 320,
      sending: false,
      loadingMessages: false,
      saveAnswer: false,
      analysisAnswerDraft: '',
      analysisConfig: {},
      analysisChanges: [],
      analysisStructureTab: 'objects',
      analysisSaved: false,
      lastAssistantAnswer: '',
      analysisAnswerSaving: false,
      proposalId: '',
      sourcePreviewOpen: false,
      sourcePreview: {},
      examples: ['总结当前资料中的核心业务规则', '这些资料描述了哪些业务对象？', '这个业务流程中有哪些关键状态？'],
      analysisExamples: ['这里分析出了哪些对象和字段？', '请列出菜单权限表的字段及当前数据类型。', '菜单权限表的路由地址应该是 text 类型，请修正字段并说明影响。']
    }
  },
  computed: {
    scopeDescription() {
      if (this.scopeType === 'ALL') return '使用所有已分组及未分组的 Wiki 资料'
      if (this.scopeType === 'SPACE') {
        const item = this.spaces.find(space => String(space.space_id) === this.scopeId)
        return this.scopeId ? `仅使用「${item ? item.space_name : '所选空间'}」中的资产和 Wiki 页面` : '请选择一个空间'
      }
      const key = this.scopeType === 'ASSET' ? 'asset_id' : 'task_id'
      const label = this.scopeType === 'ASSET' ? 'asset_name' : 'task_name'
      const item = this.scopeOptions.find(row => String(row[key]) === this.scopeId)
      return this.scopeId ? `仅围绕「${item ? item[label] : (this.scopeType === 'ASSET' ? '所选资产' : '所选分析任务')}」及其关联资料回答` : (this.scopeType === 'ASSET' ? '搜索并选择一个 Wiki 资产' : '搜索并选择一个分析任务')
    },
    scopeReady() {
      return this.scopeType === 'ALL' || !!this.scopeId
    },
    questionContextLabel() {
      if (!this.questionContext) return ''
      const row = (this.analysisConfig.properties || []).find(item => String(item.id) === String(this.questionContext.id)) || this.questionContext
      const field = row.column || row.technicalName || row.name || '未命名字段'
      return `${this.analysisObjectName(row.objectId)}.${field}`
    },
    selectedFieldLabel() {
      if (!this.selectedAnalysisField) return ''
      const row = (this.analysisConfig.properties || []).find(item => String(item.id) === String(this.selectedAnalysisField.id)) || this.selectedAnalysisField
      return `${this.analysisObjectName(row.objectId)}.${row.column || row.technicalName || row.name || '未命名字段'}`
    }
  },
  created() {
    this.scopeType = this.$route.query.scopeType || (this.$route.query.spaceId ? 'SPACE' : 'ALL')
    this.scopeId = this.$route.query.scopeId || this.$route.query.spaceId || ''
    this.proposalId = this.$route.query.proposalId || ''
    this.spaceId = this.scopeType === 'SPACE' ? this.scopeId : ''
    this.loadSpaces().then(() => this.loadScopeTarget()).then(() => this.loadAnalysisAnswer()).then(() => this.loadSessions()).then(() => {
      const id = this.$route.query.sessionId
      if (!id) return
      const session = this.sessions.find(item => item.sessionId === id)
      this.openSession(session || { sessionId: id, scopeType: this.scopeType, scopeId: this.scopeId || null, spaceId: this.spaceId || null }, false)
    })
  },
  mounted() {
    this.updateTableHeight()
    window.addEventListener('resize', this.updateTableHeight)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.updateTableHeight)
  },
  methods: {
    loadSpaces() {
      return listResource('spaces', { pageNum: 1, pageSize: 100 }).then(result => {
        this.spaces = (result.rows || []).filter(item => item.status !== '1')
      }).catch(() => { this.spaces = [] })
    },
    loadSessions() {
      return listWikiSessions().then(({ data }) => { this.sessions = data || [] }).catch(() => { this.sessions = [] })
    },
    onKeydown(event) {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault()
        this.send()
      }
    },
    changeScopeType() {
      this.scopeId = ''
      this.spaceId = ''
      this.proposalId = ''
      this.scopeOptions = []
      this.scopeOptionsError = ''
      this.analysisConfig = {}
      this.analysisChanges = []
      this.analysisSaved = false
      this.analysisAnswerDraft = ''
      this.questionContext = null
      this.selectedAnalysisField = null
      this.mobilePanel = 'assistant'
      this.newChat()
    },
    scopeDropdownChanged(visible) {
      if (visible && !this.scopeOptions.length) this.searchScopeOptions('')
    },
    changeScopeTarget() {
      const key = this.scopeType === 'ASSET' ? 'asset_id' : 'task_id'
      const item = this.scopeOptions.find(row => String(row[key]) === this.scopeId)
      this.spaceId = this.scopeType === 'SPACE' ? this.scopeId : (item ? String(this.scopeType === 'ASSET' ? (item.space_id || '') : (item.wiki_space_id || '')) : '')
      this.proposalId = ''
      if (this.scopeType === 'ANALYSIS') {
        this.analysisConfig = {}
        this.analysisChanges = []
        this.analysisSaved = false
      }
      this.questionContext = null
      this.selectedAnalysisField = null
      this.mobilePanel = 'assistant'
      this.newChat()
      if (this.scopeType === 'ANALYSIS') this.loadAnalysisAnswer()
    },
    searchScopeOptions(keyword) {
      const type = this.scopeType
      const resource = type === 'ASSET' ? 'assets' : 'analyses'
      const seq = ++this.scopeSearchSeq
      this.scopeOptionsLoading = true
      this.scopeOptionsError = ''
      listResource(resource, { pageNum: 1, pageSize: 20, keyword: keyword || '' }).then(result => {
        if (seq !== this.scopeSearchSeq || type !== this.scopeType) return
        const rows = result.rows || []
        const key = type === 'ASSET' ? 'asset_id' : 'task_id'
        const selected = this.scopeOptions.find(item => String(item[key]) === this.scopeId)
        if (selected && !rows.some(item => String(item[key]) === this.scopeId)) rows.unshift(selected)
        this.scopeOptions = rows
      }).catch(() => { if (seq === this.scopeSearchSeq) { this.scopeOptions = []; this.scopeOptionsError = `加载${type === 'ASSET' ? 'Wiki 资产' : '分析任务'}失败，请检查权限或服务状态` } }).finally(() => { if (seq === this.scopeSearchSeq) this.scopeOptionsLoading = false })
    },
    scopeOptionKey(item) { return this.scopeType === 'ASSET' ? item.asset_id : item.task_id },
    scopeOptionLabel(item) { return this.scopeType === 'ASSET' ? item.asset_name : item.task_name },
    assetTypeText(type) { return ({TEXT:'文本',FILE:'文件',WIKI_PAGE:'Wiki 页面',URL:'网页链接'})[type] || type || '资料' },
    loadScopeTarget() {
      if (!this.scopeId || !['ASSET', 'ANALYSIS'].includes(this.scopeType)) return Promise.resolve()
      const resource = this.scopeType === 'ASSET' ? 'assets' : 'analyses'
      return getResource(resource, this.scopeId).then(({ data }) => {
        if (data) {
          this.scopeOptions = [data]
          this.spaceId = this.scopeType === 'ASSET' ? (data.space_id ? String(data.space_id) : '') : (data.wiki_space_id ? String(data.wiki_space_id) : '')
        }
      }).catch(() => { this.scopeOptions = [] })
    },
    newChat() {
      this.historyOpen = false
      this.sessionId = ''
      this.messages = []
      this.lastAssistantAnswer = ''
      this.question = ''
      this.saveAnswer = false
      this.updateRoute()
    },
    loadAnalysisAnswer() {
      if (this.scopeType !== 'ANALYSIS' || !this.scopeId) return Promise.resolve()
      return getAnalysisContext(this.scopeId, this.proposalId).then(({ data }) => {
        if (!data) return
        let config = {}
        try { config = typeof data.config_json === 'string' ? JSON.parse(data.config_json) : (data.config_json || {}) } catch (e) { config = {} }
        this.analysisConfig = config
        this.analysisChanges = Array.isArray(config.analysisCorrections) ? config.analysisCorrections : []
        this.analysisSaved = config.answerStatus === 'CONFIRMED'
        this.analysisAnswerDraft = config.llmAnswer || data.summary || ''
        if (data.proposal_id) this.proposalId = String(data.proposal_id)
        this.$nextTick(this.updateTableHeight)
      }).catch(() => {})
    },
    updateTableHeight() {
      this.$nextTick(() => {
        const column = this.$refs.structureColumn
        if (column) this.analysisTableHeight = Math.max(180, column.clientHeight - 210)
      })
    },
    onStructureTabChange() {
      this.updateTableHeight()
    },
    analysisObjectLabel(row) { return row && (row.name || row.table || row.technicalName) || '未命名对象' },
    analysisPropertyLabel(row) { return row && (row.name || row.column || row.technicalName) || '未命名字段' },
    analysisObjectName(id) { const row = (this.analysisConfig.objects || []).find(item => String(item.id) === String(id)); return row ? this.analysisObjectLabel(row) : (id || '未关联对象') },
    analysisRowChanged(category, row) { return this.analysisChanges.some(change => change.category === category && String(change.id) === String(row && row.id)) },
    analysisFieldChanged(category, row, field) { return this.analysisChanges.some(change => change.category === category && String(change.id) === String(row && row.id) && change.field === field) },
    analysisRowClass(category, scope) {
      const row = scope && scope.row ? scope.row : scope
      if (!row) return ''
      const selected = category === 'properties' && this.selectedAnalysisField && String(row.id) === String(this.selectedAnalysisField.id)
      const located = this.locatedAnalysisChange && this.locatedAnalysisChange.category === category && String(row.id) === String(this.locatedAnalysisChange.id)
      return [selected && 'analysis-row-selected', located && 'analysis-row-located'].filter(Boolean).join(' ')
    },
    selectAnalysisField(row) {
      this.selectedAnalysisField = row ? Object.assign({}, row) : null
      this.locatedAnalysisChange = null
    },
    askAboutSelectedField() {
      if (!this.selectedAnalysisField) return
      this.questionContext = Object.assign({}, this.selectedAnalysisField)
      this.question = '请说明这个字段的用途和当前数据类型。'
      this.mobilePanel = 'assistant'
      this.$nextTick(() => { if (this.$refs.questionInput) this.$refs.questionInput.focus() })
    },
    clearQuestionContext() {
      this.questionContext = null
    },
    structureTabForCategory(category) {
      return category === 'permissionCandidates' ? 'permissions' : category
    },
    rowsForCategory(category) {
      const key = category === 'permissionCandidates' ? 'permissionCandidates' : category
      return Array.isArray(this.analysisConfig[key]) ? this.analysisConfig[key] : []
    },
    locateAnalysisChange(change) {
      if (!change) return
      const tab = this.structureTabForCategory(change.category)
      this.analysisStructureTab = tab
      this.locatedAnalysisChange = { category: change.category, id: change.id }
      this.mobilePanel = 'structure'
      this.$nextTick(() => this.$nextTick(() => {
        const table = this.$refs[`table-${change.category}`]
        const rows = this.rowsForCategory(change.category)
        const index = rows.findIndex(row => String(row.id) === String(change.id))
        if (!table || index < 0) return
        table.setCurrentRow(rows[index])
        const body = table.$el.querySelector('.el-table__body-wrapper')
        const element = body && body.querySelectorAll('tbody tr')[index]
        if (body && element) body.scrollTop = Math.max(0, element.offsetTop - 48)
        this.updateTableHeight()
      }))
    },
    cloneData(value) {
      return value == null ? value : JSON.parse(JSON.stringify(value))
    },
    analysisCandidate(category, id, config = this.analysisConfig) {
      const rows = Array.isArray(config && config[category]) ? config[category] : []
      return rows.find(row => String(row.id) === String(id))
    },
    sameValue(left, right) {
      return JSON.stringify(left) === JSON.stringify(right)
    },
    undoAnalysisChanges(item) {
      if (!item || !item.undoState || item.undone) return
      const snapshot = item.undoState
      ;(item.analysisUpdatedChanges || []).forEach(change => {
        const current = this.analysisCandidate(change.category, change.id)
        const before = this.analysisCandidate(change.category, change.id, snapshot.beforeConfig)
        const after = this.analysisCandidate(change.category, change.id, snapshot.afterConfig)
        if (current && after && this.sameValue(current[change.field], after[change.field])) {
          if (before && Object.prototype.hasOwnProperty.call(before, change.field)) this.$set(current, change.field, before[change.field])
          else this.$delete(current, change.field)
        }

        const markerIndex = this.analysisChanges.findIndex(row => row.category === change.category && String(row.id) === String(change.id) && row.field === change.field)
        const afterMarker = (snapshot.afterChanges || []).find(row => row.category === change.category && String(row.id) === String(change.id) && row.field === change.field)
        const beforeMarker = (snapshot.beforeChanges || []).find(row => row.category === change.category && String(row.id) === String(change.id) && row.field === change.field)
        if (markerIndex >= 0 && afterMarker && this.sameValue(this.analysisChanges[markerIndex].value, afterMarker.value)) {
          if (beforeMarker) this.$set(this.analysisChanges, markerIndex, this.cloneData(beforeMarker))
          else this.analysisChanges.splice(markerIndex, 1)
        }
      })
      this.$set(this.analysisConfig, 'analysisCorrections', this.analysisChanges)
      this.analysisSaved = false
      item.undone = true
      if (this.selectedAnalysisField) this.selectedAnalysisField = this.analysisCandidate('properties', this.selectedAnalysisField.id) || this.selectedAnalysisField
      this.$modal.msgSuccess('已撤销本次结构修正')
    },
    analysisChangeSummary(changes) {
      return `已修正：${changes.map(change => {
        const row = this.analysisCandidate(change.category, change.id)
        let name = row ? (row.name || row.table || row.column || '未命名候选') : '结构候选'
        if (change.category === 'properties' && row) name = `${this.analysisObjectName(row.objectId)}.${row.column || row.technicalName || row.name}`
        return `${name} · ${this.analysisChangeLabel(change)} → ${change.value}`
      }).join('；')}`
    },
    analysisCategoryText(value) { return ({objects:'对象',properties:'字段',relations:'关系',logics:'业务逻辑',actions:'业务动作',serviceFlows:'服务流',permissionCandidates:'权限候选'})[value] || value },
    analysisChangeLabel(change) { return ({name:'名称',description:'说明',dataType:'数据类型',nullable:'可空',isPrimaryKey:'主键',cardinality:'关系基数',conditionText:'规则内容',risk:'风险',operation:'操作',trigger:'触发方式',subjectType:'主体类型',rowFilterRequired:'行级过滤'})[change.field] || change.field },
    analysisRiskText(value) { return ({LOW:'低',MEDIUM:'中',HIGH:'高',CRITICAL:'严重'})[String(value || '').toUpperCase()] || value || '未评估' },
    analysisStatusText(value) { return ({NEEDS_REVIEW:'待审核',DRAFT:'待审核',REVIEWED:'待审核',APPROVED:'已通过',REJECTED:'已驳回',IMPORTED:'已导入',ACTIVE:'已启用',DISABLED:'已停用'})[String(value || '').toUpperCase()] || value || '未设置' },
    analysisOperationText(value) { return ({READ:'查询',SELECT:'查询',INSERT:'新增',UPDATE:'更新',DELETE:'删除'})[String(value || '').toUpperCase()] || value || '未指定' },
    analysisTriggerText(value) { return ({MCP_REQUEST:'工具 / API 请求',MANUAL:'人工触发',SCHEDULE:'定时触发',EVENT:'事件触发',WEBHOOK:'Webhook 回调'})[String(value || '').toUpperCase()] || value || '未指定' },
    analysisCardinalityText(value) { return ({ONE_TO_ONE:'一对一',ONE_TO_MANY:'一对多',MANY_TO_ONE:'多对一',MANY_TO_MANY:'多对多'})[String(value || '').toUpperCase()] || value || '未设置' },
    analysisSubjectTypeText(value) { return ({ROLE:'角色',USER:'用户',GROUP:'用户组',SERVICE:'服务账号'})[String(value || '').toUpperCase()] || value || '未指定' },
    analysisBooleanText(value) { if (value===true || String(value).toLowerCase()==='true') return '是'; if (value===false || String(value).toLowerCase()==='false') return '否'; return '未设置' },
    saveAnalysisAnswer() {
      if (!this.scopeId || !this.proposalId) { this.$modal.msgWarning('当前分析没有关联配置草案，无法保存'); return }
      this.$modal.confirm('保存当前分析结构，并同步沉淀到 LLM Wiki？').then(() => {
      this.analysisAnswerSaving = true
      const answer = String(this.analysisAnswerDraft || this.analysisConfig.llmAnswer || '分析结构已由分析者确认。').trim()
      return saveAnalysisAnswer(this.scopeId, { answer, confirm: true, proposalId: Number(this.proposalId), analysisChanges: this.analysisChanges }).then(() => {
        this.analysisConfig.answerStatus = 'CONFIRMED'
        this.analysisConfig.llmAnswer = answer
        this.analysisSaved = true
        this.$modal.msgSuccess('分析结构已保存，并沉淀到 LLM Wiki')
      }).finally(() => { this.analysisAnswerSaving = false })
      }).catch(() => {})
    },
    askExample(value) {
      this.question = value
      this.send()
    },
    goAnalysis() {
      let scope = this.question.trim()
      if (!scope) {
        for (let i = this.messages.length - 1; i >= 0; i--) {
          if (this.messages[i].role === 'user') { scope = this.messages[i].content; break }
        }
      }
      this.$router.push({ path: '/ontology/analysis', query: { new: '1', wiki_space_id: this.spaceId || undefined, scope: scope || undefined } })
    },
    send() {
      const question = this.question.trim()
      if (!question || this.sending || !this.scopeReady) return
      const beforeConfig = this.cloneData(this.analysisConfig)
      const beforeChanges = this.cloneData(this.analysisChanges)
      const questionContextLabel = this.questionContextLabel
      const apiQuestion = questionContextLabel ? `当前引用字段：${questionContextLabel}\n本轮问题：${question}` : question
      if (!this.sessionId) {
        this.sessionId = 'chat-' + Date.now() + '-' + Math.random().toString(36).slice(2, 9)
        this.updateRoute()
      }
      this.sending = true
      this.messages.push({ role: 'user', content: question, questionContextLabel })
      const pending = { role: 'assistant', content: '正在查阅所选知识范围并生成回答…', pending: true, failed: false, tempId: 'pending-' + Date.now(), failedQuestion: question, failedContextLabel: questionContextLabel, failedContext: this.cloneData(this.questionContext) }
      this.messages.push(pending)
      this.question = ''
      this.scrollMessages()
      askWiki({ question: apiQuestion, sessionId: this.sessionId || undefined, scopeType: this.scopeType, scopeId: this.scopeId ? Number(this.scopeId) : null, proposalId: this.scopeType === 'ANALYSIS' && this.proposalId ? Number(this.proposalId) : undefined, analysisChanges: this.scopeType === 'ANALYSIS' ? this.analysisChanges : undefined, spaceId: this.scopeType === 'SPACE' && this.scopeId ? Number(this.scopeId) : null, saveAsPage: this.saveAnswer }).then(({ data }) => {
        this.sessionId = data.sessionId
        this.updateRoute()
        const pendingIndex = this.messages.indexOf(pending)
        if (pendingIndex >= 0) this.messages.splice(pendingIndex, 1)
        this.lastAssistantAnswer = data.answer || ''
        let assistantMessage = { role: 'assistant', content: data.answer, sources: data.citedSources || [], savedToWiki: !!data.savedToWiki, pageTitle: data.pageTitle || '', analysisUpdatedChanges: data.analysisUpdatedChanges || [] }
        if (this.scopeType === 'ANALYSIS') {
          this.analysisConfig = data.analysisConfig || this.analysisConfig
          this.analysisChanges = data.analysisChanges || this.analysisChanges
          this.analysisAnswerDraft = data.answer || this.analysisAnswerDraft
          if (data.analysisUpdatedChanges && data.analysisUpdatedChanges.length) this.analysisSaved = false
          if (data.analysisUpdatedChanges && data.analysisUpdatedChanges.length) {
            assistantMessage.content = this.analysisChangeSummary(data.analysisUpdatedChanges)
            assistantMessage.undoState = { beforeConfig, beforeChanges, afterConfig: this.cloneData(this.analysisConfig), afterChanges: this.cloneData(this.analysisChanges) }
          }
        }
        if (questionContextLabel) assistantMessage.questionContextLabel = questionContextLabel
        this.messages.push(assistantMessage)
        this.saveAnswer = false
        this.loadSessions()
        this.scrollMessages()
      }).catch(error => {
        pending.pending = false
        pending.failed = true
        pending.content = error && error.message ? error.message : '回答暂时失败，请检查服务状态后重试。'
        this.loadSessions()
        this.scrollMessages()
      }).finally(() => { this.sending = false })
    },
    retryAnswer(item, index) {
      if (this.sending) return
      if (index > 0 && this.messages[index - 1].role === 'user') this.messages.splice(index - 1, 2)
      else this.messages.splice(index, 1)
      this.question = item.failedQuestion
      this.questionContext = this.cloneData(item.failedContext)
      this.$nextTick(() => this.send())
    },
    scrollMessages() {
      this.$nextTick(() => { const box = this.$refs.messages; if (box) box.scrollTop = box.scrollHeight })
    },
    openSession(session, updateUrl = true) {
      if (this.sending) return
      const sessionType = session.scopeType || (session.spaceId == null ? 'ALL' : 'SPACE')
      const sameAnalysis = this.scopeType === 'ANALYSIS' && sessionType === 'ANALYSIS' && String(this.scopeId || '') === String(session.scopeId || '')
      this.sessionId = session.sessionId
      this.scopeType = sessionType
      this.scopeId = session.scopeId == null ? (this.scopeType === 'SPACE' && session.spaceId != null ? String(session.spaceId) : '') : String(session.scopeId)
      this.spaceId = session.spaceId == null ? '' : String(session.spaceId)
      if (!sameAnalysis) this.proposalId = ''
      this.scopeOptions = []
      if (!sameAnalysis) {
        this.analysisConfig = {}
        this.analysisChanges = []
        this.analysisSaved = false
        this.loadScopeTarget().then(() => this.loadAnalysisAnswer())
      }
      this.messages = []
      this.historyOpen = false
      this.questionContext = null
      this.selectedAnalysisField = null
      this.lastAssistantAnswer = ''
      this.loadingMessages = true
      if (updateUrl) this.updateRoute()
      getWikiMessages(session.sessionId).then(({ data }) => {
        this.messages = (data || []).reverse().map(item => Object.assign({}, item, { sources: item.sources || [] }))
        for (let i = this.messages.length - 1; i >= 0; i--) {
          if (this.messages[i].role === 'assistant' && !this.messages[i].pending && !this.messages[i].failed) {
            this.lastAssistantAnswer = this.messages[i].content || ''
            break
          }
        }
      }).catch(() => this.$modal.msgError('问答记录加载失败')).finally(() => { this.loadingMessages = false })
    },
    updateRoute() {
      const query = Object.assign({}, this.$route.query)
      if (this.sessionId) query.sessionId = this.sessionId
      else delete query.sessionId
      query.scopeType = this.scopeType
      if (this.scopeId) query.scopeId = this.scopeId
      else delete query.scopeId
      if (this.scopeType === 'SPACE' && this.scopeId) query.spaceId = this.scopeId
      else delete query.spaceId
      this.$router.replace({ query }).catch(() => {})
    },
    viewSource(source) {
      getResource('assets', source.assetId).then(({ data }) => {
        this.sourcePreview = data || {}
        this.sourcePreviewOpen = true
      }).catch(() => this.$modal.msgError('引用资料加载失败'))
    },
    formatTime(value) {
      return value ? String(value).replace('T', ' ').slice(0, 16) : ''
    },
    sessionScope(item) {
      const type = item.scopeType || (item.spaceId == null ? 'ALL' : 'SPACE')
      if (type === 'ALL') return '全部资料'
      if (type === 'SPACE') {
        const space = this.spaces.find(row => String(row.space_id) === String(item.scopeId || item.spaceId))
        return space ? space.space_name : '行业知识空间'
      }
      return type === 'ASSET' ? '单个 Wiki 资产' : '分析任务'
    }
  }
}
</script>

<style scoped>
.wiki-assistant { display:flex; flex-direction:column; max-width:none; height:calc(100vh - 106px); min-height:0; padding:16px 24px 20px; overflow:hidden; }
.page-toolbar { display:grid; grid-template-columns:minmax(180px,1fr) minmax(420px,2fr) auto; align-items:center; gap:16px; flex:0 0 auto; min-height:52px; margin-bottom:10px; }
.title-block { display:flex; align-items:baseline; gap:10px; min-width:0; }
.title-block h1 { margin:0; color:#24324b; font-size:22px; white-space:nowrap; }
.title-block span,.scope-hint,.conversation-heading span { color:#8a96a8; font-size:12px; }
.scope-controls { display:flex; align-items:center; justify-content:center; gap:8px; min-width:0; }
.scope-label { color:#526174; font-size:13px; font-weight:600; white-space:nowrap; }
.scope-controls .el-select { width:170px; }
.scope-controls .el-select + .el-select { width:210px; }
.help-button { padding:0 4px; }
.start-analysis { white-space:nowrap; }
.scope-error { color:#c45656; font-size:12px; white-space:nowrap; }
.toolbar-actions { display:flex; align-items:center; justify-content:flex-end; gap:8px; white-space:nowrap; }
.save-state { margin-right:0; }
.icon-button { padding:8px; }
.mobile-workspace-tabs { display:none; }
.workspace-shell { display:flex; flex:1 1 auto; min-height:0; overflow:hidden; border:1px solid #dfe6ef; border-radius:10px; background:#fff; }
.structure-column,.conversation-panel { min-width:0; min-height:0; }
.structure-column { display:flex; flex-direction:column; flex:0 0 65%; padding:16px 18px 0; overflow:hidden; }
.conversation-panel { display:flex; flex:1 1 35%; flex-direction:column; padding:16px 16px 12px; border-left:1px solid #e4eaf1; overflow:hidden; }
.structure-heading,.conversation-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; flex:0 0 auto; margin-bottom:10px; }
.structure-heading strong,.conversation-heading strong { color:#313f53; font-size:16px; }
.structure-heading span { display:block; margin-top:3px; color:#8a96a8; font-size:12px; }
.structure-metrics { display:flex; flex-wrap:wrap; justify-content:flex-end; gap:8px; color:#8491a3; font-size:11px; }
.structure-metrics span { white-space:nowrap; }
.structure-tabs { flex:1 1 auto; min-height:0; }
.structure-tabs ::v-deep .el-tabs__content,.structure-tabs ::v-deep .el-tab-pane { height:100%; }
.structure-tabs ::v-deep .el-table { width:100%; }
.structure-tabs ::v-deep .el-table__body-wrapper { overflow-y:auto; }
.selected-field-toolbar { display:flex; align-items:center; justify-content:space-between; gap:8px; flex:0 0 auto; min-height:34px; margin-bottom:6px; padding:5px 8px; border-radius:5px; background:#f4f8fc; color:#526174; font-size:12px; }
.corrected-tag { border:0; background:#e7f7ee; color:#25845f; }
.analysis-structure ::v-deep .analysis-row-corrected td { background:#f7fcf9 !important; }
.analysis-structure ::v-deep .analysis-row-selected td { background:#eef6ff !important; }
.structure-tabs ::v-deep .analysis-row-located td { background:#fff5d9 !important; transition:background .2s; }
.conversation-heading { padding-bottom:10px; border-bottom:1px solid #edf1f5; }
.messages { flex:1 1 auto; min-height:0; overflow:auto; padding:12px 4px 8px; }
.message { margin-bottom:16px; }
.message.user { text-align:right; }
.role { margin-bottom:4px; color:#8a96a8; font-size:11px; }
.content { display:inline-block; max-width:92%; padding:9px 12px; border-radius:8px; background:#f3f6fa; color:#334155; line-height:1.65; text-align:left; white-space:pre-wrap; overflow-wrap:anywhere; }
.user .content { background:#2f88ff; color:#fff; }
.wait-spinner { margin-right:8px; color:#2f88ff; }
.message.failed .content { color:#b54747; background:#fff4f2; }
.message-context,.question-context { color:#708096; font-size:11px; }
.message-context { margin-bottom:4px; }
.message-actions { display:flex; align-items:center; gap:8px; margin-top:3px; }
.undo-done { color:#8a96a8; font-size:11px; }
.citations { display:flex; align-items:center; flex-wrap:wrap; gap:4px; margin-top:5px; color:#7b889b; font-size:11px; }
.citations .el-button { margin:0; }
.saved-note { margin-top:5px; color:#21936f; font-size:11px; }
.examples { display:flex; flex-wrap:wrap; align-items:center; gap:5px; flex:0 0 auto; padding:8px 0; border-top:1px solid #edf1f6; color:#8190a4; font-size:11px; }
.composer { flex:0 0 auto; padding-top:8px; border-top:1px solid #edf1f6; }
.save-answer-option { margin-bottom:6px; color:#64748b; font-size:11px; }
.question-context { display:flex; align-items:center; gap:6px; margin-bottom:6px; }
.input-wrap { position:relative; }
.input-wrap ::v-deep .el-textarea__inner { padding:10px 84px 10px 10px; border-color:#d9e2ec; }
.send-button { position:absolute; right:8px; bottom:8px; z-index:1; }
.composer-footer { display:flex; justify-content:space-between; margin-top:4px; color:#a0aaba; font-size:10px; }
.source-heading { display:flex; align-items:center; gap:10px; color:#24324b; font-size:16px; }
.source-description { color:#718096; }
.source-content { max-height:480px; overflow:auto; padding:16px; border:1px solid #e7edf5; border-radius:8px; background:#f8fafc; color:#334155; white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.65; }
.history-list { padding:0 16px 20px; }
.session-item { display:block; width:100%; padding:10px; margin-bottom:5px; border:0; border-radius:7px; background:transparent; color:#43536a; text-align:left; cursor:pointer; }
.session-item:hover,.session-item.active { background:#eef6ff; color:#1d6fd1; }
.session-item span { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:13px; }
.session-item small { display:block; margin-top:5px; color:#9aa5b4; font-size:11px; }
.analysis-chat-tip { display:block; margin-bottom:6px; color:#8a817a; font-size:11px; }
@media (min-width:1024px) and (max-width:1439px) { .structure-column { flex-basis:60%; }.conversation-panel { flex-basis:40%; } }
@media (max-width:1023px) {
  .wiki-assistant { height:auto; min-height:calc(100vh - 106px); overflow:visible; padding:14px 16px 24px; }
  .page-toolbar { grid-template-columns:1fr auto; gap:8px; }
  .title-block { grid-column:1 / 2; }
  .scope-controls { grid-column:1 / -1; grid-row:2; justify-content:flex-start; flex-wrap:wrap; }
  .toolbar-actions { grid-column:2; grid-row:1; }
  .mobile-workspace-tabs { display:flex; gap:4px; margin-bottom:8px; padding:3px; border-radius:6px; background:#edf2f7; }
  .mobile-workspace-tabs button { flex:1; padding:8px; border:0; border-radius:4px; background:transparent; color:#68778c; cursor:pointer; }
  .mobile-workspace-tabs button.active { background:#fff; color:#1d6fd1; box-shadow:0 1px 4px rgba(35,61,91,.12); }
  .workspace-shell { display:block; min-height:620px; overflow:visible; border:0; background:transparent; }
  .structure-column,.conversation-panel { height:calc(100vh - 220px); min-height:520px; padding:12px; border:1px solid #dfe6ef; border-radius:8px; background:#fff; }
  .conversation-panel { border-left:1px solid #dfe6ef; }
  .mobile-hidden { display:none; }
  .structure-metrics { justify-content:flex-start; }
  .scope-controls .el-select,.scope-controls .el-select + .el-select { width:180px; }
}
@media (max-width:600px) { .title-block h1 { font-size:18px; }.title-block span { display:none; }.toolbar-actions .el-button:not(.icon-button) { padding:8px; }.toolbar-actions .el-button:not(.icon-button) { font-size:0; }.toolbar-actions .el-button:not(.icon-button) i { margin:0; }.scope-controls .el-select,.scope-controls .el-select + .el-select { width:100%; }.scope-controls { align-items:stretch; }.scope-label { align-self:center; } }
</style>
