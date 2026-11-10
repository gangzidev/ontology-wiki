# 本体 Wiki 资产系统

本项目是数智本体 Wiki 资产系统，沉淀 LLM Wiki 行业知识、数据库结构分析输入、本体配置草案和导入审计记录。

## 技术路线

- 后端：Spring Boot 2.5、Java 8、Spring Security、JWT、MyBatis、MySQL。
- 前端：Vue 2.6、Vuex、Vue Router、Element UI。
- 权限：提供登录、动态菜单、用户、角色、部门和操作日志。
- 业务模块：`backend/ontology-core`。
- 第三方代码许可与版权归属：`backend/LICENSE-THIRD-PARTY`。

## 启动

### 数据库

首次部署到全新的专用数据库时，执行 [数据库初始化脚本](backend/sql/ontology-wiki.sql)：它一次性创建系统表、业务表并写入菜单与权限数据。该脚本包含 `DROP TABLE`，只允许对空库执行；不要在已有业务数据或已有数据的库上运行。

目标库若已有数据，请先备份并核对表结构，不要重跑初始化脚本；当前项目未提供对现有库的升级迁移脚本。不要把密码写入仓库。连接配置使用环境变量：

```sh
export ONTOLOGY_DB_URL='jdbc:mysql://10.19.38.197:3306/ontology-wiki?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai'
export ONTOLOGY_DB_USERNAME='ontology-wiki'
export ONTOLOGY_DB_PASSWORD='本地安全注入的密码'
export ONTOLOGY_TOKEN_SECRET='本地安全注入的令牌密钥'
export ONTOLOGY_CREDENTIAL_KEY='至少32位随机密钥'
export REDIS_HOST='10.19.38.197'
export REDIS_PORT='6379'
export REDIS_DATABASE='11'
export REDIS_PASSWORD='本地安全注入的 Redis 密码'
```

### 后端

```sh
cd backend
set -a
. ../.env.local
set +a
mvn -pl ontology-admin -am package -DskipTests
java -jar ontology-admin/target/ontology-admin.jar
```

默认地址：`http://localhost:8080`。

### 前端

```sh
cd frontend
npm install
npm run dev
```

默认地址：`http://localhost:5173`。登录后从左侧“本体 Wiki”进入工作台、Wiki资产、数据源、分析任务和配置草案。

## 已接通能力与配置

当前版本已接通：登录、菜单权限、Wiki 资产/空间/数据源/分析任务/配置草案管理；MySQL、PostgreSQL、Hive 的 JDBC 元数据扫描；按表选择的受限样例扫描；LLM Wiki 原生问答和基于数据库元数据的配置草案分析；分析答案可直接编辑或通过知识问答优化，确认后会生成带来源的 Wiki 知识页面并供后续分析优先参考；本体工程师完成草案修正后可通过配置化 HTTP 接口导入本体平台。Hive 驱动由业务模块依赖引入。

LLM Wiki 已内置在本项目 Spring Boot 后端，不需要安装/启动第三方 Wiki 客户端或本地 Wiki 服务。问答、来源整理、Wiki 页面和索引保存在本项目 MySQL；模型由 OpenAI 兼容 API 提供：

```sh
export LLM_WIKI_ENABLED=true
export LLM_API_BASE_URL='https://fast.sbbbbbbbbb.xyz/v1'
export LLM_API_KEY='本地安全注入的模型密钥'
export LLM_MODEL='gpt-6-sol'
```

在 Wiki 资产列表点击“整理 Wiki”可将原始资料整理为可累积页面；问答使用页面索引选取相关资料，不建设向量库或传统向量 RAG。详细流程见[内置 LLM Wiki](docs/08-llm-wiki-backend-integration.md)。如继续使用智文服务，可设置：

```sh
export ONTOLOGY_ZHIWEN_ENABLED=true
export ONTOLOGY_ZHIWEN_BASE_URL='https://你的智文服务地址'
export ONTOLOGY_ZHIWEN_SECRET_ID='...'
export ONTOLOGY_ZHIWEN_SECRET_KEY='...'
export ONTOLOGY_ZHIWEN_TENANT_ID='...'
export ONTOLOGY_ZHIWEN_AGENT_CODE='...'
```

如需把已完成的草案提交到本体平台，再设置 `ONTOLOGY_PLATFORM_ENABLED`、`ONTOLOGY_PLATFORM_BASE_URL`、`ONTOLOGY_PLATFORM_IMPORT_PATH` 和 `ONTOLOGY_PLATFORM_TOKEN`。导入协议由目标本体平台决定，未配置时不会发起外呼。

数据源密码使用 `ONTOLOGY_CREDENTIAL_KEY` 做 AES-GCM 加密保存，只能通过“设置密码”操作写入；不要把数据库密码或外部密钥提交到仓库。分析生成的对象、关系、逻辑和动作只保存为配置草案，由本体工程师修正并标记“草案完成”后即可使用或导入本体平台。

## 当前限制

智文接口的 HMAC 字段和本体平台导入 body 已做成配置/适配入口，但真实服务字段仍需以部署方接口文档和联调结果为准。当前已验证 MySQL 凭证可读取 `wiki_asset`，模型 API 的 `/models` 与 Chat 调用可用；尚未对没有业务资产的空库执行端到端 Wiki 整理。
