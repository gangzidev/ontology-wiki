-- Persistent, rebuildable Wiki relationship catalog.
-- Apply once to an existing installation before deploying the relation-index code.
CREATE TABLE IF NOT EXISTS wiki_asset_relation (
    relation_id BIGINT NOT NULL AUTO_INCREMENT,
    space_id BIGINT NULL,
    source_asset_id BIGINT NOT NULL,
    target_asset_id BIGINT NULL,
    target_name VARCHAR(500) NULL,
    relation_type VARCHAR(40) NOT NULL,
    source_position INT NOT NULL DEFAULT 0,
    source_version CHAR(64) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (relation_id),
    KEY idx_wiki_relation_space_source (space_id, source_asset_id),
    KEY idx_wiki_relation_target_type (target_asset_id, relation_type),
    KEY idx_wiki_relation_unresolved (space_id, target_asset_id, target_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS wiki_relation_markdown_index (
    scope_key VARCHAR(64) NOT NULL,
    space_id BIGINT NULL,
    index_version CHAR(64) NOT NULL,
    markdown_content MEDIUMTEXT NOT NULL,
    generated_at DATETIME NOT NULL,
    PRIMARY KEY (scope_key),
    KEY idx_wiki_relation_markdown_space (space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
