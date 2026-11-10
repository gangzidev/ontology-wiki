<template>
  <div class="wiki-assistant">

    <!-- ============================================================
         顶部单行 Header（品牌 + 面包屑范围 + 操作 三区合并）
    ============================================================ -->
    <header class="page-header">

      <!-- 左：图标 + 标题 -->
      <div class="header-brand">
        <div class="header-icon-wrap">
          <i :class="scopeType === SCOPE_TYPE.ANALYSIS ? 'el-icon-data-analysis' : 'el-icon-chat-dot-round'" />
        </div>
        <div>
          <h1 class="page-title">{{ pageTitle }}</h1>
          <p class="page-subtitle">{{ pageSubtitle }}</p>
        </div>
      </div>

      <!-- 右：操作按钮 -->
      <div class="header-actions">
        <template v-if="scopeType === SCOPE_TYPE.ANALYSIS">
          <el-tag v-if="analysisChanges.length" class="save-badge" size="small" type="warning">
            {{ analysisChanges.length }} 项待保存
          </el-tag>
          <el-tag v-else-if="analysisSaved" class="save-badge" size="small" type="success">已保存</el-tag>
          <el-button
            size="small"
            type="primary"
            icon="el-icon-collection-tag"
            :loading="analysisAnswerSaving"
            @click="saveAnalysisAnswer"
          >保存分析</el-button>
        </template>
        <el-button size="small" icon="el-icon-time" @click="historyOpen = true">历史会话</el-button>
        <el-button
          class="icon-btn"
          circle
          size="small"
          icon="el-icon-plus"
          title="新建问答"
          aria-label="新建问答"
          :disabled="sending"
          @click="newChat"
        />
      </div>
    </header>

    <!-- ============================================================
         移动端工作区切换 Tab（仅窄屏显示）
    ============================================================ -->
    <div v-if="scopeType === SCOPE_TYPE.ANALYSIS" class="mobile-tabs" role="tablist" aria-label="分析问答工作区">
      <button type="button" :class="{ active: mobilePanel === 'structure' }" @click="mobilePanel = 'structure'">
        分析结构
      </button>
      <button type="button" :class="{ active: mobilePanel === 'assistant' }" @click="mobilePanel = 'assistant'">
        分析助手
      </button>
    </div>

    <!-- ============================================================
         主工作区：分析结构面板 + 对话面板
    ============================================================ -->
    <main ref="workspaceShell" :class="['workspace', { 'has-structure': scopeType === SCOPE_TYPE.ANALYSIS }]">

      <!-- ── 左栏：分析结构（仅 ANALYSIS 模式显示） ── -->
      <section
        v-if="scopeType === SCOPE_TYPE.ANALYSIS"
        ref="structureColumn"
        :class="['structure-panel', { 'mobile-hidden': mobilePanel !== 'structure' }]"
        :style="{ flexBasis: structureWidth + 'px', flexGrow: 0, flexShrink: 0 }"
      >
        <!-- 面板标题 -->
        <div ref="structureHeader" class="structure-heading">
          <div class="structure-heading-text">
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

        <!-- 已选字段工具条 -->
        <div v-if="selectedAnalysisField" class="selected-field-bar">
          <span>已选：{{ selectedFieldLabel }}</span>
          <el-button size="mini" type="primary" plain icon="el-icon-chat-dot-round" @click="askAboutSelectedField">
            询问此字段
          </el-button>
        </div>

        <!-- 分类 Tab + 表格 -->
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
                <template slot-scope="s">
                  <el-tag v-if="analysisRowChanged('properties', s.row)" class="corrected-tag" size="mini" type="success">已修正</el-tag>
                  {{ analysisPropertyLabel(s.row) }}
                </template>
              </el-table-column>
              <el-table-column label="所属对象" min-width="140">
                <template slot-scope="s">{{ analysisObjectName(s.row.objectId) }}</template>
              </el-table-column>
              <el-table-column prop="column" label="物理字段" min-width="120" />
              <el-table-column prop="dataType" label="数据类型" width="130" />
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
              <el-table-column label="关系基数" width="100"><template slot-scope="s">{{ cardinalityText(s.row.cardinality) }}</template></el-table-column>
              <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="业务逻辑" name="logics">
            <el-table ref="table-logics" :data="analysisConfig.logics || []" :height="analysisTableHeight" size="mini" :row-class-name="row => analysisRowClass('logics', row)" empty-text="暂无业务逻辑候选">
              <el-table-column label="逻辑名称" min-width="150">
                <template slot-scope="s">
                  <el-tag v-if="analysisRowChanged('logics', s.row)" class="corrected-tag" size="mini" type="success">已修正</el-tag>
                  {{ s.row.name }}
                </template>
              </el-table-column>
              <el-table-column prop="conditionText" label="规则内容" min-width="240" show-overflow-tooltip />
              <el-table-column label="风险" width="90"><template slot-scope="s">{{ riskText(s.row.risk) }}</template></el-table-column>
              <el-table-column label="状态" width="100"><template slot-scope="s">{{ statusText(s.row.status) }}</template></el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="业务动作" name="actions">
            <el-table ref="table-actions" :data="analysisConfig.actions || []" :height="analysisTableHeight" size="mini" :row-class-name="row => analysisRowClass('actions', row)" empty-text="暂无业务动作候选">
              <el-table-column label="动作名称" min-width="150">
                <template slot-scope="s">
                  <el-tag v-if="analysisRowChanged('actions', s.row)" class="corrected-tag" size="mini" type="success">已修正</el-tag>
                  {{ s.row.name }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="100"><template slot-scope="s">{{ operationText(s.row.operation) }}</template></el-table-column>
              <el-table-column label="所属对象" min-width="140"><template slot-scope="s">{{ analysisObjectName(s.row.objectId) }}</template></el-table-column>
              <el-table-column label="风险" width="90"><template slot-scope="s">{{ riskText(s.row.risk) }}</template></el-table-column>
              <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="服务流" name="serviceFlows">
            <el-table ref="table-serviceFlows" :data="analysisConfig.serviceFlows || []" :height="analysisTableHeight" size="mini" :row-class-name="row => analysisRowClass('serviceFlows', row)" empty-text="暂无服务流候选">
              <el-table-column label="服务流名称" min-width="150">
                <template slot-scope="s">
                  <el-tag v-if="analysisRowChanged('serviceFlows', s.row)" class="corrected-tag" size="mini" type="success">已修正</el-tag>
                  {{ s.row.name }}
                </template>
              </el-table-column>
              <el-table-column label="触发方式" width="130"><template slot-scope="s">{{ triggerText(s.row.trigger) }}</template></el-table-column>
              <el-table-column label="状态" width="100"><template slot-scope="s">{{ statusText(s.row.status) }}</template></el-table-column>
              <el-table-column prop="steps" label="步骤" min-width="180" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="权限候选" name="permissions">
            <el-table ref="table-permissionCandidates" :data="analysisConfig.permissionCandidates || []" :height="analysisTableHeight" size="mini" :row-class-name="row => analysisRowClass('permissionCandidates', row)" empty-text="暂无权限候选">
              <el-table-column label="资源对象" min-width="150">
                <template slot-scope="s">
                  <el-tag v-if="analysisRowChanged('permissionCandidates', s.row)" class="corrected-tag" size="mini" type="success">已修正</el-tag>
                  {{ analysisObjectName(s.row.resourceId) }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90"><template slot-scope="s">{{ operationText(s.row.operation) }}</template></el-table-column>
              <el-table-column label="主体类型" width="100"><template slot-scope="s">{{ subjectTypeText(s.row.subjectType) }}</template></el-table-column>
              <el-table-column prop="suggestedSubjects" label="建议主体" min-width="150" />
              <el-table-column label="行级过滤" width="100"><template slot-scope="s">{{ booleanText(s.row.rowFilterRequired) }}</template></el-table-column>
              <el-table-column label="状态" width="100"><template slot-scope="s">{{ statusText(s.row.status) }}</template></el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </section>

      <!-- ── 可拖拽分割线（仅 ANALYSIS 模式） ── -->
      <div
        v-if="scopeType === SCOPE_TYPE.ANALYSIS"
        class="resize-handle"
        title="拖拽调整宽度"
        @mousedown="onResizeStart"
      >
        <span class="resize-dots" />
      </div>

      <!-- ── 右栏：对话面板 ── -->
      <section :class="['conversation-panel', { 'mobile-hidden': scopeType === SCOPE_TYPE.ANALYSIS && mobilePanel !== 'assistant' }]">
        <!-- 对话标题（单行：左=面板名，右=范围 Pill） -->
        <div class="conversation-heading">
          <strong>{{ scopeType === SCOPE_TYPE.ANALYSIS ? '分析助手' : '知识问答' }}</strong>

          <div class="inline-scope">
            <!-- 主类型 Pill -->
            <el-dropdown trigger="click" :disabled="sending" @command="onScopeTypeCommand">
              <el-tooltip :content="currentScopeConfig.label" placement="bottom-end">
                <div class="scope-pill-inline">
                  <i :class="currentScopeConfig.icon" />
                  <span>{{ currentScopeConfig.label }}</span>
                  <i class="el-icon-arrow-down" />
                </div>
              </el-tooltip>
              <el-dropdown-menu slot="dropdown" class="scope-type-menu">
                <el-dropdown-item v-for="(cfg, key) in SCOPE_TYPE_CONFIG" :key="key" :command="key" :class="{ 'is-selected': scopeType === key }">
                  <i :class="cfg.icon" />{{ cfg.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </el-dropdown>

            <!-- 行业空间（SPACE） -->
            <template v-if="scopeType === SCOPE_TYPE.SPACE">
              <i class="el-icon-arrow-right scope-arrow" />
              <el-dropdown trigger="click" :disabled="sending" @command="onSpaceCommand">
                <div :class="['scope-pill-inline', { 'scope-pill-inline--placeholder': !scopeId }]">
                  <i class="el-icon-folder-opened" />
                  <span>{{ scopeId ? ((spaces.find(s => String(s.space_id) === scopeId) || {}).space_name || '所选空间') : '选择空间' }}</span>
                  <i class="el-icon-arrow-down" />
                </div>
                <el-dropdown-menu slot="dropdown" class="scope-type-menu">
                  <el-dropdown-item v-for="item in spaces" :key="item.space_id" :command="String(item.space_id)" :class="{ 'is-selected': String(item.space_id) === scopeId }">{{ item.space_name }}</el-dropdown-item>
                </el-dropdown-menu>
              </el-dropdown>
            </template>

            <!-- 资产 / 分析任务（搜索 Popover） -->
            <template v-if="scopeType === SCOPE_TYPE.ASSET || scopeType === SCOPE_TYPE.ANALYSIS">
              <i class="el-icon-arrow-right scope-arrow" />
              <el-popover v-model="scopeDropdownVisible" popper-class="scope-dropdown-popper" trigger="click" placement="bottom-end" :disabled="sending" :width="300">
                <div class="sdp-wrap">
                  <div class="sdp-search">
                    <el-input v-model="scopeSearchKeyword" size="small" prefix-icon="el-icon-search" :placeholder="scopeType === SCOPE_TYPE.ANALYSIS ? '搜索分析任务…' : '搜索 Wiki 资产…'" clearable @input="onScopeSearch" />
                  </div>
                  <ul class="sdp-list" role="listbox">
                    <li v-if="scopeOptionsLoading" class="sdp-item sdp-loading"><i class="el-icon-loading" /> 加载中…</li>
                    <template v-else>
                      <li v-for="item in scopeOptions" :key="scopeOptionKey(item)" :class="['sdp-item', { 'sdp-item--active': String(scopeOptionKey(item)) === scopeId }]" role="option" @click="selectScopeOption(item)">
                        <div class="sdp-item-title">{{ scopeOptionLabel(item) }}</div>
                        <div v-if="item.status" class="sdp-item-sub">{{ item.status }}</div>
                      </li>
                      <li v-if="!scopeOptions.length" class="sdp-item sdp-empty">没有匹配项</li>
                    </template>
                  </ul>
                  <p v-if="scopeOptionsError" class="sdp-error">{{ scopeOptionsError }}</p>
                </div>
                <!-- pill 按钮：hover 显示完整范围描述 -->
                <el-tooltip slot="reference" :content="scopeDescription" placement="bottom-end">
                  <div :class="['scope-pill-inline', { 'scope-pill-inline--placeholder': !scopeId }]">
                    <i :class="scopeType === SCOPE_TYPE.ANALYSIS ? 'el-icon-data-analysis' : 'el-icon-document'" />
                    <span>{{ scopePillLabel }}</span>
                    <i class="el-icon-arrow-down" />
                  </div>
                </el-tooltip>
              </el-popover>
            </template>

            <el-tooltip :content="scopeTooltip" placement="bottom-end">
              <button class="help-btn" type="button" aria-label="查看使用说明"><i class="el-icon-question" /></button>
            </el-tooltip>
            <el-button v-if="scopeType !== SCOPE_TYPE.ANALYSIS" type="text" size="small" icon="el-icon-data-analysis" class="btn-go-analysis" @click="goAnalysis">分析</el-button>
          </div>
        </div>

        <!-- 能力边界提示条：首次显示，用户可关闭，仅非分析模式显示 -->
        <div v-if="showRetrievalTip && scopeType !== SCOPE_TYPE.ANALYSIS" class="retrieval-tip-bar">
          <i class="el-icon-info" />
          <span>当前基于关键词检索，请尽量使用资料中的原词；同义词或近义词可能无法关联。复杂语义问题建议先把资料「整理为 Wiki 页面」再提问。</span>
          <button type="button" class="tip-close-btn" aria-label="关闭提示" @click="closeRetrievalTip"><i class="el-icon-close" /></button>
        </div>

        <!-- 消息列表（气泡样式） -->
        <div ref="messages" class="messages">
          <div
            v-for="(item, index) in messages"
            :key="item.message_id || item.tempId || index"
            :class="['msg', item.role, { 'msg-failed': item.failed }]"
          >
            <!-- 头像 -->
            <div :class="['msg-avatar', item.role === 'user' ? 'avatar-user' : 'avatar-bot']">
              <i :class="item.role === 'user' ? 'el-icon-user' : 'el-icon-cpu'" />
            </div>
            <!-- 消息体 -->
            <div class="msg-body">
              <div class="msg-role">{{ item.role === 'user' ? '你' : '业务知识助手' }}</div>
              <div v-if="item.questionContextLabel" class="msg-context">{{ item.questionContextLabel }}</div>
              <div class="msg-content">
                <i v-if="item.pending" class="el-icon-loading msg-spinner" />
                <span>{{ item.content }}</span>
              </div>
              <el-button v-if="item.failed" type="text" size="mini" class="msg-retry" @click="retryAnswer(item, index)">
                重新回答
              </el-button>
              <!-- 引用资料 -->
              <div v-if="item.sources && item.sources.length" class="msg-citations">
                <span class="citations-label">参考资料</span>
                <el-button v-for="source in item.sources" :key="source.assetId" type="text" size="mini" class="citation-btn" @click="viewSource(source)">
                  {{ source.title }}
                </el-button>
              </div>
              <div v-if="item.savedToWiki" class="msg-saved">
                <i class="el-icon-circle-check" /> 已保存为 Wiki 页面：{{ item.pageTitle }}
              </div>
              <!-- 分析修正操作 -->
              <div v-if="item.role === 'assistant' && item.analysisUpdatedChanges && item.analysisUpdatedChanges.length" class="msg-actions">
                <el-button type="text" size="mini" @click="locateAnalysisChange(item.analysisUpdatedChanges[0])">
                  查看修改位置
                </el-button>
                <el-button v-if="item.undoState && !item.undone" type="text" size="mini" @click="undoAnalysisChanges(item)">
                  撤销
                </el-button>
                <span v-else-if="item.undone" class="undo-done">已撤销</span>
              </div>
            </div>
          </div>
          <!-- 空状态 -->
          <el-empty v-if="!messages.length && !loadingMessages" :image-size="80" :description="scopeType === SCOPE_TYPE.ANALYSIS ? '围绕当前分析结构提问或提出修正' : '从已整理的业务知识中找答案'" />
          <el-skeleton v-if="loadingMessages" :rows="4" animated />
        </div>

        <!-- 示例问题引导（卡片网格） -->
        <div v-if="!messages.length && !loadingMessages" class="example-cards">
          <span class="example-title">可以这样问</span>
          <div class="example-grid">
            <button
              v-for="example in (scopeType === SCOPE_TYPE.ANALYSIS ? analysisExamples : examples)"
              :key="example"
              class="example-card"
              type="button"
              @click="askExample(example)"
            >
              <i :class="scopeType === SCOPE_TYPE.ANALYSIS ? 'el-icon-magic-stick' : 'el-icon-chat-line-square'" class="example-card-icon" />
              <span class="example-card-text">{{ example }}</span>
            </button>
          </div>
        </div>

        <!-- 输入区 -->
        <div class="composer">
          <div class="composer-options">
            <el-checkbox v-if="scopeType !== SCOPE_TYPE.ANALYSIS" v-model="saveAnswer" class="save-answer-check">
              将本次回答保存为 Wiki 页面
            </el-checkbox>
          </div>
          <div v-if="questionContext" class="question-context">
            <span class="qc-label">引用字段</span>
            <el-tag size="mini" closable @close="clearQuestionContext">{{ questionContextLabel }}</el-tag>
          </div>
          <div class="input-wrap">
            <el-input
              ref="questionInput"
              v-model="question"
              type="textarea"
              :rows="3"
              :maxlength="QUESTION_MAX_LENGTH"
              resize="none"
              placeholder="输入问题；Enter 发送，Shift + Enter 换行"
              @keydown.native="onKeydown"
            />
            <el-button
              class="send-btn"
              type="primary"
              icon="el-icon-position"
              :loading="sending"
              :disabled="!question.trim() || !scopeReady"
              aria-label="发送问题"
              @click="send"
            >发送</el-button>
          </div>
          <div class="composer-footer">
            <span>Enter 发送 · Shift + Enter 换行</span>
            <span>{{ question.length }} / {{ QUESTION_MAX_LENGTH }}</span>
          </div>
        </div>
      </section>
    </main>

    <!-- ============================================================
         历史会话抽屉
    ============================================================ -->
    <el-drawer
      title="历史会话"
      :visible.sync="historyOpen"
      direction="rtl"
      size="360px"
      append-to-body
      custom-class="wiki-history-drawer"
    >
      <div class="history-list">
        <button
          v-for="item in sessions"
          :key="item.sessionId"
          type="button"
          :disabled="sending"
          :class="['session-item', { active: item.sessionId === sessionId }]"
          @click="openSession(item)"
        >
          <span class="session-title">{{ item.title || '新建问答' }}</span>
          <small class="session-meta">{{ sessionScope(item) }} · {{ formatTime(item.updatedAt) }}</small>
        </button>
        <el-empty v-if="!sessions.length" :image-size="56" description="还没有问答记录" />
      </div>
    </el-drawer>

    <!-- ============================================================
         引用资料预览弹窗
    ============================================================ -->
    <el-dialog title="引用资料" :visible.sync="sourcePreviewOpen" width="820px" append-to-body>
      <div class="source-heading">
        <strong>{{ sourcePreview.asset_name }}</strong>
        <el-tag size="mini">{{ assetTypeText(sourcePreview.asset_type) }}</el-tag>
      </div>
      <p v-if="sourcePreview.description" class="source-description">{{ sourcePreview.description }}</p>
      <pre class="source-content">{{ sourcePreview.content || '暂无正文' }}</pre>
      <span slot="footer"><el-button @click="sourcePreviewOpen = false">关闭</el-button></span>
    </el-dialog>
  </div>
</template>

<script>
import { askWiki, getWikiMessages, getResource, listResource, listWikiSessions, saveAnalysisAnswer, getAnalysisContext } from '@/api/ontology'
import {
  SCOPE_TYPE,
  DYNAMIC_SCOPE_TYPES,
  SCOPE_TYPE_CONFIG,
  SCOPE_RESOURCE_MAP,
  ANSWER_STATUS,
  QUESTION_MAX_LENGTH,
  SEARCH_PAGE_SIZE,
  SPACE_PAGE_SIZE,
} from './constants'
import {
  riskText,
  statusText,
  operationText,
  triggerText,
  cardinalityText,
  subjectTypeText,
  assetTypeText,
  booleanText,
} from './analysisTextUtils'

export default {
  name: 'OntologyWikiChat',
  data() {
    return {
      // ── 问答会话 ─────────────────────────────────
      sessionId: '',
      question: '',
      messages: [],
      sending: false,
      loadingMessages: false,
      saveAnswer: false,
      lastAssistantAnswer: '',

      // ── 知识范围选择 ─────────────────────────────
      scopeType: SCOPE_TYPE.ALL,
      scopeId: '',
      spaceId: '',
      spaces: [],
      scopeOptions: [],
      scopeOptionsLoading: false,
      scopeOptionsError: '',
      scopeSearchSeq: 0,

      // ── 自定义下拉框状态（ASSET / ANALYSIS 模式）──
      scopeDropdownVisible: false,
      scopeSearchKeyword: '',

      // ── 分析结构（仅 ANALYSIS 模式）──────────────
      analysisConfig: {},
      analysisChanges: [],
      analysisSaved: false,
      analysisAnswerDraft: '',
      analysisAnswerSaving: false,
      analysisStructureTab: 'objects',
      analysisTableHeight: 320,
      proposalId: '',
      selectedAnalysisField: null,
      locatedAnalysisChange: null,

      // ── UI 状态 ──────────────────────────────────
      historyOpen: false,
      mobilePanel: 'assistant',
      questionContext: null,
      sourcePreviewOpen: false,
      sourcePreview: {},
      sessions: [],
      showRetrievalTip: localStorage.getItem('ontologywiki:retrieval-tip:v1') !== 'closed',

      // ── 分割线拖拽 ───────────────────────────────
      structureWidth: 480,   // 分析结构栏初始宽度（px），可被用户拖拽修改

      // ── 示例问题（常量，无需响应式） ─────────────
      examples: ['总结当前资料中的核心业务规则', '这些资料描述了哪些业务对象？', '这个业务流程中有哪些关键状态？'],
      analysisExamples: ['这里分析出了哪些对象和字段？', '请列出菜单权限表的字段及当前数据类型。', '菜单权限表的路由地址应该是 text 类型，请修正字段并说明影响。'],
    }
  },
  watch: {
    /** 自定义下拉框打开时自动触发一次空白搜索，确保选项列表有内容 */
    scopeDropdownVisible(val) {
      if (val && !this.scopeOptions.length && !this.scopeOptionsLoading) {
        this.searchScopeOptions('')
      }
    },
  },
  computed: {
    // 将常量暴露给模板直接使用
    SCOPE_TYPE: () => SCOPE_TYPE,
    SCOPE_TYPE_CONFIG: () => SCOPE_TYPE_CONFIG,
    QUESTION_MAX_LENGTH: () => QUESTION_MAX_LENGTH,

    /** 当前范围类型的显示配置（图标、标签、提示） */
    currentScopeConfig() {
      return SCOPE_TYPE_CONFIG[this.scopeType] || SCOPE_TYPE_CONFIG[SCOPE_TYPE.ALL]
    },

    /** 页面主标题 */
    pageTitle() {
      return this.scopeType === SCOPE_TYPE.ANALYSIS ? '分析结果问答' : '业务知识问答'
    },
    /** 页面副标题 */
    pageSubtitle() {
      return this.scopeType === SCOPE_TYPE.ANALYSIS ? '分析校对助手' : '基于已整理的业务知识回答'
    },

    /** 范围选择器的 tooltip 提示文字 */
    scopeTooltip() {
      const cfg = SCOPE_TYPE_CONFIG[this.scopeType]
      return cfg ? cfg.hint || '' : ''
    },

    /** 自定义下拉 pill 按钮上显示的文字 */
    scopePillLabel() {
      if (!this.scopeId) {
        return this.scopeType === SCOPE_TYPE.ANALYSIS ? '选择分析任务' : '搜索 Wiki 资产'
      }
      const key = this.scopeType === SCOPE_TYPE.ASSET ? 'asset_id' : 'task_id'
      const labelKey = this.scopeType === SCOPE_TYPE.ASSET ? 'asset_name' : 'task_name'
      const item = this.scopeOptions.find(row => String(row[key]) === this.scopeId)
      return item ? item[labelKey] : (this.scopeType === SCOPE_TYPE.ANALYSIS ? '已选分析任务' : '已选 Wiki 资产')
    },

    /** 对话面板副标题，描述当前问答范围 */
    scopeDescription() {
      if (this.scopeType === SCOPE_TYPE.ALL) return '使用所有已分组及未分组的 Wiki 资料'
      if (this.scopeType === SCOPE_TYPE.SPACE) {
        const space = this.spaces.find(s => String(s.space_id) === this.scopeId)
        return this.scopeId ? `仅使用「${space ? space.space_name : '所选空间'}」中的资产和 Wiki 页面` : '请选择一个空间'
      }
      const key = this.scopeType === SCOPE_TYPE.ASSET ? 'asset_id' : 'task_id'
      const labelKey = this.scopeType === SCOPE_TYPE.ASSET ? 'asset_name' : 'task_name'
      const item = this.scopeOptions.find(row => String(row[key]) === this.scopeId)
      if (!this.scopeId) return this.scopeType === SCOPE_TYPE.ASSET ? '搜索并选择一个 Wiki 资产' : '搜索并选择一个分析任务'
      return `仅围绕「${item ? item[labelKey] : '所选目标'}」及其关联资料回答`
    },

    /** 范围是否已就绪（可发送问题） */
    scopeReady() {
      return this.scopeType === SCOPE_TYPE.ALL || !!this.scopeId
    },

    /** 当前引用字段的显示标签：对象名.字段名 */
    questionContextLabel() {
      if (!this.questionContext) return ''
      const row = (this.analysisConfig.properties || []).find(item => String(item.id) === String(this.questionContext.id)) || this.questionContext
      const field = row.column || row.technicalName || row.name || '未命名字段'
      return `${this.analysisObjectName(row.objectId)}.${field}`
    },

    /** 结构面板中当前选中字段的标签 */
    selectedFieldLabel() {
      if (!this.selectedAnalysisField) return ''
      const row = (this.analysisConfig.properties || []).find(item => String(item.id) === String(this.selectedAnalysisField.id)) || this.selectedAnalysisField
      return `${this.analysisObjectName(row.objectId)}.${row.column || row.technicalName || row.name || '未命名字段'}`
    },
  },
  created() {
    // 从路由参数恢复上次状态
    this.scopeType = this.$route.query.scopeType || (this.$route.query.spaceId ? SCOPE_TYPE.SPACE : SCOPE_TYPE.ALL)
    this.scopeId = this.$route.query.scopeId || this.$route.query.spaceId || ''
    this.proposalId = this.$route.query.proposalId || ''
    this.spaceId = this.scopeType === SCOPE_TYPE.SPACE ? this.scopeId : ''
    this.loadSpaces()
      .then(() => this.loadScopeTarget())
      .then(() => this.loadAnalysisAnswer())
      .then(() => this.loadSessions())
      .then(() => {
        const id = this.$route.query.sessionId
        if (!id) return
        const session = this.sessions.find(item => item.sessionId === id)
        this.openSession(session || { sessionId: id, scopeType: this.scopeType, scopeId: this.scopeId || null, spaceId: this.spaceId || null }, false)
      })
  },
  mounted() {
    this.updateTableHeight()
    window.addEventListener('resize', this.updateTableHeight)
    // 初始化结构栏宽度为工作区的 44%（首次渲染后量实际宽度）
    this.$nextTick(() => {
      const ws = this.$refs.workspaceShell
      if (ws) this.structureWidth = Math.round(ws.clientWidth * 0.44)
    })
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.updateTableHeight)
    // 确保拖拽监听器在组件销毁时也被清理
    if (this._onResizeMove) document.removeEventListener('mousemove', this._onResizeMove)
    if (this._onResizeEnd) document.removeEventListener('mouseup', this._onResizeEnd)
  },
  methods: {
    // ════════════════════════════════════════════════════════════
    //  数据加载
    // ════════════════════════════════════════════════════════════

    /** 加载行业知识空间列表 */
    loadSpaces() {
      return listResource(SCOPE_RESOURCE_MAP[SCOPE_TYPE.SPACE], { pageNum: 1, pageSize: SPACE_PAGE_SIZE })
        .then(result => {
          // status === '1' 表示已停用，不展示
          this.spaces = (result.rows || []).filter(item => item.status !== '1')
        })
        .catch(() => { this.spaces = [] })
    },

    /** 加载历史会话列表 */
    loadSessions() {
      return listWikiSessions()
        .then(({ data }) => { this.sessions = data || [] })
        .catch(() => { this.sessions = [] })
    },

    /** 加载当前选中目标的详情（用于回显名称） */
    loadScopeTarget() {
      if (!this.scopeId || !DYNAMIC_SCOPE_TYPES.includes(this.scopeType)) return Promise.resolve()
      const resource = SCOPE_RESOURCE_MAP[this.scopeType]
      return getResource(resource, this.scopeId).then(({ data }) => {
        if (!data) return
        this.scopeOptions = [data]
        const spaceField = this.scopeType === SCOPE_TYPE.ASSET ? 'space_id' : 'wiki_space_id'
        this.spaceId = data[spaceField] ? String(data[spaceField]) : ''
      }).catch(() => { this.scopeOptions = [] })
    },

    /** 加载分析任务的结构配置 */
    loadAnalysisAnswer() {
      if (this.scopeType !== SCOPE_TYPE.ANALYSIS || !this.scopeId) return Promise.resolve()
      return getAnalysisContext(this.scopeId, this.proposalId).then(({ data }) => {
        if (!data) return
        // 兼容 config_json 为字符串或对象两种格式
        let config = {}
        try {
          config = typeof data.config_json === 'string' ? JSON.parse(data.config_json) : (data.config_json || {})
        } catch (e) { config = {} }
        this.analysisConfig = config
        this.analysisChanges = Array.isArray(config.analysisCorrections) ? config.analysisCorrections : []
        this.analysisSaved = config.answerStatus === ANSWER_STATUS.CONFIRMED
        this.analysisAnswerDraft = config.llmAnswer || data.summary || ''
        if (data.proposal_id) this.proposalId = String(data.proposal_id)
        this.$nextTick(this.updateTableHeight)
      }).catch(() => {})
    },

    // ════════════════════════════════════════════════════════════
    //  知识范围切换
    // ════════════════════════════════════════════════════════════

    /** el-dropdown command：主类型切换（面包屑第一级） */
    onScopeTypeCommand(key) {
      if (key === this.scopeType) return
      if (this.messages.length > 0) {
        this.$confirm('切换范围将开始新会话，当前会话已保留在历史记录中，可随时恢复。', '切换问答范围', {
          confirmButtonText: '确认切换',
          cancelButtonText: '取消',
          type: 'info'
        }).then(() => {
          this.scopeType = key
          this.changeScopeType()
        }).catch(() => {})
      } else {
        this.scopeType = key
        this.changeScopeType()
      }
    },

    /** el-dropdown command：行业空间切换（SPACE 模式） */
    onSpaceCommand(id) {
      if (id === this.scopeId) return
      this.scopeId = id
      this.changeScopeTarget()
    },

    /** 切换主范围类型，重置相关状态 */
    changeScopeType() {      this.scopeId = ''
      this.spaceId = ''
      this.proposalId = ''
      this.scopeOptions = []
      this.scopeOptionsError = ''
      this.scopeSearchKeyword = ''
      this.analysisConfig = {}
      this.analysisChanges = []
      this.analysisSaved = false
      this.analysisAnswerDraft = ''
      this.questionContext = null
      this.selectedAnalysisField = null
      this.mobilePanel = 'assistant'
      this.newChat()
    },

    /** 切换目标（空间 / 资产 / 分析任务） */
    changeScopeTarget() {
      const key = this.scopeType === SCOPE_TYPE.ASSET ? 'asset_id' : 'task_id'
      const item = this.scopeOptions.find(row => String(row[key]) === this.scopeId)
      // 同步 spaceId（用于"开始本体分析"等功能）
      if (this.scopeType === SCOPE_TYPE.SPACE) {
        this.spaceId = this.scopeId
      } else if (item) {
        const spaceField = this.scopeType === SCOPE_TYPE.ASSET ? 'space_id' : 'wiki_space_id'
        this.spaceId = item[spaceField] ? String(item[spaceField]) : ''
      } else {
        this.spaceId = ''
      }
      this.proposalId = ''
      if (this.scopeType === SCOPE_TYPE.ANALYSIS) {
        this.analysisConfig = {}
        this.analysisChanges = []
        this.analysisSaved = false
      }
      this.questionContext = null
      this.selectedAnalysisField = null
      this.mobilePanel = 'assistant'
      this.newChat()
      if (this.scopeType === SCOPE_TYPE.ANALYSIS) this.loadAnalysisAnswer()
    },

    // ════════════════════════════════════════════════════════════
    //  自定义下拉框搜索（ASSET / ANALYSIS 模式）
    // ════════════════════════════════════════════════════════════

    /** 搜索框输入事件（300ms 防抖） */
    onScopeSearch() {
      clearTimeout(this._scopeSearchTimer)
      this._scopeSearchTimer = setTimeout(() => {
        this.searchScopeOptions(this.scopeSearchKeyword)
      }, 300)
    },

    /** 远程搜索资产 / 分析任务列表 */
    searchScopeOptions(keyword) {
      const type = this.scopeType
      const resource = type === SCOPE_TYPE.ASSET ? 'assets' : 'analyses'
      // 序号防竞态：只采用最新一次结果
      const seq = ++this.scopeSearchSeq
      this.scopeOptionsLoading = true
      this.scopeOptionsError = ''
      listResource(resource, { pageNum: 1, pageSize: SEARCH_PAGE_SIZE, keyword: keyword || '' })
        .then(result => {
          if (seq !== this.scopeSearchSeq || type !== this.scopeType) return
          const rows = result.rows || []
          // 确保当前已选项始终在列表中
          const key = type === SCOPE_TYPE.ASSET ? 'asset_id' : 'task_id'
          const selected = this.scopeOptions.find(item => String(item[key]) === this.scopeId)
          if (selected && !rows.some(item => String(item[key]) === this.scopeId)) rows.unshift(selected)
          this.scopeOptions = rows
        })
        .catch(() => {
          if (seq === this.scopeSearchSeq) {
            this.scopeOptions = []
            this.scopeOptionsError = `加载${type === SCOPE_TYPE.ASSET ? 'Wiki 资产' : '分析任务'}失败，请检查权限或服务状态`
          }
        })
        .finally(() => {
          if (seq === this.scopeSearchSeq) this.scopeOptionsLoading = false
        })
    },

    /** 选中下拉项 */
    selectScopeOption(item) {
      this.scopeId = String(this.scopeOptionKey(item))
      this.scopeDropdownVisible = false
      this.changeScopeTarget()
    },

    /** 获取选项的唯一标识（资产用 asset_id，任务用 task_id） */
    scopeOptionKey(item) {
      return this.scopeType === SCOPE_TYPE.ASSET ? item.asset_id : item.task_id
    },

    /** 获取选项的显示名称 */
    scopeOptionLabel(item) {
      return this.scopeType === SCOPE_TYPE.ASSET ? item.asset_name : item.task_name
    },

    // ════════════════════════════════════════════════════════════
    //  会话管理
    // ════════════════════════════════════════════════════════════

    /** 新建问答（清空当前会话） */
    newChat() {
      this.historyOpen = false
      this.sessionId = ''
      this.messages = []
      this.lastAssistantAnswer = ''
      this.question = ''
      this.saveAnswer = false
      this.updateRoute()
    },

    /** 打开历史会话 */
    openSession(session, updateUrl = true) {
      if (this.sending) return
      const sessionType = session.scopeType || (session.spaceId == null ? SCOPE_TYPE.ALL : SCOPE_TYPE.SPACE)
      const sameAnalysis = this.scopeType === SCOPE_TYPE.ANALYSIS
        && sessionType === SCOPE_TYPE.ANALYSIS
        && String(this.scopeId || '') === String(session.scopeId || '')

      // 恢复会话元数据
      this.sessionId = session.sessionId
      this.scopeType = sessionType
      this.scopeId = session.scopeId == null
        ? (sessionType === SCOPE_TYPE.SPACE && session.spaceId != null ? String(session.spaceId) : '')
        : String(session.scopeId)
      this.spaceId = session.spaceId == null ? '' : String(session.spaceId)
      if (!sameAnalysis) this.proposalId = ''

      // 仅在分析目标变化时重新加载结构
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
      if (updateUrl) this.updateRoute()

      // 加载历史消息
      this.loadingMessages = true
      getWikiMessages(session.sessionId).then(({ data }) => {
        this.messages = (data || []).reverse().map(item => Object.assign({}, item, { sources: item.sources || [] }))
        // 找到最后一条 assistant 消息，更新 lastAssistantAnswer
        for (let i = this.messages.length - 1; i >= 0; i--) {
          if (this.messages[i].role === 'assistant' && !this.messages[i].pending && !this.messages[i].failed) {
            this.lastAssistantAnswer = this.messages[i].content || ''
            break
          }
        }
      }).catch(() => this.$modal.msgError('问答记录加载失败'))
        .finally(() => { this.loadingMessages = false })
    },

    /** 同步当前状态到 URL query（支持刷新恢复） */
    updateRoute() {
      const query = Object.assign({}, this.$route.query)
      if (this.sessionId) query.sessionId = this.sessionId
      else delete query.sessionId
      query.scopeType = this.scopeType
      if (this.scopeId) query.scopeId = this.scopeId
      else delete query.scopeId
      if (this.scopeType === SCOPE_TYPE.SPACE && this.scopeId) query.spaceId = this.scopeId
      else delete query.spaceId
      this.$router.replace({ query }).catch(() => {})
    },

    // ════════════════════════════════════════════════════════════
    //  消息发送
    // ════════════════════════════════════════════════════════════

    /** 键盘事件：Enter 发送，Shift+Enter 换行 */
    onKeydown(event) {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault()
        this.send()
      }
    },

    /** 构建乐观占位消息（发送前立即推入列表，提升响应感） */
    _buildPendingMessage(question, contextLabel) {
      return {
        role: 'assistant',
        content: '正在查阅所选知识范围并生成回答…',
        pending: true,
        failed: false,
        tempId: 'pending-' + Date.now(),
        failedQuestion: question,
        failedContextLabel: contextLabel,
        failedContext: this.cloneData(this.questionContext),
      }
    },

    /** 处理接口返回，更新消息列表和分析结构 */
    _applyAnswerResponse(data, { beforeConfig, beforeChanges, pending, contextLabel }) {
      this.sessionId = data.sessionId
      this.updateRoute()
      const pendingIndex = this.messages.indexOf(pending)
      if (pendingIndex >= 0) this.messages.splice(pendingIndex, 1)

      this.lastAssistantAnswer = data.answer || ''
      const msg = {
        role: 'assistant',
        content: data.answer,
        sources: data.citedSources || [],
        savedToWiki: !!data.savedToWiki,
        pageTitle: data.pageTitle || '',
        analysisUpdatedChanges: data.analysisUpdatedChanges || [],
      }

      // 分析模式：更新本地结构，并建立 undo 快照
      if (this.scopeType === SCOPE_TYPE.ANALYSIS) {
        this.analysisConfig = data.analysisConfig || this.analysisConfig
        this.analysisChanges = data.analysisChanges || this.analysisChanges
        this.analysisAnswerDraft = data.answer || this.analysisAnswerDraft
        if (data.analysisUpdatedChanges && data.analysisUpdatedChanges.length) {
          this.analysisSaved = false
          msg.content = `分析结构已更新（${data.analysisUpdatedChanges.length} 项）`
          msg.undoState = {
            beforeConfig,
            beforeChanges,
            afterConfig: this.cloneData(this.analysisConfig),
            afterChanges: this.cloneData(this.analysisChanges),
          }
        }
      }

      if (contextLabel) msg.questionContextLabel = contextLabel
      this.messages.push(msg)
      this.saveAnswer = false
      this.loadSessions()
      this.scrollMessages()
    },

    /** 发送问题（入口） */
    send() {
      const question = this.question.trim()
      if (!question || this.sending || !this.scopeReady) return

      const beforeConfig = this.cloneData(this.analysisConfig)
      const beforeChanges = this.cloneData(this.analysisChanges)
      const contextLabel = this.questionContextLabel
      // 若有字段引用，将其作为问题前缀发给接口
      const apiQuestion = contextLabel ? `当前引用字段：${contextLabel}\n本轮问题：${question}` : question

      if (!this.sessionId) {
        this.sessionId = 'chat-' + Date.now() + '-' + Math.random().toString(36).slice(2, 9)
        this.updateRoute()
      }

      this.sending = true
      this.messages.push({ role: 'user', content: question, questionContextLabel: contextLabel })
      const pending = this._buildPendingMessage(question, contextLabel)
      this.messages.push(pending)
      this.question = ''
      this.scrollMessages()

      askWiki({
        question: apiQuestion,
        sessionId: this.sessionId || undefined,
        scopeType: this.scopeType,
        scopeId: this.scopeId ? Number(this.scopeId) : null,
        proposalId: this.scopeType === SCOPE_TYPE.ANALYSIS && this.proposalId ? Number(this.proposalId) : undefined,
        analysisChanges: this.scopeType === SCOPE_TYPE.ANALYSIS ? this.analysisChanges : undefined,
        spaceId: this.scopeType === SCOPE_TYPE.SPACE && this.scopeId ? Number(this.scopeId) : null,
        saveAsPage: this.saveAnswer,
      }).then(({ data }) => {
        this._applyAnswerResponse(data, { beforeConfig, beforeChanges, pending, contextLabel })
      }).catch(error => {
        pending.pending = false
        // 603 表示未配置模型，全局拦截器已弹窗并引导跳转，此处不再追加失败气泡
        if (error && error.bizCode === 603) {
          // 同时移除本轮用户消息，避免留下半截对话
          this.messages = this.messages.filter(item => item !== pending && !(item.role === 'user' && item.content === question))
          this.question = question
          this.scrollMessages()
          return
        }
        pending.failed = true
        pending.content = error && error.message ? error.message : '回答暂时失败，请检查服务状态后重试。'
        this.loadSessions()
        this.scrollMessages()
      }).finally(() => { this.sending = false })
    },

    /** 重试失败的问题 */
    retryAnswer(item, index) {
      if (this.sending) return
      if (index > 0 && this.messages[index - 1].role === 'user') this.messages.splice(index - 1, 2)
      else this.messages.splice(index, 1)
      this.question = item.failedQuestion
      this.questionContext = this.cloneData(item.failedContext)
      this.$nextTick(() => this.send())
    },

    /** 滚动到消息列表底部 */
    scrollMessages() {
      this.$nextTick(() => {
        const box = this.$refs.messages
        if (box) box.scrollTop = box.scrollHeight
      })
    },

    // ════════════════════════════════════════════════════════════
    //  示例问题 & 跳转
    // ════════════════════════════════════════════════════════════

    /** 点击示例问题，直接发送 */
    askExample(value) {
      this.question = value
      this.send()
    },

    /** 跳转到本体分析页（携带当前范围和问题上下文） */
    goAnalysis() {
      let scope = this.question.trim()
      if (!scope) {
        for (let i = this.messages.length - 1; i >= 0; i--) {
          if (this.messages[i].role === 'user') { scope = this.messages[i].content; break }
        }
      }
      this.$router.push({
        path: '/engineering/analysis',
        query: { new: '1', wiki_space_id: this.spaceId || undefined, scope: scope || undefined },
      })
    },

    /** 查看引用资料详情 */
    viewSource(source) {
      getResource('assets', source.assetId).then(({ data }) => {
        this.sourcePreview = data || {}
        this.sourcePreviewOpen = true
      }).catch(() => this.$modal.msgError('引用资料加载失败'))
    },

    // ════════════════════════════════════════════════════════════
    //  分析结构：选中 & 定位
    // ════════════════════════════════════════════════════════════

    /** 字段行点击：选中并高亮 */
    selectAnalysisField(row) {
      this.selectedAnalysisField = row ? Object.assign({}, row) : null
      this.locatedAnalysisChange = null
    },

    /** 基于选中字段提问 */
    askAboutSelectedField() {
      if (!this.selectedAnalysisField) return
      this.questionContext = Object.assign({}, this.selectedAnalysisField)
      this.question = '请说明这个字段的用途和当前数据类型。'
      this.mobilePanel = 'assistant'
      this.$nextTick(() => { if (this.$refs.questionInput) this.$refs.questionInput.focus() })
    },

    /** 清除引用字段 */
    clearQuestionContext() {
      this.questionContext = null
    },

    /** 分析对象显示名 */
    analysisObjectLabel(row) {
      return row && (row.name || row.table || row.technicalName) || '未命名对象'
    },

    /** 分析字段显示名 */
    analysisPropertyLabel(row) {
      return row && (row.name || row.column || row.technicalName) || '未命名字段'
    },

    /** 根据 objectId 查找对象名 */
    analysisObjectName(id) {
      const row = (this.analysisConfig.objects || []).find(item => String(item.id) === String(id))
      return row ? this.analysisObjectLabel(row) : (id || '未关联对象')
    },

    /** 判断某行是否已被修正 */
    analysisRowChanged(category, row) {
      return this.analysisChanges.some(change => change.category === category && String(change.id) === String(row && row.id))
    },

    /** 计算行的 class（选中态 / 定位态） */
    analysisRowClass(category, scope) {
      const row = scope && scope.row ? scope.row : scope
      if (!row) return ''
      const selected = category === 'properties' && this.selectedAnalysisField && String(row.id) === String(this.selectedAnalysisField.id)
      const located = this.locatedAnalysisChange && this.locatedAnalysisChange.category === category && String(row.id) === String(this.locatedAnalysisChange.id)
      return [selected && 'analysis-row-selected', located && 'analysis-row-located'].filter(Boolean).join(' ')
    },

    /** 定位到某条变更：切换 Tab + 滚动到行 */
    locateAnalysisChange(change) {
      if (!change) return
      // 先切换页签，下一帧再滚动，确保表格已渲染
      const tab = change.category === 'permissionCandidates' ? 'permissions' : change.category
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

    /** 根据分类获取行数据 */
    rowsForCategory(category) {
      return Array.isArray(this.analysisConfig[category]) ? this.analysisConfig[category] : []
    },

    // ════════════════════════════════════════════════════════════
    //  分析结构：撤销修正
    // ════════════════════════════════════════════════════════════

    /** 深拷贝工具 */
    cloneData(value) {
      return value == null ? value : JSON.parse(JSON.stringify(value))
    },

    /** 在 config 中查找指定分类+id 的候选项 */
    analysisCandidate(category, id, config = this.analysisConfig) {
      const rows = Array.isArray(config && config[category]) ? config[category] : []
      return rows.find(row => String(row.id) === String(id))
    },

    /** 深比较两个值是否相等 */
    sameValue(left, right) {
      return JSON.stringify(left) === JSON.stringify(right)
    },

    /** 撤销某轮问答引入的分析结构修正 */
    undoAnalysisChanges(item) {
      if (!item || !item.undoState || item.undone) return
      const snapshot = item.undoState
      // 逐条回滚修正项
      ;(item.analysisUpdatedChanges || []).forEach(change => {
        const current = this.analysisCandidate(change.category, change.id)
        const before = this.analysisCandidate(change.category, change.id, snapshot.beforeConfig)
        const after = this.analysisCandidate(change.category, change.id, snapshot.afterConfig)
        // 仅当当前值与修正后一致时才回滚，避免误覆盖后续编辑
        if (current && after && this.sameValue(current[change.field], after[change.field])) {
          if (before && Object.prototype.hasOwnProperty.call(before, change.field)) {
            this.$set(current, change.field, before[change.field])
          } else {
            this.$delete(current, change.field)
          }
        }

        // 同步修正标记列表
        const markerIndex = this.analysisChanges.findIndex(row =>
          row.category === change.category && String(row.id) === String(change.id) && row.field === change.field
        )
        const afterMarker = (snapshot.afterChanges || []).find(row =>
          row.category === change.category && String(row.id) === String(change.id) && row.field === change.field
        )
        const beforeMarker = (snapshot.beforeChanges || []).find(row =>
          row.category === change.category && String(row.id) === String(change.id) && row.field === change.field
        )
        if (markerIndex >= 0 && afterMarker && this.sameValue(this.analysisChanges[markerIndex].value, afterMarker.value)) {
          if (beforeMarker) this.$set(this.analysisChanges, markerIndex, this.cloneData(beforeMarker))
          else this.analysisChanges.splice(markerIndex, 1)
        }
      })

      this.$set(this.analysisConfig, 'analysisCorrections', this.analysisChanges)
      this.analysisSaved = false
      item.undone = true
      if (this.selectedAnalysisField) {
        this.selectedAnalysisField = this.analysisCandidate('properties', this.selectedAnalysisField.id) || this.selectedAnalysisField
      }
      this.$modal.msgSuccess('已撤销本次结构修正')
    },

    // ════════════════════════════════════════════════════════════
    //  分析结构：保存 & 表格高度
    // ════════════════════════════════════════════════════════════

    /** 保存分析结构，并同步沉淀到 LLM Wiki */
    saveAnalysisAnswer() {
      if (!this.scopeId || !this.proposalId) {
        this.$modal.msgWarning('当前分析没有关联配置草案，无法保存')
        return
      }
      this.$modal.confirm('保存当前分析结构，并同步沉淀到 LLM Wiki？').then(() => {
        this.analysisAnswerSaving = true
        const answer = String(this.analysisAnswerDraft || this.analysisConfig.llmAnswer || '分析结构已由分析者确认。').trim()
        return saveAnalysisAnswer(this.scopeId, {
          answer,
          confirm: true,
          proposalId: Number(this.proposalId),
          analysisChanges: this.analysisChanges,
        }).then(() => {
          this.analysisConfig.answerStatus = ANSWER_STATUS.CONFIRMED
          this.analysisConfig.llmAnswer = answer
          this.analysisSaved = true
          this.$modal.msgSuccess('分析结构已保存，并沉淀到 LLM Wiki')
        }).finally(() => { this.analysisAnswerSaving = false })
      }).catch(() => {})
    },

    /** 动态计算表格高度（仅占结构栏剩余空间，避免页面整体滚动） */
    updateTableHeight() {
      this.$nextTick(() => {
        const column = this.$refs.structureColumn
        if (column) this.analysisTableHeight = Math.max(180, column.clientHeight - 210)
      })
    },

    // ════════════════════════════════════════════════════════════
    //  分割线拖拽（鼠标拖动调整结构栏宽度）
    // ════════════════════════════════════════════════════════════

    /** 鼠标按下分割线，启动拖拽 */
    onResizeStart(e) {
      e.preventDefault()
      this._resizeStartX = e.clientX
      this._resizeStartWidth = this.structureWidth
      this._onResizeMove = this._handleResizeMove.bind(this)
      this._onResizeEnd = this._handleResizeEnd.bind(this)
      document.addEventListener('mousemove', this._onResizeMove)
      document.addEventListener('mouseup', this._onResizeEnd)
      document.body.style.cursor = 'col-resize'
      document.body.style.userSelect = 'none'
    },

    /** 拖拽移动：实时更新结构栏宽度 */
    _handleResizeMove(e) {
      const workspace = this.$refs.workspaceShell
      if (!workspace) return
      const totalWidth = workspace.clientWidth
      const minWidth = Math.max(280, totalWidth * 0.25)   // 最小 25%
      const maxWidth = Math.min(totalWidth - 320, totalWidth * 0.70)  // 最大 70%，且对话栏至少 320px
      const delta = e.clientX - this._resizeStartX
      this.structureWidth = Math.min(maxWidth, Math.max(minWidth, this._resizeStartWidth + delta))
      this.updateTableHeight()
    },

    /** 鼠标松开，结束拖拽 */
    _handleResizeEnd() {
      document.removeEventListener('mousemove', this._onResizeMove)
      document.removeEventListener('mouseup', this._onResizeEnd)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    },

    /** 切换结构 Tab 后重新计算表格高度 */
    onStructureTabChange() {
      this.updateTableHeight()
    },

    // ════════════════════════════════════════════════════════════
    //  显示格式化
    // ════════════════════════════════════════════════════════════

    /** 格式化时间：截取到分钟 */
    formatTime(value) {
      return value ? String(value).replace('T', ' ').slice(0, 16) : ''
    },

    /** 关闭检索能力边界提示条，localStorage 记忆 */
    closeRetrievalTip() {
      this.showRetrievalTip = false
      localStorage.setItem('ontologywiki:retrieval-tip:v1', 'closed')
    },

    /** 历史会话的范围显示文字 */
    sessionScope(item) {
      const type = item.scopeType || (item.spaceId == null ? SCOPE_TYPE.ALL : SCOPE_TYPE.SPACE)
      if (type === SCOPE_TYPE.ALL) return '全部资料'
      if (type === SCOPE_TYPE.SPACE) {
        const space = this.spaces.find(row => String(row.space_id) === String(item.scopeId || item.spaceId))
        return space ? space.space_name : '行业知识空间'
      }
      return type === SCOPE_TYPE.ASSET ? '单个 Wiki 资产' : '分析任务'
    },

    // ── 暴露枚举转中文工具函数给模板 ──
    riskText,
    statusText,
    operationText,
    triggerText,
    cardinalityText,
    subjectTypeText,
    assetTypeText,
    booleanText,
  },
}
</script>

<style scoped>
/* ════════════════════════════════════════════════════════════
   设计变量（Awesome Design 体系）
   统一管理颜色、圆角、间距，避免魔法数字散落
════════════════════════════════════════════════════════════ */
.wiki-assistant {
  /* 主色 */
  --c-primary: #2563eb;
  --c-primary-hover: #1d4ed8;
  --c-primary-light: #eff6ff;
  /* 中性 */
  --c-text: #1e293b;
  --c-text-secondary: #64748b;
  --c-text-hint: #94a3b8;
  --c-border: #e2e8f0;
  --c-border-light: #f1f5f9;
  --c-surface: #f8fafc;
  --c-bg: #ffffff;
  /* 状态 */
  --c-success: #16a34a;
  --c-success-light: #f0fdf4;
  --c-warning: #d97706;
  --c-warning-light: #fffbeb;
  --c-error: #dc2626;
  /* 圆角 */
  --r-panel: 12px;
  --r-card: 8px;
  --r-chip: 6px;
  /* 布局 */
  --header-height: 64px;
}

/* ═══ 页面布局 ═══ */
.wiki-assistant {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 106px);
  min-height: 0;
  padding: 12px 20px 16px;
  overflow: hidden;
  color: var(--c-text);
}

/* ═══ 顶部单行 Header（品牌 + 操作） ═══ */
.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 0 0 auto;
  height: 52px;
  padding: 0 16px;
  background: var(--c-bg);
  border: 1px solid var(--c-border);
  border-radius: var(--r-panel);
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
  margin-bottom: 8px;
}

/* 左区：品牌 */
.header-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.header-icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--r-chip);
  background: linear-gradient(135deg, var(--c-primary), #4f46e5);
  color: #fff;
  font-size: 16px;
  flex-shrink: 0;
}
.page-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  white-space: nowrap;
}
.page-subtitle {
  margin: 1px 0 0;
  font-size: 11px;
  color: var(--c-text-hint);
  white-space: nowrap;
}

/* 右区：操作按钮 */
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
}
.save-badge { margin-right: 0; }
.icon-btn { padding: 7px; }
.help-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 50%;
  background: var(--c-surface);
  color: var(--c-text-hint);
  cursor: pointer;
  transition: all 0.15s;
  flex-shrink: 0;
}
.help-btn:hover { background: var(--c-primary-light); color: var(--c-primary); }
.btn-go-analysis { white-space: nowrap; flex-shrink: 0; font-size: 12px; }
.scope-error-text { color: var(--c-error); font-size: 12px; white-space: nowrap; }

/* ═══ 自定义下拉面板（el-popover 内部） ═══ */
.sdp-wrap { padding: 4px; }
.sdp-search { padding: 4px 4px 8px; }
.sdp-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 320px;
  overflow-y: auto;
}
.sdp-item {
  padding: 10px 12px;
  border-radius: var(--r-chip);
  cursor: pointer;
  transition: background 0.12s;
}
.sdp-item:hover { background: var(--c-surface); }
.sdp-item--active {
  background: var(--c-primary-light);
  color: var(--c-primary);
}
.sdp-item-title {
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sdp-item-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--c-text-hint);
}
.sdp-loading, .sdp-empty {
  text-align: center;
  color: var(--c-text-hint);
  cursor: default;
  font-size: 13px;
}
.sdp-loading:hover, .sdp-empty:hover { background: transparent; }
.sdp-error {
  margin: 8px 4px 4px;
  padding: 6px 8px;
  border-radius: var(--r-chip);
  background: #fef2f2;
  color: var(--c-error);
  font-size: 12px;
}

/* ═══ 移动端 Tab ═══ */
.mobile-tabs {
  display: none;
  gap: 4px;
  margin-bottom: 10px;
  padding: 3px;
  border-radius: var(--r-chip);
  background: var(--c-surface);
}
.mobile-tabs button {
  flex: 1;
  padding: 8px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--c-text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}
.mobile-tabs button.active {
  background: var(--c-bg);
  color: var(--c-primary);
  box-shadow: 0 1px 4px rgba(35, 61, 91, 0.12);
}

/* ═══ 主工作区 ═══ */
.workspace {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--c-border);
  border-radius: var(--r-panel);
  background: var(--c-bg);
}
.structure-panel, .conversation-panel {
  min-width: 0;
  min-height: 0;
}
.structure-panel {
  display: flex;
  flex-direction: column;
  /* 宽度由 JS structureWidth 控制，这里只设最小宽度兜底 */
  min-width: 280px;
  padding: 16px 18px 0;
  overflow: hidden;
}
.conversation-panel {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 320px;
  padding: 16px 16px 12px;
  border-left: 1px solid var(--c-border);
  overflow: hidden;
}

/* ── 可拖拽分割线 ── */
.resize-handle {
  flex: 0 0 8px;
  width: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: col-resize;
  background: transparent;
  transition: background 0.15s;
  position: relative;
  z-index: 10;
}
.resize-handle:hover { background: var(--c-primary-light); }
.resize-handle:active { background: var(--c-primary-light); }
.resize-dots {
  display: block;
  width: 2px;
  height: 32px;
  border-radius: 2px;
  background: var(--c-border);
  transition: background 0.15s;
}
.resize-handle:hover .resize-dots { background: var(--c-primary); }

/* ── 结构面板标题 ── */
.structure-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex: 0 0 auto;
  margin-bottom: 10px;
}
.structure-heading-text strong {
  font-size: 16px;
  color: var(--c-text);
}
.structure-heading-text span {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  color: var(--c-text-hint);
}
.structure-metrics {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  font-size: 11px;
  color: var(--c-text-hint);
}
.structure-metrics span { white-space: nowrap; }

/* ── 已选字段条 ── */
.selected-field-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex: 0 0 auto;
  min-height: 34px;
  margin-bottom: 6px;
  padding: 5px 10px;
  border-radius: var(--r-chip);
  background: var(--c-primary-light);
  color: var(--c-text-secondary);
  font-size: 12px;
}

/* ── 结构 Tab + 表格 ── */
.structure-tabs { flex: 1 1 auto; min-height: 0; }
.structure-tabs ::v-deep .el-tabs__content,
.structure-tabs ::v-deep .el-tab-pane { height: 100%; }
.structure-tabs ::v-deep .el-table { width: 100%; }
.structure-tabs ::v-deep .el-table__body-wrapper { overflow-y: auto; }
.corrected-tag {
  border: 0;
  background: var(--c-success-light);
  color: var(--c-success);
}
.structure-tabs ::v-deep .analysis-row-selected td {
  background: var(--c-primary-light) !important;
}
.structure-tabs ::v-deep .analysis-row-located td {
  background: var(--c-warning-light) !important;
  transition: background 0.2s;
}

/* ═══ 对话面板 ═══ */
.conversation-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex: 0 0 auto;
  padding-bottom: 10px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--c-border-light);
}
.conversation-heading strong {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  white-space: nowrap;
  flex-shrink: 0;
}

/* 右侧内联范围选择器 */
.inline-scope {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  overflow: hidden;
}
.scope-arrow {
  font-size: 10px;
  color: var(--c-border);
  flex-shrink: 0;
}

/* 内联 Pill 按钮（比 header 里的更紧凑） */
.scope-pill-inline {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--c-border);
  border-radius: 20px;
  background: var(--c-bg);
  color: var(--c-text);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  user-select: none;
  white-space: nowrap;
  max-width: 180px;
}
.scope-pill-inline:hover {
  border-color: var(--c-primary);
  background: var(--c-primary-light);
  color: var(--c-primary);
}
.scope-pill-inline > i:first-child {
  font-size: 12px;
  color: var(--c-primary);
  flex-shrink: 0;
}
.scope-pill-inline > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.scope-pill-inline > i:last-child {
  font-size: 10px;
  color: var(--c-text-hint);
  flex-shrink: 0;
}
.scope-pill-inline--placeholder {
  border-style: dashed;
  color: var(--c-text-hint);
}
.scope-pill-inline--placeholder > i:first-child {
  color: var(--c-text-hint);
}

/* ── 消息流 ── */
.messages {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 16px 4px 8px;
}
.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}
.msg.user { flex-direction: row-reverse; }

/* 头像 */
.msg-avatar {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  line-height: 1;
}
.avatar-user {
  background: var(--c-primary-light);
  color: var(--c-primary);
}
.avatar-bot {
  background: linear-gradient(135deg, var(--c-primary), #4f46e5);
  color: #fff;
}

/* 消息体 */
.msg-body { flex: 1; min-width: 0; }
.msg.user .msg-body {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}
.msg-role {
  margin-bottom: 4px;
  font-size: 11px;
  color: var(--c-text-hint);
}
.msg-context {
  margin-bottom: 4px;
  font-size: 11px;
  color: var(--c-text-secondary);
  padding: 2px 8px;
  background: var(--c-surface);
  border-radius: var(--r-chip);
  border-left: 2px solid var(--c-primary);
}
.msg-content {
  display: inline-block;
  max-width: 92%;
  padding: 10px 14px;
  border-radius: 12px 12px 12px 4px;
  background: var(--c-surface);
  color: var(--c-text);
  font-size: 14px;
  line-height: 1.7;
  text-align: left;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  border: 1px solid var(--c-border);
}
.msg.user .msg-content {
  background: var(--c-primary);
  color: #fff;
  border-color: var(--c-primary);
  border-radius: 12px 12px 4px 12px;
}
.msg-failed .msg-content {
  background: #fef2f2;
  border-color: #fecaca;
  color: var(--c-error);
}
.msg-spinner { margin-right: 6px; color: var(--c-primary); }
.msg-retry { margin-top: 4px; }
.msg-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.undo-done { font-size: 11px; color: var(--c-text-hint); }

/* 引用资料 */
.msg-citations {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}
.citations-label { font-size: 11px; color: var(--c-text-hint); }
.citation-btn { margin: 0; font-size: 12px; }
.msg-saved { margin-top: 5px; font-size: 11px; color: var(--c-success); }

/* ═══ 示例问题卡片 ═══ */
.example-cards {
  flex: 0 0 auto;
  padding: 12px 0 6px;
  border-top: 1px solid var(--c-border-light);
}
.example-title {
  display: block;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--c-text-hint);
  font-weight: 500;
}
.example-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.example-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-card);
  background: var(--c-bg);
  cursor: pointer;
  text-align: left;
  transition: all 0.15s;
}
.example-card:hover {
  border-color: var(--c-primary);
  background: var(--c-primary-light);
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.1);
}
.example-card-icon { font-size: 16px; color: var(--c-primary); }
.example-card-text {
  font-size: 12px;
  color: var(--c-text-secondary);
  line-height: 1.5;
}

/* ═══ 输入区 ═══ */
.composer {
  flex: 0 0 auto;
  padding-top: 10px;
  border-top: 1px solid var(--c-border-light);
}
.composer-options { margin-bottom: 6px; }
.save-answer-check { color: var(--c-text-secondary); font-size: 12px; }
.question-context {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.qc-label { font-size: 12px; color: var(--c-text-secondary); }
.input-wrap { position: relative; }
.input-wrap ::v-deep .el-textarea__inner {
  padding: 10px 88px 10px 12px;
  border-color: var(--c-border);
  border-radius: var(--r-card);
  resize: none;
  font-size: 14px;
  line-height: 1.6;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.input-wrap ::v-deep .el-textarea__inner:focus {
  border-color: var(--c-primary);
  box-shadow: 0 0 0 3px var(--c-primary-light);
}
.send-btn {
  position: absolute;
  right: 8px;
  bottom: 8px;
  z-index: 1;
  border-radius: var(--r-chip);
}
.composer-footer {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 11px;
  color: var(--c-text-hint);
}

/* ═══ 历史会话抽屉 ═══ */
.history-list { padding: 0 16px 20px; }
.session-item {
  display: block;
  width: 100%;
  padding: 10px 12px;
  margin-bottom: 5px;
  border: 0;
  border-radius: var(--r-card);
  background: transparent;
  color: var(--c-text);
  text-align: left;
  cursor: pointer;
  transition: all 0.15s;
}
.session-item:hover {
  background: var(--c-surface);
}
.session-item.active {
  background: var(--c-primary-light);
  color: var(--c-primary);
}
.session-title {
  display: block;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-meta {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--c-text-hint);
}

/* ═══ 引用资料弹窗 ═══ */
.source-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 16px;
  color: var(--c-text);
}
.source-description { color: var(--c-text-secondary); }
.source-content {
  max-height: 480px;
  overflow: auto;
  margin-top: 12px;
  padding: 16px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-card);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* ═══ 响应式：中等桌面 ═══ */
@media (min-width: 1024px) and (max-width: 1279px) {
  .page-title { font-size: 15px; }
  .crumb-pill { max-width: 160px; }
}

/* ═══ 响应式：平板 / 小桌面 ═══ */
@media (max-width: 1023px) {
  .wiki-assistant {
    height: auto;
    min-height: calc(100vh - 106px);
    overflow: visible;
    padding: 10px 14px 20px;
  }
  .page-header { flex-wrap: wrap; height: auto; padding: 8px 12px; min-height: 44px; }
  .context-crumbs { flex: 1 1 100%; order: 2; justify-content: flex-start; flex-wrap: wrap; }
  .header-brand { flex: 1 1 auto; order: 1; }
  .header-actions { order: 1; flex-shrink: 0; }
  .crumb-pill { max-width: 160px; }

  .mobile-tabs { display: flex; }
  .resize-handle { display: none; }
  .workspace {
    display: block;
    min-height: 620px;
    overflow: visible;
    border: 0;
    background: transparent;
  }
  .structure-panel, .conversation-panel {
    width: 100% !important;
    height: calc(100vh - 220px);
    min-height: 520px;
    padding: 12px;
    border: 1px solid var(--c-border);
    border-radius: var(--r-panel);
    background: var(--c-bg);
  }
  .conversation-panel { border-left: 1px solid var(--c-border); margin-top: 10px; }
  .mobile-hidden { display: none; }
  .structure-metrics { justify-content: flex-start; }
}

/* ═══ 响应式：手机 ═══ */
@media (max-width: 600px) {
  .page-title { font-size: 14px; }
  .page-subtitle { display: none; }
  .header-actions .el-button:not(.icon-btn) { font-size: 0; padding: 7px; }
  .header-actions .el-button:not(.icon-btn) i { margin: 0; }
  .crumb-pill { max-width: 120px; }
  .crumb-label { display: none; }
  .example-grid { grid-template-columns: 1fr; }
}
</style>

<!-- el-popover / el-dropdown 挂载到 body，需用非 scoped 样式覆盖 -->
<style>
.scope-dropdown-popper {
  padding: 8px !important;
  border-radius: 10px !important;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.14) !important;
  border-color: #e2e8f0 !important;
}

/* 范围类型 dropdown 菜单 */
.scope-type-menu {
  padding: 6px !important;
  border-radius: 10px !important;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.14) !important;
  border-color: #e2e8f0 !important;
}
.scope-type-menu .el-dropdown-menu__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px !important;
  border-radius: 6px !important;
  font-size: 13px;
  color: #1e293b;
  transition: background 0.12s;
}
.scope-type-menu .el-dropdown-menu__item i {
  font-size: 14px;
  color: #64748b;
  width: 16px;
  flex-shrink: 0;
}
.scope-type-menu .el-dropdown-menu__item:hover {
  background: #f8fafc !important;
  color: #1e293b !important;
}
.scope-type-menu .el-dropdown-menu__item.is-selected {
  background: #eff6ff !important;
  color: #2563eb !important;
  font-weight: 600;
}
.scope-type-menu .el-dropdown-menu__item.is-selected i {
  color: #2563eb !important;
}
/* 去掉 el-dropdown-menu 默认的三角箭头 */
.scope-type-menu[x-placement^="bottom"] .popper__arrow,
.scope-dropdown-popper[x-placement^="bottom"] .popper__arrow {
  display: none;
}
/* 检索能力边界提示条 */
.retrieval-tip-bar { display:flex; align-items:flex-start; gap:10px; padding:10px 14px; margin:0 0 10px; border-radius:8px; background:#f0f9ff; border:1px solid #bae6fd; color:#0369a1; font-size:13px; line-height:1.6; }
.retrieval-tip-bar i.el-icon-info { flex-shrink:0; margin-top:2px; font-size:15px; }
.retrieval-tip-bar span { flex:1; }
.tip-close-btn { flex-shrink:0; background:none; border:none; cursor:pointer; color:#64748b; padding:0 2px; font-size:14px; line-height:1; }
.tip-close-btn:hover { color:#0369a1; }
</style>




