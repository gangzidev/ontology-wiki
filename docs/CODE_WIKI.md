# 数智本体 Wiki · Code Wiki

> 核对基线 `55ce5f4`，工程声明版本 `3.9.1`。以仓库源码、构建清单、SQL 和脚本为依据。工作区环境文件仅用于辨认配置入口，线上配置、容量和业务准确率需要运行验证。

## 阅读入口

| 读者 | 建议入口 |
| --- | --- |
| 研发和交接人员 | 本页架构、模块依赖、数据地图 → [核心实现与源码索引](code-wiki-implementation.md) |
| 部署和测试人员 | [构建、运行、迁移与排障](code-wiki-operations.md) |
| 项目负责人、汇报与验收人员 | [领导关注点与建议答复](code-wiki-management.md) |

## 项目定位

数智本体 Wiki 将行业资料、规则和数据库结构整理为可追溯的本体草案。业务人员可通过知识问答查阅来源，在分析校对页修订候选对象、属性和关系。草案可完成、回退、导出或按配置提交到目标平台。符合准入条件的草案还可提供受控的数据问数、图表和明细下钻。部署中的推理、动作执行和业务口径验收需结合目标平台确认。[cite:1][cite:4][cite:5]

| 主链路 | 输入 | 产出 |
| --- | --- | --- |
| 知识整理与问答 | 空间、资料正文、提问范围、模型配置 | Wiki 页面、带来源的答复、会话历史 |
| 本体分析与交付 | 业务表元数据、可选受限样例、关联知识空间 | 候选配置、作业检查点、草案版本、导出件或导入记录 |
| 数据问数 | 已完成或已导入草案、自然语言或结构化计划 | 目录及准入状态、查询结果、图表与明细、评测和历史 |

## 整体架构

后端采用 Maven 多模块组织，部署时由 `ontology-admin` 装配为单个 Spring Boot JAR。前端是独立构建的 Vue SPA，通过 HTTP 请求后端。系统库保存应用资产与配置，外部业务库作为分析和查询的目标，Redis 保存登录会话等缓存。模型、智文、目标本体平台是按功能接入的外部依赖。[cite:1][cite:2][cite:3][cite:4][cite:5]

```text
浏览器 Vue SPA ──HTTP / JSON──> Spring Boot 应用
外部 MCP 客户端 ──POST /mcp────> 同一应用中的 MCP 入口
                                 │
         登录、权限、后台管理 ──┼──> MySQL 系统库、Redis
                  Wiki 与草案 ──┼──> MySQL 系统库、模型 / 智文 / 目标平台
          数据源扫描与数据问数 ──┴──> JDBC 业务数据源
```

## 模块职责与依赖

表中箭头表示 Maven POM 的直接模块依赖。`ontology-platform` 为父 POM，管理版本、公共依赖与模块聚合。[cite:2]

| 模块 | 职责 | 出向模块依赖 |
| --- | --- | --- |
| `ontology-admin` | `OntologyWikiApplication.main()` 启动进程。装配控制器、登录、系统管理、监控及公共上传 | framework、scheduler、generator、core、query |
| `ontology-framework` | Spring Security 过滤链、JWT 与 Redis 会话、Druid、MyBatis、数据权限切面 | system |
| `ontology-system` | 用户、角色、菜单、部门、岗位、字典、系统参数、日志 | common |
| `ontology-common` | 公共 Web 类型、注解、常量、异常、工具 | — |
| `ontology-core` | 知识空间与资产、模型调用、本体分析、草案生命周期、导出与 MCP | common |
| `ontology-query` | 语义目录、查询规划、权限和准入、计划校验、SQL 编译、执行、评测与历史 | core、common |
| `ontology-scheduler` | Quartz 定时任务、执行日志和监控接口 | common |
| `ontology-generator` | 通过 Velocity 模板从数据库表结构生成样板代码 | common |
| `frontend` | Vue 路由和状态、业务页面、后台管理、Axios API 客户端 | HTTP 依赖后端接口和菜单路由 |

`ontology-query` 通过草案与分析任务定位业务数据源，复用 core 的语义和连接能力。Quartz 管理的通用定时任务与本体分析的专用线程池是两条独立执行链。[cite:4][cite:5][cite:6]

## 技术栈与运行依赖

| 层次 | 技术与用途 |
| --- | --- |
| 后端构建 | Java 8 编译目标、Maven、Spring Boot `2.5.15`、Spring Framework `5.3.39`、Spring Security `5.7.14` |
| 应用数据 | MySQL、MyBatis Mapper XML、Druid `1.2.27`、PageHelper `1.4.7` |
| 会话 | JWT（JJWT `0.9.1`）+ Redis，应用层方法权限通过 Spring Security 校验 |
| 业务数据 | 原生 JDBC 读表结构与执行查询。扫描器识别 MySQL、PostgreSQL、Hive，实际查询方言须按业务源验证 |
| 前端 | Vue `2.6.12`、Vue Router `3.4.9`、Vuex `3.6.0`、Element UI `2.15.14`、Axios `0.28.1`、ECharts `5.4.0`、Vue CLI `4.4.6` |
| 外部服务 | OpenAI 兼容 `/chat/completions`。可选智文服务、目标本体平台与 MCP 客户端 |

以上版本为构建清单声明。[cite:2][cite:3][cite:4]

## 持久化与数据关系

| 数据 | 位置 | 关系与用途 |
| --- | --- | --- |
| 系统账号、角色与菜单 | MySQL `sys_*` | 用户角色关联菜单与方法权限，控制后台功能访问 |
| 知识空间、资产、Wiki 页面 | MySQL `wiki_space`、`wiki_asset` | 空间组织资料。页面资产通过 `SOURCE:{assetId}` 标记原始资料 |
| 业务源、分析定义、作业 | MySQL `ontology_datasource`、`analysis_task` 及分析作业表 | 任务关联数据源和知识空间，作业保存阶段、租约及检查点 |
| 草案与版本 | MySQL `ontology_proposal` | 草案关联分析任务，配置 JSON 描述对象、属性与关系，并可携带行动、逻辑等配置 |
| 问答与引用 | MySQL `wiki_chat_session`、`wiki_chat_message` 及引用记录 | 数据问数以 `DATA_QUERY` 范围复用会话机制，按用户与草案读取历史 |
| 查询评测 | MySQL `ontology_query_evaluation` | 由增量脚本建立，按草案、配置哈希与评测版本记录 |
| 登录会话及系统缓存 | Redis | JWT 用于定位登录会话，服务端可续期和清理会话 |
| 业务明细 | 外部 JDBC 业务库 | 查询在源库执行，系统库的查询历史可能保存结果副本 |

这是应用层关系图，实际表结构以 SQL 定义为准。[cite:4][cite:5][cite:7][cite:8]

## 常见变更入口

| 需求 | 首先定位 |
| --- | --- |
| 新增资源字段或列表条件 | 前端 `ResourcePage` → `OntologyResource` 定义 → 资源服务、仓储、Mapper → SQL 迁移 |
| 调整资料检索或模型回答 | `OntologyController` 问答入口 → `LlmWikiClient.queryResult()` → `WikiLexicalRetriever` |
| 调整候选生成、任务恢复或草案版本 | `AnalysisJobServiceImpl` → `AnalysisJobStore`、`OntologyGenerationEngine` → `OntologyProposalService` |
| 调整问数口径或支持的新查询形态 | 语义目录 → `QueryPlannerService` → `QueryPlanValidator` → `QuerySqlCompiler` → `JdbcBusinessQueryDao` |
| 排查菜单不可见 | 前端路由守卫 → `/getInfo` 和 `/getRouters` → 角色菜单授权及初始化或迁移 SQL |

关键方法和可跳转的源码入口见 [核心实现与源码索引](code-wiki-implementation.md)。

## 源码依据

- <a id="cite-1"></a>[cite:1] [README.md](../README.md) 与 [产品需求](01-product-requirements.md)。
- <a id="cite-2"></a>[cite:2] [父 POM](../backend/pom.xml)、[Admin POM](../backend/ontology-admin/pom.xml)、[Core POM](../backend/ontology-core/pom.xml)、[Query POM](../backend/ontology-query/pom.xml)。
- <a id="cite-3"></a>[cite:3] [package.json](../frontend/package.json)、[vue.config.js](../frontend/vue.config.js)。
- <a id="cite-4"></a>[cite:4] [OntologyController.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/controller/OntologyController.java)、[OntologyResource.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/domain/OntologyResource.java)。
- <a id="cite-5"></a>[cite:5] [DataQueryController.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/controller/DataQueryController.java)、[QueryConfigurationRepository.java](../backend/ontology-query/src/main/java/com/ontologywiki/query/repository/QueryConfigurationRepository.java)、[QueryHistoryMapper.xml](../backend/ontology-query/src/main/resources/mapper/query/QueryHistoryMapper.xml)。
- <a id="cite-6"></a>[cite:6] [AnalysisExecutorConfig.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/config/AnalysisExecutorConfig.java)、[SysJobServiceImpl.java](../backend/ontology-scheduler/src/main/java/com/ontologywiki/quartz/service/impl/SysJobServiceImpl.java)。
- <a id="cite-7"></a>[cite:7] [ontology-wiki.sql](../backend/sql/ontology-wiki.sql)、[migrate-query-evaluation.sql](../backend/sql/migrate-query-evaluation.sql)。
- <a id="cite-8"></a>[cite:8] [application.yml](../backend/ontology-admin/src/main/resources/application.yml)、[TokenService.java](../backend/ontology-framework/src/main/java/com/ontologywiki/framework/web/service/TokenService.java)。
