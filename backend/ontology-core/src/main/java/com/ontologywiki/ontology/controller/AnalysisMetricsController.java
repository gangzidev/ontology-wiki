package com.ontologywiki.ontology.controller;

import com.ontologywiki.common.core.domain.AjaxResult;
import com.ontologywiki.ontology.service.analysis.IAnalysisJobService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalysisMetricsController {
    private final IAnalysisJobService jobs;

    public AnalysisMetricsController(IAnalysisJobService jobs) {
        this.jobs = jobs;
    }

    @PreAuthorize("@ss.hasPermi('monitor:server:list')")
    @GetMapping("/ontology/analysis/runtime/metrics")
    public AjaxResult metrics() {
        return AjaxResult.success(jobs.runtimeMetrics());
    }
}
