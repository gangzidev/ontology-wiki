CREATE TABLE IF NOT EXISTS ontology_analysis_job (
  task_id bigint NOT NULL,
  run_token varchar(64) NOT NULL,
  status varchar(20) NOT NULL,
  stage varchar(30) NOT NULL,
  request_json longtext NOT NULL,
  checkpoint_json longtext NOT NULL,
  username varchar(64) NOT NULL,
  message varchar(1000),
  lease_until bigint NOT NULL,
  model_calls int NOT NULL DEFAULT 0,
  proposal_id bigint NULL,
  created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (task_id),
  KEY idx_analysis_job_state (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
