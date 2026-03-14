# Datasets

This directory contains synthetic and golden datasets used for development, testing, and regression validation.

## Structure

```
datasets/
├── synthetic/
│   ├── text/          ← Sample EmailMessage and DocumentContent JSON files
│   ├── embeddings/    ← Pre-computed embedding vectors (optional, for offline testing)
│   ├── graph/         ← Cypher seed scripts for Neo4j
│   └── scoring/       ← Sample ScoringRequest payloads
├── golden/
│   ├── prompts/           ← Frozen prompt versions used for regression
│   ├── expected_outputs/  ← Expected LLM response snapshots
│   └── regression_cases/  ← JSON test cases with expected behaviour
└── README.md
```

## Populating Qdrant

1. Start the Docker stack: `docker compose -f docker/docker-compose.yml up -d`
2. POST each document to the ingestion endpoint:
   ```
   curl -X POST http://localhost:8080/camel/ingest/knowledge \
        -H "Content-Type: application/json" \
        -d @datasets/synthetic/text/document-001.json
   ```
3. The `KnowledgeIngestionRoute` will chunk, embed, and store all vectors automatically.

## Populating Neo4j

1. Copy the seed script into the container:
   ```
   docker cp datasets/synthetic/graph/seed.cypher aibook-neo4j:/var/lib/neo4j/import/seed.cypher
   ```
2. Execute via cypher-shell:
   ```
   docker exec aibook-neo4j cypher-shell -u neo4j -p password --file /var/lib/neo4j/import/seed.cypher
   ```
3. Verify in the browser at http://localhost:7474

## Running Regression Cases

Golden regression cases live in `golden/regression_cases/`. Each JSON file defines a query, 
expected keywords, and acceptable score ranges. Use the test harness in `tools/test-harness/` 
to run them automatically against a live stack.
