# Code Wiki · 领导关注点与建议答复

[返回 Code Wiki](CODE_WIKI.md) · [核心实现](code-wiki-implementation.md) · [构建运行](code-wiki-operations.md)

## 项目汇报口径

项目将行业资料和数据库表结构整理成可追溯、可人工审核的本体草案，并基于审核后的语义配置开展问数。仓库可以定位资料整理、问答引用、异步分析、草案管理、受控查询、导出和可选的外部导入代码。交付验收应以目标环境的联调结果、业务口径准确率、峰值容量和数据权限为准。[cite:1][cite:2][cite:3]

以下问题为建议汇报口径。实际管理层的优先级、验收指标和上线范围需要项目方确认。

## 常见问题与证据

| 关注点 | 建议答复 | 建议出示的证据 |
| --- | --- | --- |
| **投入能换来什么？** | 可将资料、规则和表结构串联到同一份可版本化草案。业务人员能查资料出处、确认语义、复用草案进行问数。量化收益需要测量建模工时、人工改写比例和资料复用次数。[cite:1][cite:2] | 同一业务域的原资料、草案及修订记录 |
| **既有 BI 和数据仓库怎么配合？** | 应用通过 JDBC 在业务库读结构和查询，草案承担术语、字段、对象与关系映射。可以选已有报表作为业务口径核对基准。[cite:2][cite:3] | 指标口径、草案映射和同一日期范围的结果对照 |
| **没有模型还能做什么？** | 基础管理、JDBC 接入、规则分析和部分确定性查询有对应代码路径。资料整理、Wiki 问答、部分语义补充与复杂语言解析依赖模型配置。[cite:2][cite:3] | 用相同测试数据分别验证启用模型和关闭模型的可用功能 |
| **AI 生成的知识和语义怎么验收？** | Wiki 问答展示引用。分析草案支持人工校对、差异对比和回退。由领域负责人核对来源、主外键关系、口径和变更记录。[cite:2] | 附引用的回答、可复核的草案差异和误判案例 |
| **能对业务数据执行什么操作？** | 查询按草案目录生成结构化计划，服务端校验并编译 SQL，JDBC 执行使用参数绑定、只读设置、超时和行数限制。实际数据库用户权限、对象和字段授权、行策略及租户隔离需逐项验收。[cite:3] | 只读账号权限清单、按角色测试、跨用户历史隔离结果 |
| **敏感数据会传到哪里？** | JDBC 样例和 Wiki 上下文随功能进入分析链路，具体模型外发范围取决于场景和配置。查询历史可能在系统 MySQL 保存结果副本。上线前需确认可用模型、可外发字段、留存与清理策略、CSV 权限。[cite:1][cite:2][cite:3] | 经过授权的样例、模型请求字段清单、脱敏与留存验证 |
| **千表、多用户和故障恢复能保证吗？** | 源码有异步线程池、租约、检查点、取消与恢复。千表耗时、资源占用、并发吞吐、模型成本和多实例恢复时效需要真实负载测试。[cite:2][cite:5] | 真实规模数据源的分阶段耗时、队列指标与重启恢复记录 |
| **问数评测通过能覆盖哪些能力？** | 评测执行预设 `plan` 并比对结果，主要验证计划执行正确性。`queryEnabled` 依据配置准入校验，`evaluationPassed` 单独返回。自然语言解析和业务口径准确率需要端到端标注集验证。[cite:3][cite:4] | 计划评测结果、自然语言问句与标准答案、澄清用例 |
| **可以交付给外部系统吗？** | 有 OWL/RDF 文件、工程 TAR、外部配置适配和目标平台导入代码。工程 TAR 部分生成逻辑仍为 stub。接收格式、校验、推理和正式行动执行需在目标平台验收。[cite:1][cite:2] | 真实草案导入、目标平台回执和产物完整性检查 |
| **外部助手如何接入？** | 提供带独立令牌的 MCP HTTP 入口，支持初始化、列举工具和调用。`run_analysis` 返回操作指引，适配的客户端及令牌范围需联调验证。[cite:2] | 目标客户端工具清单、调用成功与令牌撤销记录 |
| **是否具备生产上线条件？** | 仓库有部署入口、数据库增量和测试源码。生产拓扑、权限与秘密管理、备份恢复、集成可用性、发布回滚和运营指标情况需要目标环境证据。[cite:1][cite:5] | 生产演练记录、恢复结果、可观测指标与业务签收 |

## 建议验收顺序

1. **完整业务链。** 选择一个真实业务域，走通资料录入及引用、接源扫描、分析、校对、草案完成、问数与导出。用同一业务样本关联各环节证据。
2. **口径与权限。** 由业务方核对维度、指标、时间窗口及明细，验证不同角色的菜单、草案和历史访问边界，以及外部业务账号权限。
3. **容量与可靠性。** 分别记录大表量分析与日常并发问数的耗时和资源占用，验证调用超时、任务取消、服务重启恢复和模型不可用时的表现。
4. **发布和交付。** 在目标环境验证数据库迁移、静态访问、备份恢复、可选平台导入及可回退性，确定负责人与可量化上线门槛。

## 源码依据

- <a id="cite-1"></a>[cite:1] [仓库 README](../README.md)、[产品需求](01-product-requirements.md)。
- <a id="cite-2"></a>[cite:2] [分析和 Wiki 入口](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/controller/OntologyController.java)、[导出实现](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/OntologyArtifactExporter.java#L379)、[MCP 工具](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/mcp/McpToolRegistry.java)。
- <a id="cite-3"></a>[cite:3] [查询规划](../backend/ontology-query/src/main/java/com/ontologywiki/query/planning/QueryPlannerService.java)、[JDBC 查询](../backend/ontology-query/src/main/java/com/ontologywiki/query/dao/JdbcBusinessQueryDao.java)、[查询历史](../backend/ontology-query/src/main/java/com/ontologywiki/query/service/impl/QueryHistoryServiceImpl.java)。
- <a id="cite-4"></a>[cite:4] [评测实现](../backend/ontology-query/src/main/java/com/ontologywiki/query/service/impl/QueryBenchmarkServiceImpl.java)、[查询准入](../backend/ontology-query/src/main/java/com/ontologywiki/query/policy/QueryReadinessService.java)。
- <a id="cite-5"></a>[cite:5] [分析线程池](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/config/AnalysisExecutorConfig.java)、[作业存储](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/analysis/AnalysisJobStore.java)、[工程运行章节](code-wiki-operations.md)。
