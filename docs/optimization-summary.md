# 本体分析优化总结

## 优化日期
2024-11-24

## 优化内容

### 1. 减少模型调用次数

#### 问题
- 分析12张表产生19次模型调用，成本过高
- 每24项一批，导致批次过多

#### 解决方案
**A. 增加批次大小**
- 文件：`backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/OntologySemanticNamingService.java`
- 修改：
  - `BATCH_SIZE`: 24 → 60 (增加150%)
  - `BATCH_CHARACTERS`: 16000 → 32000 (增加100%)

**B. 优化Wiki文档优先策略**
- 文件：`backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/analysis/SemanticEnrichmentService.java`
- 新增：
  - `countWikiCoveredItems()` 方法：统计Wiki覆盖率
  - `hasChineseName()` 方法：检查是否已有中文名称
- 逻辑：
  - 当Wiki文档覆盖率超过60%时，跳过总体模型语义分析
  - 优先使用Wiki资产中的业务语义
  - 只对Wiki覆盖不到的内容调用模型

#### 预期效果
- **优化前**：12张表 → 19次调用
  - 总体语义补充：1次
  - 名称补全：18次（约75个对象/字段，每24项一批）

- **优化后**：12张表 → 5-8次调用
  - Wiki覆盖率高时跳过总体语义补充：0次
  - 名称补全：5-7次（每60项一批）
  
- **预计减少**：约60-70%的模型调用次数

---

### 2. 重新设计进度页面

#### 问题
- 信息分散：执行记录、阶段耗时、模型调用需要多次点击查看
- 缺乏直观性：看不到当前正在执行什么阶段
- 追踪信息割裂："阶段耗时"和"模型调用"是两个独立弹窗
- 缺少关键信息：Token消耗、实时进度没有突出显示
- 交互繁琐：需要点击多个按钮才能了解执行情况

#### 解决方案
创建全新的统一进度视图组件 `AnalysisProgressView.vue`

**核心特性：**

1. **执行记录列表视图**
   - 显示所有历史执行记录
   - 进度条直观展示完成百分比
   - 显示模型调用次数
   - 点击行查看详情

2. **详细进度视图**
   - 总体统计卡片：
     - 分析范围
     - 模型调用次数
     - Token 消耗（格式化显示，如 48.3k）
     - 总耗时（自动转换为分秒）
   
   - 执行进度时间轴：
     - 视觉化显示所有阶段
     - 每个阶段显示：
       - 状态图标（成功✓、进行中⟳、失败✗、等待⏸）
       - 阶段名称和状态标签
       - 耗时、模型调用次数、Token消耗
       - 当前活动描述（进行中时）
       - 错误信息（失败时）
     - 颜色区分：
       - 成功：绿色
       - 进行中：蓝色（带脉冲动画）
       - 失败：红色
       - 等待中：灰色半透明

   - 模型调用详情（可折叠）：
     - 表格显示所有模型调用
     - 显示序号、阶段、结果、耗时、Token、时间
   
   - 执行日志（可折叠）：
     - 类终端样式的日志查看器
     - 自动从阶段和模型调用生成日志
     - 颜色区分日志级别（INFO/SUCCESS/ERROR）

3. **设计亮点**
   - 单一视图集成所有信息
   - 时间轴展示清晰直观
   - 卡片式统计一目了然
   - 进行中阶段带脉冲动画，吸引注意力
   - 详细日志可折叠，默认隐藏避免干扰
   - 响应式设计，支持不同屏幕尺寸

#### 文件变更
- 新增：`frontend/src/views/ontology/resource/AnalysisProgressView.vue` (完整的新组件)
- 修改：`frontend/src/views/ontology/resource/index.vue` (集成新组件)
  - 导入：`AnalysisRunHistory` → `AnalysisProgressView`
  - 组件注册：`AnalysisRunHistory` → `AnalysisProgressView`
  - 使用：`<analysis-run-history>` → `<analysis-progress-view>`

---

## 技术细节

### 阶段映射
```javascript
{
  SCAN: '数据源结构扫描',
  WIKI_DOCUMENTS: 'Wiki 文档检索',
  BUSINESS_RULES: '业务规则检索',
  GENERATION: '候选生成',
  MODEL_ENRICHMENT: '模型语义补充',
  NAME_COMPLETION: '中文名称补全'
}
```

### Token格式化
- 小于1000：显示原值（如 "320"）
- 大于等于1000：显示千位（如 "48.3k"）

### 耗时格式化
- 小于1秒：显示毫秒（如 "850ms"）
- 1-60秒：显示秒（如 "45秒"）
- 大于60秒：显示分秒（如 "2分35秒"）

### 进度计算
- 基于阶段索引计算百分比
- 阶段顺序：SCAN → WIKI_DOCUMENTS → BUSINESS_RULES → GENERATION → MODEL_ENRICHMENT → NAME_COMPLETION → READY
- 公式：`(当前阶段索引 + 1) / 总阶段数 * 100`

---

## 下一步建议

1. **验证优化效果**
   - 启动项目测试
   - 执行一次完整分析
   - 观察模型调用次数是否减少到预期的5-8次

2. **监控Token消耗**
   - 记录优化前后的Token消耗对比
   - 验证成本是否明显降低

3. **用户体验测试**
   - 测试新进度页面的交互流畅性
   - 确认所有信息都能正确显示
   - 验证实时更新是否工作正常

4. **性能优化（可选）**
   - 如果进度页面数据量大，考虑添加虚拟滚动
   - 优化时间轴渲染性能

5. **功能增强（可选）**
   - 添加导出执行报告功能
   - 添加执行对比功能（对比两次执行的差异）
   - 添加执行暂停/恢复功能

---

## 兼容性说明

- 新组件完全替代旧的 `AnalysisRunHistory` 组件
- API接口无变化，使用相同的后端接口
- 数据格式完全兼容，无需迁移数据
- 保留了所有原有功能，同时增加了新的可视化展示

---

## 相关文件清单

### 后端文件
1. `backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/OntologySemanticNamingService.java`
2. `backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/analysis/SemanticEnrichmentService.java`

### 前端文件
1. `frontend/src/views/ontology/resource/AnalysisProgressView.vue` (新增)
2. `frontend/src/views/ontology/resource/index.vue` (修改)
3. `frontend/src/views/ontology/resource/AnalysisRunHistory.vue` (可删除，已废弃)

---

## 测试检查清单

- [ ] 启动后端服务
- [ ] 启动前端服务
- [ ] 创建新的分析任务
- [ ] 观察模型调用次数
- [ ] 验证Wiki优先策略是否生效
- [ ] 打开进度页面查看统一视图
- [ ] 验证实时进度更新
- [ ] 验证Token消耗显示
- [ ] 测试阶段时间轴展示
- [ ] 测试模型调用详情折叠面板
- [ ] 测试执行日志查看器
- [ ] 验证历史记录列表
- [ ] 测试返回列表功能

---

**优化完成时间**: 2024-11-24
**预计收益**: 减少60-70%的模型调用次数，显著降低成本，提升用户体验
