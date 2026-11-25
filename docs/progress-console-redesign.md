# 分析进度界面重新设计 - 控制台风格

## 更新时间
2024-11-24

## 设计目标

1. **合并信息**：将"执行记录"和"实时进度"合并到一个统一的控制台视图
2. **无需点击**：一屏展示所有关键信息，不需要额外点击查看详情
3. **符合项目风格**：参考项目整体设计语言（data-query 页面的风格）
4. **清晰直观**：控制台样式的日志输出，实时滚动更新

## 主要改进

### 1. 顶部状态栏
```
┌────────────────────────────────────────────────────────┐
│ [图标] 分析进度信息              ⏱ 125s  🔗 15次调用 │
│        任务名称                                        │
└────────────────────────────────────────────────────────┘
```

**特点：**
- 大图标展示当前状态（运行中/成功/失败）
- 运行中图标带旋转动画
- 实时显示已用时间和模型调用次数
- 显示任务名称

### 2. 简化的进度条
```
● 扫描 ——— ● 候选 ——— ○ 语义 ——— ○ 保存
(已完成)   (已完成)   (进行中)   (等待)
```

**特点：**
- 4个步骤水平排列
- 已完成：绿色实心圆点
- 进行中：蓝色实心圆点 + 脉冲动画
- 等待中：灰色空心圆点
- 占用空间更小，一目了然

### 3. 控制台日志
```
┌─ 📋 执行日志                          实时更新 ─┐
│ 14:30:25  开始数据源扫描...                     │
│ 14:30:27  已扫描 12 张表，128 个字段             │
│ 14:30:30  Wiki 文档检索完成，找到 3 篇文档       │
│ 14:30:35  正在调用模型，第 1 次...               │
│ 14:30:40  2/4 正在补充业务语义...                │
│ ...                                              │
└──────────────────────────────────────────────────┘
```

**特点：**
- 黑色背景（#0d1117）+ 浅色文字（GitHub 风格）
- 时间戳 + 日志消息
- 自动滚动到最新日志
- 等宽字体（SF Mono、Monaco、Consolas）
- 自定义滚动条样式
- 最大高度 360px，超出自动滚动

## 技术实现

### 修改的文件
- `frontend/src/views/ontology/resource/index.vue`

### 主要变更

#### 1. 模板结构
```vue
<el-dialog title="分析进度">
  <!-- 顶部状态栏 -->
  <div class="progress-status-bar">
    <div class="status-main">
      <i class="status-icon" />
      <div class="status-text">
        <strong>{{ message }}</strong>
        <span>{{ taskName }}</span>
      </div>
    </div>
    <div class="status-stats">
      <div class="stat-item">⏱ {{ elapsedSeconds }}s</div>
      <div class="stat-item">🔗 {{ modelCalls }} 次调用</div>
    </div>
  </div>

  <!-- 简化进度条 -->
  <div class="progress-steps-compact">
    <div class="step-item" v-for="step in steps" />
  </div>

  <!-- 控制台日志 -->
  <div class="console-log">
    <div class="console-header">📋 执行日志</div>
    <div class="console-content">
      <div class="log-line" v-for="log in logs">
        <span class="log-time">{{ log.time }}</span>
        <span class="log-message">{{ log.message }}</span>
      </div>
    </div>
  </div>
</el-dialog>
```

#### 2. Data 增强
```javascript
analysisProgress: {
  visible: false,
  status: '',
  step: 1,
  message: '',
  elapsedSeconds: 0,
  error: '',
  logs: [],
  modelCalls: 0,        // 新增：模型调用次数
  taskId: null,         // 新增：任务ID
  taskName: '',         // 新增：任务名称
  resumable: false      // 新增：是否可恢复
}
```

#### 3. 轮询更新
```javascript
startAnalysisProgress(taskId, taskName, isReanalysis) {
  const poll = async () => {
    const { data } = await getAnalysisJob(taskId)
    
    // 更新模型调用次数
    this.analysisProgress.modelCalls = data.modelCalls || 0
    
    // 更新进度步骤
    if (match) {
      this.analysisProgress.step = Number(match[1])
      this.analysisProgress.message = message
      this.addAnalysisProgressLog(message)
    }
    
    // 1.5秒轮询一次（从1秒改为1.5秒，减少频率）
    this.analysisProgressTimer = setTimeout(poll, 1500)
  }
}
```

### 样式设计

#### 颜色规范
- 主色调：`#0075de`（蓝色，项目主色）
- 成功：`#67c23a`（绿色）
- 失败：`#f56c6c`（红色）
- 背景：`#0d1117`（深色控制台背景）
- 文字：`#c9d1d9`（浅色文字）
- 时间戳：`#8b949e`（灰色）

#### 动画效果
1. **旋转动画**（运行中图标）
   ```css
   @keyframes rotate {
     from { transform: rotate(0deg) }
     to { transform: rotate(360deg) }
   }
   ```

2. **脉冲动画**（当前步骤圆点）
   ```css
   @keyframes pulse-dot {
     0%, 100% {
       transform: scale(1);
       box-shadow: 0 0 0 0 rgba(0,117,222,.4)
     }
     50% {
       transform: scale(1.2);
       box-shadow: 0 0 0 6px rgba(0,117,222,0)
     }
   }
   ```

## 对比：改进前 vs 改进后

### 改进前
- ❌ 信息分散：状态、步骤、日志分开显示
- ❌ 需要点击："查看进度"才能看到执行记录
- ❌ 占用空间大：el-steps 组件垂直占用空间
- ❌ 日志样式普通：白色背景，不够醒目
- ❌ 缺少关键信息：模型调用次数不显示

### 改进后
- ✅ 信息集中：状态、耗时、调用次数、步骤、日志一屏展示
- ✅ 无需点击：打开对话框即可看到所有信息
- ✅ 占用空间小：水平进度条更紧凑
- ✅ 控制台风格：黑色背景，专业感强
- ✅ 关键信息完整：实时显示模型调用次数

## 用户体验提升

### 1. 更快速的信息获取
- **改进前**：点击"查看进度" → 看到状态 → 点击"阶段耗时" → 查看详情 → 点击"模型调用" → 查看次数
- **改进后**：点击"查看进度" → 一屏看到所有信息

### 2. 更清晰的进度展示
- **改进前**：el-steps 垂直布局，4个步骤占用较大空间
- **改进后**：水平点状进度，紧凑且一目了然

### 3. 更专业的日志展示
- **改进前**：浅色背景，普通文本
- **改进后**：深色控制台背景，等宽字体，时间戳对齐

### 4. 更实时的信息更新
- **改进前**：只显示主要进度消息
- **改进后**：显示所有详细日志，包括模型调用次数

## 兼容性说明

### 完全兼容
- 保留了所有原有功能
- 不影响后端接口
- 不影响数据结构
- 只是UI层面的优化

### API 依赖
- `getAnalysisJob(taskId)` - 获取任务状态
  - 需要返回 `modelCalls` 字段（模型调用次数）
  - 如果后端未提供，前端显示为 0

## 下一步优化建议

### 1. 后端增强（可选）
- 在 `getAnalysisJob` 接口中返回 `modelCalls` 字段
- 在进度消息中包含更多细节：
  - "2/4 已扫描 12 张表，128 个字段"
  - "2/4 从 Wiki 召回 5 篇文档、3 条业务规则"
  - "3/4 正在调用模型，第 8 次（共预计 15 次）"

### 2. Token 消耗展示（需要后端支持）
- 如果后端能返回 token 消耗数据
- 在状态栏增加第三个统计项：
  ```
  ⏱ 125s  🔗 15次调用  💰 48.3k tokens
  ```

### 3. 日志搜索（增强功能）
- 在控制台头部添加搜索框
- 支持过滤日志内容

### 4. 日志导出（增强功能）
- 添加"导出日志"按钮
- 下载为 `.log` 或 `.txt` 文件

## 测试检查清单

- [ ] 启动分析任务
- [ ] 点击"查看进度"按钮
- [ ] 验证状态栏显示正确（图标、消息、耗时、调用次数）
- [ ] 验证进度条正确显示当前步骤
- [ ] 验证控制台日志实时更新
- [ ] 验证日志自动滚动到最新
- [ ] 验证运行中图标旋转动画
- [ ] 验证当前步骤圆点脉冲动画
- [ ] 验证分析完成后状态更新
- [ ] 验证分析失败时错误提示
- [ ] 测试"取消分析"功能
- [ ] 测试"从检查点继续"功能
- [ ] 测试"查看分析结果"功能

## 移除的组件

- `AnalysisProgressView.vue` - 新创建的组件（未使用）
- 在 `index.vue` 中移除了对 `<analysis-progress-view>` 的引用

原有的进度对话框已完全重新设计，不再需要额外的组件。

---

**完成时间**: 2024-11-24  
**设计理念**: 简洁、直观、专业的控制台风格进度展示
