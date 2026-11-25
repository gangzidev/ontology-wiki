-- 创建分析任务执行日志表
-- 用于存储分析任务执行过程中的详细日志信息

CREATE TABLE IF NOT EXISTS ontology_analysis_log (
  log_id bigint NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  task_id bigint NOT NULL COMMENT '分析任务ID',
  run_token varchar(64) NOT NULL COMMENT '执行批次token',
  log_level varchar(10) NOT NULL COMMENT '日志级别：INFO, WARN, ERROR',
  log_category varchar(30) NOT NULL COMMENT '日志分类：STAGE, MODEL_CALL, PROGRESS, SYSTEM',
  stage varchar(30) COMMENT '所属阶段',
  message text NOT NULL COMMENT '日志消息',
  detail_json longtext COMMENT '详细信息JSON（如：表数量、字段数量、Wiki文档数等）',
  created_at bigint NOT NULL COMMENT '日志时间戳（毫秒）',
  PRIMARY KEY (log_id),
  KEY idx_analysis_log_run (task_id, run_token, log_id),
  KEY idx_analysis_log_created (task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析任务执行日志表';

-- 示例数据说明：
-- log_category:
--   STAGE - 阶段开始/完成日志
--   MODEL_CALL - 模型调用日志
--   PROGRESS - 进度更新日志
--   SYSTEM - 系统日志（错误、警告等）
--
-- detail_json 示例：
-- {
--   "tableCount": 42,
--   "columnCount": 215,
--   "wikiDocs": 5,
--   "businessRules": 3,
--   "batchNumber": 1,
--   "batchTotal": 3,
--   "itemsInBatch": 150
-- }
