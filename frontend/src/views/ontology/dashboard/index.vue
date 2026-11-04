<template>
  <div class="app-container dashboard-page">
    <section class="dashboard-hero">
      <div>
        <p class="eyebrow">ONTOLOGY KNOWLEDGE WORKSPACE</p>
        <h1>你好，开始构建你的本体资产。</h1>
        <p>从行业知识和数据库结构出发，协作沉淀对象、逻辑与动作配置。</p>
      </div>
      <div class="hero-actions"><el-button v-hasPermi="['ontology:wiki:settings']" icon="el-icon-setting" @click="openSettings">大模型设置</el-button><div class="hero-mark"><i class="el-icon-connection"></i><span>Evidence<br>first</span></div></div>
    </section>
    <el-row :gutter="16" class="cards">
      <el-col v-for="item in cards" :key="item.key" :xs="12" :sm="8" :md="4">
        <el-card shadow="never" class="metric-card"><div class="metric-icon" :class="item.tone"><i :class="item.icon"></i></div><div class="label">{{ item.label }}</div><div class="count">{{ counts[item.key] || 0 }}</div></el-card>
      </el-col>
    </el-row>
    <el-card class="flow" shadow="never">
      <div slot="header" class="section-heading"><span>工作流程</span><span class="section-caption">按记录状态实时显示，不预置进度</span></div>
      <el-steps align-center>
        <el-step v-for="step in workflowSteps" :key="step.title" :title="step.title" :description="step.description" :status="step.status" />
      </el-steps>
      <div v-if="!hasWorkflowData" class="empty-flow"><i class="el-icon-info"></i> 当前还没有流程记录，所有步骤保持待开始。</div>
    </el-card>
    <el-dialog title="大模型设置" :visible.sync="settingsOpen" width="560px" append-to-body>
      <el-alert title="配置保存在本系统数据库，API Key 只写入加密密文；留空表示沿用已保存密钥。" type="info" :closable="false" style="margin-bottom:18px" />
      <el-form label-width="110px">
        <el-form-item label="启用模型"><el-switch v-model="settings.enabled" /></el-form-item>
        <el-form-item label="API 地址"><el-input v-model="settings.apiBaseUrl" placeholder="https://api.example.com/v1" /></el-form-item>
        <el-form-item label="模型名称"><el-input v-model="settings.model" placeholder="例如 gpt-4o-mini" /></el-form-item>
        <el-form-item label="API Key"><el-input v-model="settings.apiKey" type="password" show-password :placeholder="settings.apiKeyConfigured ? '已配置，留空保持不变' : '请输入 API Key'" /></el-form-item>
        <el-form-item v-if="settings.apiKeyConfigured"><el-checkbox v-model="settings.clearApiKey">清除已保存 API Key</el-checkbox></el-form-item>
      </el-form>
      <span slot="footer"><el-button :loading="settingsTesting" :disabled="settingsSaving" @click="testSettings">保存并测试</el-button><el-button type="primary" :loading="settingsSaving" :disabled="settingsTesting" @click="saveSettings">保存设置</el-button><el-button @click="settingsOpen=false">取消</el-button></span>
    </el-dialog>
  </div>
</template>
<script>
import { getDashboard, getLlmSettings, saveLlmSettings, testLlmSettings } from '@/api/ontology'
export default {
  name: 'OntologyDashboard',
  data: () => ({ counts: {}, settingsOpen: false, settingsSaving: false, settingsTesting: false, settings: { enabled: false, apiBaseUrl: '', model: '', apiKey: '', apiKeyConfigured: false, clearApiKey: false }, cards: [
    { key: 'spaces', label: '行业知识空间', icon: 'el-icon-folder-opened', tone: 'blue' },
    { key: 'assets', label: 'Wiki资产', icon: 'el-icon-notebook-2', tone: 'green' },
    { key: 'datasources', label: '数据源', icon: 'el-icon-coin', tone: 'orange' },
    { key: 'analyses', label: '分析任务', icon: 'el-icon-data-analysis', tone: 'purple' },
    { key: 'proposals', label: '配置草案', icon: 'el-icon-document-checked', tone: 'blue' },
    { key: 'imports', label: '导入记录', icon: 'el-icon-upload2', tone: 'green' }
  ] }),
  computed: {
    hasWorkflowData() { return Object.keys(this.counts).some(key => Number(this.counts[key]) > 0) },
    workflowSteps() {
      const ready = [
        Number(this.counts.assets || 0) + Number(this.counts.spaces || 0) > 0,
        Number(this.counts.datasources || 0) > 0,
        Number(this.counts.analyses || 0) > 0,
        Number(this.counts.proposals || 0) > 0,
        Number(this.counts.imports || 0) > 0
      ]
      const current = ready.findIndex(item => !item)
      return [
        { title: '沉淀 Wiki 资产', description: '行业词汇、表说明和业务规则', status: !this.hasWorkflowData ? 'wait' : (ready[0] ? 'finish' : (current === 0 ? 'process' : 'wait')) },
        { title: '登记数据源', description: 'Hive / PostgreSQL / MySQL', status: !this.hasWorkflowData ? 'wait' : (ready[1] ? 'finish' : (current === 1 ? 'process' : 'wait')) },
        { title: '创建分析任务', description: '扫描结构并整理候选', status: !this.hasWorkflowData ? 'wait' : (ready[2] ? 'finish' : (current === 2 ? 'process' : 'wait')) },
        { title: '生成配置草案', description: '对象、逻辑、动作供人工审核', status: !this.hasWorkflowData ? 'wait' : (ready[3] ? 'finish' : (current === 3 ? 'process' : 'wait')) },
        { title: '导入本体平台', description: '在本体平台测试和发布', status: !this.hasWorkflowData ? 'wait' : (ready[4] ? 'finish' : (current === 4 ? 'process' : 'wait')) }
      ]
    }
  },
  created() { getDashboard().then(res => { this.counts = res.data || {} }) },
  methods: {
    openSettings() { this.settingsOpen = true; this.settings.apiKey = ''; this.settings.clearApiKey = false; getLlmSettings().then(({ data }) => { this.settings = Object.assign(this.settings, data || {}, { apiKey: '', clearApiKey: false }) }).catch(() => {}) },
    saveSettings() { this.settingsSaving = true; return saveLlmSettings(this.settings).then(({ data }) => { this.settings = Object.assign(this.settings, data || {}, { apiKey: '', clearApiKey: false }); this.$modal.msgSuccess('大模型设置已保存'); return true }).catch(() => false).finally(() => { this.settingsSaving = false }) },
    testSettings() { this.settingsTesting = true; this.settingsSaving = true; saveLlmSettings(this.settings).then(({ data }) => { this.settings = Object.assign(this.settings, data || {}, { apiKey: '', clearApiKey: false }); this.$modal.msgSuccess('大模型设置已保存'); return testLlmSettings() }).then(({ data }) => this.$modal.msgSuccess((data && data.message) || '模型 API 调用成功')).catch(() => {}).finally(() => { this.settingsTesting = false; this.settingsSaving = false }) }
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
.metric-card { min-height:148px; }
.metric-icon { display:flex; align-items:center; justify-content:center; width:34px; height:34px; margin-bottom:18px; border-radius:10px; font-size:17px; }
.metric-icon.blue { color:#0075de; background:#f2f9ff; }.metric-icon.green { color:#2a9d99; background:#edf9f7; }.metric-icon.orange { color:#dd5b00; background:#fff4e9; }.metric-icon.purple { color:#6a48a8; background:#f4effb; }
.label { color:#615d59; font-size:13px; }.count { margin-top:8px; color:#31302e; font-size:30px; font-weight:700; letter-spacing:-1px; }
.flow { margin-top:20px; }
.section-heading { display:flex; align-items:center; justify-content:space-between; color:#31302e; font-weight:700; }.section-caption { color:#a39e98; font-size:12px; font-weight:400; }
.empty-flow { margin-top:14px; color:#9a9da3; font-size:12px; text-align:center; }.empty-flow i { margin-right:5px; }
@media (max-width: 640px) { .dashboard-hero { padding:24px; }.dashboard-hero h1 { font-size:24px; }.hero-mark { display:none; }.hero-actions { gap:0; } }
</style>
