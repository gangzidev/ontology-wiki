package com.ontologywiki.query.controller;

import com.ontologywiki.common.core.domain.AjaxResult;
import com.ontologywiki.common.utils.SecurityUtils;
import com.ontologywiki.query.service.IDataQueryService;
import com.ontologywiki.query.service.IQueryHistoryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/ontology/data-query")
public class DataQueryController {
    private final IDataQueryService queries;
    private final IQueryHistoryService history;

    public DataQueryController(IDataQueryService queries, IQueryHistoryService history) {
        this.queries = queries;
        this.history = history;
    }

    @GetMapping("/history/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult history(@PathVariable long proposalId, @RequestParam(defaultValue = "1") int page) {
        return AjaxResult.success(history.sessions(SecurityUtils.getUsername(), proposalId, page));
    }

    @GetMapping("/history/{proposalId}/{sessionId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult messages(@PathVariable long proposalId, @PathVariable String sessionId, @RequestParam(required = false) Long beforeId) {
        return AjaxResult.success(history.messages(SecurityUtils.getUsername(), proposalId, sessionId, beforeId));
    }

    @GetMapping("/readiness/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult readiness(@PathVariable long proposalId) {
        return AjaxResult.success(queries.readiness(proposalId));
    }

    @PostMapping("/compile/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult compile(@PathVariable long proposalId, @RequestBody Map<String, Object> plan) {
        return AjaxResult.success(queries.compile(proposalId, plan));
    }

    @PostMapping("/plan/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult plan(@PathVariable long proposalId, @RequestBody Map<String, Object> request) {
        return AjaxResult.success(queries.plan(proposalId, request.get("question") == null ? null : String.valueOf(request.get("question"))));
    }

    @PostMapping("/execute/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult execute(@PathVariable long proposalId, @RequestBody Map<String, Object> plan) {
        return AjaxResult.success(queries.execute(proposalId, plan));
    }

    @PostMapping("/evaluate/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:edit')")
    public AjaxResult evaluate(@PathVariable long proposalId, @RequestBody Map<String, Object> benchmark) {
        return AjaxResult.success(queries.evaluate(proposalId, benchmark, SecurityUtils.getUsername()));
    }

    @PostMapping("/ask/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult ask(@PathVariable long proposalId, @RequestBody Map<String, Object> request) {
        return AjaxResult.success(queries.ask(proposalId, request, SecurityUtils.getUsername()));
    }

    @PostMapping("/drilldown/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult drilldown(@PathVariable long proposalId, @RequestBody Map<String, Object> request) {
        return AjaxResult.success(queries.drilldown(proposalId, request, SecurityUtils.getUsername()));
    }

    @GetMapping("/catalog/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult catalog(@PathVariable long proposalId) {
        return AjaxResult.success(queries.catalog(proposalId));
    }
}
