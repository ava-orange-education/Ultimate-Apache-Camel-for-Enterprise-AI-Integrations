# Ultimate-Apache-Camel-for-Enterprise-AI-Integrations Project

A production-ready **Apache Camel 4.x + Spring Boot 3.x** multi-module pipeline project
demonstrating AI integration patterns with **LangChain4j**, **Qdrant** vector search,
and **Neo4j** graph enrichment.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Spring Boot App                          │
│                                                                 │
│  ┌──────────────┐  ┌───────┐  ┌─────────┐  ┌───────────────┐  │
│  │Summarization │  │  RAG  │  │ Scoring │  │ Explanation/  │  │
│  │   Routes     │  │Routes │  │ Routes  │  │  Audit Routes │  │
│  └──────┬───────┘  └───┬───┘  └────┬────┘  └───────┬───────┘  │
│         │              │           │                │           │
│  ┌──────▼──────────────▼───────────▼────────────────▼───────┐  │
│  │             ai module (LangChain4j Services)              │  │
│  │  EmbeddingService │ LlmGateway │ QdrantVectorStore        │  │
│  │  VectorSearchService │ Neo4jGraphClient │ GraphTraversal  │  │
│  └────────────────────────────────────────────────────────── ┘  │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │        core module (DTOs, Processors, Config)            │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
         │                    │                  │
    ┌────▼────┐          ┌────▼───┐        ┌─────▼─────┐
    │  Qdrant │          │ Neo4j  │        │  Ollama   │
    │ :6333   │          │ :7687  │        │  :11434   │
    └─────────┘          └────────┘        └───────────┘
```

---

## Module Overview

| Module | Description |
|---|---|
| `core` | Immutable Java 21 record DTOs, Camel processors, error handling, config properties |
| `ai` | LangChain4j embedding/LLM services, Qdrant vector store wrapper, Neo4j graph client |
| `routes:summarization` | Email/document ingestion, LLM summarization, output persistence |
| `routes:rag` | Knowledge ingestion, chunking/embedding pipeline, vector retrieval, RAG assembly |
| `routes:scoring` | Feature assembly with graph enrichment, LLM scoring, contextual routing |
| `routes:graph` | Neo4j node ingestion, graph enrichment via LLM, graph-based decision routing |
| `routes:explanation` | Explainability artifacts, audit trail persistence, human-review escalation |
| `routes:shared` | Shared tracing processors and payload validators |
| `app` | Spring Boot entry-point, application.yml, all prompt templates |

---

## Prerequisites

- **Java 21** (via SDKMAN or your preferred manager)
- **Docker & Docker Compose** (for Qdrant, Neo4j, Ollama)
- **Gradle 8.x** (or use the wrapper `./gradlew`)

---

## Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/ava-orange-education/Ultimate-Apache-Camel-for-Enterprise-AI-Integrations.git
cd Ultimate-Apache-Camel-for-Enterprise-AI-Integrations
```

### 2. Start the Docker services

```bash
docker compose -f docker/docker-compose.yml up -d
```

Wait for all health checks to pass (≈ 30 s):

```bash
docker compose -f docker/docker-compose.yml ps
```

### 3. Pull the default LLM model (Ollama)

```bash
docker exec aibook-ollama ollama pull llama3.2
```

### 4. Build the project

```bash
./gradlew build -x test
```

### 5. Run the application

```bash
./gradlew :app:bootRun
```

The application will start on **http://localhost:8080**.

Camel management console: **http://localhost:8080/actuator/camel**

---

## Populating Qdrant with Synthetic Data

Ingest the sample document into the RAG knowledge base:

```bash
curl -X POST http://localhost:8080/camel/knowledge-ingest \
     -H "Content-Type: application/json" \
     -d @datasets/synthetic/text/document-001.json
```

The `KnowledgeIngestionRoute` will automatically:
1. Normalize the text
2. Split it into overlapping 500-char chunks
3. Embed each chunk using all-MiniLM-L6-v2
4. Store all vectors in the Qdrant `documents` collection

Verify via Qdrant dashboard: **http://localhost:6333/dashboard**

---

## Populating Neo4j with Synthetic Data

```bash
# Copy seed script into the container
docker cp datasets/synthetic/graph/seed.cypher aibook-neo4j:/var/lib/neo4j/import/seed.cypher

# Run it
docker exec aibook-neo4j cypher-shell \
    -u neo4j -p password \
    --file /var/lib/neo4j/import/seed.cypher
```

Verify in the Neo4j Browser: **http://localhost:7474**

---

## Invoking Pipelines via REST

All pipelines are exposed via Apache Camel Servlet at `/camel/*`.

### Summarization — Email

```bash
curl -X POST http://localhost:8080/camel/ingest/email \
     -H "Content-Type: application/json" \
     -d @datasets/synthetic/text/email-001.json
```

### Summarization — Document

```bash
curl -X POST http://localhost:8080/camel/ingest/document \
     -H "Content-Type: application/json" \
     -d @datasets/synthetic/text/document-001.json
```

### RAG — Query

```bash
curl -X POST http://localhost:8080/camel/rag/query \
     -H "Content-Type: text/plain" \
     -d "What is Retrieval-Augmented Generation?"
```

### Scoring

```bash
curl -X POST http://localhost:8080/camel/score \
     -H "Content-Type: application/json" \
     -d @datasets/synthetic/scoring/scoring-payload-001.json
```

### Graph Ingestion

```bash
curl -X POST http://localhost:8080/camel/graph/ingest \
     -H "Content-Type: application/json" \
     -d @datasets/synthetic/text/document-001.json
```

---

## Configuration Reference

All configuration is in `app/src/main/resources/application.yml`.
Environment variable overrides:

| Env Var | Default | Description |
|---|---|---|
| `AIBOOK_LLM_ENDPOINT` | `http://localhost:11434` | LLM API base URL |
| `AIBOOK_LLM_MODEL` | `llama3.2` | Model name |
| `AIBOOK_LLM_API_KEY` | _(empty)_ | API key (OpenAI-compatible) |
| `AIBOOK_EMBEDDINGS_MODEL` | `local` | `local` or `openai` |
| `AIBOOK_QDRANT_HOST` | `localhost` | Qdrant host |
| `AIBOOK_QDRANT_PORT` | `6334` | Qdrant gRPC port |
| `AIBOOK_NEO4J_URI` | `bolt://localhost:7687` | Neo4j Bolt URI |
| `AIBOOK_NEO4J_USERNAME` | `neo4j` | Neo4j username |
| `AIBOOK_NEO4J_PASSWORD` | `password` | Neo4j password |

---

## Using OpenAI Instead of Ollama

```bash
AIBOOK_LLM_ENDPOINT=https://api.openai.com/v1 \
AIBOOK_LLM_MODEL=gpt-4o-mini \
AIBOOK_LLM_API_KEY=sk-... \
AIBOOK_EMBEDDINGS_MODEL=text-embedding-3-small \
./gradlew :app:bootRun
```

---

## Running Tests

```bash
# All tests
./gradlew test

# Single module
./gradlew :core:test
./gradlew :routes:summarization:test
```

---

## Project Directory Map

```
ai-camel-project/
├── build.gradle              ← Root build with BOM versions
├── settings.gradle           ← Module declarations
├── core/                     ← DTOs, processors, error, config
├── ai/                       ← LLM/embedding/vector/graph services
├── routes/
│   ├── summarization/        ← Email/doc ingestion → summarization
│   ├── rag/                  ← Knowledge store → retrieval → RAG answer
│   ├── scoring/              ← Feature assembly → LLM scoring → routing
│   ├── graph/                ← Neo4j ingestion → enrichment → decision
│   ├── explanation/          ← Audit trail, explainability, human review
│   └── shared/               ← Tracing headers, payload validation
├── app/                      ← Spring Boot app, application.yml, prompts/
├── docker/
│   └── docker-compose.yml    ← Qdrant + Neo4j + Ollama
├── datasets/
│   ├── synthetic/            ← Sample emails, documents, graph seeds
│   └── golden/               ← Regression test cases
└── tools/
    ├── test-harness/         ← Integration test runner
    ├── schema-validators/    ← JSON schema validation utilities
    └── utilities/            ← Seed scripts, data generators
```

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
