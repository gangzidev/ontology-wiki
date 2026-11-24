-- Apply once to existing installations. Rebuild chunks through the paged API after migration.
CREATE TABLE IF NOT EXISTS wiki_asset_chunk (
  chunk_id bigint NOT NULL AUTO_INCREMENT,
  space_id bigint NULL,
  asset_id bigint NOT NULL,
  title varchar(200) NOT NULL,
  source_ref varchar(500) NULL,
  source_version char(64) NOT NULL,
  chunk_index int NOT NULL,
  paragraph_no int NOT NULL,
  start_offset int NOT NULL,
  content text NOT NULL,
  PRIMARY KEY (chunk_id),
  UNIQUE KEY uk_wiki_chunk_asset_index (asset_id, chunk_index),
  KEY idx_wiki_chunk_asset_version (asset_id, source_version),
  KEY idx_wiki_chunk_space_asset (space_id, asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS wiki_asset_chunk_index (
  asset_id bigint NOT NULL,
  space_id bigint NULL,
  source_version char(64) NULL,
  status varchar(20) NOT NULL,
  chunk_count int NOT NULL DEFAULT 0,
  indexed_at datetime NOT NULL,
  PRIMARY KEY (asset_id),
  KEY idx_wiki_chunk_index_status (status, indexed_at),
  KEY idx_wiki_chunk_index_space (space_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
