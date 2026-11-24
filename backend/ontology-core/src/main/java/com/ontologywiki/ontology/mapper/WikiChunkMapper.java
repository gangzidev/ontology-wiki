package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface WikiChunkMapper {
    Map<String, Object> selectAsset(@Param("assetId") long assetId);
    List<Map<String, Object>> selectAssetsAfter(@Param("afterAssetId") long afterAssetId,
                                                @Param("limit") int limit);
    int deleteAssetChunks(@Param("assetId") long assetId);
    int insertChunk(@Param("spaceId") Long spaceId, @Param("assetId") long assetId,
                    @Param("title") String title, @Param("sourceRef") String sourceRef,
                    @Param("sourceVersion") String sourceVersion, @Param("chunkIndex") int chunkIndex,
                    @Param("paragraphNo") int paragraphNo, @Param("startOffset") int startOffset,
                    @Param("content") String content);
    int replaceIndexState(@Param("assetId") long assetId, @Param("spaceId") Long spaceId,
                          @Param("sourceVersion") String sourceVersion, @Param("status") String status,
                          @Param("chunkCount") int chunkCount);
    List<Map<String, Object>> search(@Param("terms") List<String> terms,
                                     @Param("assetScope") boolean assetScope,
                                     @Param("scopeId") Long scopeId,
                                     @Param("spaceId") Long spaceId,
                                     @Param("assetTypes") List<String> assetTypes,
                                     @Param("limit") int limit);
}
