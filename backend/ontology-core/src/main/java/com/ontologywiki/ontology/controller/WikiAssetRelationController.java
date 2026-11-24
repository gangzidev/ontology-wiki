package com.ontologywiki.ontology.controller;

import com.ontologywiki.common.constant.HttpStatus;
import com.ontologywiki.common.core.domain.AjaxResult;
import com.ontologywiki.common.core.page.TableDataInfo;
import com.ontologywiki.ontology.service.wiki.WikiRelationService;
import com.ontologywiki.ontology.repository.OntologyResourceRepository.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.LinkedHashMap;

/** Wiki asset relationship directory endpoints. */
@RestController
@RequestMapping("/ontology")
public class WikiAssetRelationController {
    private final WikiRelationService relationService;

    public WikiAssetRelationController(WikiRelationService relationService) {
        this.relationService = relationService;
    }

    @PreAuthorize("@ss.hasPermi('ontology:assets:list')")
    @GetMapping("/assets/graph")
    public AjaxResult assetGraph(@RequestParam(name = "spaceId", required = false) Long spaceId,
            @RequestParam(name = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(name = "pageSize", defaultValue = "50") int pageSize) {
        return AjaxResult.success(relationService.graph(spaceId, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('ontology:assets:list')")
    @GetMapping("/assets/{assetId}/relations")
    public TableDataInfo assetRelations(@PathVariable("assetId") Long assetId,
            @RequestParam(name = "direction", defaultValue = "BOTH") String direction,
            @RequestParam(name = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        PageResult page = relationService.relationPage(assetId, direction, pageNum, pageSize);
        TableDataInfo result = new TableDataInfo(page.getRows(), page.getTotal());
        result.setCode(HttpStatus.SUCCESS);
        result.setMsg("查询成功");
        return result;
    }

    @PreAuthorize("@ss.hasPermi('ontology:assets:edit')")
    @PostMapping("/assets/relations/rebuild")
    public AjaxResult rebuildAssetRelations() {
        int rebuiltPages = relationService.rebuildAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rebuiltPages", rebuiltPages);
        result.put("message", "Wiki 页面关系索引已重建");
        return AjaxResult.success(result);
    }

    @PreAuthorize("@ss.hasPermi('ontology:assets:list')")
    @GetMapping("/assets/relations/markdown")
    public AjaxResult assetRelationMarkdown(@RequestParam(name = "spaceId", required = false) Long spaceId) {
        return AjaxResult.success(relationService.markdownProjection(spaceId));
    }
}
