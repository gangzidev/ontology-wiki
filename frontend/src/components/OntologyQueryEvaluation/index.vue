<template>
<el-dialog
      :title="`配置质量评测 · ${proposalName}`"
      :visible.sync="evaluationDialog.visible"
      width="min(780px, 94vw)"
      append-to-body
      custom-class="dq-evaluation-dialog"
      :close-on-click-modal="false"
      :close-on-press-escape="!evaluationDialog.submitting"
      :show-close="!evaluationDialog.submitting"
      @closed="reset"
    >
      <div class="dq-evaluation-intro">
        <i class="el-icon-info" />
        <div><strong>用标准问题核对配置质量</strong><p>由配置管理员核对业务问题、统计口径和标准结果。评测记录用于配置验收，不影响已审核配置的日常只读查询。</p></div>
      </div>
      <el-form :disabled="evaluationDialog.submitting" label-width="92px" size="small">
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
        <el-button :disabled="evaluationDialog.submitting" @click="evaluationDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="evaluationDialog.submitting" @click="runEvaluation">运行评测</el-button>
      </span>
    </el-dialog>
</template>

<script>
import { getDataQueryReadiness, planDataQuery, evaluateDataQuery } from '@/api/ontology'

export default {
  name: 'OntologyQueryEvaluation',
  data() {
    return {
      proposalId: '',
      proposalName: '',
      evaluationDialog: { visible: false, version: 'v1', submitting: false },
      evaluationCases: []
    }
  },
  methods: {
    async open(proposal) {
      if (this.evaluationDialog.submitting) return
      const { data } = await getDataQueryReadiness(proposal.proposal_id)
      if (!data || !data.eligibleForEvaluation) {
        this.$modal.msgWarning((data && data.reasons || ['请先完成配置审核并补全统计口径']).join('；'))
        return
      }
      this.reset()
      this.proposalId = proposal.proposal_id
      this.proposalName = proposal.proposal_name || '未命名配置'
      this.addEvaluationCase()
      this.evaluationDialog.visible = true
    },
    reset() {
      this.proposalId = ''
      this.proposalName = ''
      this.evaluationCases = []
      this.evaluationDialog.version = 'v1'
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
      planDataQuery(this.proposalId, item.question).then(({ data }) => {
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
      evaluateDataQuery(this.proposalId, { benchmarkVersion: this.evaluationDialog.version.trim(), cases }).then(({ data }) => {
        if (!data || !data.passed) {
          const failed = (data && data.details || []).filter(item => !item.passed).map(item => `${item.caseId}: ${item.error || '结果不一致'}`).join('；')
          throw new Error(`评测未全部通过（${data && data.passedCount || 0}/${data && data.caseCount || cases.length}）${failed ? `：${failed}` : ''}`)
        }
        this.$modal.msgSuccess(`标准问题评测通过，共 ${data.passedCount} 条`)
        this.evaluationDialog.visible = false
        this.$emit('evaluated', data)
      }).catch(error => this.$modal.msgError((error && error.message) || '标准问题评测失败')).finally(() => { this.evaluationDialog.submitting = false })
    },
  }
}
</script>

<style scoped>
.dq-evaluation-intro { display:flex; gap:10px; margin-bottom:16px; padding:12px 14px; border:1px solid #cfe2fb; border-radius:8px; background:#f4f8ff; color:#303133; }
.dq-evaluation-intro > i { flex:0 0 auto; margin-top:2px; color:#409eff; font-size:17px; }
.dq-evaluation-intro strong { font-size:13px; }
.dq-evaluation-intro p { margin:4px 0 0; color:#606266; font-size:12px; line-height:1.6; }
.dq-evaluation-case { margin:0 0 14px; padding:12px 14px 4px; border:1px solid #dcdfe6; border-radius:8px; background:#fafbfc; }
.dq-evaluation-case-head { display:flex; align-items:center; justify-content:space-between; margin-bottom:8px; color:#303133; font-size:13px; }
.dq-evaluation-case .el-form-item { margin-bottom:12px; }
.dq-evaluation-case .el-textarea__inner, .dq-evaluation-case .el-input__inner { font-size:12px; }
.dq-evaluation-question { display:flex; gap:8px; }
.dq-evaluation-question .el-input { flex:1; }
.dq-evaluation-question .el-button { flex:0 0 auto; }
.dq-evaluation-dialog ::v-deep .el-dialog__body { max-height:68vh; overflow:auto; }
</style>

