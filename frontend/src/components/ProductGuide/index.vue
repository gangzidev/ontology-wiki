<template>
  <div>
    <el-button
      class="product-guide-trigger"
      type="primary"
      icon="el-icon-reading"
      aria-label="打开产品使用教程"
      @click="openGuide"
    >使用教程</el-button>

    <el-dialog
      title="产品使用教程"
      :visible.sync="visible"
      width="min(560px, calc(100vw - 32px))"
      append-to-body
      :close-on-click-modal="false"
      @closed="resetStep"
    >
      <div class="guide-content">
        <el-steps :active="activeStep" align-center finish-status="success">
          <el-step v-for="(step, index) in steps" :key="step.title" :title="step.shortTitle" :icon="step.icon" />
        </el-steps>
        <section class="guide-step">
          <div class="guide-step-icon"><i :class="steps[activeStep].icon" /></div>
          <div>
            <h3>{{ steps[activeStep].title }}</h3>
            <p>{{ steps[activeStep].description }}</p>
            <div class="guide-tip"><i class="el-icon-info" />{{ steps[activeStep].tip }}</div>
          </div>
        </section>
      </div>
      <span slot="footer" class="guide-footer">
        <span class="guide-count">{{ activeStep + 1 }} / {{ steps.length }}</span>
        <span>
          <el-button v-if="activeStep > 0" @click="previousStep">上一步</el-button>
          <el-button v-if="activeStep < steps.length - 1" type="primary" @click="nextStep">下一步</el-button>
          <el-button v-else type="primary" @click="closeGuide">完成</el-button>
        </span>
      </span>
    </el-dialog>
  </div>
</template>

<script>
const GUIDE_SEEN_KEY = 'ontologywiki:product-guide:v1'

export default {
  name: 'ProductGuide',
  data() {
    return {
      visible: false,
      activeStep: 0,
      // 教程按平台的日常工作流程组织，便于首次使用时逐步浏览。
      steps: [
        {
          shortTitle: '概览',
          title: '先从首页和工作台了解进度',
          description: '首页提供常用功能入口；工作台汇总知识空间、Wiki 资产、数据源、分析任务和配置草案数量。',
          tip: '工作台的流程状态会随实际记录更新。',
          icon: 'el-icon-s-grid'
        },
        {
          shortTitle: '知识',
          title: '建立行业知识空间并整理资料',
          description: '先在“行业知识空间”按业务领域归类，再到“Wiki资产”录入术语、规则、流程和业务文档。',
          tip: '分析任务只会使用所选知识空间内的 Wiki 资料。',
          icon: 'el-icon-notebook-2'
        },
        {
          shortTitle: '数据源',
          title: '连接并扫描业务数据库',
          description: '在“数据源管理”配置数据库连接，测试通过后扫描表结构，为后续分析准备数据。',
          tip: '请确认账号权限和连接配置符合所在环境要求。',
          icon: 'el-icon-coin'
        },
        {
          shortTitle: '分析',
          title: '创建分析任务并生成候选配置',
          description: '选择数据源、行业知识空间和分析范围后启动任务，系统会整理对象、字段、关系、逻辑与动作候选。',
          tip: '候选内容需要人工检查，不会自动发布到本体平台。',
          icon: 'el-icon-data-analysis'
        },
        {
          shortTitle: '审核',
          title: '审核配置草案，再测试和导入',
          description: '到“配置草案”查看分析结果和依据，由本体工程师修正并标记完成后即可导出或提交到本体平台。',
          tip: '建议先完成审核与平台测试，再进行正式导入。',
          icon: 'el-icon-document-checked'
        },
        {
          shortTitle: '问答',
          title: '用业务知识问答查询已整理资料',
          description: '在“业务知识问答”选择全部 Wiki、行业知识空间、单个资产或分析任务作为范围，输入问题查看带资料依据的回答。',
          tip: '问答依据当前选定范围内的资料，不会查询实时数据库。',
          icon: 'el-icon-chat-line-round'
        }
      ]
    }
  },
  mounted() {
    this.showForFirstLogin()
  },
  methods: {
    // 首次显示时立即记为已看过，之后可通过右下角入口再次打开。
    showForFirstLogin() {
      try {
        if (!window.localStorage.getItem(GUIDE_SEEN_KEY)) {
          window.localStorage.setItem(GUIDE_SEEN_KEY, '1')
          this.visible = true
        }
      } catch (error) {
        // 浏览器禁用本地存储时，仍展示教程；之后可从悬浮入口重新打开。
        this.visible = true
      }
    },
    openGuide() {
      this.activeStep = 0
      this.visible = true
    },
    previousStep() {
      if (this.activeStep > 0) this.activeStep -= 1
    },
    nextStep() {
      if (this.activeStep < this.steps.length - 1) this.activeStep += 1
    },
    closeGuide() {
      this.visible = false
    },
    resetStep() {
      this.activeStep = 0
    }
  }
}
</script>

<style lang="scss" scoped>
.product-guide-trigger {
  position: fixed;
  right: 28px;
  bottom: calc(24px + env(safe-area-inset-bottom, 0px));
  z-index: 1001;
  border-radius: 22px;
  box-shadow: 0 6px 18px rgba(47, 136, 255, .28);
}

.guide-content { min-height: 230px; }
.guide-step { display: flex; gap: 16px; min-height: 130px; padding: 30px 10px 8px; }
.guide-step-icon { display: flex; align-items: center; justify-content: center; flex: 0 0 44px; height: 44px; border-radius: 13px; background: #edf5ff; color: #2f88ff; font-size: 22px; }
.guide-step h3 { margin: 1px 0 9px; color: #24324b; font-size: 17px; }
.guide-step p { margin: 0; color: #657187; font-size: 14px; line-height: 1.8; }
.guide-tip { margin-top: 12px; color: #7b8798; font-size: 12px; line-height: 1.6; }
.guide-tip i { margin-right: 5px; color: #409eff; }
.guide-footer { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.guide-count { color: #98a2b3; font-size: 13px; }

@media (max-width: 600px) {
  .product-guide-trigger { right: 16px; bottom: calc(16px + env(safe-area-inset-bottom, 0px)); }
  .guide-content { min-height: 270px; }
  .guide-step { padding: 24px 0 8px; }
}
</style>
