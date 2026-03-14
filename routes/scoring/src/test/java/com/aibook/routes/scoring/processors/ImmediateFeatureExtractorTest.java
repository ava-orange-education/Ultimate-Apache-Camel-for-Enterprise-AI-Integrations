package com.aibook.routes.scoring.processors;

import com.aibook.core.dto.ScoringRequest;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ImmediateFeatureExtractor}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>featureCount header is set from the request feature map</li>
 *   <li>needsHistory header is derived correctly</li>
 *   <li>validationFailed is set for missing required fields</li>
 *   <li>Exchange properties for audit are populated</li>
 * </ul>
 */
class ImmediateFeatureExtractorTest {

    private DefaultCamelContext        camelContext;
    private ImmediateFeatureExtractor  extractor;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        extractor = new ImmediateFeatureExtractor();
        // Inject default: alwaysFetchHistory = false
        setField(extractor, "alwaysFetchHistory", false);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(ScoringRequest req) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(req);
        return ex;
    }

    // ── featureCount ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("featureCount header set to size of features map")
    void featureCount_setFromFeaturesMap() throws Exception {
        ScoringRequest req = new ScoringRequest("req-1", "entity-1", null, null,
                Map.of("account_age_days", 100, "kyc_status", "VERIFIED", "extra", "val"), null);

        Exchange ex = exchange(req);
        extractor.process(ex);

        assertThat(ex.getIn().getHeader("featureCount", Integer.class)).isEqualTo(3);
    }

    // ── needsHistory ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("needsHistory=true when feature map contains needs_history=true")
    void needsHistory_trueWhenFeatureFlagSet() throws Exception {
        ScoringRequest req = new ScoringRequest("req-2", "entity-2", null, null,
                Map.of("account_age_days", 50, "needs_history", Boolean.TRUE), null);

        Exchange ex = exchange(req);
        extractor.process(ex);

        assertThat(ex.getIn().getHeader("needsHistory", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("needsHistory=false when no trigger key present and alwaysFetchHistory=false")
    void needsHistory_falseByDefault() throws Exception {
        ScoringRequest req = new ScoringRequest("req-3", "entity-3", null, null,
                Map.of("account_age_days", 200), null);

        Exchange ex = exchange(req);
        extractor.process(ex);

        assertThat(ex.getIn().getHeader("needsHistory", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("needsHistory=true when alwaysFetchHistory config is true")
    void needsHistory_trueWhenConfigEnabled() throws Exception {
        setField(extractor, "alwaysFetchHistory", true);
        ScoringRequest req = new ScoringRequest("req-4", "entity-4", null, null,
                Map.of("account_age_days", 200), null);

        Exchange ex = exchange(req);
        extractor.process(ex);

        assertThat(ex.getIn().getHeader("needsHistory", Boolean.class)).isTrue();
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("validationFailed=false when all required fields present")
    void validationFailed_falseWhenValid() throws Exception {
        ScoringRequest req = new ScoringRequest("req-5", "entity-5", null, null,
                Map.of("account_age_days", 365), null);

        Exchange ex = exchange(req);
        extractor.process(ex);

        assertThat(ex.getIn().getHeader("validationFailed", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("validationFailed=true when account_age_days missing from features")
    void validationFailed_trueWhenRequiredFeatureMissing() throws Exception {
        ScoringRequest req = new ScoringRequest("req-6", "entity-6", null, null,
                Map.of("other_field", "value"), null);

        Exchange ex = exchange(req);
        extractor.process(ex);

        assertThat(ex.getIn().getHeader("validationFailed", Boolean.class)).isTrue();
        assertThat(ex.getIn().getHeader("validationFailureReason", String.class))
                .contains("account_age_days");
    }

    @Test
    @DisplayName("validationFailed=true when entityId is blank")
    void validationFailed_trueWhenEntityIdBlank() throws Exception {
        ScoringRequest req = new ScoringRequest("req-7", "", null, null,
                Map.of("account_age_days", 100), null);

        Exchange ex = exchange(req);
        extractor.process(ex);

        assertThat(ex.getIn().getHeader("validationFailed", Boolean.class)).isTrue();
        assertThat(ex.getIn().getHeader("validationFailureReason", String.class))
                .contains("entityId");
    }

    // ── Exchange properties for audit ─────────────────────────────────────────

    @Test
    @DisplayName("decisionId exchange property is set from requestId")
    void decisionId_setFromRequestId() throws Exception {
        ScoringRequest req = new ScoringRequest("my-request-id", "entity-8", null, null,
                Map.of("account_age_days", 100), null);

        Exchange ex = exchange(req);
        extractor.process(ex);

        assertThat(ex.getProperty("decisionId")).isEqualTo("my-request-id");
    }

    @Test
    @DisplayName("stage exchange property is set to 'feature-assembly'")
    void stage_setToFeatureAssembly() throws Exception {
        ScoringRequest req = new ScoringRequest("req-9", "entity-9", null, null,
                Map.of("account_age_days", 100), null);

        Exchange ex = exchange(req);
        extractor.process(ex);

        assertThat(ex.getProperty("stage")).isEqualTo("feature-assembly");
    }

    // ── Null body ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Null body throws IllegalArgumentException")
    void nullBody_throwsIllegalArgument() {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(null);

        assertThatThrownBy(() -> extractor.process(ex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ImmediateFeatureExtractor");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void setField(Object target, String fieldName, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}