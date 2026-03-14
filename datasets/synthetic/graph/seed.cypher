// ─── Neo4j seed script ──────────────────────────────────────────────────────
// Run via: cypher-shell -u neo4j -p password < seed.cypher
// Or paste in the Neo4j Browser at http://localhost:7474

// 1. Clear existing data (dev only!)
MATCH (n) DETACH DELETE n;

// 2. Create Concept nodes
UNWIND [
  'Retrieval-Augmented Generation', 'Large Language Model', 'Vector Database',
  'Apache Camel', 'LangChain4j', 'Qdrant', 'Neo4j', 'Embedding Model',
  'Spring Boot', 'Knowledge Graph'
] AS name
MERGE (c:Concept {name: name})
SET c.createdAt = datetime();

// 3. Create Document nodes
MERGE (d1:Document {id: 'doc-001'})
SET d1.title = 'Introduction to Retrieval-Augmented Generation',
    d1.mimeType = 'text/plain',
    d1.ingestedAt = datetime('2025-11-01T08:00:00Z');

MERGE (d2:Document {id: 'doc-002'})
SET d2.title = 'Apache Camel Integration Patterns',
    d2.mimeType = 'text/plain',
    d2.ingestedAt = datetime('2025-11-02T10:00:00Z');

// 4. Link Documents to Concepts
MATCH (d:Document {id: 'doc-001'}), (c:Concept {name: 'Retrieval-Augmented Generation'})
MERGE (d)-[:MENTIONS]->(c);

MATCH (d:Document {id: 'doc-001'}), (c:Concept {name: 'Vector Database'})
MERGE (d)-[:MENTIONS]->(c);

MATCH (d:Document {id: 'doc-001'}), (c:Concept {name: 'Large Language Model'})
MERGE (d)-[:MENTIONS]->(c);

MATCH (d:Document {id: 'doc-002'}), (c:Concept {name: 'Apache Camel'})
MERGE (d)-[:MENTIONS]->(c);

MATCH (d:Document {id: 'doc-002'}), (c:Concept {name: 'Spring Boot'})
MERGE (d)-[:MENTIONS]->(c);

// 5. Create Customer nodes for scoring
MERGE (c1:Customer {id: 'customer-42'})
SET c1.name = 'Acme Corp',
    c1.kycStatus = 'VERIFIED',
    c1.createdAt = datetime('2023-06-15T00:00:00Z');

MERGE (c2:Customer {id: 'customer-99'})
SET c2.name = 'Beta Ltd',
    c2.kycStatus = 'PENDING',
    c2.createdAt = datetime('2025-10-01T00:00:00Z');

// 6. Link Customers to Concepts (sectors they operate in)
MATCH (c:Customer {id: 'customer-42'}), (concept:Concept {name: 'Knowledge Graph'})
MERGE (c)-[:OPERATES_IN]->(concept);
