-- =============================================================================
-- 本体 Wiki 存量库升级脚本
--
-- 适用场景：数据库已执行过旧版 ontology-wiki.sql，需要升级到当前版本。
-- 执行方式：部署时人工按顺序整段执行；脚本可重复执行（幂等），不会破坏已有数据。
-- 全新安装请直接执行 ontology-wiki.sql，无需执行本脚本。
--
-- 说明：应用启动时不再做任何 DDL，所有结构变更都必须由本脚本完成，
--       否则新代码读取新列时会报 Unknown column。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. wiki_chat_session：补问答范围与上下文状态列
--    旧表只有 space_id，新代码需要 scope_type / scope_id / context_state_json
-- -----------------------------------------------------------------------------
SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'wiki_chat_session' AND column_name = 'scope_type') = 0,
    'ALTER TABLE wiki_chat_session ADD COLUMN scope_type varchar(20) NOT NULL DEFAULT ''ALL'' AFTER space_id',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'wiki_chat_session' AND column_name = 'scope_id') = 0,
    'ALTER TABLE wiki_chat_session ADD COLUMN scope_id bigint NULL AFTER scope_type',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'wiki_chat_session' AND column_name = 'context_state_json') = 0,
    'ALTER TABLE wiki_chat_session ADD COLUMN context_state_json longtext NULL AFTER scope_id',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史数据：已有空间的会话默认按空间限定范围
UPDATE wiki_chat_session
SET scope_type = 'SPACE', scope_id = space_id
WHERE space_id IS NOT NULL AND scope_type = 'ALL';

-- -----------------------------------------------------------------------------
-- 2. analysis_task：补所属行业知识空间列
-- -----------------------------------------------------------------------------
SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'analysis_task' AND column_name = 'wiki_space_id') = 0,
    'ALTER TABLE analysis_task ADD COLUMN wiki_space_id bigint NULL AFTER source_id',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 3. ontology_proposal：补审核人 / 审核时间 / 审核意见列
-- -----------------------------------------------------------------------------
SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'ontology_proposal' AND column_name = 'review_by') = 0,
    'ALTER TABLE ontology_proposal ADD COLUMN review_by varchar(64) NULL AFTER status',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'ontology_proposal' AND column_name = 'review_time') = 0,
    'ALTER TABLE ontology_proposal ADD COLUMN review_time datetime NULL AFTER review_by',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'ontology_proposal' AND column_name = 'review_comment') = 0,
    'ALTER TABLE ontology_proposal ADD COLUMN review_comment varchar(1000) NULL AFTER review_time',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 旧审核状态缺少审核人时不可信，统一回落到待审核
UPDATE ontology_proposal
SET status = 'DRAFT'
WHERE status = 'REVIEWED' OR (status = 'APPROVED' AND review_by IS NULL);

-- -----------------------------------------------------------------------------
-- 4. ontology_analysis_memory：补知识条目检索关键词列
-- -----------------------------------------------------------------------------
SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'ontology_analysis_memory' AND column_name = 'keywords') = 0,
    'ALTER TABLE ontology_analysis_memory ADD COLUMN keywords varchar(500) NULL COMMENT ''知识条目检索关键词'' AFTER content',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 5. ontology_llm_settings：单配置表升级为多配置表
--
--    旧结构：setting_id 是主键，只有一个配置。
--    新结构：id 自增主键 + setting_id 唯一键，可存多个场景配置。
--    注意 MySQL 要求 AUTO_INCREMENT 列在定义时即为索引列，
--    因此必须先删除旧主键，再在同一条语句里把 id 定义为自增主键。
-- -----------------------------------------------------------------------------
SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'ontology_llm_settings' AND column_name = 'id') = 0,
    'ALTER TABLE ontology_llm_settings DROP PRIMARY KEY',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'ontology_llm_settings' AND column_name = 'id') = 0,
    'ALTER TABLE ontology_llm_settings ADD COLUMN id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- setting_id 保留，便于旧代码按 setting_id = 1 读取默认配置
SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'ontology_llm_settings' AND index_name = 'uk_llm_setting_id') = 0,
    'ALTER TABLE ontology_llm_settings ADD UNIQUE KEY uk_llm_setting_id (setting_id)',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 旧表 setting_id 为 NOT NULL 且无默认值，多配置新增不会写入该列，严格模式下会报 1364
SET @ddl := (SELECT IF(
    (SELECT is_nullable FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'ontology_llm_settings' AND column_name = 'setting_id') = 'NO',
    'ALTER TABLE ontology_llm_settings MODIFY COLUMN setting_id tinyint NULL',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'ontology_llm_settings' AND column_name = 'setting_name') = 0,
    'ALTER TABLE ontology_llm_settings ADD COLUMN setting_name varchar(100) NOT NULL DEFAULT ''默认模型'' AFTER setting_id',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'ontology_llm_settings' AND column_name = 'provider_type') = 0,
    'ALTER TABLE ontology_llm_settings ADD COLUMN provider_type varchar(30) NOT NULL DEFAULT ''OPENAI_COMPAT'' AFTER setting_name',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'ontology_llm_settings' AND column_name = 'usage_scene') = 0,
    'ALTER TABLE ontology_llm_settings ADD COLUMN usage_scene varchar(30) NOT NULL DEFAULT ''ALL'' AFTER provider_type',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 6. 补建新增的表（旧库可能没有）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ontology_mcp_token (
  token_id bigint NOT NULL AUTO_INCREMENT, token_name varchar(100) NOT NULL,
  token_hash varchar(64) NOT NULL COMMENT 'SHA-256(token)',
  created_by varchar(64), create_time datetime, last_used datetime, status tinyint NOT NULL DEFAULT 1,
  PRIMARY KEY(token_id), UNIQUE KEY uk_token_hash(token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wiki_chat_citation (
  message_id bigint NOT NULL, asset_id bigint NOT NULL, PRIMARY KEY(message_id,asset_id),
  KEY idx_wiki_chat_citation_asset(asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用历史消息回填会话表，标题取该会话第一条用户提问
INSERT IGNORE INTO wiki_chat_session
  (session_id, space_id, title, created_by, create_time, update_time)
SELECT m.session_id, NULL,
       COALESCE((
         SELECT SUBSTRING(m2.content, 1, 80) FROM wiki_chat_message m2
         WHERE m2.session_id = m.session_id AND m2.created_by = m.created_by AND m2.role = 'user'
         ORDER BY m2.message_id LIMIT 1
       ), '历史问答'),
       m.created_by, MIN(m.create_time), MAX(m.create_time)
FROM wiki_chat_message m
WHERE m.created_by IS NOT NULL
GROUP BY m.session_id, m.created_by;

-- -----------------------------------------------------------------------------
-- 7. 菜单补建与改名
-- -----------------------------------------------------------------------------
-- 草案审核按钮
INSERT IGNORE INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES (2043,'草案审核',2005,5,'','','1',0,'F','1','0','ontology:proposals:review','','admin',NOW());
INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES (1,2043);

-- 旧版大模型设置入口（保留以兼容历史授权）
INSERT IGNORE INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES (2080,'大模型设置',2008,1,'','','1',0,'F','1','0','ontology:wiki:settings','','admin',NOW());
INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES (1,2080);

-- 模型配置页面
INSERT IGNORE INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES (2090,'模型配置',2000,9,'settings','ontology/settings/index','1',0,'C','0','0','ontology:llm:list','robot','admin',NOW());
INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES (1,2090);
UPDATE sys_menu SET menu_name='模型配置', update_by='system', update_time=NOW()
WHERE menu_id=2090 AND menu_name='大模型管理';

-- MCP Token 页面
INSERT IGNORE INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES (2091,'MCP Token',2000,10,'mcp','ontology/settings/mcp','1',0,'C','0','0','ontology:llm:list','key','admin',NOW());
INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES (1,2091);

-- 历史菜单改名
UPDATE sys_menu SET menu_name='行业知识空间', update_by='system', update_time=NOW()
WHERE path='spaces' AND component='ontology/spaces/index' AND menu_name='Wiki空间';

UPDATE sys_menu SET menu_name='业务知识问答', update_by='system', update_time=NOW()
WHERE path='chat' AND component='ontology/chat/index' AND menu_name IN ('Wiki问答','LLM Wiki 问答');
