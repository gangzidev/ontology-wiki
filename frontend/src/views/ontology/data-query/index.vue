<template>
  <div class="dq-shell" :aria-busy="String(initializing)">
    <div v-if="initializing" class="dq-initial-loading" role="status">
      <i class="el-icon-loading" />
      <strong>正在初始化数据查询</strong>
      <span>加载本体配置、数据目录和查询历史…</span>
      <div class="dq-loading-preview"><span /><span /><span /></div>
    </div>

    <!-- ══════════════════════════════════════════════════════════
         顶栏：配置选择 + 查询状态
    ══════════════════════════════════════════════════════════ -->
    <header v-show="!initializing" class="dq-header">
      <div class="dq-header-brand">
        <span class="dq-logo-icon"><i class="el-icon-connection" /></span>
        <div>
          <h1 class="dq-title">数据查询</h1>
          <p class="dq-subtitle">用业务语言，直接看数据</p>
        </div>
      </div>

      <div class="dq-header-controls">
        <el-popover v-model="proposalDropdownVisible" placement="bottom-end" trigger="click" width="330" popper-class="dq-proposal-popper">
          <div class="dq-proposal-menu">
            <el-input v-model="proposalSearch" size="small" prefix-icon="el-icon-search" placeholder="搜索草案" clearable />
            <div class="dq-proposal-list">
              <button
                v-for="item in filteredProposals"
                :key="item.proposal_id"
                type="button"
                :disabled="sending || workspaceLoading"
                :class="['dq-proposal-item', { active: String(item.proposal_id) === String(selectedProposalId) }]"
                @click="selectProposal(item)"
              >
                <i class="el-icon-collection-tag" />
                <span><strong>{{ item.proposal_name || '未命名草案' }}</strong><small>#{{ item.proposal_id }} · {{ statusLabel(item.status) }}</small></span>
                <i v-if="String(item.proposal_id) === String(selectedProposalId)" class="el-icon-check" />
              </button>
              <div v-if="proposalsLoading" class="dq-proposal-empty"><i class="el-icon-loading" /> 加载中…</div>
              <div v-else-if="!filteredProposals.length" class="dq-proposal-empty">没有匹配的草案</div>
            </div>
          </div>
          <button slot="reference" type="button" class="dq-proposal-select" :disabled="sending || workspaceLoading">
            <i class="el-icon-collection-tag" />
            <span>{{ selectedProposal ? (selectedProposal.proposal_name || '未命名草案') : '选择本体配置' }}</span>
            <i v-if="proposalsLoading" class="el-icon-loading" />
            <i v-else class="el-icon-arrow-down" />
          </button>
        </el-popover>

        <div v-if="readiness" class="dq-status-wrap">
          <el-popover placement="bottom-end" width="300" trigger="hover" popper-class="dq-readiness-popover">
            <div class="dq-readiness-popover-content">
              <strong>{{ readiness.queryEnabled ? '查询已开放' : '配置待完善' }}</strong>
              <ul v-if="readinessReasons.length">
                <li v-for="reason in readinessReasons" :key="reason">{{ readinessReasonText(reason) }}</li>
              </ul>
              <p v-else>直接输入业务问题，系统会自动查找相关对象和统计口径。</p>
            </div>
            <span slot="reference" class="dq-status-badge" :class="readiness.queryEnabled ? 'badge-ready' : readiness.eligibleForEvaluation ? 'badge-pending' : 'badge-off'">
              <i :class="readiness.queryEnabled ? 'el-icon-success' : 'el-icon-warning-outline'" />
              {{ readiness.queryEnabled ? '可查询' : '未就绪' }}
              <i v-if="!readiness.queryEnabled" class="el-icon-question dq-status-help" />
            </span>
          </el-popover>
        </div>
      </div>
    </header>

    <div v-show="!initializing" class="dq-workspace" v-loading="workspaceLoading" element-loading-text="正在加载查询配置…">
      <button v-if="catalogVisible" type="button" class="dq-sidebar-backdrop" aria-label="关闭侧栏" @click="toggleCatalog" />
      <!-- 左侧数据目录：树节点点击后加入问题输入框。 -->
      <aside v-show="catalogVisible" id="query-catalog" class="dq-catalog-panel" :style="{ flexBasis: catalogWidth + 'px' }">
        <div class="dq-sidebar-tabs">
          <button type="button" :class="{ active: sidebarTab === 'history' }" @click="sidebarTab = 'history'"><i class="el-icon-time" /> 历史</button>
          <button type="button" :class="{ active: sidebarTab === 'catalog' }" @click="sidebarTab = 'catalog'"><i class="el-icon-price-tag" /> 目录</button>
          <button type="button" :class="{ active: sidebarTab === 'analysis' }" @click="sidebarTab = 'analysis'"><i class="el-icon-share" /> 分析</button>
          <button type="button" class="dq-sidebar-close" aria-label="关闭侧栏" @click="toggleCatalog"><i class="el-icon-close" /></button>
        </div>
        <div v-show="sidebarTab === 'history'" class="dq-history-panel">
          <button type="button" class="dq-new-query" :disabled="sending || historyOpening" @click="newConversation"><i class="el-icon-plus" /> 新建查询</button>
          <p class="dq-history-caption">当前配置的查询记录</p>
          <div class="dq-history-list" v-loading="historyLoading" element-loading-text="加载历史记录…">
            <button v-for="item in sessions" :key="item.sessionId" type="button" :class="['dq-history-item', { active: item.sessionId === sessionId }]" :disabled="sending || historyOpening" @click="openConversation(item)">
              <i class="el-icon-chat-line-round" />
              <span><strong>{{ item.title }}</strong><small>{{ historyDate(item.updatedAt) }}</small></span>
            </button>
            <div v-if="historyError" class="dq-history-empty"><i class="el-icon-warning-outline" /><span>历史记录加载失败</span><el-button type="text" size="small" @click="loadHistory()">重新加载</el-button></div>
            <div v-else-if="!sessions.length && !historyLoading" class="dq-history-empty"><i class="el-icon-chat-line-square" /><span>还没有查询记录</span><small>发起查询后自动保存，可随时继续。</small></div>
            <button v-if="historyHasMore" type="button" class="dq-history-more" :disabled="historyLoading" @click="loadHistory(true)">加载更多会话</button>
          </div>
        </div>
        <div v-show="sidebarTab === 'catalog'" class="dq-catalog-content">
        <div class="dq-catalog-panel-head">
          <div>
            <strong><i class="el-icon-price-tag" /> 数据目录</strong>
            <small>选择表查看数据，也可选择统计指标</small>
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
          :default-expanded-keys="['objects']"
          highlight-current
          @node-click="onCatalogNodeClick"
        >
          <span slot-scope="{ node, data }" class="dq-tree-node">
            <i :class="data.kind === 'group' ? 'el-icon-folder-opened' : data.kind === 'object' ? 'el-icon-s-grid' : data.kind === 'field' ? 'el-icon-document' : data.kind === 'metric' ? 'el-icon-data-analysis' : data.kind === 'dimension' ? 'el-icon-s-operation' : 'el-icon-date'" />
            <el-tooltip placement="right" effect="light" :disabled="data.kind === 'group' || !data.sourceText">
              <div slot="content" class="dq-tree-tooltip">{{ data.sourceText }}</div>
              <span class="dq-tree-label">{{ node.label }}</span>
            </el-tooltip>
          </span>
        </el-tree>
        <div v-else class="dq-catalog-empty"><i :class="catalogLoading ? 'el-icon-loading' : 'el-icon-folder-opened'" /><span>{{ catalogLoading ? '正在加载数据目录…' : '暂无数据目录' }}</span></div>
        </div>
        <div v-show="sidebarTab === 'analysis'" class="dq-analysis-content">
          <div class="dq-catalog-panel-head">
            <div><strong><i class="el-icon-share" /> 结构与关系</strong><small>基于当前已审核的本体配置</small></div>
          </div>
          <div class="dq-structure-summary">
            <div><strong>{{ structureSummary.tableCount || 0 }}</strong><small>数据表</small></div>
            <div><strong>{{ structureSummary.fieldCount || 0 }}</strong><small>字段</small></div>
            <div><strong>{{ structureSummary.relationCount || 0 }}</strong><small>本体关系</small></div>
          </div>
          <div v-if="analysisSourceObjects.length" class="dq-analysis-sources">
            <strong>本次查询涉及表</strong>
            <el-tag v-for="object in analysisSourceObjects" :key="object.id" size="mini" effect="plain">{{ object.name || object.table }}</el-tag>
          </div>
          <p class="dq-analysis-note">关系可能来自数据库外键，也可能是名称推断；请查看证据和审核状态。这里不是字段级数据血缘，也不执行实时数据质量扫描。</p>
          <el-collapse v-if="analysisObjects.length" class="dq-analysis-tables">
            <el-collapse-item v-for="object in analysisObjects" :key="object.id" :name="String(object.id)">
              <template slot="title"><i class="el-icon-s-grid" /> {{ object.name || object.table }} <small>{{ object.fields.length }} 个字段</small></template>
              <p class="dq-analysis-table-name">{{ object.schema ? object.schema + '.' : '' }}{{ object.table }}</p>
              <el-table :data="object.fields" size="mini" max-height="260" class="dq-analysis-field-table">
                <el-table-column prop="name" label="字段" min-width="110"><template slot-scope="scope">{{ scope.row.name || scope.row.column }}</template></el-table-column>
                <el-table-column prop="dataType" label="类型" min-width="90" />
                <el-table-column prop="description" label="说明" min-width="130" show-overflow-tooltip />
              </el-table>
              <el-button type="text" size="mini" @click="exploreObject(object)">查询这张表</el-button>
            </el-collapse-item>
          </el-collapse>
          <div v-else class="dq-catalog-empty"><i class="el-icon-warning-outline" /><span>当前配置没有可展示的表结构</span></div>
          <div v-if="analysisProfiles.length" class="dq-relation-list">
            <h4>已有抽样画像 <small>仅代表分析时抽取的样本，不是实时统计</small></h4>
            <article v-for="profile in analysisProfiles" :key="profile.key || profile.table" class="dq-relation-card">
              <strong>{{ profile.table }}</strong><p>样本 {{ profile.sampleCount }} 条</p>
              <p v-for="column in (profile.columns || []).slice(0, 8)" :key="column.column" class="dq-profile-column">
                <span>{{ column.column }}</span><span>非空 {{ column.nonNullCount }}/{{ profile.sampleCount }}</span><span>去重 {{ column.distinctCount }}</span><span>{{ column.cardinality }}</span>
              </p>
            </article>
          </div>
          <div class="dq-relation-list">
            <h4>{{ analysisSourceObjectIds.length ? '涉及表之间的配置关系' : '关系与可达路径' }}</h4>
            <article v-for="(relation, index) in analysisRelationsForSources" :key="relation.id || index" class="dq-relation-card">
              <div class="dq-relation-title"><strong>{{ relation.sourceName || relation.sourceTable || relation.sourceObjectId }}</strong><i class="el-icon-right" /><strong>{{ relation.targetName || relation.targetTable || relation.targetObjectId }}</strong></div>
              <p>{{ relation.sourceColumn || '—' }} → {{ relation.targetColumn || '—' }}<span v-if="relation.cardinality"> · {{ relation.cardinality }}</span></p>
              <p class="dq-relation-evidence">{{ relation.evidence || relation.inferenceMethod || '关系定义未提供证据' }}</p>
              <div class="dq-relation-actions">
                <el-button v-if="relationQuestion(relation)" type="text" size="mini" @click="exploreRelation(relation)">按已配置口径查询</el-button>
                <el-tag size="mini" :type="relation.reviewRequired || relation.status === 'NEEDS_REVIEW' ? 'warning' : 'info'">{{ relation.reviewRequired || relation.status === 'NEEDS_REVIEW' ? '待人工确认' : '配置关系' }}</el-tag>
              </div>
            </article>
            <div v-if="!analysisRelationsForSources.length" class="dq-analysis-empty">暂无匹配的已配置对象关系</div>
          </div>
        </div>
        <div class="dq-catalog-resizer" @mousedown.prevent="startCatalogResize" />
      </aside>

      <main ref="main" class="dq-main">
        <div class="dq-conversation-toolbar">
          <div class="dq-conversation-title"><h2>{{ activeSessionTitle }}</h2><span>{{ messages.length ? messages.length + ' 个问题' : '结果可追溯、可复核' }}</span></div>
          <div class="dq-conversation-actions">
            <button v-if="messages.length" type="button" class="dq-toolbar-btn" :disabled="sending || historyOpening" @click="newConversation"><i class="el-icon-plus" />新建</button>
            <button type="button" class="dq-toolbar-btn" :aria-expanded="String(catalogVisible)" aria-controls="query-catalog" @click="toggleCatalog">
              <i :class="catalogVisible ? 'el-icon-s-fold' : 'el-icon-s-unfold'" />{{ catalogVisible ? '收起侧栏' : '历史 / 目录' }}
            </button>
          </div>
        </div>
        <!-- ══════════════════════════════════════════════════════════
             对话区
        ══════════════════════════════════════════════════════════ -->
        <div ref="feed" class="dq-feed" v-loading="historyOpening" element-loading-text="正在恢复查询记录…">
          <div v-if="initializationError" class="dq-load-error" role="alert"><i class="el-icon-warning-outline" /><span>{{ initializationError }}</span><el-button type="text" @click="initialize">重试</el-button></div>
          <button v-if="messagesHaveMore" type="button" class="dq-history-more" :disabled="olderMessagesLoading || sending" @click="loadOlderMessages"><i v-if="olderMessagesLoading" class="el-icon-loading" /> 查看更早的消息</button>

      <!-- 空态引导 -->
      <div v-if="!messages.length && !initializationError && !historyOpening" class="dq-empty">
        <div class="dq-empty-icon"><i class="el-icon-chat-line-round" /></div>
        <h2>从一个业务问题开始</h2>
        <p>直接提问，系统会自动查找相关数据；含义不明确时再向你确认。</p>
        <div v-if="!selectedProposalId" class="dq-empty-hint">
          <i class="el-icon-arrow-up" /> 先选择一个本体配置
        </div>
        <div v-else-if="exampleQuestions.length" class="dq-example-grid">
          <button
            v-for="(ex, i) in exampleQuestions"
            :key="i"
            type="button"
            class="dq-example-btn"
            :disabled="initializing || workspaceLoading || historyOpening || olderMessagesLoading || sending"
            @click="sendQuestion(ex)"
          >
            <i class="el-icon-chat-dot-round" />
            <span>{{ ex }}</span>
          </button>
        </div>
      </div>

      <!-- 消息列表 -->
      <template v-for="(msg, idx) in messages">

        <!-- 用户气泡 -->
        <div :key="'q-' + idx" class="dq-bubble dq-bubble-user">
          <span>{{ msg.question }}</span>
        </div>

        <!-- 助手回答卡片 -->
        <div :key="'a-' + idx" class="dq-answer-card" :aria-busy="String(!!msg.loading)">
          <p v-if="msg.historyWarning" class="dq-history-warning"><i class="el-icon-warning-outline" /> {{ msg.historyWarning }}</p>

          <!-- 加载中 -->
          <div v-if="msg.loading" class="dq-answer-loading" role="status">
            <span class="dq-dot-anim" /><span class="dq-dot-anim dq-dot-2" /><span class="dq-dot-anim dq-dot-3" />
            <span>{{ queryStageText }}<small v-if="queryElapsed"> · 已等待 {{ queryElapsed }} 秒</small></span>
          </div>

          <!-- 追问/澄清 -->
          <div v-else-if="msg.type === 'clarification'" class="dq-clarification">
            <i class="el-icon-question" />
            <p>{{ msg.clarification }}<small v-if="!msg.skipFollowUp" class="dq-clarification-hint">在下方补充说明，将继续处理这个问题。<el-button type="text" size="mini" @click="startNewQuestion(msg)">另问一个问题</el-button></small><small v-else class="dq-clarification-hint dq-new-question-hint">已切换为新问题，请在下方输入框提问。</small></p>
          </div>

          <!-- 查询计划预览 -->
          <div v-else-if="msg.type === 'preview'" class="dq-preview-block">
            <div class="dq-preview-notice">
              <i class="el-icon-warning-outline" />
              <div>
                <strong>查询计划预览</strong>
                <p>{{ (msg.readinessReasons || []).map(readinessReasonText).join('；') }}</p>
              </div>
            </div>
            <div class="dq-plan-chips">
              <span v-for="m in (msg.plan && msg.plan.metrics || [])" :key="'pm-'+m.id" class="dq-plan-chip chip-metric">
                <i class="el-icon-data-analysis" />{{ semanticName('metrics', m.id) }}
              </span>
              <span v-for="d in (msg.plan && msg.plan.dimensions || [])" :key="'pd-'+d.id" class="dq-plan-chip chip-dim">
                <i class="el-icon-s-operation" />{{ semanticName('dimensions', d.id) }}
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
            <div class="dq-result-header">
              <div class="dq-result-heading">
                <span class="dq-result-icon"><i class="el-icon-data-analysis" /></span>
                <div><h3>查询结果</h3><p>{{ (msg.rows || []).length }} 条记录<span v-if="msg.changedFields"> · {{ followUpSummary(msg.changedFields) }}</span><span v-else-if="msg.rows && msg.rows.length"> · 可切换视图或导出数据</span></p></div>
              </div>
              <div class="dq-result-actions">
                <button v-if="msg.explanation && msg.explanation.sourceObjects && msg.explanation.sourceObjects.length" type="button" class="dq-toolbar-btn" @click="openStructureAnalysis(msg)"><i class="el-icon-share" /> 来源结构与关系</button>
                <button v-if="msg.rows && msg.rows.length" type="button" class="dq-toolbar-btn" @click="exportRows(msg)"><i class="el-icon-download" /> 导出 CSV</button>
              </div>
            </div>
            <div v-if="msg.plan" class="dq-plan-summary">
              <span v-if="msg.plan.detailObjectId"><b>数据表</b>{{ msg.explanation && msg.explanation.detailObject && (msg.explanation.detailObject.name || msg.explanation.detailObject.table) || msg.plan.detailObjectId }}</span>
              <span v-for="m in (msg.plan.metrics || [])" :key="'summary-m-' + m.id"><b>指标</b>{{ semanticName('metrics', m.id) }}</span>
              <span v-for="d in (msg.plan.dimensions || [])" :key="'summary-d-' + d.id"><b>维度</b>{{ semanticName('dimensions', d.id) }}</span>
              <span v-if="msg.plan.timeRange"><b>时间</b>{{ msg.plan.timeRange.from }} 至 {{ msg.plan.timeRange.to }}</span>
              <span v-if="msg.plan.comparison"><b>对比</b>{{ comparisonLabel(msg.plan.comparison.type) }}</span>
            </div>
            <div v-else-if="msg.semanticEvidence" class="dq-plan-summary">
              <span v-if="msg.semanticEvidence.metric"><b>指标</b>{{ msg.semanticEvidence.metric }}</span>
              <span v-if="msg.semanticEvidence.dimension"><b>维度</b>{{ msg.semanticEvidence.dimension }}</span>
            </div>

            <!-- 图表 / 表格切换 -->
            <div class="dq-view-toggle">
              <button
                type="button"
                :class="['dq-toggle-btn', { active: msg.viewMode === 'chart' }]"
                :disabled="!canChart(msg)"
                :aria-pressed="String(msg.viewMode === 'chart')"
                @click="setViewMode(msg, 'chart')"
              >
                <i class="el-icon-data-analysis" /> 图表
              </button>
              <button
                type="button"
                :class="['dq-toggle-btn', { active: msg.viewMode === 'table' }]"
                :aria-pressed="String(msg.viewMode === 'table')"
                @click="setViewMode(msg, 'table')"
              >
                <i class="el-icon-s-grid" /> 表格
              </button>
            </div>

            <!-- stat 数值卡片 -->
            <div v-if="msg.viewMode === 'chart' && msg.chartHint && msg.chartHint.type === 'stat'" class="dq-stat-card">
              <div class="dq-stat-label">{{ msg.chartHint.label || '' }}</div>
              <div class="dq-stat-value">{{ statValue(msg) }}</div>
              <span class="dq-stat-caption">当前查询范围内的统计结果</span>
            </div>

            <!-- ECharts 图表容器 -->
            <div
              v-else-if="msg.viewMode === 'chart' && canChart(msg)"
              :ref="'chart-' + idx"
              class="dq-chart-container"
            />

            <!-- 表格 -->
            <div v-else class="dq-table-section">
              <div v-if="msg.plan && msg.plan.detailObjectId" class="dq-table-pagination">
                <span>已返回 {{ Number(msg.offset || 0) + (msg.rows.length ? 1 : 0) }}–{{ Number(msg.offset || 0) + msg.rows.length }} 条，每批最多 {{ msg.limit || 100 }} 条{{ msg.mayHaveMore ? '，可继续查看' : '' }}</span>
                <el-button size="mini" :disabled="sending || !Number(msg.offset)" @click="loadDetailPage(msg, -1)">上一批</el-button>
                <el-button size="mini" :disabled="sending || !msg.mayHaveMore" @click="loadDetailPage(msg, 1)">下一批</el-button>
              </div>
              <p v-if="(msg.columns || []).length > 4" class="dq-table-hint"><i class="el-icon-sort" /> 左右滚动查看其余列</p>
              <el-table
                ref="resultTables"
                :data="visibleRows(msg)"
                stripe
                size="small"
                class="dq-result-table"
                empty-text="查询完成，没有匹配结果"
              >
                <el-table-column
                  v-for="col in (msg.columns || [])"
                  :key="col"
                  :prop="col"
                  :label="(msg.columnLabels && msg.columnLabels[col]) || col"
                  min-width="180"
                  show-overflow-tooltip
                />
                <el-table-column v-if="msg.plan && msg.plan.dimensions && msg.plan.dimensions.length && hasDrillTarget(msg)" label="分析" width="104">
                  <template slot-scope="scope">
                    <el-button type="text" size="mini" :disabled="sending" @click="drilldown(msg, scope.row)">继续下钻</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <div v-if="(msg.rows || []).length > 20" class="dq-table-pagination">
                <span>{{ msg.plan && msg.plan.detailObjectId ? '本批' : '共' }} {{ msg.rows.length }} 条记录</span>
                <el-select :value="msg.pageSize || 20" size="mini" aria-label="每页记录数" @change="setPageSize(msg, $event)">
                  <el-option v-for="size in [20, 50, 100]" :key="size" :label="size + ' 条 / 页'" :value="size" />
                </el-select>
                <el-pagination
                  background
                  small
                  layout="prev, pager, next"
                  :pager-count="5"
                  :current-page="msg.page || 1"
                  :page-size="msg.pageSize || 20"
                  :total="msg.rows.length"
                  @current-change="$set(msg, 'page', $event)"
                />
              </div>
            </div>

            <div v-if="msg.plan && msg.plan.dimensions && msg.plan.dimensions.length && availableDrillDimensions(msg).length" class="dq-drill-bar">
              <span><i class="el-icon-share" /> 选择下一级维度</span>
              <el-select v-model="msg.nextDimensionId" size="mini" placeholder="选择维度" class="dq-drill-select">
                <el-option v-for="dimension in availableDrillDimensions(msg)" :key="dimension.id" :label="dimension.name || dimension.id" :value="dimension.id" />
              </el-select>
              <small>选择下一级维度后，点击表格中的“继续下钻”；系统会保留当前行筛选，并按所选维度重新统计。</small>
            </div>

            <!-- SQL 折叠 -->
            <el-collapse class="dq-sql-collapse">
              <el-collapse-item name="sql">
                <template slot="title">
                  <i class="el-icon-document" /> 查看查询依据
                </template>
                <div v-if="msg.configHash" class="dq-config-version"><span>配置版本</span><code>{{ msg.configHash }}</code></div>
                <pre>{{ msg.sql }}</pre>
              </el-collapse-item>
            </el-collapse>
          </template>

          <!-- 错误 -->
          <div v-else-if="msg.type === 'error'" class="dq-error-block" role="alert">
            <i class="el-icon-warning" />{{ msg.error }}
          </div>

        </div>
      </template>

      <!-- 底部锚点 -->
        </div>

        <!-- ══════════════════════════════════════════════════════════
             输入区
        ══════════════════════════════════════════════════════════ -->
        <div ref="composer" class="dq-composer">
          <div class="dq-composer-inner">
        <el-input
          v-model="inputText"
          ref="questionInput"
          type="textarea"
          :rows="1"
          :autosize="{ minRows: 1, maxRows: 4 }"
          resize="none"
          :placeholder="selectedProposalId ? '输入业务问题，Enter 发送，Shift+Enter 换行…' : '请先选择本体配置'"
          :disabled="!selectedProposalId || sending || workspaceLoading || historyOpening"
          maxlength="2000"
          aria-label="业务问题"
          class="dq-input"
          @keydown.native="onQuestionKeydown"
        />
        <button
          type="button"
          class="dq-send-btn"
          :aria-label="sending ? '正在查询' : '发送问题'"
          :disabled="!canSend"
          @click="submit"
        >
          <i v-if="sending" class="el-icon-loading" />
          <i v-else class="el-icon-s-promotion" />
          <span>{{ sending ? '查询中' : '发送' }}</span>
        </button>
      </div>
          <p class="dq-composer-hint">Ctrl + Enter 发送 &nbsp;·&nbsp; 结果来自已审核的本体配置</p>
        </div>
      </main>
    </div>


  </div>
</template>

<script>
import { listResource, askDataQuery, getDataQueryCatalog, drilldownDataQuery, listDataQueryHistory, getDataQueryHistory } from '@/api/ontology'
import * as echarts from 'echarts'

export default {
  name: 'OntologyDataQuery',
  data() {
    return {
      proposals: [],
      initializing: true,
      initializationError: '',
      proposalsLoading: false,
      selectedProposalId: '',
      proposalSearch: '',
      proposalDropdownVisible: false,
      readiness: null,
      catalog: null,
      catalogLoading: false,
      workspaceLoading: false,
      catalogWidth: 252,
      catalogVisible: true,
      sidebarTab: 'history',
      sessions: [],
      sessionId: '',
      historyLoading: false,
      historyOpening: false,
      historyError: false,
      historyPage: 1,
      historyHasMore: false,
      messagesHaveMore: false,
      olderMessagesLoading: false,
      inputText: '',
      catalogSearch: '',
      messages: [],
      sending: false,
      chartInstances: {},
      queryElapsed: 0,
      queryTimer: null,
      resizingCatalog: false,
      catalogResizeStartX: 0,
      catalogResizeStartWidth: 252,
      catalogTreeProps: { children: 'children', label: 'label' },
      analysisSourceObjectIds: [],
      analysisSourceRelations: []
    }
  },
  computed: {
    canSend() {
      return this.selectedProposalId && this.readiness && this.readiness.queryEnabled && this.inputText.trim() && !this.sending && !this.initializing && !this.workspaceLoading && !this.historyOpening && !this.olderMessagesLoading
    },
    activeSessionTitle() {
      const session = this.sessions.find(item => item.sessionId === this.sessionId)
      return session ? session.title : this.messages.length ? this.messages[0].question : '新查询'
    },
    selectedProposal() {
      return this.proposals.find(item => String(item.proposal_id) === String(this.selectedProposalId)) || null
    },
    filteredProposals() {
      const keyword = String(this.proposalSearch || '').trim().toLowerCase()
      if (!keyword) return this.proposals
      return this.proposals.filter(item => this.proposalLabel(item).toLowerCase().includes(keyword))
    },
    exampleQuestions() {
      return this.readiness && this.readiness.queryEnabled && this.catalog ? this.catalog.exampleQuestions || [] : []
    },
    catalogItemCount() {
      if (!this.catalog) return 0
      return ['objects', 'metrics', 'dimensions', 'timeDimensions'].reduce((total, key) => total + (this.catalog[key] || []).length, 0)
    },
    structureSummary() {
      return this.catalog && this.catalog.structureSummary || {}
    },
    analysisObjects() {
      return this.catalog && this.catalog.objects || []
    },
    analysisRelations() {
      return this.catalog && this.catalog.relations || []
    },
    analysisProfiles() {
      return this.catalog && this.catalog.dataProfiles || []
    },
    analysisSourceObjects() {
      const selected = new Set(this.analysisSourceObjectIds.map(String))
      return this.analysisObjects.filter(object => selected.has(String(object.id)))
    },
    analysisRelationsForSources() {
      if (!this.analysisSourceObjectIds.length) return this.analysisRelations
      return this.analysisSourceRelations
    },
    catalogTreeData() {
      if (!this.catalog) return []
      const groups = [
        { key: 'objects', label: '数据表', kind: 'group', items: this.catalog.objects, childKind: 'object' },
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
          label: group.childKind === 'object' ? `${item.name || item.table}（${item.schema ? item.schema + '.' : ''}${item.table}）` : (item.name || item.label || item.id),
          table: item.schema ? `${item.schema}.${item.table}` : item.table,
          kind: group.childKind,
          children: group.childKind === 'object' ? (item.fields || []).map(field => ({
            key: `field-${item.id}-${field.id}`, id: field.id, kind: 'field',
            name: field.name || field.column, table: item.schema ? `${item.schema}.${item.table}` : item.table,
            label: `${field.name || field.column} · ${field.dataType || ''}`,
            sourceText: `${field.column} ${field.description || ''}`
          })) : undefined,
          synonyms: item.synonyms || [],
          sourceText: this.catalogSourceText(item)
        }))
      }))
    },
    queryStageText() {
      return this.queryElapsed < 5 ? '正在处理查询…' : '查询仍在处理中…'
    },
    readinessReasons() {
      return this.readiness && Array.isArray(this.readiness.reasons) ? this.readiness.reasons : []
    }
  },
  created() {
    this.initialize()
  },
  mounted() {
    if (window.innerWidth <= 900) this.catalogVisible = false
    if (window.ResizeObserver) {
      this.layoutObserver = new ResizeObserver(entries => {
        const width = entries[0].contentRect.width
        if (width > 0 && width !== this.observedWidth) {
          this.observedWidth = width
          this.resizeResults()
        }
      })
      this.layoutObserver.observe(this.$refs.main)
    }
    window.addEventListener('resize', this.resizeResults)
  },
  activated() {
    this.$nextTick(this.resizeResults)
  },
  watch: {
    catalogSearch(value) {
      if (this.$refs.catalogTree) this.$refs.catalogTree.filter(value)
    }
  },
  beforeDestroy() {
    if (this.layoutObserver) this.layoutObserver.disconnect()
    window.removeEventListener('resize', this.resizeResults)
    cancelAnimationFrame(this.layoutFrame)
    this.destroyCharts()
    this.stopQueryTimer()
    this.stopCatalogResize()
  },
  methods: {
    async initialize() {
      this.initializing = true
      this.initializationError = ''
      try {
        await this.loadProposals()
        const sessionId = this.$route.query.querySessionId
        if (sessionId && this.selectedProposalId) await this.openConversation({ sessionId })
      } catch (error) {
        this.initializationError = '查询配置加载失败，请重试。'
      } finally {
        this.initializing = false
        this.$nextTick(() => {
          this.renderHistoryCharts()
          this.resizeResults()
        })
      }
    },
    async loadHistory(append = false) {
      if (!this.selectedProposalId) return
      const proposalId = this.selectedProposalId
      const page = append ? this.historyPage + 1 : 1
      this.historyLoading = true
      this.historyError = false
      try {
        const { data } = await listDataQueryHistory(proposalId, page)
        if (proposalId !== this.selectedProposalId) return
        this.sessions = append ? [...this.sessions, ...data.items] : data.items
        this.historyPage = page
        this.historyHasMore = data.hasMore
      } catch (error) {
        if (proposalId === this.selectedProposalId) this.historyError = true
      } finally {
        if (proposalId === this.selectedProposalId) this.historyLoading = false
      }
    },
    async openConversation(session) {
      if (this.sending || this.historyOpening || this.olderMessagesLoading) return
      this.historyOpening = true
      try {
        const { data } = await getDataQueryHistory(this.selectedProposalId, session.sessionId)
        this.destroyCharts()
        this.sessionId = session.sessionId
        this.messages = (data.items || []).map(this.restoreMessage)
        this.messagesHaveMore = data.hasMore
        this.inputText = ''
        this.syncConversationRoute()
        if (window.innerWidth <= 900) this.catalogVisible = false
        this.$nextTick(() => {
          this.renderHistoryCharts()
          this.scrollToLatestMessage()
        })
      } finally {
        this.historyOpening = false
      }
    },
    async loadOlderMessages() {
      if (this.olderMessagesLoading || !this.sessionId || this.sending) return
      this.olderMessagesLoading = true
      const feed = this.$refs.feed
      const previousHeight = feed.scrollHeight
      const previousTop = feed.scrollTop
      try {
        const { data } = await getDataQueryHistory(this.selectedProposalId, this.sessionId, this.messages[0].messageId)
        this.destroyCharts()
        this.messages = [...(data.items || []).map(this.restoreMessage), ...this.messages]
        this.messagesHaveMore = data.hasMore
        this.$nextTick(() => {
          this.renderHistoryCharts()
          feed.scrollTop = previousTop + feed.scrollHeight - previousHeight
        })
      } finally {
        this.olderMessagesLoading = false
      }
    },
    restoreMessage(message) {
      return { ...message, loading: false, viewMode: message.chartHint && message.chartHint.type !== 'table' ? 'chart' : 'table', nextDimensionId: '', page: 1, pageSize: 20 }
    },
    renderHistoryCharts() {
      if (this.initializing || !this.$refs.main || !this.$refs.main.clientWidth) return
      this.messages.forEach((message, index) => {
        if (message.viewMode === 'chart') this.renderChart(index, message)
      })
    },
    newConversation() {
      if (this.sending || this.historyOpening || this.olderMessagesLoading) return
      this.destroyCharts()
      this.messages = []
      this.sessionId = ''
      this.inputText = ''
      this.messagesHaveMore = false
      this.syncConversationRoute()
      if (window.innerWidth <= 900) this.catalogVisible = false
      this.$nextTick(this.focusComposer)
    },
    syncConversationRoute() {
      const query = { ...this.$route.query, proposalId: this.selectedProposalId }
      if (this.sessionId) query.querySessionId = this.sessionId
      else delete query.querySessionId
      this.$router.replace({ query }).catch(() => {})
    },
    rememberResponse(response) {
      if (response && response.sessionId) {
        this.sessionId = response.sessionId
        this.syncConversationRoute()
        this.loadHistory()
      }
    },
    historyDate(value) {
      return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    },
    focusComposer() {
      this.$refs.questionInput.focus()
    },
    toggleCatalog() {
      this.catalogVisible = !this.catalogVisible
      this.$nextTick(this.resizeResults)
    },
    resizeResults() {
      cancelAnimationFrame(this.layoutFrame)
      this.layoutFrame = requestAnimationFrame(() => {
        Object.values(this.chartInstances).forEach(chart => {
          if (!chart.isDisposed() && chart.getDom().clientWidth) chart.resize()
        })
        const tables = this.$refs.resultTables || []
        tables.forEach(table => table.doLayout())
      })
    },
    visibleRows(msg) {
      const size = msg.pageSize || 20
      const start = ((msg.page || 1) - 1) * size
      return (msg.rows || []).slice(start, start + size)
    },
    setPageSize(msg, size) {
      this.$set(msg, 'pageSize', size)
      this.$set(msg, 'page', 1)
    },
    proposalLabel(item) {
      return `#${item.proposal_id} · ${item.proposal_name || '未命名草案'} · ${this.statusLabel(item.status)}`
    },
    selectProposal(item) {
      if (this.sending || this.workspaceLoading || this.historyOpening || this.olderMessagesLoading) return
      this.proposalDropdownVisible = false
      this.proposalSearch = ''
      const nextId = String(item.proposal_id)
      if (nextId !== String(this.selectedProposalId)) {
        this.selectedProposalId = nextId
        this.onProposalChange()
        this.syncConversationRoute()
      }
    },
    statusLabel(s) {
      return ({ DRAFT: '待修正', COMPLETED: '草案完成', IMPORTED: '已导入' })[s] || s || '未知'
    },
    loadProposals() {
      this.proposalsLoading = true
      return listResource('proposals', { pageNum: 1, pageSize: 100 }).then(res => {
        this.proposals = res.rows || []
        const req = String(this.$route.query.proposalId || '')
        if (req && this.proposals.some(i => String(i.proposal_id) === req)) {
          this.selectedProposalId = req
        } else if (this.proposals.length) {
          this.selectedProposalId = String(this.proposals[0].proposal_id)
        }
        if (this.selectedProposalId) return this.onProposalChange()
      }).finally(() => { this.proposalsLoading = false })
    },
    onProposalChange() {
      this.readiness = null
      this.catalog = null
      this.catalogLoading = !!this.selectedProposalId
      this.workspaceLoading = !!this.selectedProposalId
      this.catalogSearch = ''
      this.initializationError = ''
      this.messages = []
      this.sessionId = ''
      this.sessions = []
      this.messagesHaveMore = false
      this.inputText = ''
      this.destroyCharts()
      if (!this.selectedProposalId) return
      const proposalId = this.selectedProposalId
      // 历史列表使用自己的加载状态，不阻塞查询配置和输入区域。
      this.loadHistory()
      return getDataQueryCatalog(proposalId).then(({ data }) => {
        if (proposalId !== this.selectedProposalId) return
        this.catalog = data || null
        this.readiness = data && data.readiness || null
      }).catch(() => {
        if (proposalId === this.selectedProposalId) this.initializationError = '查询配置加载失败，请重试。'
      }).finally(() => {
        if (proposalId === this.selectedProposalId) {
          this.catalogLoading = false
          this.workspaceLoading = false
        }
      })
    },
    catalogSourceText(item) {
      const evidence = item && item.evidence && typeof item.evidence === 'object' ? item.evidence : {}
      const table = item && (item.sourceTable || evidence.table)
      const column = item && (item.sourceColumn || evidence.column)
      const definition = item && (item.definition || '')
      const source = [table && `来源表：${table}`, column && `物理字段：${column}`].filter(Boolean).join(' · ')
      return [source, definition && `口径：${definition}`].filter(Boolean).join('\n')
    },
    startCatalogResize(event) {
      if (window.innerWidth <= 900 || this.resizingCatalog) return
      this.resizingCatalog = true
      this.catalogResizeStartX = event.clientX
      this.catalogResizeStartWidth = this.catalogWidth
      document.body.style.cursor = 'col-resize'
      document.body.style.userSelect = 'none'
      document.addEventListener('mousemove', this.onCatalogResize)
      document.addEventListener('mouseup', this.stopCatalogResize)
    },
    onCatalogResize(event) {
      if (!this.resizingCatalog) return
      this.catalogWidth = Math.min(420, Math.max(220, this.catalogResizeStartWidth + event.clientX - this.catalogResizeStartX))
      this.$nextTick(this.resizeResults)
    },
    stopCatalogResize() {
      if (typeof document === 'undefined') return
      this.resizingCatalog = false
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
      document.removeEventListener('mousemove', this.onCatalogResize)
      document.removeEventListener('mouseup', this.stopCatalogResize)
    },
    onCatalogNodeClick(data) {
      if (!data || data.kind === 'group') return
      if (data.kind === 'object' || data.kind === 'field') {
        this.inputText = data.kind === 'object' ? `查看${data.table}的数据` : `查看${data.table}的${data.name}`
        this.$nextTick(this.focusComposer)
      } else this.appendQuestion(data.name || data.id)
    },
    exploreObject(object) {
      if (!object || !object.table) return
      const table = object.schema ? `${object.schema}.${object.table}` : object.table
      this.inputText = `查看${table}的数据`
      this.sidebarTab = 'catalog'
      this.$nextTick(this.focusComposer)
    },
    openStructureAnalysis(message) {
      this.catalogVisible = true
      this.sidebarTab = 'analysis'
      this.analysisSourceObjectIds = message && message.explanation && message.explanation.sourceObjects || []
      this.analysisSourceRelations = message && message.explanation && message.explanation.sourceRelations || []
      this.$nextTick(this.resizeResults)
    },
    relationQuestion(relation) {
      const metrics = (this.catalog && this.catalog.metrics || []).filter(item => String(item.objectId) === String(relation.sourceObjectId))
      const dimensions = (this.catalog && this.catalog.dimensions || []).filter(item => String(item.objectId) === String(relation.targetObjectId))
      const reverseMetrics = (this.catalog && this.catalog.metrics || []).filter(item => String(item.objectId) === String(relation.targetObjectId))
      const reverseDimensions = (this.catalog && this.catalog.dimensions || []).filter(item => String(item.objectId) === String(relation.sourceObjectId))
      if (metrics.length === 1 && dimensions.length === 1) return `按${dimensions[0].name}统计${metrics[0].name}`
      if (reverseMetrics.length === 1 && reverseDimensions.length === 1) return `按${reverseDimensions[0].name}统计${reverseMetrics[0].name}`
      return ''
    },
    exploreRelation(relation) {
      const question = this.relationQuestion(relation)
      if (!question) return
      this.inputText = question
      this.sidebarTab = 'catalog'
      this.$nextTick(this.focusComposer)
    },
    filterTreeNode(value, data) {
      if (!value) return true
      if (data.kind === 'group') return (data.children || []).some(child => this.filterTreeNode(value, child))
      return `${data.name || ''} ${data.table || ''} ${data.id || ''} ${(data.synonyms || []).join(' ')}`.toLowerCase().includes(String(value).trim().toLowerCase())
    },
    appendQuestion(text) {
      this.inputText = this.inputText ? `${this.inputText} ${text}` : text
      this.$nextTick(this.focusComposer)
    },
    readinessReasonText(reason) {
      const text = String(reason || '')
      if (text === '配置草案尚未完成审核') return '先到“配置草案”检查对象、字段和关系，并点击“草案完成”。'
      if (/querySemantics\.metrics 不能为空/.test(text)) return '还没有可统计指标，例如订单金额、订单数量。请在分析问答中补充“指标”。'
      if (/querySemantics\.dimensions 不能为空/.test(text)) return '还没有可分组维度，例如地区、门店、订单状态。请在分析问答中补充“维度”。'
      if (/querySemantics\.metrics\[\d+\]/.test(text)) return '有指标没有绑定有效字段或聚合方式，请回到分析问答检查指标口径。'
      if (/querySemantics\.dimensions\[\d+\]/.test(text)) return '有维度没有绑定有效字段，请回到分析问答检查字段映射。'
      return text || '请先完善查询语义配置。'
    },
    showReadinessGuide() {
      this.$alert((this.readinessReasons || []).map(reason => this.readinessReasonText(reason)).join('\n') || '请先完善查询语义配置。', '查询尚未开放', { confirmButtonText: '知道了' })
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
      const metrics = this.catalog && this.catalog.metrics || []
      const dimensions = this.catalog && this.catalog.dimensions || []
      const sourceObjects = msg.explanation && msg.explanation.sourceObjects || []
      const firstMetric = metrics.find(item => msg.plan && msg.plan.metrics && msg.plan.metrics[0] && String(item.id) === String(msg.plan.metrics[0].id))
      const base = sourceObjects[0] || (firstMetric && firstMetric.objectId)
      if (!base) return []
      // Use the same endpoint graph and unique shortest path rule as the SQL compiler.
      const distances = new Map([[String(base), 0]])
      const paths = new Map([[String(base), 1]])
      const queue = [String(base)]
      const relations = (this.catalog && this.catalog.relations || []).filter(item => item.inferenceMethod !== 'm2mReification')
      for (let index = 0; index < queue.length; index++) {
        const current = queue[index]
        relations.forEach(relation => {
          const source = String(relation.sourceObjectId || '')
          const target = String(relation.targetObjectId || '')
          const next = current === source ? target : current === target ? source : ''
          if (!next) return
          const distance = distances.get(current) + 1
          if (!distances.has(next)) {
            distances.set(next, distance)
            paths.set(next, paths.get(current))
            queue.push(next)
          } else if (distances.get(next) === distance) {
            paths.set(next, Math.min(2, paths.get(next) + paths.get(current)))
          }
        })
      }
      return dimensions.filter(item => !selected.includes(item.id) && item.objectId && paths.get(String(item.objectId)) === 1)
    },
    hasDrillTarget(msg) {
      return this.availableDrillDimensions(msg).some(item => String(item.id) === String(msg.nextDimensionId))
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
      if (this.sending || this.workspaceLoading || this.historyOpening || this.olderMessagesLoading) return
      const target = this.availableDrillDimensions(msg).find(item => String(item.id) === String(msg.nextDimensionId))
      if (!target) { this.$modal.msgWarning('请先选择可用的下一级维度'); return }
      const next = target.id
      const dimensions = (msg.plan && msg.plan.dimensions || []).map(item => item.id)
      const previous = msg.plan && msg.plan.drilldown
      const currentId = previous && previous.nextDimensionId ? previous.nextDimensionId : dimensions[dimensions.length - 1]
      const currentValue = this.rowValue(row, currentId)
      if (!next || !currentId || currentValue == null) return
      const plan = JSON.parse(JSON.stringify(msg.plan))
      plan.filters = plan.filters || []
      if (previous) {
        const previousDimension = (this.catalog.dimensions || []).find(item => item.id === previous.dimensionId)
        if (!previousDimension || !previousDimension.propertyId) { this.$modal.msgWarning('数据目录没有该下钻维度的字段映射'); return }
        const previousFilter = { propertyId: previousDimension.propertyId, operator: '=', value: previous.value }
        if (!plan.filters.some(item => item.propertyId === previousFilter.propertyId && item.operator === previousFilter.operator && item.value === previousFilter.value)) plan.filters.push(previousFilter)
        if (!dimensions.includes(previous.nextDimensionId)) plan.dimensions.push({ id: previous.nextDimensionId })
      }
      for (const id of dimensions.filter(id => id !== currentId)) {
        const dimension = (this.catalog.dimensions || []).find(item => item.id === id)
        if (!dimension || !dimension.propertyId) { this.$modal.msgWarning('数据目录没有该分组字段的映射'); return }
        const value = this.rowValue(row, id)
        const filter = value == null ? { propertyId: dimension.propertyId, operator: 'IS_NULL' } : { propertyId: dimension.propertyId, operator: '=', value }
        if (!plan.filters.some(item => item.propertyId === filter.propertyId && item.operator === filter.operator && item.value === filter.value)) plan.filters.push(filter)
      }
      plan.drilldown = { dimensionId: currentId, value: currentValue, nextDimensionId: next }
      const question = `筛选 ${this.dimensionLabel(currentId)} = ${currentValue}，按${this.dimensionLabel(next)}统计`
      this.sending = true
      drilldownDataQuery(this.selectedProposalId, plan, this.sessionId, question).then(({ data }) => {
        const result = Object.assign({}, data || {}, { question, viewMode: 'table', nextDimensionId: '' })
        this.messages.push(result)
        this.rememberResponse(data)
      }).catch(error => this.$modal.msgError((error && error.message) || '下钻失败')).finally(() => {
        this.sending = false
        this.$nextTick(this.scrollToLatestMessage)
      })
    },
    loadDetailPage(msg, direction) {
      if (this.sending || this.workspaceLoading || this.historyOpening || this.olderMessagesLoading) return
      const plan = JSON.parse(JSON.stringify(msg.plan))
      plan.offset = Math.max(0, Number(msg.offset || 0) + direction * Number(msg.limit || 100))
      const question = `查看第 ${plan.offset + 1} 条起的数据`
      this.sending = true
      drilldownDataQuery(this.selectedProposalId, plan, this.sessionId, question).then(({ data }) => {
        this.messages.push(Object.assign({}, data, { viewMode: 'table' }))
        this.rememberResponse(data)
      }).catch(error => this.$modal.msgError((error && error.message) || '数据加载失败')).finally(() => {
        this.sending = false
        this.$nextTick(this.scrollToLatestMessage)
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
    startNewQuestion(msg) {
      if (!msg) return
      this.$set(msg, 'skipFollowUp', true)
      this.$nextTick(this.focusComposer)
    },
    submit() {
      if (!this.canSend) return
      this.sendQuestion(this.inputText.trim())
      this.inputText = ''
    },
    onQuestionKeydown(event) {
      if (event.isComposing || event.keyCode === 229 || event.key !== 'Enter' || event.shiftKey) return
      event.preventDefault()
      this.submit()
    },
    followUpSummary(fields) {
      const labels = { metrics: '指标', dimensions: '分组', filters: '筛选', timeDimension: '时间口径', timeRange: '时间范围', timeGranularity: '时间粒度', orderBy: '排序', limit: '条数', comparison: '对比方式', detailObjectId: '明细表', fields: '展示字段' }
      const changed = (fields || []).map(field => labels[field]).filter(Boolean)
      return changed.length ? `沿用上一轮未修改的条件，更新：${changed.join('、')}` : '沿用上一轮查询条件'
    },
    sendQuestion(question) {
      if (!this.selectedProposalId || this.sending || this.initializing || this.workspaceLoading || this.historyOpening || this.olderMessagesLoading) return
      if (!this.readiness || !this.readiness.queryEnabled) { this.showReadinessGuide(); return }
      const previous = this.messages[this.messages.length - 1]
      const newQuestion = !!(previous && previous.skipFollowUp)
      this.sending = true
      this.startQueryTimer()
      const msg = { question, type: 'loading', loading: true, viewMode: 'chart' }
      this.messages.push(msg)
      this.$nextTick(this.scrollToLatestMessage)
      const idx = this.messages.length - 1
      askDataQuery(this.selectedProposalId, question, this.sessionId, newQuestion).then(({ data }) => {
        const valid = data && (
          (data.type === 'result' && Array.isArray(data.rows)) ||
          (data.type === 'preview' && data.plan) ||
          (data.type === 'clarification' && typeof data.clarification === 'string' && data.clarification.trim()) ||
          (data.type === 'error' && typeof data.error === 'string' && data.error.trim())
        )
        const response = valid ? data : { type: 'error', error: '未收到有效的查询响应，请重试' }
        Object.assign(msg, response)
        msg.question = question
        msg.loading = false
        this.rememberResponse(data)
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
        this.$nextTick(this.scrollToLatestMessage)
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
      if (!this.canChart(msg) || msg.chartHint.type === 'stat') return
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
      const baseColor = '#0075de'
      const palette = ['#0075de', '#2a9d99', '#8b6ac8', '#f0ad4e', '#65a9ee', '#e68188']
      const common = {
        animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
        animationDuration: 300,
        grid: { top: 44, right: 24, bottom: 32, left: 24, containLabel: true },
        textStyle: { fontFamily: 'Inter, Arial, sans-serif', fontSize: 12, color: '#31302e' },
        tooltip: { trigger: type === 'pie' ? 'item' : 'axis', confine: true, backgroundColor: '#fff', borderColor: '#e5eaf0', textStyle: { color: '#31302e', fontSize: 12 }, extraCssText: 'max-width:calc(100% - 24px);white-space:normal;overflow-wrap:anywhere;' },
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
          tooltip: { ...common.tooltip, trigger: 'item', formatter: '{b}: {c} ({d}%)' },
          legend: { type: 'scroll', bottom: 8, left: 16, right: 16, textStyle: { fontSize: 11, color: '#64748b' } },
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
        baseSeries.areaStyle = { color: 'rgba(0,117,222,0.08)' }
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
    scrollToLatestMessage() {
      const feed = this.$refs.feed
      if (!feed) return
      const questions = feed.querySelectorAll('.dq-bubble-user')
      const latest = questions[questions.length - 1]
      if (latest) feed.scrollTo({ top: feed.scrollTop + latest.getBoundingClientRect().top - feed.getBoundingClientRect().top - 16, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
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
.dq-shell { position:relative; display:flex; flex:1; flex-direction:column; width:100%; min-height:0; margin:0 auto; padding:16px 20px; overflow:hidden; color:var(--aw-ink); }
.dq-shell button { font-family:inherit; }
.dq-shell button:focus-visible { outline:2px solid var(--aw-blue); outline-offset:3px; }
.dq-header { display:flex; flex:none; align-items:center; justify-content:space-between; gap:16px; padding:12px 18px; margin-bottom:14px; border:1px solid var(--aw-border); border-radius:12px; background:#fff; box-shadow:0 2px 10px rgba(0,0,0,.025); }
.dq-header-brand { display:flex; align-items:center; gap:12px; min-width:0; }
.dq-logo-icon { display:grid; place-items:center; flex:none; width:38px; height:38px; border-radius:10px; background:#edf5ff; color:var(--aw-blue); font-size:20px; }
.dq-title { margin:0; color:#31302e; font-size:18px; font-weight:700; line-height:1.3; }
.dq-subtitle { margin:4px 0 0; color:var(--aw-soft); font-size:12px; line-height:1.5; }
.dq-header-controls { display:flex; align-items:center; gap:10px; flex:0 1 370px; min-width:0; }
.dq-header-controls > span { flex:1; min-width:0; }
.dq-proposal-select { display:flex; align-items:center; gap:8px; width:100%; min-height:40px; padding:10px 12px; border:1px solid #dce5ef; border-radius:9px; background:#fff; color:var(--aw-muted); font-size:12px; cursor:pointer; transition:border-color .18s,box-shadow .18s; }
.dq-proposal-select span { flex:1; min-width:0; text-align:left; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; line-height:1.5; }
.dq-proposal-select > i:first-child { color:var(--aw-blue); }
.dq-proposal-select > i:last-child { color:var(--aw-soft); font-size:10px; }
.dq-proposal-select:hover { border-color:#a8cdf4; box-shadow:0 4px 12px rgba(0,117,222,.06); }
.dq-proposal-menu { display:flex; flex-direction:column; gap:8px; }
.dq-proposal-list { display:flex; max-height:280px; flex-direction:column; gap:4px; overflow-y:auto; }
.dq-proposal-item { display:flex; align-items:center; gap:8px; width:100%; padding:10px; border:1px solid transparent; border-radius:8px; background:transparent; color:var(--aw-muted); text-align:left; cursor:pointer; }
.dq-proposal-item:hover, .dq-proposal-item.active { border-color:#d7e7ff; background:#edf5ff; color:var(--aw-blue); }
.dq-proposal-item > span { display:flex; min-width:0; flex:1; flex-direction:column; }
.dq-proposal-item strong { color:inherit; font-size:12px; font-weight:600; overflow-wrap:anywhere; line-height:1.5; }
.dq-proposal-item small { margin-top:3px; color:var(--aw-soft); font-size:11px; }
.dq-proposal-empty { padding:14px 4px; color:var(--aw-soft); font-size:12px; text-align:center; }
.dq-status-badge { display:inline-flex; align-items:center; gap:5px; padding:7px 10px; border:1px solid; border-radius:20px; font-size:12px; font-weight:600; white-space:nowrap; }
.dq-status-help { margin-left:2px; }
.dq-readiness-popover-content { color:#31302e; font-size:12px; line-height:1.7; }
.dq-readiness-popover-content strong { display:block; margin-bottom:5px; font-size:13px; }
.dq-readiness-popover-content ul { margin:0; padding-left:18px; color:#615d59; }
.dq-readiness-popover-content p { margin:0; color:#615d59; }
.badge-ready { border-color:#d5eadd; background:#f0f8f3; color:#25865e; }
.badge-pending { border-color:#f5d97e; background:#fff8e6; color:#8a6d00; }
.badge-off { border-color:#f0c8c4; background:#fdf0ef; color:#b3261e; }

.dq-workspace { position:relative; display:flex; flex:1; min-height:0; gap:14px; }
.dq-main { display:flex; flex:1; min-width:0; min-height:0; flex-direction:column; overflow:hidden; border:1px solid var(--aw-border); border-radius:12px; background:#fbfcfe; }
.dq-conversation-toolbar { display:flex; flex:none; align-items:center; justify-content:space-between; gap:12px; min-height:58px; padding:12px 16px; border-bottom:1px solid #edf0f4; background:#fff; }
.dq-conversation-title { display:flex; min-width:0; align-items:baseline; gap:10px; }
.dq-conversation-actions { display:flex; flex:none; align-items:center; gap:8px; margin-left:auto; }
.dq-conversation-toolbar h2 { margin:0; overflow:hidden; color:#31302e; font-size:14px; font-weight:600; text-overflow:ellipsis; white-space:nowrap; }
.dq-conversation-toolbar span { color:var(--aw-soft); font-size:12px; }
.dq-conversation-title > span { flex:none; }
.dq-toolbar-btn { display:inline-flex; align-items:center; justify-content:center; gap:6px; flex:none; padding:8px 11px; border:1px solid #e5e9ef; border-radius:8px; background:#fff; color:var(--aw-muted); font-size:12px; line-height:1.4; cursor:pointer; transition:background .18s,border-color .18s,color .18s; }
.dq-toolbar-btn:hover { border-color:#cfe2fb; background:#f2f8ff; color:var(--aw-blue); }
.dq-toolbar-btn:disabled { opacity:.4; cursor:not-allowed; }
.dq-catalog-panel { position:relative; display:flex; flex:0 0 252px; min-width:0; min-height:0; flex-direction:column; border:1px solid var(--aw-border); border-radius:12px; background:#fff; }
.dq-catalog-content { display:flex; flex:1; min-height:0; flex-direction:column; padding:16px 12px; }
.dq-analysis-content { display:flex; flex:1; min-height:0; flex-direction:column; gap:10px; overflow:auto; padding:16px 12px; }
.dq-structure-summary { display:grid; grid-template-columns:repeat(3, 1fr); gap:6px; }
.dq-structure-summary > div { display:flex; flex-direction:column; align-items:center; padding:9px 4px; border-radius:8px; background:#f3f7fc; }
.dq-structure-summary strong { color:var(--aw-blue); font-size:17px; }
.dq-structure-summary small { margin-top:3px; color:var(--aw-soft); font-size:10px; }
.dq-analysis-note { margin:0; padding:9px; border-radius:7px; background:#fff8e6; color:#78652c; font-size:10px; line-height:1.6; }
.dq-analysis-sources { display:flex; flex-wrap:wrap; align-items:center; gap:5px; }
.dq-analysis-sources strong { flex-basis:100%; color:#4d5c70; font-size:11px; }
.dq-analysis-tables { border-top:1px solid #edf0f4; }
.dq-analysis-tables ::v-deep .el-collapse-item__header { gap:6px; height:auto; min-height:42px; line-height:1.5; font-size:12px; }
.dq-analysis-tables ::v-deep .el-collapse-item__header > i { color:var(--aw-blue); }
.dq-analysis-tables ::v-deep .el-collapse-item__header small { margin-left:auto; color:var(--aw-soft); font-size:10px; }
.dq-analysis-tables ::v-deep .el-collapse-item__content { padding-bottom:8px; }
.dq-analysis-table-name { margin:0 0 7px; color:var(--aw-soft); font-size:10px; }
.dq-analysis-field-table { margin-bottom:5px; font-size:11px; }
.dq-relation-list h4 { margin:8px 0; color:#31302e; font-size:12px; }
.dq-relation-card { margin-bottom:8px; padding:10px; border:1px solid #e8edf3; border-radius:8px; background:#fff; }
.dq-relation-title { display:flex; align-items:center; gap:5px; color:#344256; font-size:11px; }
.dq-relation-title strong { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.dq-relation-title i { flex:none; color:var(--aw-blue); }
.dq-relation-card p { margin:6px 0 0; color:#657182; font-size:10px; line-height:1.5; overflow-wrap:anywhere; }
.dq-relation-card .dq-relation-evidence { color:#8490a0; }
.dq-relation-list h4 small { display:block; margin-top:4px; color:var(--aw-soft); font-size:10px; font-weight:400; }
.dq-profile-column { display:flex; flex-wrap:wrap; justify-content:space-between; gap:4px; padding-top:5px; border-top:1px solid #eff2f6; }
.dq-relation-actions { display:flex; align-items:center; justify-content:space-between; margin-top:4px; }
.dq-analysis-empty { padding:12px; color:var(--aw-soft); font-size:11px; text-align:center; }
.dq-result-actions { display:flex; align-items:center; gap:7px; }
.dq-catalog-panel-head { display:flex; align-items:flex-start; justify-content:space-between; gap:8px; margin:0 4px 16px; }
.dq-catalog-panel-head strong { display:block; color:#31302e; font-size:14px; }
.dq-catalog-panel-head strong i { margin-right:5px; color:var(--aw-blue); }
.dq-catalog-panel-head small { display:block; margin-top:7px; color:var(--aw-soft); font-size:11px; line-height:1.5; }
.dq-catalog-count { min-width:24px; padding:3px 7px; border-radius:7px; background:#edf5ff; color:var(--aw-blue); font-size:11px; line-height:18px; text-align:center; }
.dq-catalog-search { margin-bottom:12px; }
.dq-catalog-search ::v-deep .el-input__inner { border-radius:8px; background:#fafbfc; }
.dq-catalog-tree { flex:1; min-height:0; overflow:auto; }
.dq-catalog-tree ::v-deep .el-tree-node__content { height:auto; min-height:36px; padding:5px 6px; border-radius:7px; }
.dq-catalog-tree ::v-deep .el-tree-node__content:hover, .dq-catalog-tree ::v-deep .is-current > .el-tree-node__content { background:#edf5ff; }
.dq-catalog-tree ::v-deep .el-tree-node__expand-icon:not(.is-leaf) { color:#94a3b8; }
.dq-tree-node { display:flex; align-items:flex-start; min-width:0; width:100%; gap:7px; font-size:12px; line-height:1.6; }
.dq-tree-node > i { flex:none; margin-top:3px; color:var(--aw-blue); }
.dq-tree-label { min-width:0; color:var(--aw-muted); white-space:normal; overflow-wrap:anywhere; }
.dq-tree-tooltip { max-width:260px; white-space:pre-line; line-height:1.6; }
.dq-catalog-resizer { position:absolute; z-index:2; top:0; right:-9px; width:12px; height:100%; cursor:col-resize; }
.dq-catalog-resizer::after { content:""; position:absolute; top:50%; left:6px; width:3px; height:36px; border-radius:3px; background:#cad8e8; transform:translateY(-50%); transition:background .18s; }
.dq-catalog-resizer:hover::after { background:var(--aw-blue); }
.dq-catalog-empty { display:flex; min-height:140px; align-items:center; justify-content:center; flex-direction:column; gap:10px; color:var(--aw-soft); font-size:12px; }
.dq-catalog-empty i { font-size:26px; }

.dq-feed { display:flex; flex:1; min-width:0; min-height:0; flex-direction:column; gap:16px; padding:18px; overflow:auto; overscroll-behavior:contain; scrollbar-gutter:stable; }
.dq-feed > * { flex-shrink:0; }
.dq-empty { display:flex; flex:1 0 auto; flex-direction:column; align-items:center; justify-content:center; padding:24px; text-align:center; }
.dq-empty-icon { display:grid; place-items:center; width:48px; height:48px; border-radius:14px; background:#edf5ff; color:var(--aw-blue); font-size:24px; margin-bottom:16px; }
.dq-empty h2 { margin:0 0 10px; font-size:20px; color:#31302e; font-weight:600; }
.dq-empty p { margin:0; font-size:13px; color:var(--aw-muted); max-width:420px; line-height:1.8; }
.dq-empty-hint { margin-top:18px; font-size:13px; color:var(--aw-blue); }
.dq-example-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; width:100%; max-width:620px; margin-top:24px; }
.dq-example-btn { display:flex; align-items:center; gap:10px; min-width:0; padding:15px; border:1px solid #e5eaf0; border-radius:10px; background:#fff; color:var(--aw-muted); font-size:13px; text-align:left; line-height:1.6; cursor:pointer; transition:transform .18s,box-shadow .18s,border-color .18s; }
.dq-example-btn span { overflow-wrap:anywhere; }
.dq-example-btn:hover { transform:translateY(-2px); border-color:#cfe2fb; color:var(--aw-blue); box-shadow:0 8px 20px rgba(0,117,222,.09); }
.dq-example-btn i { color:var(--aw-blue); font-size:17px; flex:none; }
.dq-bubble-user { align-self:flex-end; max-width:90%; padding:13px 18px; border:1px solid #d5e6fa; border-radius:14px 14px 4px 14px; background:#eaf4ff; color:#245785; font-size:14px; line-height:1.8; overflow-wrap:anywhere; white-space:pre-wrap; scroll-margin-top:20px; }
.dq-answer-card { width:100%; min-width:0; border:1px solid var(--aw-border); border-radius:14px; background:#fff; box-shadow:0 4px 18px rgba(0,0,0,.035); animation:dq-appear .2s ease-out; }
@keyframes dq-appear { from { opacity:0; transform:translateY(6px); } to { opacity:1; transform:translateY(0); } }
.dq-answer-loading { display:flex; align-items:center; gap:8px; padding:24px; color:var(--aw-muted); font-size:13px; line-height:1.7; }
.dq-answer-loading small { color:var(--aw-soft); font-size:12px; }
.dq-dot-anim { display:inline-block; flex:none; width:6px; height:6px; border-radius:50%; background:var(--aw-blue); animation:dq-pulse 1.1s ease-in-out infinite; }
.dq-dot-2 { animation-delay:.18s; }
.dq-dot-3 { animation-delay:.36s; }
@keyframes dq-pulse { 0%,80%,100% { transform:scale(.6); opacity:.4; } 40% { transform:scale(1); opacity:1; } }
.dq-clarification { display:flex; align-items:flex-start; gap:12px; padding:24px; color:#615d59; overflow-wrap:anywhere; }
.dq-clarification > i { flex:none; color:var(--aw-blue); font-size:22px; margin-top:3px; }
.dq-clarification p { min-width:0; margin:0; font-size:14px; line-height:1.85; white-space:pre-wrap; }
.dq-clarification-hint { display:block; margin-top:10px; color:var(--aw-soft); font-size:12px; }
.dq-clarification-hint .el-button { margin-left:10px; }.dq-new-question-hint { color:var(--aw-primary); }
.dq-preview-block { padding:20px; }
.dq-preview-notice { display:flex; gap:10px; padding:14px; margin-bottom:14px; border:1px solid #f5d97e; border-radius:9px; background:#fff8e6; color:#8a6d00; overflow-wrap:anywhere; }
.dq-preview-notice > i { flex:none; font-size:18px; margin-top:2px; }
.dq-preview-notice strong { display:block; font-size:13px; margin-bottom:5px; }
.dq-preview-notice p { margin:0; font-size:12px; line-height:1.7; }
.dq-plan-chips { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:14px; }
.dq-plan-chip { display:inline-flex; align-items:center; gap:5px; padding:6px 10px; border-radius:6px; font-size:12px; overflow-wrap:anywhere; }
.chip-metric { background:#edf5ff; color:var(--aw-blue); }
.chip-dim { background:#f4effb; color:#6a48a8; }

.dq-result-header { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:12px; padding:22px 24px 18px; }
.dq-result-heading { display:flex; align-items:center; min-width:0; gap:12px; }
.dq-result-icon { display:grid; place-items:center; flex:none; width:38px; height:38px; border-radius:11px; background:#edf5ff; color:var(--aw-blue); font-size:20px; }
.dq-result-heading h3 { margin:0; color:#31302e; font-size:16px; font-weight:600; }
.dq-result-heading p { margin:6px 0 0; color:var(--aw-soft); font-size:12px; line-height:1.6; }
.dq-plan-summary { display:flex; flex-wrap:wrap; gap:8px; padding:0 24px 18px; }
.dq-plan-summary > span { display:inline-flex; align-items:baseline; gap:7px; min-width:0; max-width:100%; padding:6px 10px; border-radius:6px; background:#f6f8fa; color:var(--aw-muted); font-size:12px; line-height:1.7; overflow-wrap:anywhere; }
.dq-plan-summary b { flex:none; color:#8a95a3; font-size:11px; font-weight:500; }
.dq-view-toggle { display:flex; gap:4px; width:max-content; max-width:calc(100% - 48px); padding:4px; margin:0 24px 18px; border-radius:9px; background:#f1f4f8; }
.dq-toggle-btn { display:inline-flex; align-items:center; gap:6px; padding:7px 14px; border:1px solid transparent; border-radius:6px; background:transparent; color:var(--aw-muted); font-size:12px; cursor:pointer; transition:background .18s,color .18s,box-shadow .18s; }
.dq-toggle-btn.active { background:#fff; color:var(--aw-blue); font-weight:600; box-shadow:0 1px 5px rgba(25,55,85,.1); }
.dq-toggle-btn:hover:not(:disabled) { color:var(--aw-blue); }
.dq-toggle-btn:disabled { opacity:.4; cursor:not-allowed; }
.dq-stat-card { margin:0 24px 24px; padding:28px; border:1px solid #e3edf9; border-radius:12px; background:linear-gradient(120deg,#f4f9ff,#fff); overflow-wrap:anywhere; }
.dq-stat-label { color:var(--aw-muted); font-size:14px; line-height:1.7; }
.dq-stat-value { margin:14px 0; color:#245785; font-size:clamp(32px,4vw,54px); font-weight:700; font-variant-numeric:tabular-nums; letter-spacing:-1.5px; line-height:1.2; }
.dq-stat-caption { color:#8a95a3; font-size:12px; }
.dq-chart-container { width:calc(100% - 32px); min-height:300px; aspect-ratio:2 / 1; margin:0 16px 20px; }
.dq-table-section { min-width:0; padding:0 24px 20px; }
.dq-table-hint { margin:0 0 10px; color:var(--aw-soft); font-size:11px; }
.dq-table-hint i { display:inline-block; transform:rotate(90deg); margin-right:4px; }
.dq-result-table { width:100%; border:1px solid #e9edf2; border-radius:9px; color:#4b5563; }
.dq-result-table ::v-deep .cell { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; line-height:1.7; }
.dq-result-table ::v-deep th.el-table__cell { background:#f5f7fa; color:#657182; font-size:12px; }
.dq-result-table ::v-deep td.el-table__cell { padding:12px 0; border-bottom-color:#eef1f5; }
.dq-result-table ::v-deep .el-table__body tr:hover > td { background:#f0f7ff; }
.dq-table-pagination { display:flex; flex-wrap:wrap; align-items:center; justify-content:flex-end; gap:10px; padding-top:16px; color:var(--aw-soft); font-size:12px; }
.dq-table-pagination > span { margin-right:auto; }
.dq-table-pagination .el-select { width:110px; }
.dq-table-pagination ::v-deep .el-pagination { padding:0; white-space:normal; }
.dq-drill-bar { display:flex; flex-wrap:wrap; align-items:center; gap:10px; margin:0 24px 20px; padding:14px; border:1px solid #e3edf9; border-radius:9px; background:#f7fbff; color:var(--aw-muted); font-size:12px; }
.dq-drill-bar > span i { margin-right:5px; color:var(--aw-blue); }
.dq-drill-select { width:190px; max-width:100%; }
.dq-drill-bar small { flex-basis:100%; color:var(--aw-soft); line-height:1.7; }
.dq-sql-collapse { border:0; border-top:1px solid #eef1f5; border-radius:0 0 14px 14px; }
.dq-sql-collapse ::v-deep .el-collapse-item__header { min-height:46px; height:auto; padding:8px 24px; border-bottom:0; border-radius:0 0 14px 14px; background:#fafbfc; color:var(--aw-muted); font-size:12px; line-height:1.6; }
.dq-sql-collapse ::v-deep .el-collapse-item__header i.el-icon-document { color:#8a95a3; margin-right:8px; }
.dq-sql-collapse ::v-deep .el-collapse-item__wrap { border:0; border-radius:0 0 14px 14px; }
.dq-sql-collapse ::v-deep .el-collapse-item__content { padding:0; }
.dq-config-version { display:flex; flex-wrap:wrap; gap:8px; padding:12px 24px; background:#f7f9fb; color:var(--aw-soft); font-size:11px; line-height:1.7; }
.dq-config-version code { min-width:0; color:var(--aw-muted); overflow-wrap:anywhere; }
.dq-sql-collapse pre { margin:0; padding:18px 24px; border-top:1px solid #eef1f5; background:#f7f9fb; color:#526276; font:12px/1.8 'SFMono-Regular',Consolas,monospace; white-space:pre-wrap; overflow-wrap:anywhere; }
.dq-error-block { display:flex; align-items:flex-start; gap:10px; padding:24px; color:#b3261e; font-size:13px; line-height:1.8; overflow-wrap:anywhere; white-space:pre-wrap; }
.dq-error-block i { flex:none; margin-top:3px; font-size:18px; }

.dq-composer { flex:none; margin:0 16px 14px; padding:10px 14px; border:1px solid #dbe4ef; border-radius:10px; background:#fff; box-shadow:0 2px 10px rgba(30,69,115,.04); transition:border-color .18s,box-shadow .18s; }
.dq-composer:focus-within { border-color:#89bdf0; box-shadow:0 0 0 3px rgba(0,117,222,.07),0 6px 24px rgba(30,69,115,.08); }
.dq-composer-inner { display:flex; align-items:flex-end; gap:12px; }
.dq-input { flex:1; min-width:0; }
.dq-input ::v-deep .el-textarea__inner { border:none; padding:0; font-size:14px; line-height:1.8; resize:none; color:#31302e; background:transparent; box-shadow:none; }
.dq-input ::v-deep .el-textarea__inner::placeholder { color:#a3adba; }
.dq-input ::v-deep .el-input__count { background:transparent; }
.dq-send-btn { display:inline-flex; align-items:center; justify-content:center; gap:7px; flex:none; min-height:40px; padding:0 16px; border:0; border-radius:9px; background:var(--aw-blue); color:#fff; font-size:13px; cursor:pointer; transition:background .18s,transform .18s,box-shadow .18s; }
.dq-send-btn i { font-size:17px; }
.dq-send-btn:hover:not(:disabled) { background:var(--aw-blue-dark); transform:translateY(-1px); box-shadow:0 4px 12px rgba(0,117,222,.2); }
.dq-send-btn:disabled { opacity:.4; cursor:not-allowed; }
.dq-composer-hint { margin:6px 0 0; color:var(--aw-soft); font-size:11px; line-height:1.6; }

.dq-initial-loading { display:flex; flex:1; min-height:0; flex-direction:column; align-items:center; justify-content:center; gap:14px; border:1px solid var(--aw-border); border-radius:12px; background:#fff; text-align:center; }
.dq-initial-loading > i { font-size:30px; color:var(--aw-blue); }
.dq-initial-loading strong { font-size:16px; color:#31302e; }
.dq-initial-loading > span { color:var(--aw-soft); font-size:12px; }
.dq-loading-preview { display:flex; flex-direction:column; gap:10px; width:230px; margin-top:10px; }
.dq-loading-preview span { height:10px; border-radius:5px; background:#edf2f8; animation:dq-pulse 1.5s ease-in-out infinite; }
.dq-loading-preview span:nth-child(2) { width:80%; animation-delay:.15s; }
.dq-loading-preview span:nth-child(3) { width:60%; animation-delay:.3s; }
.dq-sidebar-tabs { display:flex; flex:none; align-items:center; gap:4px; min-height:58px; padding:10px; border-bottom:1px solid #edf0f4; }
.dq-sidebar-tabs button { display:flex; flex:1; align-items:center; justify-content:center; gap:5px; padding:9px 5px; border:0; border-radius:7px; background:transparent; color:var(--aw-muted); font-size:12px; white-space:nowrap; cursor:pointer; }
.dq-sidebar-tabs button.active { background:#edf5ff; color:var(--aw-blue); font-weight:600; }
.dq-sidebar-tabs .dq-sidebar-close, .dq-sidebar-backdrop { display:none; }
.dq-history-panel { display:flex; flex:1; min-height:0; flex-direction:column; padding:14px 10px; }
.dq-new-query { display:flex; flex:none; align-items:center; justify-content:center; gap:8px; margin:0 2px; padding:11px; border:1px solid #d4e5fa; border-radius:8px; background:#f2f8ff; color:var(--aw-blue); font-size:13px; cursor:pointer; }
.dq-new-query:hover { background:#e6f1ff; }
.dq-history-caption { margin:18px 8px 8px; color:var(--aw-soft); font-size:11px; }
.dq-history-list { flex:1; min-height:0; overflow:auto; }
.dq-history-item { display:flex; align-items:flex-start; gap:9px; width:100%; margin-bottom:4px; padding:12px 10px; border:1px solid transparent; border-radius:8px; background:transparent; color:var(--aw-muted); text-align:left; cursor:pointer; }
.dq-history-item > i { flex:none; margin-top:2px; font-size:15px; color:#8a95a3; }
.dq-history-item > span { min-width:0; }
.dq-history-item strong { display:-webkit-box; overflow:hidden; -webkit-box-orient:vertical; -webkit-line-clamp:2; font-size:12px; font-weight:500; line-height:1.6; overflow-wrap:anywhere; }
.dq-history-item small { display:block; margin-top:6px; color:var(--aw-soft); font-size:11px; }
.dq-history-item:hover, .dq-history-item.active { border-color:#dceafb; background:#f2f8ff; color:var(--aw-blue); }
.dq-history-item:disabled, .dq-new-query:disabled { opacity:.5; cursor:not-allowed; }
.dq-history-empty { display:flex; flex-direction:column; align-items:center; gap:10px; padding:32px 8px; color:var(--aw-soft); font-size:12px; text-align:center; line-height:1.7; }
.dq-history-empty > i { font-size:25px; color:#b2c3d5; }
.dq-history-empty small { font-size:11px; }
.dq-history-more { align-self:center; padding:8px 12px; border:0; border-radius:7px; background:#f2f6fb; color:var(--aw-blue); font-size:12px; cursor:pointer; }
.dq-history-warning, .dq-load-error { display:flex; align-items:center; gap:8px; margin:0; padding:12px 16px; border-radius:10px; background:#fff8e6; color:#8a6d00; font-size:12px; line-height:1.7; }
.dq-history-warning { border-radius:14px 14px 0 0; }
.dq-load-error .el-button { margin-left:auto; }

@media (max-width:1100px) {
}
@media (max-width:900px) {
  .dq-shell { padding:12px; }
  .dq-catalog-panel { position:absolute; z-index:8; top:0; bottom:0; left:0; width:min(310px, calc(100% - 36px)); box-shadow:8px 0 24px rgba(30,69,115,.12); }
  .dq-sidebar-backdrop { position:absolute; z-index:7; inset:0; display:block; width:100%; border:0; border-radius:12px; background:rgba(30,45,65,.18); }
  .dq-sidebar-tabs .dq-sidebar-close { display:block; flex:none; width:26px; }
  .dq-catalog-resizer { display:none; }
}
@media (max-width:600px) {
  .dq-shell { padding:8px; }
  .dq-header { flex-wrap:wrap; padding:10px 12px; margin-bottom:8px; gap:8px; }
  .dq-header-brand > div { display:flex; align-items:baseline; gap:10px; }
  .dq-title { font-size:16px; }
  .dq-logo-icon { width:28px; height:28px; font-size:16px; }
  .dq-subtitle { margin:0; font-size:11px; }
  .dq-header-controls { flex-basis:100%; gap:8px; }
  .dq-proposal-select { min-height:32px; padding:6px 10px; }
  .dq-conversation-toolbar { gap:8px; min-height:48px; padding:8px 10px; }
  .dq-conversation-title > span { display:none; }
  .dq-conversation-actions { gap:4px; }
  .dq-toolbar-btn { padding:7px 8px; }
  .dq-feed { padding:12px 10px; }
  .dq-empty { padding:18px 6px; }
  .dq-empty h2 { font-size:18px; }
  .dq-example-grid { grid-template-columns:1fr; }
  .dq-result-header { padding:18px 16px 14px; }
  .dq-plan-summary { padding:0 16px 14px; }
  .dq-view-toggle { margin:0 16px 16px; }
  .dq-table-section { padding:0 12px 16px; }
  .dq-stat-card { margin:0 16px 18px; padding:22px 18px; }
  .dq-chart-container { min-height:260px; aspect-ratio:1 / 1; width:100%; margin:0 0 16px; }
  .dq-drill-bar { margin:0 16px 16px; }
  .dq-sql-collapse ::v-deep .el-collapse-item__header { padding:10px 16px; }
  .dq-sql-collapse pre, .dq-config-version { padding:14px 16px; }
  .dq-clarification, .dq-error-block, .dq-answer-loading { padding:20px 16px; }
  .dq-composer { margin:0 8px 8px; padding:8px 10px; }
  .dq-composer-inner { gap:8px; }
  .dq-send-btn { padding:0 12px; }
  .dq-send-btn span { display:none; }
}
@media (max-height:650px) {
  .dq-composer-hint { display:none; }
  .dq-empty-icon { display:none; }
}
@media (max-height:500px) {
  .dq-shell { padding:8px 12px; }
  .dq-header { padding:6px 12px; margin-bottom:8px; }
  .dq-logo-icon { width:30px; height:30px; font-size:17px; }
  .dq-subtitle { display:none; }
  .dq-title { font-size:16px; }
  .dq-proposal-select { min-height:32px; padding:6px 10px; }
  .dq-conversation-toolbar { min-height:40px; padding:6px 10px; }
  .dq-feed { padding:8px 10px; }
  .dq-composer { margin:0 8px 8px; padding:8px 10px; }
  .dq-send-btn { min-height:34px; }
}
@media (prefers-reduced-motion:reduce) {
  .dq-shell *, .dq-shell *::after { animation:none !important; transition:none !important; }
}
</style>
