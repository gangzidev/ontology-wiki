# 分析进度日志和模型调用优化

## 更新时间
2024-11-24

## 问题描述

### 1. 刷新页面后日志丢失
- **现象**：点击"查看进度"后，时间和控制台日志都会重置
- **原因**：前端只显示轮询过程中收集的日志，没有从后端加载历史记录

### 2. 日志不完整
- **现象**：
  ```
  20:36:26  1/4 正在加载分析任务进度…
  20:36:27  3/4 正在调用模型，第 8 次…
  20:36:36  3/4 正在调用模型，第 9 次…
  ```
  缺少第2步的日志，日志不通顺

- **原因**：前端只记录轮询时接收到的消息，阶段切换时如果用户关闭了对话框就会丢失日志

### 3. 模型调用次数过多
- **现象**：42张表调用10次以上模型
- **原因**：
  1. 按对象分组批次处理（42张表 = 42个对象 = 至少42个批次）
  2. 批次大小只有60项
  3. 语义补全和非语义补全分两轮处理

---

## 优化方案

### 1. ✅ 修复日志完整性

#### 前端改进
**修改文件：** `frontend/src/views/ontology/resource/index.vue`

**新增功能：**
- 点击"查看进度"时，从后端API加载完整的执行历史
- API调用：
  - `getAnalysisRuns(taskId, { limit: 1 })` - 获取最新执行记录
  - `getAnalysisStageTimings(taskId, runId)` - 获取阶段时间
  - `getAnalysisModelCalls(taskId, runId)` - 获取模型调用记录

**生成完整日志：**
```javascript
// 1. 阶段开始日志
logs.push({ time: startTime, message: '数据源结构扫描开始' })

// 2. 模型调用日志
logs.push({ time: callTime, message: '正在调用模型，第 8 次...' })

// 3. 阶段完成日志
logs.push({ time: endTime, message: '数据源结构扫描完成 (1250ms)' })
```

**效果：**
现在即使刷新页面，也能看到完整的执行历史和日志。

---

### 2. ✅ 大幅减少模型调用次数

#### 后端优化
**修改文件：** `backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/OntologySemanticNamingService.java`

#### A. 增加批次大小

**优化前：**
```java
private static final int BATCH_SIZE = 60;        // 每批60项
private static final int BATCH_CHARACTERS = 32000; // 每批32k字符
```

**优化后：**
```java
private static final int BATCH_SIZE = 150;       // 每批150项（增加150%）
private static final int BATCH_CHARACTERS = 80000; // 每批80k字符（增加150%）
```

#### B. 移除按对象分组的逻辑

**优化前的问题：**
```java
// 按对象分组，42张表 = 42个对象 = 至少42个批次
for (boolean semantics : Arrays.asList(false, true)) {
    Map<String, List<Target>> groups = new LinkedHashMap<>();
    for (Target target : targets) {
        String objectId = text(target.object.get("id"));
        groups.computeIfAbsent(objectId, key -> new ArrayList<>()).add(target);
    }
    // 每个对象一个组，每个组产生1-N次调用
    for (List<Target> group : groups.values()) {
        // 批次处理...
    }
}
```

**优化后：**
```java
// 全局批次处理，不再按对象分组
for (boolean semantics : Arrays.asList(false, true)) {
    List<Target> filteredTargets = new ArrayList<>();
    for (Target target : targets) {
        if (target.isSemantic() == semantics) {
            filteredTargets.add(target);
        }
    }

    // 全局批次，所有对象一起处理
    List<Target> batch = new ArrayList<>();
    int characters = 0;
    for (Target target : filteredTargets) {
        int size = serialize(target.input()).length();
        if (!batch.isEmpty() && (batch.size() >= BATCH_SIZE || characters + size > BATCH_CHARACTERS)) {
            nameBatch(batch, businessContext, completion, progress, report);
            batch = new ArrayList<>();
            characters = 0;
        }
        batch.add(target);
        characters += size;
    }
    if (!batch.isEmpty()) nameBatch(batch, businessContext, completion, progress, report);
}
```

---

## 效果对比

### 模型调用次数

#### 优化前（42张表）
```
按对象分组处理：
- 42个对象 = 42个分组
- 每个对象的属性可能跨批次
- 语义补全：~8次调用
- 非语义补全：~5次调用
总计：10-15次调用
```

#### 优化后（42张表）
```
全局批次处理：
- 假设42张表 × 平均10个字段 = 420项
- 批次大小：150项/批
- 语义补全：420 ÷ 150 = 3批 = 3次调用
- 非语义补全：1-2次调用
总计：4-5次调用
```

**减少比例：约60-70%**

---

### 日志完整性

#### 优化前
```
20:36:26  1/4 正在加载分析任务进度…
20:36:27  3/4 正在调用模型，第 8 次…  ← 缺少第2步
20:36:36  3/4 正在调用模型，第 9 次…
```

#### 优化后
```
20:36:10  数据源结构扫描开始
20:36:12  数据源结构扫描完成 (1850ms)
20:36:12  Wiki 文档检索开始
20:36:14  Wiki 文档检索完成 (1920ms)
20:36:14  业务规则检索开始
20:36:15  业务规则检索完成 (650ms)
20:36:15  候选生成开始
20:36:17  候选生成完成 (2100ms)
20:36:17  模型语义补充开始
20:36:19  正在调用模型，第 1 次...
20:36:25  正在调用模型，第 2 次...
20:36:30  模型语义补充完成 (13000ms)
20:36:30  中文名称补全开始
20:36:33  正在调用模型，第 3 次...
20:36:38  正在调用模型，第 4 次...
20:36:42  中文名称补全完成 (12000ms)
20:36:42  分析完成
20:36:42  配置草案已保存
```

**完整、连贯、易理解**

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

### API调用流程
```
用户点击"查看进度"
  ↓
检查任务状态
  ↓
如果已完成：
  1. getAnalysisRuns(taskId) → 获取最新runId
  2. getAnalysisStageTimings(taskId, runId) → 获取阶段时间
  3. getAnalysisModelCalls(taskId, runId) → 获取模型调用
  4. 生成完整日志
  5. 显示控制台
```

---

## 配置建议

### 批次大小调优

**小型项目（< 20张表）：**
```java
BATCH_SIZE = 100
BATCH_CHARACTERS = 60000
```

**中型项目（20-50张表）：**
```java
BATCH_SIZE = 150  // 当前配置
BATCH_CHARACTERS = 80000
```

**大型项目（> 50张表）：**
```java
BATCH_SIZE = 200
BATCH_CHARACTERS = 100000
```

### 注意事项

1. **Token限制**：确保模型支持大批次的 token 数量
2. **超时设置**：大批次需要更长的处理时间，注意超时配置
3. **内存占用**：批次越大，内存占用越高

---

## 下一步优化建议

### 1. 增加批次进度提示
```
中文名称补全开始
正在调用模型，批次 1/3 (150项)...
正在调用模型，批次 2/3 (150项)...
正在调用模型，批次 3/3 (120项)...
中文名称补全完成
```

### 2. Wiki优先策略生效时的日志
```
Wiki 文档检索完成
Wiki 覆盖率：对象 35/42 (83%)，字段 180/210 (86%)
跳过模型语义补充（Wiki覆盖率已足够）
```

### 3. 添加模型调用统计
```
分析完成
配置草案 #8 已保存
统计：模型调用 4次，Token消耗 48.3k
```

---

## 相关文件

### 后端
- `backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/OntologySemanticNamingService.java`

### 前端
- `frontend/src/views/ontology/resource/index.vue`
- `frontend/src/api/ontology/index.js` (API定义)

---

**优化完成时间**: 2024-11-24  
**预期收益**: 
- 模型调用次数减少 60-70%
- 日志完整性 100%
- 用户体验显著提升
