package com.aibook.routes.graph.processors;

import com.aibook.core.dto.GraphContext;
import com.aibook.core.dto.ScoringResult;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GraphContextMergeStrategy}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Signal headers copied from resource into original</li>
 *   <li>GraphContext stashed on exchange property</li>
 *   <li>ScoringResult.featuresUsed enriched with graph signals</li>
 *   <li>Null resource returns original unchanged</li>
 * </ul>
 */
class GraphContextMergeStrategyTest {

    private DefaultCamelContext       camelContext;
    private GraphContextMergeStrategy strategy;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        strategy = new GraphContextMergeStrategy();
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange originalExchange(Object body) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(body);
        ex.getIn().setHeader("entityId", "test-entity");
        return ex;
    }

    private Exchange resourceExchange(GraphContext ctx,
                                      int connectedCount, double avgStrength,
                                      double density, int depth) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(ctx);
        ex.getIn().setHeader("connectedEntityCount",    connectedCount);
        ex.getIn().setHeader("avgRelationshipStrength", avgStrength);
        ex.getIn().setHeader("clusterDensity",          density);
        ex.getIn().setHeader("maxPathDepth",            depth);
        ex.getIn().setHeader("graphSignalsExtracted",   true);
        return ex;
    }

    private GraphContext graphContext(int nodeCount, int pathCount) {
        List<Map<String, Object>> nodes = java.util.Collections.nCopies(nodeCount, Map.of("id", "n"));
        List<String>              paths = java.util.Collections.nCopies(pathCount, "path");
        return new GraphContext(null, "test-entity", paths, nodes, 3);
    }

    // ── Signal headers copied ─────────────────────────────────────────────────

    @Test
    @DisplayName("Signal headers are copied from resource into original exchange")
    void signalHeaders_copiedToOriginal() {
        Exchange original = originalExchange("plain body");
        Exchange resource = resourceExchange(graphContext(5, 4), 5, 0.75, 0.45, 3);

        Exchange result = strategy.aggregate(original, resource);

        assertThat(result.getIn().getHeader("connectedEntityCount")).isEqualTo(5);
        assertThat(result.getIn().getHeader("avgRelationshipStrength")).isEqualTo(0.75);
        assertThat(result.getIn().getHeader("clusterDensity")).isEqualTo(0.45);
        assertThat(result.getIn().getHeader("maxPathDepth")).isEqualTo(3);
        assertThat(result.getIn().getHeader("graphSignalsExtracted", Boolean.class)).isTrue();
    }

    // ── GraphContext stashed ──────────────────────────────────────────────────

    @Test
    @DisplayName("GraphContext from resource is stashed in original exchange property")
    void graphContext_stashedAsProperty() {
        GraphContext ctx      = graphContext(3, 2);
        Exchange     original = originalExchange("body");
        Exchange     resource = resourceExchange(ctx, 3, 0.5, 0.3, 2);

        Exchange result = strategy.aggregate(original, resource);

        assertThat(result.getProperty("graphContext")).isInstanceOf(GraphContext.class);
        GraphContext stashed = (GraphContext) result.getProperty("graphContext");
        assertThat(stashed.relatedNodes()).hasSize(3);
    }

    // ── ScoringResult enrichment ──────────────────────────────────────────────

    @Test
    @DisplayName("ScoringResult body is enriched with graph signals in featuresUsed")
    void scoringResult_enrichedWithGraphSignals() {
        ScoringResult sr = new ScoringResult(
                "req-1", "test-entity", 0.5, 0.65, "model",
                "REVIEW", Map.of("kyc", "VERIFIED"), null);

        Exchange original = originalExchange(sr);
        Exchange resource = resourceExchange(graphContext(6, 5), 6, 0.80, 0.60, 3);

        Exchange result = strategy.aggregate(original, resource);

        ScoringResult enriched = result.getIn().getBody(ScoringResult.class);
        assertThat(enriched).isNotNull();
        assertThat(enriched.featuresUsed())
                .containsKey("graph_connected_entity_count")
                .containsKey("graph_avg_relationship_strength")
                .containsKey("graph_cluster_density")
                .containsKey("graph_traversal_depth");
        // Original features are preserved
        assertThat(enriched.featuresUsed()).containsKey("kyc");
    }

    @Test
    @DisplayName("Non-ScoringResult body is preserved unchanged")
    void nonScoringResultBody_preserved() {
        String body     = "plain string body";
        Exchange original = originalExchange(body);
        Exchange resource = resourceExchange(graphContext(2, 1), 2, 0.4, 0.2, 2);

        Exchange result = strategy.aggregate(original, resource);

        assertThat(result.getIn().getBody()).isEqualTo(body);
    }

    // ── Null edge cases ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Null resource exchange returns original unchanged")
    void nullResource_returnsOriginal() {
        Exchange original = originalExchange("body");
        Exchange result   = strategy.aggregate(original, null);

        assertThat(result).isSameAs(original);
    }

    @Test
    @DisplayName("Null original exchange returns resource")
    void nullOriginal_returnsResource() {
        GraphContext ctx      = graphContext(2, 1);
        Exchange     resource = resourceExchange(ctx, 2, 0.4, 0.2, 2);

        Exchange result = strategy.aggregate(null, resource);

        assertThat(result).isSameAs(resource);
    }
}
