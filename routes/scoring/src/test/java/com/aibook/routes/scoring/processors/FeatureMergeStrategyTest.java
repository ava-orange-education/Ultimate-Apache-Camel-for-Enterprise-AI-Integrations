package com.aibook.routes.scoring.processors;

import com.aibook.core.dto.ScoringRequest;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FeatureMergeStrategy}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Features from both original and resource are merged</li>
 *   <li>Resource values override original on key collision</li>
 *   <li>featureCount header is updated after merge</li>
 *   <li>Null resource exchange returns original unchanged</li>
 *   <li>Non-ScoringRequest resource body returns original unchanged</li>
 * </ul>
 */
class FeatureMergeStrategyTest {

    private DefaultCamelContext  camelContext;
    private FeatureMergeStrategy strategy;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        strategy = new FeatureMergeStrategy();
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(Map<String, Object> features) {
        ScoringRequest req = new ScoringRequest("req-1", "entity-1", null, null, features, null);
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(req);
        return ex;
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Features from original and resource are merged into one map")
    void mergesAllFeatures() throws Exception {
        Map<String, Object> origFeatures = Map.of("age", 100, "kyc_status", "VERIFIED");
        Map<String, Object> resFeatures  = Map.of("prior_score", 0.3, "score_trend", "STABLE");

        Exchange original = exchange(origFeatures);
        Exchange resource = exchange(resFeatures);

        Exchange result = strategy.aggregate(original, resource);

        ScoringRequest merged = result.getIn().getBody(ScoringRequest.class);
        assertThat(merged.features())
                .containsKey("age")
                .containsKey("kyc_status")
                .containsKey("prior_score")
                .containsKey("score_trend");
    }

    @Test
    @DisplayName("Resource values override original values on key collision")
    void resourceWinsOnCollision() throws Exception {
        Map<String, Object> origFeatures = new HashMap<>(Map.of("prior_score", 0.9));
        Map<String, Object> resFeatures  = Map.of("prior_score", 0.2);

        Exchange original = exchange(origFeatures);
        Exchange resource = exchange(resFeatures);

        Exchange result = strategy.aggregate(original, resource);

        ScoringRequest merged = result.getIn().getBody(ScoringRequest.class);
        assertThat(merged.features().get("prior_score")).isEqualTo(0.2);
    }

    @Test
    @DisplayName("featureCount header reflects total merged feature count")
    void featureCountHeader_updatedAfterMerge() throws Exception {
        Map<String, Object> origFeatures = Map.of("a", 1, "b", 2);
        Map<String, Object> resFeatures  = Map.of("c", 3, "d", 4);

        Exchange original = exchange(origFeatures);
        Exchange resource = exchange(resFeatures);

        Exchange result = strategy.aggregate(original, resource);

        assertThat(result.getIn().getHeader("featureCount", Integer.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("requestId and entityId preserved from original after merge")
    void originalMetadataPreserved() {
        Map<String, Object> origFeatures = Map.of("kyc_status", "VERIFIED");
        Map<String, Object> resFeatures  = Map.of("prior_score", 0.4);

        ScoringRequest origReq = new ScoringRequest("original-id", "entity-original", null, null, origFeatures, null);
        ScoringRequest resReq  = new ScoringRequest("resource-id", "entity-resource", null, null, resFeatures, null);

        Exchange original = new DefaultExchange(camelContext);
        original.getIn().setBody(origReq);
        Exchange resource = new DefaultExchange(camelContext);
        resource.getIn().setBody(resReq);

        Exchange result = strategy.aggregate(original, resource);

        ScoringRequest merged = result.getIn().getBody(ScoringRequest.class);
        assertThat(merged.requestId()).isEqualTo("original-id");
        assertThat(merged.entityId()).isEqualTo("entity-original");
    }

    // ── Null / edge cases ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Null resource exchange returns original unchanged")
    void nullResource_returnsOriginal() {
        Exchange original = exchange(Map.of("a", 1));
        Exchange result   = strategy.aggregate(original, null);

        assertThat(result).isSameAs(original);
        ScoringRequest req = result.getIn().getBody(ScoringRequest.class);
        assertThat(req.features()).containsKey("a");
    }

    @Test
    @DisplayName("Null original exchange returns resource")
    void nullOriginal_returnsResource() {
        Exchange resource = exchange(Map.of("b", 2));
        Exchange result   = strategy.aggregate(null, resource);

        assertThat(result).isSameAs(resource);
    }

    @Test
    @DisplayName("Resource body that is not a ScoringRequest returns original unchanged")
    void nonScoringRequestResource_returnsOriginal() {
        Exchange original = exchange(Map.of("a", 1));
        Exchange resource = new DefaultExchange(camelContext);
        resource.getIn().setBody("some string — not a ScoringRequest");

        Exchange result = strategy.aggregate(original, resource);

        assertThat(result).isSameAs(original);
    }
}
