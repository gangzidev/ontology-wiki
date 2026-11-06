<template>
  <div class="mcp-page">
    <!-- ── 页头 ── -->
    <header class="page-head">
      <div class="head-text">
        <p class="eyebrow">MCP ACCESS</p>
        <h1>MCP Token</h1>
        <p class="lede">
          为本体 Wiki 生成专用访问令牌，供 Claude Desktop、Cursor 等 MCP 客户端调用图谱与问答工具。
          令牌仅在校验时使用哈希比对，明文只展示一次。
        </p>
      </div>
      <el-button class="btn-primary" icon="el-icon-key" @click="openCreate">生成新 Token</el-button>
    </header>

    <!-- ── Token 列表 ── -->
    <section class="panel">
      <div class="panel-head">
        <h3>已生成的令牌</h3>
        <span class="panel-count">{{ tokens.length }} 个</span>
      </div>

      <div v-if="loading" class="state-block"><i class="el-icon-loading" /><span>正在读取令牌…</span></div>

      <div v-else-if="!tokens.length" class="empty-block">
        <div class="empty-icon"><i class="el-icon-key" /></div>
        <p>还没有生成任何令牌。生成后即可在 MCP 客户端中配置调用。</p>
      </div>

      <table v-else class="token-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>创建者</th>
            <th>创建时间</th>
            <th>最后使用</th>
            <th class="col-status">状态</th>
            <th class="col-action">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in tokens" :key="row.token_id">
            <td class="strong">{{ row.token_name }}</td>
            <td>{{ row.created_by || '—' }}</td>
            <td class="mono">{{ row.create_time || '—' }}</td>
            <td class="mono">{{ row.last_used || '从未使用' }}</td>
            <td class="col-status">
              <span class="status-pill" :class="row.status ? 'on' : 'off'">{{ row.status ? '有效' : '已撤销' }}</span>
            </td>
            <td class="col-action">
              <el-button class="btn-danger" size="mini" @click="removeToken(row)">撤销</el-button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- ── 接入说明 ── -->
    <section class="panel">
      <div class="panel-head">
        <h3>客户端接入示例</h3>
      </div>
      <p class="guide-text">
        将地址替换为实际部署地址，把 <span class="mono">YOUR_MCP_TOKEN</span> 替换为上方生成的令牌。
      </p>
      <pre class="code-block">{{ mcpConfigExample }}</pre>
    </section>

    <!-- ── 生成 Token 弹窗 ── -->
    <el-dialog :visible.sync="dialogOpen" width="520px" top="12vh" append-to-body
               custom-class="model-dialog" @close="resetDialog">
      <div slot="title" class="dialog-title">
        <span>生成 MCP Token</span>
        <p>令牌用于 MCP 客户端鉴权，明文只会展示一次。</p>
      </div>

      <el-form v-if="!newToken" ref="tokenForm" :model="tokenForm" :rules="tokenRules" label-position="top">
        <el-form-item label="令牌名称" prop="tokenName">
          <el-input v-model="tokenForm.tokenName" placeholder="如：Claude Desktop / Cursor" maxlength="50"
                    show-word-limit @keyup.enter.native="doCreate" />
          <p class="field-hint">用于区分不同客户端，支持中英文、数字、空格及 . _ - ( )</p>
        </el-form-item>
      </el-form>

      <div v-else class="token-reveal">
        <p class="reveal-tip"><i class="el-icon-warning-outline" /> 请立即复制，此令牌不会再次显示。</p>
        <el-input :value="newToken" readonly class="mono">
          <el-button slot="append" icon="el-icon-copy-document" @click="copyToken">复制</el-button>
        </el-input>
      </div>

      <div slot="footer" class="dialog-foot">
        <div class="dialog-foot-right">
          <el-button class="model-btn-ghost" @click="dialogOpen = false">{{ newToken ? '完成' : '取消' }}</el-button>
          <el-button v-if="!newToken" class="model-btn-primary" :loading="creating" @click="doCreate">生成 Token</el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listMcpTokens, createMcpToken, deleteMcpToken } from '@/api/ontology'

export default {
  name: 'OntologyMcpToken',
  data() {
    return {
      loading: false,
      tokens: [],
      dialogOpen: false,
      creating: false,
      newToken: '',
      tokenForm: { tokenName: '' },
      /** 名称约束：必填、2–50 字符、只允许常见可读字符，前后端保持一致 */
      tokenRules: {
        tokenName: [
          { required: true, message: '请输入令牌名称', trigger: 'blur' },
          { min: 2, max: 50, message: '名称长度为 2–50 个字符', trigger: 'blur' },
          { pattern: /^[A-Za-z0-9\u4e00-\u9fa5 ._\-()（）]+$/, message: '仅支持中英文、数字、空格及 . _ - ( )', trigger: 'blur' }
        ]
      },
      mcpConfigExample: `{
  "mcpServers": {
    "ontology-wiki": {
      "url": "http://your-server:8080/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_MCP_TOKEN"
      }
    }
  }
}`
    }
  },
  created() {
    this.loadTokens()
  },
  methods: {
    loadTokens() {
      this.loading = true
      listMcpTokens()
        .then(({ data }) => { this.tokens = data || [] })
        .catch(() => {})
        .finally(() => { this.loading = false })
    },
    openCreate() {
      this.tokenForm = { tokenName: '' }
      this.newToken = ''
      this.dialogOpen = true
      this.$nextTick(() => { if (this.$refs.tokenForm) this.$refs.tokenForm.clearValidate() })
    },
    resetDialog() {
      this.tokenForm = { tokenName: '' }
      this.newToken = ''
      this.creating = false
    },
    doCreate() {
      this.$refs.tokenForm.validate(valid => {
        if (!valid) return
        const name = this.tokenForm.tokenName.trim()
        const duplicated = this.tokens.some(item => String(item.token_name || '').trim().toLowerCase() === name.toLowerCase())
        if (duplicated) return this.$modal.msgError('已存在同名令牌，请换一个名称')
        this.creating = true
        createMcpToken({ tokenName: name })
          .then(({ data }) => {
            this.newToken = (data && data.token) || ''
            this.loadTokens()
          })
          .catch(() => this.$modal.msgError('生成失败'))
          .finally(() => { this.creating = false })
      })
    },
    removeToken(row) {
      this.$modal.confirm(`撤销后该令牌立即失效，确认撤销「${row.token_name}」？`).then(() => {
        deleteMcpToken(row.token_id).then(() => {
          this.$modal.msgSuccess('已撤销')
          this.loadTokens()
        })
      }).catch(() => {})
    },
    copyToken() {
      if (!this.newToken) return
      const el = document.createElement('textarea')
      el.value = this.newToken
      document.body.appendChild(el)
      el.select()
      document.execCommand('copy')
      document.body.removeChild(el)
      this.$modal.msgSuccess('已复制到剪贴板')
    }
  }
}
</script>

<style scoped>
/* ── 设计变量：遵循 Notion 风格设计规范 ── */
.mcp-page {
  --ink: rgba(0, 0, 0, 0.95);
  --ink-2: #615d59;
  --ink-3: #a39e98;
  --line: rgba(0, 0, 0, 0.1);
  --warm: #f6f5f4;
  --blue: #0075de;
  --blue-active: #005bab;
  --blue-bg: #f2f9ff;
  --card-shadow: rgba(0, 0, 0, 0.04) 0 4px 18px, rgba(0, 0, 0, 0.027) 0 2px 7.8px, rgba(0, 0, 0, 0.02) 0 0.8px 2.9px;
  max-width: 1180px;
  margin: 0 auto;
  padding: 28px 24px 56px;
  color: var(--ink);
  font-family: Inter, -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
}
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }

/* ── 页头 ── */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--line);
}
.eyebrow { margin: 0 0 10px; color: var(--blue); font-size: 11px; font-weight: 700; letter-spacing: 0.14em; }
.page-head h1 { margin: 0; font-size: 32px; line-height: 1.1; letter-spacing: -1.2px; font-weight: 700; }
.lede { max-width: 660px; margin: 10px 0 0; color: var(--ink-2); font-size: 14px; line-height: 1.6; }

/* ── 面板 ── */
.panel {
  margin-top: 22px;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  box-shadow: var(--card-shadow);
}
.panel-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 14px; }
.panel-head h3 { margin: 0; font-size: 15px; font-weight: 600; letter-spacing: -0.2px; }
.panel-count { color: var(--ink-3); font-size: 12px; }

/* ── 表格 ── */
.token-table { width: 100%; border-collapse: collapse; }
.token-table th {
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
  color: var(--ink-3);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-align: left;
}
.token-table td {
  padding: 12px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
  color: var(--ink-2);
  font-size: 12px;
}
.token-table tbody tr:hover td { background: var(--warm); }
.token-table tbody tr:last-child td { border-bottom: none; }
.strong { color: var(--ink) !important; font-weight: 600; }
.col-status { width: 88px; }
.col-action { width: 72px; text-align: right; }

/* ── 状态徽章 ── */
.status-pill { padding: 3px 9px; border-radius: 9999px; font-size: 11px; font-weight: 600; }
.status-pill.on { background: #eef6ee; color: #1a7a3a; }
.status-pill.off { background: var(--warm); color: var(--ink-3); }

/* ── 空状态 ── */
.state-block { display: flex; align-items: center; justify-content: center; gap: 10px; padding: 48px 0; color: var(--ink-3); font-size: 13px; }
.empty-block { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 40px 16px; text-align: center; }
.empty-icon {
  display: flex; align-items: center; justify-content: center;
  width: 44px; height: 44px; border-radius: 12px;
  background: var(--warm); color: var(--ink-3); font-size: 20px;
}
.empty-block p { margin: 0; color: var(--ink-2); font-size: 13px; }

/* ── 接入说明 ── */
.guide-text { margin: 0 0 10px; color: var(--ink-2); font-size: 13px; line-height: 1.6; }
.code-block {
  margin: 0;
  padding: 16px;
  overflow-x: auto;
  border-radius: 8px;
  background: #31302e;
  color: #f6f5f4;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre;
}

/* ── Token 展示 ── */
.token-reveal {
  margin-top: 4px;
  padding: 12px 14px;
  border: 1px solid #f5d98b;
  border-radius: 8px;
  background: #fdf8e8;
}
.reveal-tip { margin: 0 0 8px; color: #8a6408; font-size: 12px; }

/* ── 按钮体系 ── */
.btn-primary,
.btn-primary.el-button {
  height: 38px;
  padding: 0 16px;
  background: var(--blue);
  border: 1px solid transparent;
  border-radius: 8px;
  color: #fff;
  font-weight: 600;
  letter-spacing: 0.01em;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
  transition: background 0.15s ease, box-shadow 0.18s ease, transform 0.15s ease;
}
.btn-primary:hover,
.btn-primary:focus { background: var(--blue-active); color: #fff; box-shadow: 0 6px 14px rgba(0, 117, 222, 0.22); transform: translateY(-1px); }
.btn-primary:active { transform: translateY(0); }

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
</style>
