package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface WikiRelationMapper {
    List<Map<String, Object>> selectActivePages();

    List<Map<String, Object>> selectProjectionPages(@Param("spaceId") Long spaceId);

    List<Map<String, Object>> selectProjectionRelations(@Param("spaceId") Long spaceId);

    List<Map<String, Object>> selectAffectedSpaces(@Param("assetId") Long assetId);

    List<Map<String, Object>> selectProjectionSpaces();

    List<Map<String, Object>> selectUnresolvedTargetSpaces(@Param("name") String name);

    List<Map<String, Object>> selectPage(@Param("assetId") Long assetId);

    List<Map<String, Object>> resolveTargets(@Param("names") List<String> names);

    int deletePageRelations(@Param("assetId") Long assetId);

    int resolveUnresolvedTarget(@Param("name") String name, @Param("assetId") Long assetId);

    int saveMarkdownProjection(@Param("scopeKey") String scopeKey,
                               @Param("spaceId") Long spaceId,
                               @Param("version") String version,
                               @Param("content") String content);

    List<Map<String, Object>> selectMarkdownProjection(@Param("scopeKey") String scopeKey);

    int insertRelation(@Param("spaceId") Long spaceId,
                       @Param("sourceId") Long sourceId,
                       @Param("targetId") Long targetId,
                       @Param("targetName") String targetName,
                       @Param("type") String type,
                       @Param("position") Integer position,
                       @Param("sourceVersion") String sourceVersion);

    List<Map<String, Object>> selectGraphNodes(@Param("spaceId") Long spaceId,
                                               @Param("limit") int limit,
                                               @Param("offset") long offset);

    int countGraphNodes(@Param("spaceId") Long spaceId);

    List<Map<String, Object>> selectGraphRelations(@Param("spaceId") Long spaceId,
                                                   @Param("assetIds") List<Long> assetIds,
                                                   @Param("limit") int limit);

    List<Map<String, Object>> selectAssetRelations(@Param("assetId") Long assetId,
                                                   @Param("direction") String direction,
                                                   @Param("offset") long offset,
                                                   @Param("limit") int limit);

    int countAssetRelations(@Param("assetId") Long assetId,
                            @Param("direction") String direction);
}
