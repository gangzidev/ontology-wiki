# 分析任务详细日志系统实施指南

## 背景

### 当前问题
1. **刷新页面后日志重置** - 前端只在内存中保存日志，刷新后丢失
2. **日志内容过于简单** - 只显示"第X步"和"第N次调用"，用户看不懂
3. **缺少关键信息** - 没有表数量、字段数量、Wiki文档数等详细信息

### 解决方案
创建MySQL表存储详细日志，包含：
- 阶段开始/完成时间
- 扫描的表数量和字段数量
- Wiki文档召回数量
- 模型调用的批次信息（第X批/共Y批，本批Z项）
- 每个阶段的详细进度

---

## 数据库迁移

### 1. 执行SQL脚本

```bash
mysql -u root -p ontology_wiki < backend/sql/migrate-analysis-log.sql
```

### 2. 验证表创建

```sql
DESC ontology_analysis_log;
```

应该看到：
- log_id (主键)
- task_id
- run_token
- log_level (INFO/WARN/ERROR)
- log_category (STAGE/MODEL_CALL/PROGRESS/SYSTEM)
- stage
- message
- detail_json
- created_at

---

## 后端集成

### 已创建的文件

1. ✅ `backend/sql/migrate-analysis-log.sql` - 数据库表
2. ✅ `AnalysisLogMapper.java` - MyBatis Mapper接口
3. ✅ `AnalysisLogMapper.xml` - MyBatis Mapper XML
4. ✅ `AnalysisLogService.java` - 日志服务类
5. ✅ `OntologyController.java` - 添加了 `/analyses/{id}/runs/{runId}/logs` 接口

### 需要集成的地方

#### A. 在 AnalysisJobStore 中注入日志服务

```java
@Component
public class AnalysisJobStore {
    private final AnalysisJobMapper mapper;
    private final AnalysisLogService logService;  // 新增

    public AnalysisJobStore(AnalysisJobMapper mapper, AnalysisLogService logService) {
        this.mapper = mapper;
        this.logService = logService;  // 新增
    }

    // 新增方法：获取日志
    public List<Map<String, Object>> logs(long taskId, long runId) {
        Map<String, Object> run = mapper.selectRunById(taskId, runId);
        if (run == null) return Collections.emptyList();
        String runToken = String.valueOf(run.get("runToken"));
        return logService.getLogs(taskId, runToken);
    }
}
```

#### B. 在 AnalysisJobServiceImpl 中记录详细日志

**示例位置1：SCAN 阶段**
```java
// 扫描开始
logService.info(taskId, token, "STAGE", "SCAN", "开始扫描数据源结构...");

// 扫描完成
Map<String, Object> scanDetail = new LinkedHashMap<>();
scanDetail.put("tableCount", tables.size());
scanDetail.put("totalColumns", totalColumns);
logService.info(taskId, token, "STAGE", "SCAN", 
    String.format("数据源扫描完成，发现 %d 张表，共 %d 个字段", tables.size(), totalColumns), 
    scanDetail);
```

**示例位置2：WIKI_DOCUMENTS 阶段**
```java
// Wiki检索开始
logService.info(taskId, token, "STAGE", "WIKI_DOCUMENTS", "开始检索Wiki文档...");

// Wiki检索完成
Map<String, Object> wikiDetail = new LinkedHashMap<>();
wikiDetail.put("wikiDocs", wikiDocs.size());
wikiDetail.put("totalChunks", totalChunks);
logService.info(taskId, token, "STAGE", "WIKI_DOCUMENTS", 
    String.format("Wiki文档检索完成，找到 %d 篇相关文档，共 %d 个分块", wikiDocs.size(), totalChunks), 
    wikiDetail);
```

**示例位置3：NAME_COMPLETION 阶段（批次信息）**
```java
// 在 OntologySemanticNamingService 的 nameBatch 方法中
public void nameBatch(..., Consumer<String> progress, ...) {
    int batchNum = currentBatch.get();  // 假设维护了批次计数
    int totalBatches = estimatedBatches.get();
    
    String message = String.format("正在调用模型补全中文名称，批次 %d/%d（本批 %d 项）...", 
        batchNum, totalBatches, batch.size());
    
    // 通过progress回调记录日志
    progress.accept(message);
    
    // 实际调用模型
    String result = completion.apply(prompt);
    
    // 记录完成
    progress.accept(String.format("批次 %d/%d 完成", batchNum, totalBatches));
}
```

---

## 前端集成

### 1. 添加API方法

```javascript
// frontend/src/api/ontology/index.js
export const getAnalysisLogs = (id, runId) => 
  request({ url: `/ontology/analyses/${id}/runs/${runId}/logs`, method: 'get' })
```

### 2. 修改 reopenAnalysisProgress 方法

```javascript
async reopenAnalysisProgress(row) {
  this.analysisProgress = {
    visible: true,
    status: row.status,
    step: this.inferStep(row.status, row.stage),
    stepIndex: this.inferStepIndex(row.status, row.stage),
    message: row.status === 'RUNNING' ? '分析进行中...' : '分析完成',
    elapsedSeconds: 0,
    error: '',
    logs: [],
    modelCalls: 0,
    taskId: row.task_id,
    taskName: row.task_name || '',
    resumable: false
  }

  // 加载完整的执行日志（适用于所有状态）
  try {
    const { data: runs } = await getAnalysisRuns(row.task_id, { limit: 1 })
    if (runs && runs.rows && runs.rows.length > 0) {
      const latestRun = runs.rows[0]
      
      // 加载数据库中的详细日志
      const { data: logs } = await getAnalysisLogs(row.task_id, latestRun.runId)
      
      // 转换为显示格式
      this.analysisProgress.logs = logs.map(log => ({
        time: new Date(log.createdAt).toLocaleTimeString(),
        message: log.message,
        level: log.logLevel,
        category: log.logCategory
      }))
      
      // 加载模型调用统计
      const { data: calls } = await getAnalysisModelCalls(row.task_id, latestRun.runId)
      this.analysisProgress.modelCalls = calls.length
    }
  } catch (error) {
    console.error('加载执行日志失败:', error)
  }

  // 如果正在运行，启动轮询
  if (row.status === 'RUNNING') {
    this.startProgressPolling(row.task_id, row.task_name)
  }
}
```

### 3. 轮询时增量加载新日志

```javascript
startProgressPolling(taskId, taskName) {
  const pollId = ++this.analysisPollId
  let lastLogId = 0  // 记录最后一条日志ID

  const poll = async () => {
    if (pollId !== this.analysisPollId) return
    
    try {
      // 获取任务状态
      const { data } = await getAnalysisJob(taskId)
      
      // 获取新日志（增量）
      const { data: runs } = await getAnalysisRuns(taskId, { limit: 1 })
      if (runs && runs.rows && runs.rows.length > 0) {
        const { data: logs } = await getAnalysisLogs(taskId, runs.rows[0].runId)
        
        // 只添加新日志
        const newLogs = logs.filter(log => log.logId > lastLogId)
        if (newLogs.length > 0) {
          newLogs.forEach(log => {
            this.analysisProgress.logs.push({
              time: new Date(log.createdAt).toLocaleTimeString(),
              message: log.message
            })
          })
          lastLogId = newLogs[newLogs.length - 1].logId
          
          // 自动滚动到底部
          this.$nextTick(() => {
            const content = this.$refs.consoleContent
            if (content) content.scrollTop = content.scrollHeight
          })
        }
      }
      
      // 更新状态
      this.analysisProgress.status = data.status
      this.analysisProgress.modelCalls = data.modelCalls || 0
      
      // 继续轮询或停止
      if (data.status === 'RUNNING') {
        this.analysisProgressTimer = setTimeout(poll, 2000)
      } else {
        this.stopAnalysisProgress()
      }
    } catch (error) {
      console.error('轮询失败:', error)
      this.stopAnalysisProgress()
    }
  }
  
  poll()
}
```

---

## 日志示例

### 完整的日志输出应该是这样的：

```
20:36:10  开始扫描数据源结构...
20:36:12  数据源扫描完成，发现 42 张表，共 215 个字段
20:36:12  开始检索Wiki文档...
20:36:14  Wiki文档检索完成，找到 5 篇相关文档，共 128 个分块
20:36:14  开始检索业务规则...
20:36:15  业务规则检索完成，找到 3 条相关规则
20:36:15  开始生成本体候选...
20:36:17  候选生成完成：对象 42 个，属性 215 个，关系 18 个
20:36:17  开始模型语义补充...
20:36:17  Wiki覆盖率：对象 35/42 (83%)，属性 180/215 (84%)
20:36:17  跳过模型语义补充（Wiki覆盖率已足够）
20:36:17  开始中文名称补全...
20:36:19  正在调用模型补全中文名称，批次 1/3（本批 150 项）...
20:36:25  批次 1/3 完成，成功补全 148 项
20:36:25  正在调用模型补全中文名称，批次 2/3（本批 150 项）...
20:36:30  批次 2/3 完成，成功补全 150 项
20:36:30  正在调用模型补全中文名称，批次 3/3（本批 65 项）...
20:36:33  批次 3/3 完成，成功补全 65 项
20:36:33  中文名称补全完成，共补全 363 项，失败 2 项
20:36:33  分析完成，共调用模型 3 次
20:36:33  配置草案 #8 已保存
```

---

## 实施步骤

### 阶段1：数据库和基础设施（已完成）
- [x] 创建数据库表
- [x] 创建 Mapper 和 Service
- [x] 添加 Controller 接口

### 阶段2：后端日志集成（需要实施）
- [ ] 在 `AnalysisJobStore` 注入 `AnalysisLogService`
- [ ] 在 `AnalysisJobServiceImpl` 各个阶段添加日志记录
- [ ] 在 `OntologySemanticNamingService` 添加批次进度日志
- [ ] 在 `SemanticEnrichmentService` 添加Wiki覆盖率日志

### 阶段3：前端集成（需要实施）
- [ ] 添加 `getAnalysisLogs` API方法
- [ ] 修改 `reopenAnalysisProgress` 加载数据库日志
- [ ] 修改轮询逻辑，增量加载新日志
- [ ] 添加自动滚动到底部

### 阶段4：测试验证
- [ ] 创建新的分析任务
- [ ] 执行过程中关闭对话框
- [ ] 刷新页面
- [ ] 再次点击"查看进度"
- [ ] 验证日志完整且详细

---

## 优势

### 1. 持久化存储
- ✅ 刷新页面不丢失
- ✅ 可追溯历史
- ✅ 支持审计

### 2. 详细信息
- ✅ 知道扫描了多少表
- ✅ 知道召回了多少Wiki文档
- ✅ 知道每批次处理了多少项
- ✅ 知道成功/失败的具体数量

### 3. 用户体验
- ✅ 看得懂在做什么
- ✅ 知道进度到哪了
- ✅ 预估还需要多久
- ✅ 出错时有明确的错误信息

---

## 注意事项

1. **日志记录失败不应影响主流程** - `AnalysisLogService` 已经catch了异常
2. **控制日志数量** - 单次执行限制500条，避免过多
3. **性能考虑** - 日志写入是异步的，不阻塞分析流程
4. **数据清理** - 建议定期清理3个月以上的历史日志

---

**创建时间**: 2024-11-24  
**下一步**: 执行数据库迁移，然后逐步集成后端日志记录
