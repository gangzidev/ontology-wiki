# Code Wiki · 构建、运行、迁移与排障

[返回 Code Wiki](CODE_WIKI.md) · [核心实现](code-wiki-implementation.md) · [领导问答](code-wiki-management.md)

## 环境与启动顺序

| 依赖 | 用途与检查项 |
| --- | --- |
| JDK 与 Maven | 父 POM 编译目标 Java 8。实际运行 JDK 需与驱动和依赖版本联测 |
| Node.js 与 npm | 运行 Vue CLI 4。工作区未跟踪依赖锁文件，应记录验证过的 Node/npm 版本与依赖解析结果 |
| MySQL | 系统元数据库，先初始化，再启动后端 |
| Redis | 登录会话、验证码与系统缓存，启动前配置正确地址、库号与认证 |
| 外部业务库 | 通过应用登记业务 JDBC 数据源，供表结构扫描和问数使用 |
| 模型、智文、本体平台 | 按需开通与验收，分别核对模型调用、外部问答和草案接收 |

按 MySQL 和 Redis → 后端 → 前端 → 可选集成的顺序验证。[cite:1][cite:2][cite:3]

## 系统库初始化和升级

全新环境应先创建专用 `utf8mb4` 数据库，将 `backend/sql/ontology-wiki.sql` 导入空库。脚本包含 `DROP TABLE`，也包含系统角色、菜单、初始账号和业务表。初始账号的启用和凭据应由部署方管理。如需评测留档，空库执行基线后还需应用评测表增量。[cite:1][cite:4]

已有库应比对表结构、菜单及草案状态，并按目标版本选用增量脚本。先备份并在副本验证变更及恢复。[cite:4]

| 增量 | 用途 |
| --- | --- |
| `migrate-analysis-job.sql` | 创建分析作业表。基线已含此表 |
| `migrate-data-query-menu.sql` | 补充查询菜单和角色授权 |
| `migrate-proposal-completion.sql` | 调整旧草案状态，删除指定旧菜单和授权 |
| `migrate-query-evaluation.sql` | 建立查询评测记录表 |

## 后端构建与运行

以下命令均从仓库根目录执行。[cite:1][cite:2]

```bash
mvn -f backend/pom.xml -pl ontology-admin -am clean package -DskipTests
java -jar backend/ontology-admin/target/ontology-admin.jar \
  --spring.config.additional-location=file:/path/to/external-config/
```

将示例路径替换为实际外部配置目录，按 Spring Boot 规则提供 `application.yml` 或对应的 profile 配置。IDE 可使用 `OntologyWikiApplication` 主类和同等的配置参数。本地默认端口为 `8080`、上下文为 `/`、数据库 profile 为 `druid`。[cite:1][cite:3]

| 配置键 | 用途 |
| --- | --- |
| `spring.datasource.druid.master.url`、`.username`、`.password` | MySQL 系统库与凭据 |
| `spring.redis.host`、`.port`、`.database`、`.password` | Redis 连接 |
| `token.secret`、`token.header`、`token.expireTime` | JWT 签名、请求头及会话有效期配置 |
| `ontology.integration.credential-key` | 集成凭据保护 |
| `ontology.integration.llm-wiki.*` | `enabled`、`api-base-url`、`api-key`、`model` 和连接、读取超时。启动时还可能读取系统库中的启用模型 |
| `ontology.integration.zhiwen.*` | 可选智文服务的地址、身份、流程和签名参数 |
| `ontology.integration.platform.*` | 可选本体平台的地址、令牌和导入路径 |

根目录本地 `.env.*` 或 `local-config/` 需经启动方式显式接入。`ONTOLOGY_DB_URL`、`LLM_API_KEY` 等本地简写变量在打包 YAML 中没有直接映射。以进程实际加载的 Spring 属性为准。[cite:3][cite:7]

## 前端开发与发布

在 `frontend` 目录执行。[cite:1][cite:5]

```bash
npm install
npm run dev
```

当前工作区开发配置以 `/dev-api` 为前缀，开发代理移除前缀后访问 `http://localhost:8080`。开发服务默认端口为 `5173`，可通过 `port` 或 `npm_config_port` 覆盖。干净克隆需检查各模式文件是否随仓库分发。若缺失，使用该模式的本地环境文件提供 `VUE_APP_BASE_API`，开发模式设为 `/dev-api`，并按需要补齐页面标题与其余构建配置。[cite:5]

生产执行 `npm run build:prod`，预发布执行 `npm run build:stage`。静态文件落在 `frontend/dist`。当前工作区生产使用 `/prod-api`，预发布使用 `/stage-api`，前缀在构建时确定。静态站点将对应 API 路径转发至 Spring Boot 并按部署配置移除前缀，其余深层页面支持 Vue Router history 刷新回退至 `index.html`。长请求需核对浏览器、代理和模型调用的各层超时。线上代理、TLS 和发布配置需在目标环境确认。[cite:5][cite:6]

## 验证入口

| 验证 | 入口与实际覆盖 |
| --- | --- |
| 后端自动化 | 根目录执行 `mvn -f backend/pom.xml -pl ontology-admin -am test`。现有测试涉及候选生成、草案、Mapper、任务租约及恢复、问数规划与 SQL 编译与执行。部分集成测试依赖 H2 和测试替身 |
| 前端脚本 | 安装前端依赖后，从根目录执行 `node scripts/analysis-progress-smoke.cjs` 和 `node scripts/data-query-detail-smoke.cjs`。覆盖进度与取消恢复、迟到响应、表和字段选择及明细分页。脚本通过模板编译和模拟上下文执行 |
| 端到端冒烟 | 启动实例后依次核对验证码与登录、菜单、空间及资产、测试源库并扫描、分析与草案、配置目录与问数、可选导出导入与历史。模型整理和问答需使用已授权的测试资料 |

## 排障索引

| 现象 | 检查点 |
| --- | --- |
| 后端启动失败 | 校验生效的 profile、MySQL 表和连接、Redis 认证、JDK 与运行日志 |
| 登录后无业务菜单 | 检查 `/getInfo` 权限、`/getRouters` 响应和角色菜单授权，核对迁移脚本 |
| 前端 API 404 或刷新白屏 | 比对构建时前缀和代理规则，核对静态路由回退 |
| 模型未连接或问答超时 | 检查 `/ontology/wiki/status`、场景配置及模型连通性测试，核对代理与服务端超时 |
| 分析无法恢复或进度停滞 | 查作业状态、租约、检查点及应用日志，再核对业务 JDBC 和模型调用 |
| 问数入口不可用 | 检查草案状态、配置和授权。`evaluationPassed` 与 `queryEnabled` 分开判断 |

## 源码依据

- <a id="cite-1"></a>[cite:1] [仓库 README](../README.md)。
- <a id="cite-2"></a>[cite:2] [父 POM](../backend/pom.xml)、[Admin POM](../backend/ontology-admin/pom.xml)。
- <a id="cite-3"></a>[cite:3] [application.yml](../backend/ontology-admin/src/main/resources/application.yml)、[application-druid.yml](../backend/ontology-admin/src/main/resources/application-druid.yml)。
- <a id="cite-4"></a>[cite:4] [基线](../backend/sql/ontology-wiki.sql)、[分析作业](../backend/sql/migrate-analysis-job.sql)、[菜单](../backend/sql/migrate-data-query-menu.sql)、[草案完成](../backend/sql/migrate-proposal-completion.sql)、[评测表](../backend/sql/migrate-query-evaluation.sql)。
- <a id="cite-5"></a>[cite:5] [package.json](../frontend/package.json)、[vue.config.js](../frontend/vue.config.js)、[前端忽略规则](../frontend/.gitignore)。
- <a id="cite-6"></a>[cite:6] [Router](../frontend/src/router/index.js#L179)、[Axios](../frontend/src/utils/request.js#L40)。
- <a id="cite-7"></a>[cite:7] [LlmWikiClient.java](../backend/ontology-core/src/main/java/com/ontologywiki/ontology/service/LlmWikiClient.java#L99)。
- 验证源码：[分析集成测试](../backend/ontology-query/src/test/java/com/ontologywiki/integration/AnalysisServicesIntegrationTest.java) · [分析前端脚本](../scripts/analysis-progress-smoke.cjs) · [问数前端脚本](../scripts/data-query-detail-smoke.cjs)。
