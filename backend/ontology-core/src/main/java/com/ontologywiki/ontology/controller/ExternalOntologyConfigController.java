package com.ontologywiki.ontology.controller;

import com.ontologywiki.common.core.domain.AjaxResult;
import com.ontologywiki.ontology.service.ExternalOntologyConfigAdapter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 外部本体平台的通用配置转换接口。 */
@RestController
@RequestMapping("/ontology/external-config")
public class ExternalOntologyConfigController {
    private final ExternalOntologyConfigAdapter adapter;

    public ExternalOntologyConfigController(ExternalOntologyConfigAdapter adapter) { this.adapter = adapter; }

    @PostMapping("/import")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:edit')")
    public AjaxResult importConfig(@RequestBody Map<String, Object> external) {
        try { return AjaxResult.success(adapter.importConfig(external)); }
        catch (IllegalArgumentException e) { return AjaxResult.error(e.getMessage()); }
    }

    @PostMapping("/export")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult exportConfig(@RequestBody Map<String, Object> config) {
        try { return AjaxResult.success(adapter.exportConfig(config)); }
        catch (IllegalArgumentException e) { return AjaxResult.error(e.getMessage()); }
    }
}
