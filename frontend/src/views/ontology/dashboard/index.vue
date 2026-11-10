<template>
  <div class="app-container dashboard-page">
    <!-- 前置依赖提示条：模型未配置、空间为空、数据源为空时分别提示 -->
    <div v-for="tip in activeTips" :key="tip.key" class="tip-bar" :class="tip.level">
      <i :class="tip.icon" />
      <div class="tip-text">
        <strong>{{ tip.title }}</strong>
        <span>{{ tip.desc }}</span>
      </div>
      <el-button v-if="tip.action" size="small" type="primary" plain @click="tip.action.handler">{{ tip.action.label }}</el-button>
    </div>

    <section class="dashboard-hero">
      <div>
        <p class="eyebrow">ONTOLOGY KNOWLEDGE WORKSPACE</p>
        <h1>{{ heroTitle }}</h1>
        <p>{{ heroSubtitle }}</p>
      </div>
      <div class="hero-mark"><i class="el-icon-connection"></i><span>Evidence<br>first</span></div>
    </section>

    <!-- 新用户首步引导：全 0 时显示 -->
    <el-card v-if="allEmpty" shadow="never" class="onboarding-card">
      <div class="onboarding-body">
        <div class="onboarding-icon"><i class="el-icon-rocket"></i></div>
        <div class="onboarding-text">
          <h2>从这里开始</h2>
          <p>构建本体的第一步是建立行业知识空间，把业务资料归集到对应行业。准备好资料后，系统会帮你把数据库结构整理成可审核的本体候选。</p>
        </div>
        <el-button v-hasPermi="['ontology:spaces:add']" type="primary" size="medium" icon="el-icon-plus" @click="openTarget({ route: '/knowledge/spaces', perm: 'ontology:spaces:list' })">创建第一个知识空间</el-button>
      </div>
    </el-card>

    <div class="cards" :class="`cards-${visibleCards.length}`">
      <el-card
        v-for="item in visibleCards"
        :key="item.key"
        shadow="never"
        class="metric-card"
        :class="{ 'is-clickable': canOpen(item) }"
        :tabindex="canOpen(item) ? 0 : null"
        :role="canOpen(item) ? 'link' : null"
        @click.native="openTarget(item)"
        @keyup.enter.native="openTarget(item)"
      >
        <div class="metric-title">
          <i class="metric-icon" :class="item.icon"></i>
          <span class="label">{{ item.label }}</span>
        </div>
        <div class="count">{{ counts[item.key] || 0 }}</div>
        <i v-if="canOpen(item)" class="el-icon-right card-arrow"></i>
      </el-card>
    </div>
    <el-card class="flow" shadow="never">
      <div slot="header" class="section-heading"><span>工作流程</span><span class="section-caption">从资料沉淀到导入平台的主要环节，点击步骤直达对应页面</span></div>
      <el-steps align-center>
        <el-step
          v-for="step in (isEngineer ? workflowSteps : workflowSteps.slice(0, 3))"
          :key="step.title"
          :title="step.title"
          :description="step.description"
          class="flow-step"
          @click.native="goStep(step)"
        />
      </el-steps>
    </el-card>
  </div>
</template>
<script>
import { getDashboard } from '@/api/ontology'
import { mapState } from 'vuex'
export default {
  name: 'OntologyDashboard',
  data: () => ({
    counts: {},
    cards: [
      { key: 'spaces', label: '行业知识空间', icon: 'el-icon-folder-opened', tone: 'blue', route: '/knowledge/spaces', perm: 'ontology:spaces:list' },
      { key: 'assets', label: 'Wiki资产', icon: 'el-icon-notebook-2', tone: 'green', route: '/knowledge/assets', perm: 'ontology:assets:list' },
      { key: 'datasources', label: '数据源', icon: 'el-icon-coin', tone: 'orange', route: '/engineering/datasource', perm: 'ontology:datasources:list' },
      { key: 'analyses', label: '分析任务', icon: 'el-icon-data-analysis', tone: 'purple', route: '/engineering/analysis', perm: 'ontology:analyses:list' },
      { key: 'proposals', label: '配置草案', icon: 'el-icon-document-checked', tone: 'blue', route: '/engineering/proposal', perm: 'ontology:proposals:list' }
    ],
    workflowSteps: [
      { title: '沉淀 Wiki 资产', description: '行业词汇、表说明和业务规则', key: 'spaces', route: '/knowledge/spaces', perm: 'ontology:spaces:list' },
      { title: '登记数据源', description: 'Hive / PostgreSQL / MySQL', key: 'datasources', route: '/engineering/datasource', perm: 'ontology:datasources:list' },
      { title: '创建分析任务', description: '扫描结构并整理候选', key: 'analyses', route: '/engineering/analysis', perm: 'ontology:analyses:list' },
      { title: '修正并完成草案', description: '本体工程师修正配置后标记完成，即可使用或导入', key: 'proposals', route: '/engineering/proposal', perm: 'ontology:proposals:list' }
    ]
  }),
  computed: {
    ...mapState({
      modelEnabled: state => state.model.enabled
    }),
    /** 是否工程师视角：有草案编辑权限视为工程师，否则为业务人员 */
    isEngineer() { return this.$auth.hasPermi('ontology:proposals:edit') },
    /** 业务人员只看资料、问答相关卡片；工程师看全部 */
    visibleCards() {
      if (this.isEngineer) return this.cards
      return this.cards.filter(item => ['spaces', 'assets', 'analyses'].includes(item.key))
    },
    num(key) { return Number(this.counts[key] || 0) },
    allEmpty() {
      return ['spaces', 'assets', 'datasources', 'analyses', 'proposals'].every(k => Number(this.counts[k] || 0) === 0)
    },
    heroTitle() { return this.allEmpty ? '你好，开始构建你的本体资产。' : '继续完善你的本体资产。' },
    heroSubtitle() { return this.allEmpty ? '从行业知识和数据库结构出发，协作沉淀对象、逻辑与动作配置。' : '点击下方流程步骤，直达对应页面。' },
    /** 前置依赖提示：模型、空间、数据源 */
    activeTips() {
      const tips = []
      if (!this.modelEnabled) {
        tips.push({
          key: 'model', level: 'warn', icon: 'el-icon-warning-outline',
          title: '尚未配置大模型',
          desc: 'Wiki 整理、知识问答和分析任务都依赖模型，请联系管理员完成模型配置。'
        })
      }
      if (this.counts.spaces != null && Number(this.counts.spaces) === 0) {
        tips.push({
          key: 'space', level: 'info', icon: 'el-icon-info',
          title: '还没有行业知识空间',
          desc: '分析任务必须绑定行业知识空间，先创建一个空间并把资料归入。',
          action: this.$auth.hasPermi('ontology:spaces:list') ? { label: '去创建', handler: () => this.$router.push('/knowledge/spaces') } : null
        })
      }
      if (Number(this.counts.spaces || 0) > 0 && Number(this.counts.datasources || 0) === 0) {
        tips.push({
          key: 'datasource', level: 'info', icon: 'el-icon-info',
          title: '还没有数据源',
          desc: '分析任务需要读取数据库结构，先登记一个数据源。',
          action: this.$auth.hasPermi('ontology:datasources:list') ? { label: '去登记', handler: () => this.$router.push('/engineering/datasource') } : null
        })
      }
      return tips
    }
  },
  created() {
    getDashboard().then(res => { this.counts = res.data || {} })
    // 模型状态已在登录后全局拉取，这里兜底刷新一次确保最新
    this.$store.dispatch('model/fetchStatus').catch(() => {})
  },
  methods: {
    canOpen(item) { return !!(item.route && this.$auth.hasPermi(item.perm)) },
    openTarget(item) { if (this.canOpen(item)) this.$router.push({ path: item.route }) },
    goStep(step) { if (step.route && this.$auth.hasPermi(step.perm)) this.$router.push({ path: step.route }) }
  }
}
</script>
<style scoped>
.dashboard-page { max-width: 1440px; margin: 0 auto; }
.tip-bar { display:flex; align-items:center; gap:14px; padding:14px 18px; margin-bottom:14px; border-radius:10px; border:1px solid; }
.tip-bar.warn { background:#fff8e6; border-color:#f5d97e; color:#8a6d00; }
.tip-bar.info { background:#f2f9ff; border-color:#cfe2fb; color:#1a6bb8; }
.tip-bar i { font-size:20px; flex-shrink:0; }
.tip-text { flex:1; display:flex; flex-direction:column; gap:2px; }
.tip-text strong { font-size:14px; }
.tip-text span { font-size:13px; opacity:.85; }
.dashboard-hero { display:flex; align-items:center; justify-content:space-between; padding:30px 34px; margin-bottom:20px; border:1px solid rgba(0,0,0,.08); border-radius:16px; background:#fff; box-shadow:rgba(0,0,0,.04) 0 4px 18px; }
.eyebrow { margin:0 0 12px; color:#0075de; font-size:11px; font-weight:700; letter-spacing:.14em; }
.dashboard-hero h1 { margin:0; color:#31302e; font-size:30px; letter-spacing:-.8px; line-height:1.2; }
.dashboard-hero p:last-child { margin:12px 0 0; color:#615d59; font-size:14px; }
.hero-mark { display:flex; align-items:center; gap:10px; color:#0075de; font-size:12px; line-height:1.2; text-transform:uppercase; letter-spacing:.1em; }
.hero-mark i { font-size:38px; }
.onboarding-card { margin-bottom:20px; border:1px dashed #cfe2fb; background:#fafcff; }
.onboarding-body { display:flex; align-items:center; gap:20px; }
.onboarding-icon { display:flex; align-items:center; justify-content:center; width:56px; height:56px; border-radius:14px; background:#eaf4ff; color:#0075de; font-size:28px; flex-shrink:0; }
.onboarding-text { flex:1; }
.onboarding-text h2 { margin:0 0 6px; color:#31302e; font-size:18px; }
.onboarding-text p { margin:0; color:#615d59; font-size:14px; line-height:1.6; }
.cards { display:grid; gap:12px; margin-top:0; }
.cards-3 { grid-template-columns:repeat(3, minmax(0, 1fr)); }
.cards-5 { grid-template-columns:repeat(5, minmax(0, 1fr)); }
.metric-card { position:relative; min-width:0; min-height:112px; border-radius:12px; transition:transform .18s ease, box-shadow .18s ease, border-color .18s ease; }
.metric-card ::v-deep .el-card__body { display:flex; min-height:70px; flex-direction:column; }
.metric-card.is-clickable { cursor:pointer; }
.metric-card.is-clickable:hover { transform:translateY(-2px); border-color:#cfe2fb; box-shadow:0 8px 20px rgba(0,117,222,.12); }
.metric-card.is-clickable:focus { outline:2px solid #0075de; outline-offset:2px; }
.card-arrow { position:absolute; top:16px; right:16px; color:#b8c2cc; font-size:14px; opacity:0; transition:opacity .18s ease, color .18s ease; }
.metric-card.is-clickable:hover .card-arrow { opacity:1; color:#0075de; }
.metric-title { display:flex; align-items:center; gap:9px; min-width:0; padding-right:16px; }
.metric-icon { flex:0 0 auto; color:#0075de; font-size:16px; }
.label { overflow:hidden; color:#615d59; font-size:13px; font-weight:500; text-overflow:ellipsis; white-space:nowrap; }
.count { margin-top:auto; padding-top:12px; color:#31302e; font-size:30px; font-weight:700; font-variant-numeric:tabular-nums; letter-spacing:-1px; line-height:1; }
.flow { margin-top:20px; }
/* 工作流步骤可点击，状态不随数据变化 */
.flow-step { cursor:pointer; }
.flow-step ::v-deep .el-step__title { cursor:pointer; }
.flow ::v-deep .el-step__head,
.flow ::v-deep .el-step__head.is-process,
.flow ::v-deep .el-step__head.is-wait,
.flow ::v-deep .el-step__head.is-finish { color:#606266; border-color:#c0c4cc; }
.flow ::v-deep .el-step__title,
.flow ::v-deep .el-step__title.is-process,
.flow ::v-deep .el-step__title.is-wait,
.flow ::v-deep .el-step__title.is-finish { color:#303133; font-weight:400; }
.flow ::v-deep .el-step__description,
.flow ::v-deep .el-step__description.is-process,
.flow ::v-deep .el-step__description.is-wait,
.flow ::v-deep .el-step__description.is-finish { color:#606266; }
.section-heading { display:flex; align-items:center; justify-content:space-between; color:#31302e; font-weight:700; }.section-caption { color:#a39e98; font-size:12px; font-weight:400; }
@media (max-width: 900px) { .cards-3, .cards-5 { grid-template-columns:repeat(3, minmax(0, 1fr)); } }
@media (max-width: 640px) { .dashboard-hero { padding:24px; }.dashboard-hero h1 { font-size:24px; }.hero-mark { display:none; }.onboarding-body { flex-direction:column; text-align:center; }.cards-3, .cards-5 { grid-template-columns:repeat(2, minmax(0, 1fr)); gap:10px; } }
</style>
