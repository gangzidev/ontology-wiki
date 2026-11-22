package com.ontologywiki.ontology.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class OntologyProposalSemanticValidationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    public void rejectsMissingMetric() throws Exception {
        List<String> errors = validate("{\"properties\":[{\"id\":\"status\",\"objectId\":\"order\",\"column\":\"status\",\"dataType\":\"VARCHAR\"}],\"semanticCatalog\":{\"metrics\":[],\"dimensions\":[{\"id\":\"status_dim\",\"name\":\"订单状态\",\"propertyId\":\"status\"}]}}");
        Assert.assertTrue(errors.get(0).contains("还没有可统计指标"));
    }

    @Test
    public void acceptsAggregateWithoutGroupingDimensions() throws Exception {
        List<String> errors = validate("{\"properties\":[{\"id\":\"amount\",\"objectId\":\"order\",\"column\":\"amount\",\"dataType\":\"DECIMAL\"}],\"semanticCatalog\":{\"metrics\":[{\"id\":\"amount_metric\",\"name\":\"订单金额\",\"propertyId\":\"amount\",\"aggregation\":\"SUM\"}],\"dimensions\":[]}}");
        Assert.assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void acceptsValidMetricAndDimension() throws Exception {
        List<String> errors = validate("{\"properties\":[{\"id\":\"amount\",\"objectId\":\"order\",\"column\":\"amount\",\"dataType\":\"DECIMAL\"},{\"id\":\"status\",\"objectId\":\"order\",\"column\":\"status\",\"dataType\":\"VARCHAR\"}],\"semanticCatalog\":{\"metrics\":[{\"id\":\"amount_metric\",\"name\":\"订单金额\",\"propertyId\":\"amount\",\"aggregation\":\"SUM\"}],\"dimensions\":[{\"id\":\"status_dim\",\"name\":\"订单状态\",\"propertyId\":\"status\"}]}}");
        Assert.assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void rejectsUnknownProperty() throws Exception {
        List<String> errors = validate("{\"properties\":[],\"semanticCatalog\":{\"metrics\":[{\"id\":\"amount_metric\",\"name\":\"订单金额\",\"propertyId\":\"amount\",\"aggregation\":\"SUM\"}],\"dimensions\":[{\"id\":\"status_dim\",\"name\":\"订单状态\",\"propertyId\":\"status\"}]}}");
        Assert.assertTrue(errors.get(0).contains("订单金额"));
        Assert.assertTrue(errors.get(0).contains("有效字段"));
    }

    private List<String> validate(String source) throws Exception {
        return OntologyProposalService.validateCompletionSemantics(json.readTree(source));
    }
}
