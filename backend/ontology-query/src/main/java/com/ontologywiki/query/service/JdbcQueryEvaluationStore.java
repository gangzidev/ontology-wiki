package com.ontologywiki.query.service;

import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** 从服务端评测表读取通过记录，表未迁移时安全关闭查询。 */
@Repository
public class JdbcQueryEvaluationStore implements QueryEvaluationStore {
    private final DataSource dataSource;

    public JdbcQueryEvaluationStore(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public boolean isPassed(long proposalId, String configHash) {
        try {
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "select count(*) from ontology_query_evaluation where proposal_id = ? and config_hash = ? and passed = 1")) {
                statement.setLong(1, proposalId); statement.setString(2, configHash);
                try (ResultSet result = statement.executeQuery()) { return result.next() && result.getInt(1) > 0; }
            }
        } catch (Exception ignored) {
            // shortcut: 评测表未迁移或数据库暂不可用时 fail closed，完成迁移后再开放查询。
            return false;
        }
    }
}
