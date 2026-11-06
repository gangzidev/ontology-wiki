-- 本体 Wiki 业务表；在 OntologyWiki 初始化脚本执行后执行。
CREATE TABLE IF NOT EXISTS wiki_space (
  space_id bigint NOT NULL AUTO_INCREMENT, space_name varchar(100) NOT NULL, description varchar(500), status char(1) DEFAULT '0',
  create_by varchar(64), create_time datetime, update_by varchar(64), update_time datetime, PRIMARY KEY(space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS wiki_asset (
  asset_id bigint NOT NULL AUTO_INCREMENT, space_id bigint, asset_name varchar(200) NOT NULL, asset_type varchar(30) NOT NULL, domain_name varchar(100),
  description varchar(1000), content longtext, tags varchar(500), status char(1) DEFAULT '0', create_by varchar(64), create_time datetime,
  update_by varchar(64), update_time datetime, PRIMARY KEY(asset_id), KEY idx_asset_type(asset_type), KEY idx_asset_space(space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ontology_datasource (
  datasource_id bigint NOT NULL AUTO_INCREMENT, name varchar(100) NOT NULL, db_type varchar(20) NOT NULL, host varchar(255), port int,
  database_name varchar(100), schema_name varchar(100), jdbc_url varchar(1000), username varchar(100), password_enc text, sample_limit int DEFAULT 20,
  description varchar(500), status char(1) DEFAULT '0', create_by varchar(64), create_time datetime,
  update_by varchar(64), update_time datetime, PRIMARY KEY(datasource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS analysis_task (
  task_id bigint NOT NULL AUTO_INCREMENT, task_name varchar(200) NOT NULL, source_id bigint, wiki_space_id bigint, scope varchar(1000), status varchar(30) DEFAULT 'DRAFT',
  summary text, create_by varchar(64), create_time datetime, update_by varchar(64), update_time datetime, PRIMARY KEY(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS wiki_chat_message (
  message_id bigint NOT NULL AUTO_INCREMENT, session_id varchar(64) NOT NULL, role varchar(20) NOT NULL, content longtext NOT NULL,
  created_by varchar(64), create_time datetime, PRIMARY KEY(message_id), KEY idx_chat_session_user(session_id,created_by,message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS wiki_chat_session (
  session_id varchar(64) NOT NULL, space_id bigint, scope_type varchar(20) NOT NULL DEFAULT 'ALL', scope_id bigint, context_state_json longtext NULL,
  title varchar(200) NOT NULL, created_by varchar(64) NOT NULL,
  create_time datetime, update_time datetime, PRIMARY KEY(session_id,created_by), KEY idx_wiki_chat_session_user(created_by,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS wiki_chat_citation (
  message_id bigint NOT NULL, asset_id bigint NOT NULL, PRIMARY KEY(message_id,asset_id), KEY idx_wiki_chat_citation_asset(asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ontology_proposal (
  proposal_id bigint NOT NULL AUTO_INCREMENT, proposal_name varchar(200) NOT NULL, proposal_type varchar(30) NOT NULL, task_id bigint,
  config_json longtext, status varchar(30) DEFAULT 'DRAFT', review_by varchar(64), review_time datetime, review_comment varchar(1000),
  create_by varchar(64), create_time datetime, update_by varchar(64), update_time datetime,
  PRIMARY KEY(proposal_id), KEY idx_proposal_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ontology_analysis_memory (
  memory_id bigint NOT NULL AUTO_INCREMENT, space_id bigint, task_id bigint NOT NULL, proposal_id bigint,
  memory_type varchar(40) NOT NULL DEFAULT 'ANALYSIS_ANSWER', title varchar(255) NOT NULL, content longtext NOT NULL,
  keywords varchar(500) NULL COMMENT '知识条目检索关键词',
  source_json longtext, status varchar(30) NOT NULL DEFAULT 'DRAFT', version int NOT NULL DEFAULT 1,
  created_by varchar(64), create_time datetime, update_by varchar(64), update_time datetime,
  reviewed_by varchar(64), review_time datetime, PRIMARY KEY(memory_id),
  KEY idx_analysis_memory_task(task_id,status), KEY idx_analysis_memory_space(space_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ontology_import_record (
  import_id bigint NOT NULL AUTO_INCREMENT, proposal_id bigint, target_system varchar(100), status varchar(30) DEFAULT 'PENDING', detail text,
  create_by varchar(64), create_time datetime, update_by varchar(64), update_time datetime, PRIMARY KEY(import_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ontology_llm_settings (
  id bigint NOT NULL AUTO_INCREMENT, setting_id tinyint NULL, setting_name varchar(100) NOT NULL DEFAULT '默认模型',
  provider_type varchar(30) NOT NULL DEFAULT 'OPENAI_COMPAT', usage_scene varchar(30) NOT NULL DEFAULT 'ALL',
  enabled tinyint(1) NOT NULL DEFAULT 0, api_base_url varchar(1000), api_key_enc text, model varchar(200),
  update_by varchar(64), update_time datetime, PRIMARY KEY(id), UNIQUE KEY uk_llm_setting_id(setting_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS ontology_mcp_token (
  token_id bigint NOT NULL AUTO_INCREMENT, token_name varchar(100) NOT NULL,
  token_hash varchar(64) NOT NULL COMMENT 'SHA-256(token)',
  created_by varchar(64), create_time datetime, last_used datetime, status tinyint NOT NULL DEFAULT 1,
  PRIMARY KEY(token_id), UNIQUE KEY uk_token_hash(token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 业务菜单（超级管理员角色默认拥有；其他角色在系统管理中授权）
-- 清理上游模板遗留的官网外链菜单，避免重新执行初始化脚本时恢复该入口。
SET @legacy_homepage_menu_id := (SELECT menu_id FROM sys_menu WHERE menu_id = 4 AND menu_name = '数智本体 Wiki官网' AND path = 'http://ruoyi.vip' LIMIT 1);
DELETE FROM sys_role_menu WHERE menu_id = @legacy_homepage_menu_id;
DELETE FROM sys_menu WHERE menu_id = @legacy_homepage_menu_id;
INSERT IGNORE INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time)
VALUES (2000,'本体Wiki',0,5,'ontology','Layout',NULL,1,0,'M','0','0',NULL,'tree','admin',NOW());
INSERT IGNORE INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time) VALUES
(2001,'工作台',2000,1,'dashboard','ontology/dashboard/index',NULL,1,0,'C','0','0','ontology:dashboard:list','dashboard','admin',NOW()),
(2002,'Wiki资产',2000,2,'assets','ontology/assets/index',NULL,1,0,'C','0','0','ontology:assets:list','documentation','admin',NOW()),
(2003,'数据源管理',2000,3,'datasource','ontology/datasource/index',NULL,1,0,'C','0','0','ontology:datasources:list','server','admin',NOW()),
(2004,'分析任务',2000,4,'analysis','ontology/analysis/index',NULL,1,0,'C','0','0','ontology:analyses:list','chart','admin',NOW()),
(2005,'配置草案',2000,5,'proposal','ontology/proposal/index',NULL,1,0,'C','0','0','ontology:proposals:list','form','admin',NOW()),
(2006,'导入记录',2000,6,'import','ontology/import/index',NULL,1,0,'C','0','0','ontology:imports:list','upload','admin',NOW()),
(2007,'行业知识空间',2000,7,'spaces','ontology/spaces/index',NULL,1,0,'C','0','0','ontology:spaces:list','nested','admin',NOW()),
(2008,'业务知识问答',2000,8,'chat','ontology/chat/index',NULL,1,0,'C','0','0','ontology:dashboard:list','message','admin',NOW());
INSERT IGNORE INTO sys_role_menu(role_id, menu_id) SELECT 1, menu_id FROM sys_menu WHERE menu_id BETWEEN 2000 AND 2008;
INSERT IGNORE INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time) VALUES
(2010,'资产新增',2002,1,'','','1',0,'F','1','0','ontology:assets:add','', 'admin',NOW()),
(2011,'资产编辑',2002,2,'','','1',0,'F','1','0','ontology:assets:edit','', 'admin',NOW()),
(2012,'资产删除',2002,3,'','','1',0,'F','1','0','ontology:assets:remove','', 'admin',NOW()),
(2020,'数据源新增',2003,1,'','','1',0,'F','1','0','ontology:datasources:add','', 'admin',NOW()),
(2021,'数据源编辑',2003,2,'','','1',0,'F','1','0','ontology:datasources:edit','', 'admin',NOW()),
(2022,'数据源删除',2003,3,'','','1',0,'F','1','0','ontology:datasources:remove','', 'admin',NOW()),
(2030,'任务新增',2004,1,'','','1',0,'F','1','0','ontology:analyses:add','', 'admin',NOW()),
(2031,'任务编辑',2004,2,'','','1',0,'F','1','0','ontology:analyses:edit','', 'admin',NOW()),
(2032,'任务删除',2004,3,'','','1',0,'F','1','0','ontology:analyses:remove','', 'admin',NOW()),
(2040,'草案新增',2005,1,'','','1',0,'F','1','0','ontology:proposals:add','', 'admin',NOW()),
(2041,'草案编辑',2005,2,'','','1',0,'F','1','0','ontology:proposals:edit','', 'admin',NOW()),
(2042,'草案删除',2005,3,'','','1',0,'F','1','0','ontology:proposals:remove','', 'admin',NOW()),
(2043,'草案审核',2005,5,'','','1',0,'F','1','0','ontology:proposals:review','', 'admin',NOW()),
(2050,'导入新增',2006,1,'','','1',0,'F','1','0','ontology:imports:add','', 'admin',NOW()),
(2051,'导入编辑',2006,2,'','','1',0,'F','1','0','ontology:imports:edit','', 'admin',NOW()),
(2052,'导入删除',2006,3,'','','1',0,'F','1','0','ontology:imports:remove','', 'admin',NOW()),
(2060,'空间新增',2007,1,'','','1',0,'F','1','0','ontology:spaces:add','', 'admin',NOW()),
(2061,'空间编辑',2007,2,'','','1',0,'F','1','0','ontology:spaces:edit','', 'admin',NOW()),
(2062,'空间删除',2007,3,'','','1',0,'F','1','0','ontology:spaces:remove','', 'admin',NOW()),
(2070,'空间查询',2007,4,'','','1',0,'F','1','0','ontology:spaces:query','', 'admin',NOW()),
(2071,'资产查询',2002,4,'','','1',0,'F','1','0','ontology:assets:query','', 'admin',NOW()),
(2072,'数据源查询',2003,4,'','','1',0,'F','1','0','ontology:datasources:query','', 'admin',NOW()),
(2073,'分析任务查询',2004,4,'','','1',0,'F','1','0','ontology:analyses:query','', 'admin',NOW()),
(2074,'草案查询',2005,4,'','','1',0,'F','1','0','ontology:proposals:query','', 'admin',NOW()),
(2075,'导入记录查询',2006,4,'','','1',0,'F','1','0','ontology:imports:query','', 'admin',NOW()),
(2080,'大模型设置',2008,1,'','','1',0,'F','1','0','ontology:wiki:settings','', 'admin',NOW());
INSERT IGNORE INTO sys_role_menu(role_id, menu_id) SELECT 1, menu_id FROM sys_menu WHERE menu_id >= 2010 AND menu_id <= 2080;

-- 模型配置与 MCP Token：拆成两个独立菜单
INSERT IGNORE INTO sys_menu(menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time) VALUES
(2090,'模型配置',2000,9,'settings','ontology/settings/index','1',0,'C','0','0','ontology:llm:list','robot','admin',NOW()),
(2091,'MCP Token',2000,10,'mcp','ontology/settings/mcp','1',0,'C','0','0','ontology:llm:list','key','admin',NOW());
INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES (1,2090),(1,2091);
