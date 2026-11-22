-- 查询准确性评测凭证；只有服务端写入 passed=1 且配置哈希一致时才开放只读查询。
CREATE TABLE IF NOT EXISTS ontology_query_evaluation (
  evaluation_id bigint NOT NULL AUTO_INCREMENT,
  proposal_id bigint NOT NULL,
  config_hash varchar(64) NOT NULL,
  benchmark_version varchar(100) NOT NULL,
  passed tinyint NOT NULL DEFAULT 0,
  case_count int NOT NULL DEFAULT 0,
  passed_count int NOT NULL DEFAULT 0,
  detail text,
  evaluated_by varchar(64),
  evaluated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (evaluation_id),
  UNIQUE KEY uk_query_evaluation_version (proposal_id, config_hash, benchmark_version),
  KEY idx_query_evaluation_proposal (proposal_id)
);
