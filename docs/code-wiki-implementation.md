# Code Wiki · 核心实现与源码索引

[返回 Code Wiki](CODE_WIKI.md) · [构建运行](code-wiki-operations.md) · [领导问答](code-wiki-management.md)

## 启动、登录和权限

| 入口 | 关键职责与实现 | 源码 |
| --- | --- | --- |
| `OntologyWikiApplication.main()` | 启动 Spring Boot，排除默认数据源自动装配，由 `DruidConfig` 提供系统库数据源 | [OntologyWikiApplication.java](../backend/ontology-admin/src/main/java/com/ontologywiki/OntologyWikiApplication.java#L12)、[DruidConfig.java](../backend/ontology-framework/src/main/java/com/ontologywiki/framework/config/DruidConfig.java#L36) |
| `SysLoginService.login()`、`TokenService.createToken()` | 校验验证码和登录条件，经 Spring Security 认证并校验 BCrypt 密码，签发 JWT。登录对象写入 Redis | [SysLoginService.java](../backend/ontology-framework/src/main/java/com/ontologywiki/framework/web/service/SysLoginService.java#L63)、[TokenService.java](../backend/ontology-framework/src/main/java/com/ontologywiki/framework/web/service/TokenService.java#L114) |
| `JwtAuthenticationTokenFilter`、`PermissionService.hasPermi()` | 从请求头解析令牌、恢复会话及必要时续期，将用户写入安全上下文。方法权限通过 `@PreAuthorize` 检查 | [JwtAuthenticationTokenFilter.java](../backend/ontology-framework/src/main/java/com/ontologywiki/framework/security/filter/JwtAuthenticationTokenFilter.java#L31)、[PermissionService.java](../backend/ontology-framework/src/main/java/com/ontologywiki/framework/web/service/PermissionService.java#L27) |
| `GetInfo`、`GenerateRoutes` | Vue 守卫检查会话，调用 `/getInfo`、`/getRouters` 获取角色、权限和菜单。服务端组件名解析为本地组件并动态挂载路由。按钮权限指令控制界面可见性 | [permission.js](../frontend/src/permission.js#L18)、[permission.js](../frontend/src/store/modules/permission.js#L33)、[hasPermi.js](../frontend/src/directive/permission/hasPermi.js#L11) |

通用资源以 `ontology:{resource}:{action}` 检查权限，知识问答使用 `ontology:dashboard:list`，问数接口通常使用 `ontology:proposals:query`，评测使用 `ontology:proposals:edit`。系统管理的 `@DataScope` 只作用于标注方法，典型场景为按部门或本人过滤用户列表。内容资产与外部库的对象级、字段级和行级策略需要分别验收。[cite:1][cite:2][cite:3]

## 前端页面和 API

Vue 入口装配 Router、Vuex、Element UI 与全局守卫，页签经布局组件缓存。共享 Axios 层以 `VUE_APP_BASE_API` 为前缀，附加 Bearer token，默认超时 10 秒，部分写请求做短时去重。Wiki 问答和资料整理配置 180 秒，数据问数配置 120 秒。生产代理的读超时与这些长请求有关。[cite:4][cite:5]

| 页面 | 入口和交互 | 源码 |
| --- | --- | --- |
| 工作台 | `/index` 提供入口。业务看板查询空间、资产、数据源、分析任务与草案统计 | [index.vue](../frontend/src/views/index.vue#L45)、[index.vue](../frontend/src/views/ontology/dashboard/index.vue#L137) |
| 知识空间、资料、数据源、分析、草案 | `ResourcePage` 根据路由末段选择 `configs`。`readAssetFile()` 用 `FileReader` 读取不超过 5 MB 的文本，作为正文 JSON 保存。`startAnalysisProgress()` 轮询后台作业；草案页面支持版本差异、回退、导出与完成 | [index.vue](../frontend/src/views/ontology/resource/index.vue#L313) |
| 知识问答与校对 | `send()` 提交问题、会话、范围和保存选项，收到完整响应后展示引用。`saveAnalysisAnswer()` 提交已确认的变更集。前端保留快照以撤销校对操作 | [index.vue](../frontend/src/views/ontology/chat/index.vue#L1077)、[index.vue](../frontend/src/views/ontology/chat/index.vue#L1393) |
| 数据问数 | `sendQuestion()` 调用问数接口。`buildOption()` 组装 ECharts 配置。页面可恢复历史、切换草案、分页或下钻明细、导出当前批次 CSV | [index.vue](../frontend/src/views/ontology/data-query/index.vue#L923)、[index.vue](../frontend/src/views/ontology/data-query/index.vue#L996) |
| 模型与 MCP 设置 | 模型配置支持 CRUD 和连接测试。MCP 页面管理独立令牌 | [index.vue](../frontend/src/views/ontology/settings/index.vue#L240)、[mcp.vue](../frontend/src/views/ontology/settings/mcp.vue#L139) |

Wiki 资产图与草案图的输入不同。`loadAssetGraph()` 获取后端 `nodes` 和 `edges` 并渲染 ECharts 力导向图。`renderGraph()` 从当前草案的 `analysisConfig.objects`、`relations` 在浏览器构造图。导入 API 封装与页面壳可在源码定位，实际页面调用及部署菜单可见性待联调确认。[index.vue](../frontend/src/views/ontology/resource/index.vue#L347) · [index.vue](../frontend/src/views/ontology/resource/index.vue#L566) · [index.js](../frontend/src/api/ontology/index.js#L22)

## 通用资源与知识问答

1. **资源持久化。** `OntologyController` 解析 `/ontology/{resource}` 请求。`OntologyResource` 定义资源键、对应表、主键和字段。`OntologyResourceService` 经 `OntologyResourceRepository` 和 Mapper XML 访问系统库，列表与仪表盘、关系图入口也在 Controller。[cite:1][cite:6]
2. **资料整理。** `POST /ontology/wiki/assets/{id}/ingest` 进入 `LlmWikiClient.ingestAsset()`，读取启用资料并请求模型输出包含 Markdown 的 JSON，按 `SOURCE:{assetId}` 更新或插入 `WIKI_PAGE` 并刷新索引。URL 登记保存 HTTP(S) 来源。模板化的 `WikiIngestionService` 是另一个源码入口，核对调用者时以 Controller 实际接线为准。[cite:1][cite:7]
3. **问答检索。** `POST /ontology/wiki/ask` 支持 `ALL`、`SPACE`、`ASSET`、`ANALYSIS`。普通范围由 `WikiLexicalRetriever` 按中文、英文词法排名，主路径取至多 3 条来源。分析范围装配草案上下文。全局范围可按配置调用智文。回答、引用和会话持久化，回答可选择存为 Wiki 页。词法索引是已定位的主检索实现，向量数据库能力没有对应运行依赖证据。[cite:1][cite:7]
4. **模型选择。** `LlmWikiClient` 按启用配置与业务场景选模型，并尝试 `ALL` 场景配置。请求走 OpenAI 兼容 `/chat/completions`。模型配置可由数据库加载，配置文件与后台显示需要一起核对。[cite:7]
5. **外部 MCP。** `McpController` 处理 `POST /mcp`。应用 JWT 过滤链允许该 Controller 接收请求，方法内验证专用 Bearer 令牌的 SHA-256 摘要。`McpToolRegistry` 分发 `initialize`、`tools/list`、`tools/call` 所需工具能力。`run_analysis` 给出操作指引。[cite:8]

## 异步分析与草案交付

```text
登记业务源 → 连通测试 / JDBC 扫描 → 创建分析任务并绑定知识空间
→ 提交分析 → 专用线程池加载结构、知识、规则和可选样例
→ 规则生成候选 → 按配置做语义补充及中文命名 → 保存草案和检查点
→ 人工校对与版本管理 → 完成 → 问数 / 导出 / 按配置导入目标平台
```

| 关键类和函数 | 行为及边界 | 源码 |
| --- | --- | --- |
| `DataSourceScanner.scan()`、`CredentialService` | JDBC 元数据读取表、列、键，并按任务参数采样。连接凭据使用 AES-GCM 存储。生产连接建议采用数据库端最小权限 | [DataSourceScanner.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/DataSourceScanner.java#L37)、[CredentialService.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/CredentialService.java#L28) |
| `AnalysisJobServiceImpl`、`AnalysisJobStore` | 提交作业、记录检查点，通过运行令牌和租约维护作业状态。前端轮询进度，用户可请求取消并恢复。外部调用的停止时效需实测 | [AnalysisJobServiceImpl.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/analysis/AnalysisJobServiceImpl.java#L62)、[AnalysisJobStore.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/analysis/AnalysisJobStore.java#L33) |
| `OntologyGenerationEngine.generate()`、`OntologySemanticNamingService` | 将扫描快照、资料和业务线索转成对象、属性与关系候选，支持合并增量扫描快照。模型负责部分语义补充与命名，人工确认最终口径 | [OntologyGenerationEngine.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/OntologyGenerationEngine.java#L63)、[OntologySemanticNamingService.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/OntologySemanticNamingService.java#L24) |
| `OntologyProposalService`、`OntologyArtifactExporter` | 校验完成，比较版本、回退、导出和导入。平台导入要求 `COMPLETED`，成功后为 `IMPORTED`。RDF 导出使用 `.owl` 文件名和 `text/turtle`，工程 TAR 内部分产物仍是 stub | [OntologyProposalService.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/analysis/OntologyProposalService.java#L76)、[OntologyArtifactExporter.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/OntologyArtifactExporter.java#L379) |
| `ExternalOntologyConfigController` | 通过 `/ontology/external-config/import` 和 `/export` 暴露格式转换。目标平台导入另由草案服务处理 | [ExternalOntologyConfigController.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/controller/ExternalOntologyConfigController.java#L12) |

## 数据问数的规划与执行

1. **加载配置与检查准入。** `QueryConfigurationRepository` 从草案关联分析任务定位数据源。`QueryReadinessService.check()` 校验草案状态及配置，接受 `COMPLETED` 和 `IMPORTED`。记录独立返回 `evaluationPassed`，当前 `queryEnabled` 由准入校验结果决定。[cite:9]
2. **生成语义目录与计划。** `QuerySemanticCatalogService` 提供对象、指标、维度等目录。`QueryPlannerService.plan()` 用语义匹配和检索收窄候选，常见明细和简单聚合可走确定性路径。复杂请求可引入模型解析过滤、排序、时间或比较条件，产出结构化计划，语义不明确时可以澄清。[cite:10]
3. **校验并编译。** `QueryPlanValidator` 根据草案目录检查对象、字段及计划结构。`QuerySqlCompiler` 由受控计划生成 SQL 和参数。Java 后端负责 SQL 编译，模型输出用作结构化计划。[cite:11]
4. **查询与展示。** `QueryExecutionServiceImpl` 调用 `JdbcBusinessQueryDao`。DAO 使用 `PreparedStatement`，配置连接只读、30 秒语句超时和 1,000 行上限。比较查询分期执行后合并。`DataQueryServiceImpl.ask()`、`drilldown()` 编排问数与下钻，`QueryHistoryServiceImpl` 保存结果和历史。[cite:11][cite:12]
5. **评测。** `QueryBenchmarkServiceImpl` 执行用例中的 `plan` 并比较结果，结果按草案配置哈希记录。自然语言理解、口径确认和真实数据验证需要单独的端到端用例。[cite:9][cite:12]

## API 导航

下表使用后端实际路径。前端代理前缀由构建与代理配置决定。[cite:1][cite:2][cite:5][cite:8]

| 前缀或入口 | 主要方法与路径 |
| --- | --- |
| 登录与引导 | `POST /login`，`GET /captchaImage`、`/getInfo`、`/getRouters` |
| `/ontology` 通用资源 | `GET /{resource}/list`、`/{resource}/{id}`，`POST /{resource}`，`PUT /{resource}/{id}`，`DELETE /{resource}/{ids}` |
| `/ontology/wiki` | `POST /ask`、`/sources/url`、`/assets/{id}/ingest`，`GET /sessions`、`/messages`、`/status`，以及 `/pages`、`/llm-configs`、`/mcp-tokens` 管理 |
| `/ontology/datasources` | `POST /test`、`/{id}/credential`、`/{id}/scan` |
| `/ontology/analyses/{id}` | `POST /analyze`、`/cancel`、`/resume`、`/answer/refine`，`GET /job`、`/latest-result`、`/knowledge`、`/context`、`/export`，`PUT /answer` |
| `/ontology/proposals/{id}` | `POST /complete`、`/rollback`、`/import`，`GET /diff`、`/export` |
| `/ontology/data-query` | `GET /catalog/{proposalId}`、`/readiness/{proposalId}`、`/history/{proposalId}`，历史详情追加 `/{sessionId}`。`POST /{operation}/{proposalId}` 的操作包括 `plan`、`compile`、`execute`、`ask`、`drilldown`、`evaluate` |
| MCP | `POST /mcp`，专用令牌 |

## 源码依据

- <a id="cite-1"></a>[cite:1] [OntologyController.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/controller/OntologyController.java#L90)。
- <a id="cite-2"></a>[cite:2] [SysLoginController.java](../backend/ontology-admin/src/main/java/com/ontologywiki/web/controller/system/SysLoginController.java#L57)、[DataQueryController.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/controller/DataQueryController.java#L23)。
- <a id="cite-3"></a>[cite:3] [SecurityConfig.java](../backend/ontology-framework/src/main/java/com/ontologywiki/framework/config/SecurityConfig.java#L109)、[DataScopeAspect.java](../backend/ontology-framework/src/main/java/com/ontologywiki/framework/aspectj/DataScopeAspect.java#L91)。
- <a id="cite-4"></a>[cite:4] [main.js](../frontend/src/main.js#L79)、[index.js](../frontend/src/store/index.js#L14)、[AppMain.vue](../frontend/src/layout/components/AppMain.vue#L4)。
- <a id="cite-5"></a>[cite:5] [request.js](../frontend/src/utils/request.js#L40)、[index.js](../frontend/src/api/ontology/index.js)。
- <a id="cite-6"></a>[cite:6] [OntologyResource.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/domain/OntologyResource.java#L25)、[OntologyResourceService.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/OntologyResourceService.java#L99)、[OntologyResourceRepository.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/repository/OntologyResourceRepository.java#L55)、[OntologyResourceMapper.xml](../backend/ontology-core/src/main/resources/mapper/ontology/OntologyResourceMapper.xml)。
- <a id="cite-7"></a>[cite:7] [LlmWikiClient.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/LlmWikiClient.java#L385)、[WikiLexicalRetriever.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/WikiLexicalRetriever.java#L14)、[WikiIngestionService.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/wiki/WikiIngestionService.java#L71)。
- <a id="cite-8"></a>[cite:8] [McpController.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/mcp/McpController.java#L45)、[McpToolRegistry.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/mcp/McpToolRegistry.java#L53)。
- <a id="cite-9"></a>[cite:9] [QueryConfigurationRepository.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/repository/QueryConfigurationRepository.java)、[QueryReadinessService.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/policy/QueryReadinessService.java#L42)、[QueryConfigurationValidator.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/validation/QueryConfigurationValidator.java#L18)。
- <a id="cite-10"></a>[cite:10] [QuerySemanticCatalogService.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/catalog/QuerySemanticCatalogService.java)、[QueryPlannerService.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/planning/QueryPlannerService.java#L38)。
- <a id="cite-11"></a>[cite:11] [QueryPlanValidator.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/validation/QueryPlanValidator.java#L27)、[QuerySqlCompiler.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/compiler/QuerySqlCompiler.java#L13)、[QueryExecutionServiceImpl.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/service/impl/QueryExecutionServiceImpl.java#L49)、[JdbcBusinessQueryDao.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/dao/JdbcBusinessQueryDao.java#L17)。
- <a id="cite-12"></a>[cite:12] [DataQueryServiceImpl.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/service/impl/DataQueryServiceImpl.java#L77)、[QueryHistoryServiceImpl.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/service/impl/QueryHistoryServiceImpl.java#L85)、[QueryBenchmarkServiceImpl.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/service/impl/QueryBenchmarkServiceImpl.java#L55)。
