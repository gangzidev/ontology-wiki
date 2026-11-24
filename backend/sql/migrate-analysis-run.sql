-- Run after migrate-analysis-job.sql. Existing task records remain unchanged.
CREATE TABLE IF NOT EXISTS ontology_analysis_run (
  run_id bigint NOT NULL AUTO_INCREMENT,
  task_id bigint NOT NULL,
  run_token varchar(64) NOT NULL,
  run_kind varchar(10) NOT NULL,
  source_id bigint,
  wiki_space_id bigint,
  selection_json longtext NOT NULL,
  username varchar(64) NOT NULL,
  started_at bigint NOT NULL,
  finished_at bigint,
  status varchar(20) NOT NULL,
  stage varchar(30) NOT NULL,
  model_calls int NOT NULL DEFAULT 0,
  proposal_id bigint,
  PRIMARY KEY (run_id),
  UNIQUE KEY uk_analysis_run_token (run_token),
  KEY idx_analysis_run_task (task_id, run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ontology_analysis_model_call (
  call_id bigint NOT NULL AUTO_INCREMENT,
  task_id bigint NOT NULL,
  run_token varchar(64) NOT NULL,
  call_number int NOT NULL,
  stage varchar(30) NOT NULL,
  started_at bigint NOT NULL,
  finished_at bigint NOT NULL,
  duration_ms bigint NOT NULL,
  status varchar(20) NOT NULL,
  failure_type varchar(100),
  PRIMARY KEY (call_id),
  UNIQUE KEY uk_analysis_model_call (run_token, call_number),
  KEY idx_analysis_model_call_run (task_id, run_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ontology_analysis_stage_timing (
  timing_id bigint NOT NULL AUTO_INCREMENT,
  task_id bigint NOT NULL,
  run_token varchar(64) NOT NULL,
  stage varchar(40) NOT NULL,
  started_at bigint NOT NULL,
  finished_at bigint NOT NULL,
  duration_ms bigint NOT NULL,
  status varchar(20) NOT NULL,
  failure_type varchar(100),
  PRIMARY KEY (timing_id),
  KEY idx_analysis_stage_timing_run (task_id, run_token, timing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
