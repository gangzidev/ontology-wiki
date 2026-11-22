package com.ontologywiki.query.domain;

public class QueryEvaluation {
    private Long proposalId;
    private String configHash;
    private String benchmarkVersion;
    private Boolean passed;
    private Integer caseCount;
    private Integer passedCount;
    private String detail;
    private String evaluatedBy;

    public Long getProposalId() { return proposalId; }
    public void setProposalId(Long proposalId) { this.proposalId = proposalId; }

    public String getConfigHash() { return configHash; }
    public void setConfigHash(String configHash) { this.configHash = configHash; }

    public String getBenchmarkVersion() { return benchmarkVersion; }
    public void setBenchmarkVersion(String benchmarkVersion) { this.benchmarkVersion = benchmarkVersion; }

    public Boolean getPassed() { return passed; }
    public void setPassed(Boolean passed) { this.passed = passed; }

    public Integer getCaseCount() { return caseCount; }
    public void setCaseCount(Integer caseCount) { this.caseCount = caseCount; }

    public Integer getPassedCount() { return passedCount; }
    public void setPassedCount(Integer passedCount) { this.passedCount = passedCount; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getEvaluatedBy() { return evaluatedBy; }
    public void setEvaluatedBy(String evaluatedBy) { this.evaluatedBy = evaluatedBy; }
}
