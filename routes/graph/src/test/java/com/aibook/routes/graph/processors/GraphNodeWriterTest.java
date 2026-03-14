package com.aibook.routes.graph.processors;

import com.aibook.ai.graph.Neo4jGraphClient;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link GraphNodeWriter} with mocked {@link Neo4jGraphClient}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>writeNode is called with correct label and id</li>
 *   <li>Headers entityId/entityType/nodeWritten are set</li>
 *   <li>Missing entityId or entityType throw IllegalArgumentException</li>
 *   <li>Extra properties in body map are forwarded to writeNode</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GraphNodeWriterTest {

    @Mock Neo4jGraphClient neo4jClient;

    private DefaultCamelContext camelContext;
    private GraphNodeWriter     graphNodeWriter;

    @BeforeEach
    void setUp() throws Exception {
        camelContext  = new DefaultCamelContext();
        camelContext.start();
        graphNodeWriter = new GraphNodeWriter(neo4jClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(Map<String, Object> body) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(body);
        return ex;
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("writeNode is called with correct label and properties")
    void happyPath_writesNode() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("entityId",   "customer-001");
        body.put("entityType", "Customer");
        body.put("properties", Map.of("name", "Alice", "riskScore", 0.15));

        Exchange ex = exchange(body);
        graphNodeWriter.process(ex);

        // Verify Neo4j was called with correct label
        verify(neo4jClient, times(1)).writeNode(eq("Customer"), anyMap());

        // Verify headers set
        assertThat(ex.getIn().getHeader("entityId")).isEqualTo("customer-001");
        assertThat(ex.getIn().getHeader("entityType")).isEqualTo("Customer");
        assertThat(ex.getIn().getHeader("nodeWritten", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("Audit properties decisionId and stage are set on exchange")
    void auditProperties_set() throws Exception {
        Map<String, Object> body = Map.of("entityId", "entity-1", "entityType", "Account");
        Exchange ex = exchange(new HashMap<>(body));
        graphNodeWriter.process(ex);

        assertThat(ex.getProperty("decisionId")).isEqualTo("entity-1");
        assertThat(ex.getProperty("stage")).isEqualTo("graph-node-ingestion");
    }

    @Test
    @DisplayName("Body with no 'properties' key still writes node with id and type")
    void noExtraProperties_writesMinimalNode() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("entityId",   "device-003");
        body.put("entityType", "Device");

        Exchange ex = exchange(body);
        graphNodeWriter.process(ex);

        verify(neo4jClient, times(1)).writeNode(eq("Device"), anyMap());
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Null body → IllegalArgumentException")
    void nullBody_throwsIllegalArgument() {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(null);

        assertThatThrownBy(() -> graphNodeWriter.process(ex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GraphNodeWriter");
    }

    @Test
    @DisplayName("Missing entityId → IllegalArgumentException")
    void missingEntityId_throwsIllegalArgument() {
        Exchange ex = exchange(new HashMap<>(Map.of("entityType", "Customer")));

        assertThatThrownBy(() -> graphNodeWriter.process(ex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId");
    }

    @Test
    @DisplayName("Missing entityType → IllegalArgumentException")
    void missingEntityType_throwsIllegalArgument() {
        Exchange ex = exchange(new HashMap<>(Map.of("entityId", "cust-1")));

        assertThatThrownBy(() -> graphNodeWriter.process(ex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityType");
    }
}
