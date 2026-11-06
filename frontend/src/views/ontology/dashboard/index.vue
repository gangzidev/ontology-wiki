<template>
  <div class="app-container dashboard-page">
    <section class="dashboard-hero">
      <div>
        <p class="eyebrow">ONTOLOGY KNOWLEDGE WORKSPACE</p>
        <h1>你好，开始构建你的本体资产。</h1>
        <p>从行业知识和数据库结构出发，协作沉淀对象、逻辑与动作配置。</p>
      </div>
      <div class="hero-actions"><el-button v-hasPermi="['ontology:llm:list']" icon="el-icon-setting" @click="goModelSettings">模型配置</el-button><div class="hero-mark"><i class="el-icon-connection"></i><span>Evidence<br>first</span></div></div>
    </section>
    <el-row :gutter="16" class="cards">
      <el-col v-for="item in cards" :key="item.key" :xs="12" :sm="8" :md="4">
        <el-card
          shadow="never"
          class="metric-card"
          :class="{ 'is-clickable': canOpen(item) }"
          :tabindex="canOpen(item) ? 0 : null"
          :role="canOpen(item) ? 'link' : null"
          @click.native="openTarget(item)"
          @keyup.enter.native="openTarget(item)"
        ><div class="metric-icon" :class="item.tone"><i :class="item.icon"></i></div><div class="label">{{ item.label }}</div><div class="count">{{ counts[item.key] || 0 }}</div><i v-if="canOpen(item)" class="el-icon-right card-arrow"></i></el-card>
      </el-col>
    </el-row>
    <el-card class="flow" shadow="never">
      <div slot="header" class="section-heading"><span>工作流程</span><span class="section-caption">从资料沉淀到导入平台的主要环节</span></div>
      <el-steps align-center>
        <el-step v-for="step in workflowSteps" :key="step.title" :title="step.title" :description="step.description" status="wait" />
      </el-steps>
    </el-card>
  </div>
</template>
<script>
import { getDashboard } from '@/api/ontology'
export default {
  name: 'OntologyDashboard',
  data: () => ({ counts: {}, cards: [
    { key: 'spaces', label: '行业知识空间', icon: 'el-icon-folder-opened', tone: 'blue', route: '/knowledge/spaces', perm: 'ontology:spaces:list' },
    { key: 'assets', label: 'Wiki资产', icon: 'el-icon-notebook-2', tone: 'green', route: '/knowledge/assets', perm: 'ontology:assets:list' },
    { key: 'datasources', label: '数据源', icon: 'el-icon-coin', tone: 'orange', route: '/engineering/datasource', perm: 'ontology:datasources:list' },
    { key: 'analyses', label: '分析任务', icon: 'el-icon-data-analysis', tone: 'purple', route: '/engineering/analysis', perm: 'ontology:analyses:list' },
    { key: 'proposals', label: '配置草案', icon: 'el-icon-document-checked', tone: 'blue', route: '/engineering/proposal', perm: 'ontology:proposals:list' },
    { key: 'imports', label: '导入记录', icon: 'el-icon-upload2', tone: 'green', route: '/engineering/import', perm: 'ontology:imports:list' }
  ], workflowSteps: [
    { title: '沉淀 Wiki 资产', description: '行业词汇、表说明和业务规则' },
    { title: '登记数据源', description: 'Hive / PostgreSQL / MySQL' },
    { title: '创建分析任务', description: '扫描结构并整理候选' },
    { title: '生成配置草案', description: '对象、逻辑、动作供人工审核' },
    { title: '导入本体平台', description: '在本体平台测试和发布' }
  ] }),
  created() { getDashboard().then(res => { this.counts = res.data || {} }) },
  methods: {
    /** 当前用户是否有权访问该指标对应的功能页；无权限时卡片保持静态，避免跳转后 404 */
    canOpen(item) { return !!(item.route && this.$auth.hasPermi(item.perm)) },
    /** 整卡点击/回车跳转，数字为 0 也允许进入，便于在空列表页发起新增 */
    openTarget(item) { if (this.canOpen(item)) this.$router.push({ path: item.route }) },
    /** 模型配置统一收敛到「模型配置」页面，工作台不再维护第二套设置入口 */
    goModelSettings() { this.$router.push({ path: '/ai/settings' }) }
  }
}
</script>
<style scoped>
.dashboard-page { max-width: 1440px; margin: 0 auto; }
.dashboard-hero { display:flex; align-items:center; justify-content:space-between; padding:30px 34px; margin-bottom:20px; border:1px solid rgba(0,0,0,.08); border-radius:16px; background:#fff; box-shadow:rgba(0,0,0,.04) 0 4px 18px; }
.eyebrow { margin:0 0 12px; color:#0075de; font-size:11px; font-weight:700; letter-spacing:.14em; }
.dashboard-hero h1 { margin:0; color:#31302e; font-size:30px; letter-spacing:-.8px; line-height:1.2; }
.dashboard-hero p:last-child { margin:12px 0 0; color:#615d59; font-size:14px; }
.hero-mark { display:flex; align-items:center; gap:10px; color:#0075de; font-size:12px; line-height:1.2; text-transform:uppercase; letter-spacing:.1em; }
.hero-actions { display:flex; align-items:center; gap:22px; }
.hero-mark i { font-size:38px; }
.cards { margin-top:0; }
.metric-card { position:relative; min-height:148px; transition:transform .18s ease, box-shadow .18s ease, border-color .18s ease; }
.metric-card.is-clickable { cursor:pointer; }
.metric-card.is-clickable:hover { transform:translateY(-2px); border-color:#cfe2fb; box-shadow:0 8px 20px rgba(0,117,222,.12); }
.metric-card.is-clickable:focus { outline:2px solid #0075de; outline-offset:2px; }
.card-arrow { position:absolute; top:16px; right:16px; color:#b8c2cc; font-size:14px; opacity:0; transition:opacity .18s ease, color .18s ease; }
.metric-card.is-clickable:hover .card-arrow { opacity:1; color:#0075de; }
.metric-icon { display:flex; align-items:center; justify-content:center; width:34px; height:34px; margin-bottom:18px; border-radius:10px; font-size:17px; }
.metric-icon.blue { color:#0075de; background:#f2f9ff; }.metric-icon.green { color:#2a9d99; background:#edf9f7; }.metric-icon.orange { color:#dd5b00; background:#fff4e9; }.metric-icon.purple { color:#6a48a8; background:#f4effb; }
.label { color:#615d59; font-size:13px; }.count { margin-top:8px; color:#31302e; font-size:30px; font-weight:700; letter-spacing:-1px; }
.flow { margin-top:20px; }
/* 所有步骤统一为中性色，不区分完成状态：分析任务可并发多条，进度态无参考意义 */
.flow ::v-deep .el-step__head { color:#c0c4cc; border-color:#c0c4cc; }
.flow ::v-deep .el-step__title { color:#615d59; font-weight:600; }
.flow ::v-deep .el-step__description { color:#a39e98; }
.section-heading { display:flex; align-items:center; justify-content:space-between; color:#31302e; font-weight:700; }.section-caption { color:#a39e98; font-size:12px; font-weight:400; }
@media (max-width: 640px) { .dashboard-hero { padding:24px; }.dashboard-hero h1 { font-size:24px; }.hero-mark { display:none; }.hero-actions { gap:0; } }
</style>
