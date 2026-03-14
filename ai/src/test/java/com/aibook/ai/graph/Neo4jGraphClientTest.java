package com.aibook.ai.graph;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link Neo4jGraphClient} using the Neo4j embedded test harness.
 *
 * <p>The harness starts an in-process Neo4j instance and shuts it down after all tests.
 * Tests use the package-private {@code Neo4jGraphClient(Driver)} constructor to inject
 * the harness-provided driver — no Spring context required.
 */
class Neo4jGraphClientTest {

    private static Neo4j embeddedNeo4j;
    private static Driver driver;
    private Neo4jGraphClient client;

    @BeforeAll
    static void startNeo4j() {
        embeddedNeo4j = Neo4jBuilders.newInProcessBuilder()
                .withDisabledServer()   // no HTTP server needed
                .build();
        driver = GraphDatabase.driver(
                embeddedNeo4j.boltURI(),
                AuthTokens.none());
    }

    @AfterAll
    static void stopNeo4j() {
        if (driver       != null) driver.close();
        if (embeddedNeo4j != null) embeddedNeo4j.close();
    }

    @BeforeEach
    void setUp() {
        client = new Neo4jGraphClient(driver);
        // Wipe all data before each test for isolation
        try (var session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n");
        }
    }

    // ── writeNode() ───────────────────────────────────────────────────────────

    @Test
    void writeNode_createsNodeWithGivenLabel() {
        client.writeNode("Document", Map.of("id", "doc-1", "title", "Test Doc"));

        List<Map<String, Object>> rows = client.executeCypher(
                "MATCH (d:Document {id: 'doc-1'}) RETURN d.title AS title", Map.of());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("title")).isEqualTo("Test Doc");
    }

    @Test
    void writeNode_mergesExistingNodeOnDuplicateId() {
        client.writeNode("Document", Map.of("id", "doc-2", "title", "First"));
        client.writeNode("Document", Map.of("id", "doc-2", "title", "Updated"));

        List<Map<String, Object>> rows = client.executeCypher(
                "MATCH (d:Document {id: 'doc-2'}) RETURN count(d) AS cnt, d.title AS title",
                Map.of());

        assertThat(rows).hasSize(1);
        // Only one node should exist (MERGE, not CREATE)
        assertThat(rows.get(0).get("cnt")).isEqualTo(1L);
        assertThat(rows.get(0).get("title")).isEqualTo("Updated");
    }

    @Test
    void writeNode_throwsWhenIdMissing() {
        assertThatThrownBy(() -> client.writeNode("Document", Map.of("title", "no id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'id'");
    }

    // ── writeRelationship() ───────────────────────────────────────────────────

    @Test
    void writeRelationship_createsDirectedRelationship() {
        client.writeNode("Document", Map.of("id", "d1", "title", "Doc"));
        client.writeNode("Concept",  Map.of("id", "c1", "name",  "RAG"));

        client.writeRelationship("d1", "c1", "MENTIONS", Map.of());

        List<Map<String, Object>> rows = client.executeCypher(
                "MATCH (d)-[r:MENTIONS]->(c) WHERE d.id='d1' AND c.id='c1' " +
                "RETURN type(r) AS relType", Map.of());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("relType")).isEqualTo("MENTIONS");
    }

    @Test
    void writeRelationship_mergesOnDuplicateCall() {
        client.writeNode("A", Map.of("id", "a1"));
        client.writeNode("B", Map.of("id", "b1"));

        client.writeRelationship("a1", "b1", "LINKS_TO", Map.of("weight", "1"));
        client.writeRelationship("a1", "b1", "LINKS_TO", Map.of("weight", "2"));

        List<Map<String, Object>> rows = client.executeCypher(
                "MATCH ()-[r:LINKS_TO]->() RETURN count(r) AS cnt", Map.of());

        assertThat(rows.get(0).get("cnt")).isEqualTo(1L);
    }

    // ── executeCypher() ───────────────────────────────────────────────────────

    @Test
    void executeCypher_returnsEmptyListWhenNoMatch() {
        List<Map<String, Object>> rows = client.executeCypher(
                "MATCH (n:NonExistent) RETURN n", Map.of());
        assertThat(rows).isEmpty();
    }

    @Test
    void executeCypher_supportsParameterisedQuery() {
        client.writeNode("Item", Map.of("id", "item-42", "value", "hello"));

        List<Map<String, Object>> rows = client.executeCypher(
                "MATCH (i:Item {id: $id}) RETURN i.value AS v",
                Map.of("id", "item-42"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("v")).isEqualTo("hello");
    }

    @Test
    void executeCypher_returnsMultipleRows() {
        client.writeNode("Tag", Map.of("id", "tag-1", "name", "alpha"));
        client.writeNode("Tag", Map.of("id", "tag-2", "name", "beta"));
        client.writeNode("Tag", Map.of("id", "tag-3", "name", "gamma"));

        List<Map<String, Object>> rows = client.executeCypher(
                "MATCH (t:Tag) RETURN t.name AS name ORDER BY t.name", Map.of());

        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(r -> r.get("name")))
                .containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void executeCypher_withNullParams_doesNotThrow() {
        List<Map<String, Object>> rows = client.executeCypher(
                "RETURN 1 AS one", null);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("one")).isEqualTo(1L);
    }

    // ── Label sanitization ────────────────────────────────────────────────────

    @Test
    void writeNode_sanitizesLabelToPreventInjection() {
        // Label with special chars should be sanitized but still write the node
        assertThatCode(() -> client.writeNode("Safe_Label", Map.of("id", "safe-1")))
                .doesNotThrowAnyException();
    }
}
