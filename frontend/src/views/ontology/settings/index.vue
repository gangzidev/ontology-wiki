<template>
  <div class="settings-page">
    <!-- ── 页面标题区 ── -->
    <header class="page-head">
      <div class="head-text">
        <p class="eyebrow">MODEL CONFIGURATION</p>
        <h1>模型配置</h1>
        <p class="lede">
          为不同业务场景分配模型。每个场景取最新一条已启用的配置；未匹配到场景时回退到
          <span class="mono">ALL</span>。
        </p>
      </div>
      <el-button class="btn-primary" icon="el-icon-plus" @click="openAdd">新增配置</el-button>
    </header>

    <!-- ── 场景说明 ── -->
    <section class="scene-strip">
      <div v-for="scene in scenes" :key="scene.value" class="scene-item">
        <span class="pill">{{ scene.value }}</span>
        <div class="scene-text">
          <strong>{{ scene.label }}</strong>
          <span>{{ scene.desc }}</span>
        </div>
      </div>
    </section>

    <!-- ── 配置列表 ── -->
    <section v-if="loading" class="state-block">
      <i class="el-icon-loading" />
      <span>正在读取模型配置…</span>
    </section>

    <section v-else-if="!configs.length" class="empty-block">
      <div class="empty-icon"><i class="el-icon-cpu" /></div>
      <h3>还没有模型配置</h3>
      <p>添加一个模型后，分析任务、Wiki 整理和知识问答才能调用大模型。</p>
      <el-button class="btn-primary" icon="el-icon-plus" @click="openAdd">新增第一个配置</el-button>
    </section>

    <section v-else class="config-grid">
      <article v-for="cfg in configs" :key="cfg.id" class="config-card">
        <div class="card-head">
          <div class="card-title">
            <h3>{{ cfg.setting_name }}</h3>
            <p class="card-model mono">{{ cfg.model }}</p>
          </div>
          <span class="status-pill" :class="cfg.enabled ? 'on' : 'off'">
            {{ cfg.enabled ? '已启用' : '已停用' }}
          </span>
        </div>

        <div class="tag-row">
          <span class="pill">{{ providerLabel(cfg.provider_type) }}</span>
          <span class="pill accent">{{ sceneLabel(cfg.usage_scene) }}</span>
        </div>

        <dl class="meta-list">
          <div class="meta-row">
            <dt>接口地址</dt>
            <dd class="mono break">{{ cfg.api_base_url || '—' }}</dd>
          </div>
          <div class="meta-row">
            <dt>API Key</dt>
            <dd>{{ cfg.hasApiKey ? '已配置' : '未配置' }}</dd>
          </div>
          <div class="meta-row">
            <dt>最近更新</dt>
            <dd>{{ cfg.update_time || '—' }}</dd>
          </div>
        </dl>

        <div v-if="testResults[cfg.id]" class="test-result" :class="testResults[cfg.id].connected ? 'ok' : 'fail'">
          <i :class="testResults[cfg.id].connected ? 'el-icon-success' : 'el-icon-error'" />
          <span v-if="testResults[cfg.id].connected">连接成功，模型：{{ testResults[cfg.id].model || '—' }}</span>
          <span v-else>连接失败：{{ testResults[cfg.id].message || '请检查网络和地址' }}</span>
        </div>

        <footer class="card-foot">
          <el-button class="btn-ghost" size="small" icon="el-icon-connection"
                     :loading="testingId === cfg.id" @click="testConfig(cfg)">测试连接</el-button>
          <el-button class="btn-ghost" size="small" icon="el-icon-edit" @click="openEdit(cfg)">编辑</el-button>
          <el-button class="btn-danger" size="small" icon="el-icon-delete" @click="removeConfig(cfg)">删除</el-button>
        </footer>
      </article>

      <button class="add-card" type="button" @click="openAdd">
        <i class="el-icon-plus" />
        <span>新增模型配置</span>
      </button>
    </section>

    <!-- ── 新增 / 编辑弹窗 ── -->
    <el-dialog :title="form.id ? '编辑模型配置' : '新增模型配置'"
               :visible.sync="dialogOpen" width="560px" append-to-body custom-class="notion-dialog"
               @close="resetForm">
      <el-form :model="form" label-position="top" size="medium">
        <el-form-item label="配置名称">
          <el-input v-model="form.settingName" placeholder="如：生产环境 GPT-4o" maxlength="100" />
        </el-form-item>

        <el-form-item label="使用场景">
          <el-select v-model="form.usageScene" style="width:100%">
            <el-option v-for="scene in scenes" :key="scene.value"
                       :value="scene.value" :label="scene.optionLabel" />
          </el-select>
          <p class="field-hint">{{ currentSceneHint }}</p>
        </el-form-item>

        <el-form-item label="接入类型">
          <el-select v-model="form.providerType" style="width:100%" @change="onProviderChange">
            <el-option value="OPENAI_COMPAT" label="API Key 模式（OpenAI / DeepSeek / Claude API）" />
            <el-option value="OLLAMA" label="Ollama 本地模式（无需 API Key）" />
            <el-option value="LOCAL_COMPAT" label="本地 OpenAI 兼容模式（LM Studio / vLLM）" />
          </el-select>
        </el-form-item>

        <el-form-item label="接口地址">
          <el-input v-model="form.apiBaseUrl" placeholder="如：https://api.openai.com/v1" maxlength="1000" />
        </el-form-item>

        <el-form-item label="模型名称">
          <el-input v-model="form.model" placeholder="如：gpt-4o / deepseek-chat / qwen2.5:7b" maxlength="200" />
        </el-form-item>

        <el-form-item v-if="form.providerType !== 'OLLAMA'" label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password maxlength="4096"
                    :placeholder="form.id ? '留空则保留已保存的密钥' : '请输入 API Key'" />
          <el-checkbox v-if="form.id" v-model="form.clearApiKey" class="clear-key">清除已保存的 API Key</el-checkbox>
        </el-form-item>

        <el-form-item label="启用状态">
          <div class="switch-row">
            <el-switch v-model="form.enabled" />
            <span class="switch-hint">{{ form.enabled ? '该配置会参与模型路由' : '停用后不会参与任何场景路由' }}</span>
          </div>
        </el-form-item>
      </el-form>

      <div slot="footer">
        <el-button class="btn-ghost" @click="dialogOpen = false">取消</el-button>
        <el-button class="btn-primary" :loading="saving" @click="saveConfig">保存配置</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listLlmConfigs, addLlmConfig, updateLlmConfig, deleteLlmConfig, testLlmConfig } from '@/api/ontology'

/** 场景定义：value 与后端 usage_scene 取值保持一致 */
const SCENES = [
  { value: 'ALL', label: '通用兜底', desc: '其他场景未匹配时使用', optionLabel: 'ALL — 通用兜底（未匹配场景时使用）' },
  { value: 'ANALYSIS', label: '分析语义补充', desc: '执行分析与答案优化，建议高能力模型', optionLabel: 'ANALYSIS — 分析语义补充（建议高能力模型）' },
  { value: 'WIKI', label: 'Wiki 整理', desc: '把资料整理为 Wiki 页面', optionLabel: 'WIKI — Wiki 资料整理' },
  { value: 'FAST', label: '快速问答', desc: '普通知识问答，建议轻量模型', optionLabel: 'FAST — 快速问答（建议轻量模型）' }
]

function emptyForm() {
  return {
    id: null,
    settingName: '',
    providerType: 'OPENAI_COMPAT',
    apiBaseUrl: '',
    model: '',
    apiKey: '',
    clearApiKey: false,
    usageScene: 'ALL',
    enabled: true
  }
}

export default {
  name: 'OntologyModelSettings',
  data() {
    return {
      scenes: SCENES,
      loading: false,
      saving: false,
      configs: [],
      testingId: null,
      testResults: {},
      dialogOpen: false,
      form: emptyForm()
    }
  },
  computed: {
    /** 当前所选场景的说明，帮助填写者理解该配置会被谁使用 */
    currentSceneHint() {
      const scene = SCENES.find(item => item.value === this.form.usageScene)
      return scene ? `${scene.label}：${scene.desc}。同一场景存在多条时，仅最新一条生效。` : ''
    }
  },
  created() {
    this.loadConfigs()
  },
  methods: {
    loadConfigs() {
      this.loading = true
      listLlmConfigs()
        .then(({ data }) => { this.configs = data || [] })
        .catch(() => {})
        .finally(() => { this.loading = false })
    },
    openAdd() {
      this.form = emptyForm()
      this.dialogOpen = true
    },
    openEdit(cfg) {
      this.form = {
        id: cfg.id,
        settingName: cfg.setting_name || '',
        providerType: cfg.provider_type || 'OPENAI_COMPAT',
        apiBaseUrl: cfg.api_base_url || '',
        model: cfg.model || '',
        apiKey: '',
        clearApiKey: false,
        usageScene: cfg.usage_scene || 'ALL',
        enabled: !!cfg.enabled
      }
      this.dialogOpen = true
    },
    resetForm() {
      this.form = emptyForm()
    },
    onProviderChange(type) {
      // 选择 Ollama 且地址为空时给出默认值，减少手工输入
      if (type === 'OLLAMA' && !this.form.apiBaseUrl) {
        this.form.apiBaseUrl = 'http://localhost:11434'
      }
    },
    saveConfig() {
      if (!this.form.settingName.trim()) return this.$modal.msgError('请输入配置名称')
      if (!this.form.apiBaseUrl.trim()) return this.$modal.msgError('请输入接口地址')
      if (!this.form.model.trim()) return this.$modal.msgError('请输入模型名称')
      this.saving = true
      const request = this.form.id
        ? updateLlmConfig(this.form.id, this.form)
        : addLlmConfig(this.form)
      request.then(() => {
        this.$modal.msgSuccess('保存成功')
        this.dialogOpen = false
        this.loadConfigs()
      }).catch(err => {
        this.$modal.msgError((err && err.msg) || '保存失败')
      }).finally(() => { this.saving = false })
    },
    removeConfig(cfg) {
      this.$modal.confirm(`删除后该配置不再参与模型路由，确认删除「${cfg.setting_name}」？`).then(() => {
        deleteLlmConfig(cfg.id).then(() => {
          this.$modal.msgSuccess('已删除')
          this.loadConfigs()
        })
      }).catch(() => {})
    },
    testConfig(cfg) {
      this.testingId = cfg.id
      this.$set(this.testResults, cfg.id, null)
      testLlmConfig(cfg.id)
        .then(({ data }) => this.$set(this.testResults, cfg.id, data || { connected: false }))
        .catch(() => this.$set(this.testResults, cfg.id, { connected: false, message: '请求失败，请检查网络和地址' }))
        .finally(() => { this.testingId = null })
    },
    providerLabel(value) {
      return { OPENAI_COMPAT: 'API Key', OLLAMA: 'Ollama', LOCAL_COMPAT: '本地兼容' }[value] || value
    },
    sceneLabel(value) {
      const scene = SCENES.find(item => item.value === value)
      return scene ? `${scene.value} · ${scene.label}` : value
    }
  }
}
</script>

<style scoped>
/* ── 设计变量：遵循 Notion 风格设计规范 ── */
.settings-page {
  --ink: rgba(0, 0, 0, 0.95);
  --ink-2: #615d59;
  --ink-3: #a39e98;
  --line: rgba(0, 0, 0, 0.1);
  --warm: #f6f5f4;
  --blue: #0075de;
  --blue-active: #005bab;
  --blue-bg: #f2f9ff;
  --blue-text: #097fe8;
  --card-shadow: rgba(0, 0, 0, 0.04) 0 4px 18px, rgba(0, 0, 0, 0.027) 0 2px 7.8px, rgba(0, 0, 0, 0.02) 0 0.8px 2.9px;
  max-width: 1180px;
  margin: 0 auto;
  padding: 28px 24px 56px;
  color: var(--ink);
  font-family: Inter, -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
}

/* ── 页头 ── */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--line);
}
.eyebrow {
  margin: 0 0 10px;
  color: var(--blue);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
}
.page-head h1 {
  margin: 0;
  font-size: 32px;
  line-height: 1.1;
  letter-spacing: -1.2px;
  font-weight: 700;
}
.lede {
  max-width: 620px;
  margin: 10px 0 0;
  color: var(--ink-2);
  font-size: 14px;
  line-height: 1.6;
}
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }

/* ── 场景说明条 ── */
.scene-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
  margin: 22px 0 26px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--warm);
}
.scene-item { display: flex; gap: 10px; align-items: flex-start; }
.scene-text { display: flex; flex-direction: column; gap: 2px; }
.scene-text strong { font-size: 13px; font-weight: 600; }
.scene-text span { color: var(--ink-2); font-size: 12px; line-height: 1.5; }

/* ── 药丸徽章 ── */
.pill {
  display: inline-flex;
  align-items: center;
  padding: 3px 9px;
  border-radius: 9999px;
  background: var(--blue-bg);
  color: var(--blue-text);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
  white-space: nowrap;
}
.pill.accent { background: #eef6ee; color: #1a7a3a; }

/* ── 状态块 ── */
.state-block {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 64px 0;
  color: var(--ink-3);
  font-size: 13px;
}
.empty-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 64px 24px;
  border: 1px dashed var(--line);
  border-radius: 12px;
  background: #fff;
  text-align: center;
}
.empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: var(--warm);
  color: var(--ink-3);
  font-size: 20px;
}
.empty-block h3 { margin: 4px 0 0; font-size: 16px; font-weight: 600; }
.empty-block p { margin: 0 0 8px; color: var(--ink-2); font-size: 13px; }

/* ── 配置卡片 ── */
.config-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
.config-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  box-shadow: var(--card-shadow);
  transition: box-shadow 0.18s ease, transform 0.18s ease;
}
.config-card:hover {
  box-shadow: rgba(0, 0, 0, 0.06) 0 6px 24px, rgba(0, 0, 0, 0.03) 0 2px 8px;
  transform: translateY(-1px);
}
.card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.card-title h3 { margin: 0; font-size: 15px; font-weight: 600; letter-spacing: -0.2px; }
.card-model { margin: 4px 0 0; color: var(--ink-2); font-size: 12px; }

.status-pill {
  padding: 3px 9px;
  border-radius: 9999px;
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}
.status-pill.on { background: #eef6ee; color: #1a7a3a; }
.status-pill.off { background: var(--warm); color: var(--ink-3); }

.tag-row { display: flex; flex-wrap: wrap; gap: 6px; }

/* ── 键值信息 ── */
.meta-list { margin: 0; display: flex; flex-direction: column; gap: 8px; }
.meta-row { display: flex; gap: 12px; font-size: 12px; }
.meta-row dt { flex: 0 0 62px; color: var(--ink-3); margin: 0; }
.meta-row dd { margin: 0; color: var(--ink-2); min-width: 0; }
.break { word-break: break-all; }

/* ── 测试结果 ── */
.test-result {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.5;
}
.test-result.ok { background: #eef6ee; color: #1a7a3a; }
.test-result.fail { background: #fdf0ef; color: #b3261e; }

/* ── 卡片操作区 ── */
.card-foot {
  display: flex;
  gap: 8px;
  margin-top: auto;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}

/* ── 新增卡片 ── */
.add-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 172px;
  padding: 20px;
  border: 1px dashed var(--line);
  border-radius: 12px;
  background: transparent;
  color: var(--ink-3);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.18s ease;
}
.add-card i { font-size: 22px; }
.add-card:hover { border-color: var(--blue); color: var(--blue); background: var(--blue-bg); }

/* ── 按钮体系 ── */
.btn-primary,
.btn-primary.el-button {
  background: var(--blue);
  border: 1px solid transparent;
  border-radius: 4px;
  color: #fff;
  font-weight: 600;
  padding: 9px 16px;
  transition: background 0.15s ease, transform 0.1s ease;
}
.btn-primary:hover,
.btn-primary:focus { background: var(--blue-active); color: #fff; }
.btn-primary:active { transform: scale(0.97); }

.btn-ghost,
.btn-ghost.el-button {
  background: rgba(0, 0, 0, 0.05);
  border: 1px solid transparent;
  border-radius: 4px;
  color: var(--ink);
  font-weight: 500;
}
.btn-ghost:hover,
.btn-ghost:focus { background: rgba(0, 0, 0, 0.09); color: var(--ink); }

.btn-danger,
.btn-danger.el-button {
  background: transparent;
  border: 1px solid transparent;
  border-radius: 4px;
  color: #b3261e;
  font-weight: 500;
}
.btn-danger:hover,
.btn-danger:focus { background: #fdf0ef; color: #b3261e; }

/* ── 表单 ── */
.field-hint { margin: 6px 0 0; color: var(--ink-3); font-size: 12px; line-height: 1.5; }
.switch-row { display: flex; align-items: center; gap: 10px; }
.switch-hint { color: var(--ink-2); font-size: 12px; }
.clear-key { margin-top: 6px; }

.settings-page ::v-deep .el-form-item__label {
  padding-bottom: 4px;
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 600;
}
.settings-page ::v-deep .el-input__inner,
.settings-page ::v-deep .el-textarea__inner {
  border: 1px solid #dddddd;
  border-radius: 4px;
  color: rgba(0, 0, 0, 0.9);
}
.settings-page ::v-deep .el-input__inner:focus {
  border-color: var(--blue);
  box-shadow: 0 0 0 2px rgba(9, 127, 232, 0.15);
}
.settings-page ::v-deep .el-dialog {
  border-radius: 16px;
  box-shadow: rgba(0, 0, 0, 0.01) 0 1px 3px, rgba(0, 0, 0, 0.02) 0 3px 7px,
              rgba(0, 0, 0, 0.02) 0 7px 15px, rgba(0, 0, 0, 0.04) 0 14px 28px,
              rgba(0, 0, 0, 0.05) 0 23px 52px;
}
.settings-page ::v-deep .el-dialog__title { font-weight: 600; letter-spacing: -0.2px; }
</style>
