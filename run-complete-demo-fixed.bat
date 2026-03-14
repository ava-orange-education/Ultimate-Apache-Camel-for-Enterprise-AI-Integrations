@echo off
REM ============================================================
REM  run-complete-demo-fixed.bat
REM  AI-Camel Project -- Full End-to-End Demo (All 5 Pipelines)
REM  ASCII-safe version (no Unicode box-drawing characters)
REM
REM  Steps performed:
REM    [00] Preflight checks (Docker, Java, curl)
REM    [01] Start Docker infrastructure (Qdrant + Neo4j + Ollama)
REM    [02] Pull llama3.2 model into Ollama (skipped if already present)
REM    [03] Build + test ALL pipeline modules
REM    [04] Start Spring Boot application
REM    [05] Pipeline 1 -- Email & Document Summarization smoke test
REM    [06] Pipeline 2 -- RAG ingest + query smoke test
REM    [07] Pipeline 3 -- Scoring (APPROVE / REVIEW / ESCALATE) smoke test
REM    [08] Pipeline 4 -- Graph ingest + enrichment smoke test
REM    [09] Pipeline 5 -- Explanation, Audit & Human Review smoke test
REM    [10] Print output locations summary
REM ============================================================
setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"

REM Change to project root so Gradle finds settings.gradle regardless of where bat is launched from
cd /d "%~dp0"
set "BASE_URL=http://localhost:8080/camel"
set "COMPOSE_FILE=%SCRIPT_DIR%docker\docker-compose.yml"
set "SAMPLES_TEXT=%SCRIPT_DIR%datasets\synthetic\text"
set "SAMPLES_SCORING=%SCRIPT_DIR%datasets\synthetic\scoring"
set "SAMPLES_GRAPH=%SCRIPT_DIR%datasets\synthetic\graph"

if not exist "%SCRIPT_DIR%logs" mkdir "%SCRIPT_DIR%logs"

echo.
echo ==============================================================
echo   AI-Camel Project -- Complete End-to-End Demo
echo   All 5 Pipelines   March 2026
echo ==============================================================
echo.

REM ==============================================================
REM  [00] PREFLIGHT CHECKS
REM ==============================================================
echo -- [00] Preflight Checks -------------------------------------
echo.

where docker >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker not found on PATH. Please install Docker Desktop.
    goto :FAIL
)
echo  [OK] Docker found.

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found on PATH. Please install Java 21.
    goto :FAIL
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr "version"') do set JAVA_VER=%%v
echo  [OK] Java found: %JAVA_VER%

where curl >nul 2>&1
if errorlevel 1 (
    echo [ERROR] curl not found on PATH.
    goto :FAIL
)
echo  [OK] curl found.

docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker daemon is not running. Please start Docker Desktop.
    goto :FAIL
)
echo  [OK] Docker daemon is running.
echo.

REM ==============================================================
REM  [01] START INFRASTRUCTURE
REM ==============================================================
echo -- [01] Starting Docker Infrastructure -----------------------
echo  Starting: Qdrant, Neo4j, Ollama ...
echo.

docker compose -f "%COMPOSE_FILE%" up -d --remove-orphans
if errorlevel 1 (
    echo [ERROR] docker compose up failed.
    goto :FAIL
)
echo.
echo  Containers started. Waiting for health checks ...
echo  (This may take 30-60 seconds on first run.)
echo.

:WAIT_QDRANT
curl -s --fail http://localhost:6333/healthz >nul 2>&1
if errorlevel 1 ( timeout /t 4 /nobreak >nul & goto :WAIT_QDRANT )
echo  [OK] Qdrant  healthy  -- http://localhost:6333

:WAIT_NEO4J
curl -s --fail http://localhost:7474 >nul 2>&1
if errorlevel 1 ( timeout /t 4 /nobreak >nul & goto :WAIT_NEO4J )
echo  [OK] Neo4j   healthy  -- http://localhost:7474

:WAIT_OLLAMA
curl -s --fail http://localhost:11434/api/tags >nul 2>&1
if errorlevel 1 ( timeout /t 4 /nobreak >nul & goto :WAIT_OLLAMA )
echo  [OK] Ollama  healthy  -- http://localhost:11434
echo.

REM ==============================================================
REM  [02] PULL llama3.2 MODEL (skip if already present)
REM ==============================================================
echo -- [02] Checking / Pulling llama3.2 Model --------------------
echo.

curl -s http://localhost:11434/api/tags | findstr "llama3.2" >nul 2>&1
if errorlevel 1 (
    echo  Model llama3.2 not found -- pulling now (~2 GB, please wait ...^)
    docker exec -it aibook-ollama ollama pull llama3.2
    if errorlevel 1 (
        echo [ERROR] Failed to pull llama3.2. Check Ollama container logs.
        goto :FAIL
    )
    echo  [OK] llama3.2 pulled successfully.
) else (
    echo  [OK] llama3.2 already present -- skipping pull.
)
echo.

REM ==============================================================
REM  [03] BUILD + TEST ALL PIPELINE MODULES
REM ==============================================================
echo -- [03] Build + Test All Pipeline Modules --------------------
echo  Running: gradlew test
echo  (Tests use mocks -- no live services required for unit tests)
echo  (Skipping 'clean' to avoid locking conflicts with running app)
echo.

call "%SCRIPT_DIR%gradlew.bat" test
if errorlevel 1 (
    echo.
    echo [ERROR] One or more tests FAILED. See output above.
    echo  Reports: routes\{module}\build\reports\tests\test\index.html
    goto :FAIL
)
echo.
echo  [OK] All pipeline tests PASSED.
echo.

REM ==============================================================
REM  [04] START SPRING BOOT APPLICATION
REM ==============================================================
echo -- [04] Starting Spring Boot Application ---------------------
echo  Entry-point: com.aibook.app.Application
echo  Port: 8080
echo.

REM Always stop any existing app process so the new logback-spring.xml is picked up
echo  Stopping any existing process on port 8080 ...
for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    echo  Killing PID %%p ...
    taskkill /PID %%p /F >nul 2>&1
)
timeout /t 2 /nobreak >nul

REM Resolve the absolute path to the project-root logs\ directory
set "LOG_DIR=%SCRIPT_DIR%logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

REM NOTE: stdout is redirected to bootrun-console.log (Gradle/JVM console output).
REM       Logback writes its own logs\app.log directly as a file appender.
REM       Opening the same file from two streams causes a Windows file-lock conflict.
start "AI-Camel App" /B cmd /c "cd /d "%SCRIPT_DIR%" && gradlew.bat :app:bootRun -Pjvm.log.path="%LOG_DIR:\=/%" > "%LOG_DIR%\bootrun-console.log" 2>&1"

echo  Waiting for application to start ...
:WAIT_APP
timeout /t 3 /nobreak >nul
curl -s --fail http://localhost:8080/actuator/health >nul 2>&1
if errorlevel 1 goto :WAIT_APP

:APP_READY
echo  [OK] Application is UP on http://localhost:8080
echo.

REM ==============================================================
REM  [05] PIPELINE 1 -- EMAIL & DOCUMENT SUMMARIZATION
REM ==============================================================
echo ==============================================================
echo  [05] Pipeline 1 -- Email ^& Document Summarization
echo ==============================================================
echo.

echo  POST /api/summarize/email  (email-001.json)
curl -s -X POST "%BASE_URL%/api/summarize/email" ^
     -H "Content-Type: application/json" ^
     -H "correlationId: demo-email-001" ^
     --data-binary @"%SAMPLES_TEXT%\email-001.json"
echo.
echo.

echo  POST /api/summarize/document  (document-001.json)
curl -s -X POST "%BASE_URL%/api/summarize/document" ^
     -H "Content-Type: application/json" ^
     -H "correlationId: demo-doc-001" ^
     --data-binary @"%SAMPLES_TEXT%\document-001.json"
echo.
echo.

echo  [OK] Pipeline 1 payloads submitted (async LLM -- see logs\pipeline-summarization.log for results)
echo  Output: %TEMP%\aibook\summaries\
echo.
timeout /t 3 /nobreak >nul

REM ==============================================================
REM  [05b] PIPELINE 1 (cont.) -- FILE-DROP INGEST DEMO
REM ==============================================================
echo ==============================================================
echo  [05b] Pipeline 1 ^(cont.^) -- File-Drop Ingest Demo
echo ==============================================================
echo.
echo  Dropping sample document into ingest watch folder ...

if not exist "%TEMP%\aibook\ingest" mkdir "%TEMP%\aibook\ingest"

echo {"documentId":"drop-001","contentType":"text/plain","extractedText":"This document was automatically ingested via the file-drop polling route. The Camel file consumer watches the ingest folder every 30 seconds and feeds new files directly into the document summarization pipeline without any REST API call.","metadata":{"source":"file-drop","fileName":"drop-001.json"}} > "%TEMP%\aibook\ingest\drop-001.json"

echo  [OK] drop-001.json written to %TEMP%\aibook\ingest\
echo  (Camel polls every 30s -- summary will appear in summaries\ asynchronously)
echo  Ingest folder: %TEMP%\aibook\ingest\
echo.
timeout /t 3 /nobreak >nul

REM ==============================================================
REM  [06] PIPELINE 2 -- RAG (INGEST + QUERY)
REM ==============================================================
echo ==============================================================
echo  [06] Pipeline 2 -- Retrieval-Augmented Generation (RAG)
echo ==============================================================
echo.

echo  POST /api/rag/ingest  (document-001.json)
curl -s -X POST "%BASE_URL%/api/rag/ingest" ^
     -H "Content-Type: application/json" ^
     --data-binary @"%SAMPLES_TEXT%\document-001.json"
echo.
echo  Waiting 4 seconds for Qdrant indexing ...
timeout /t 4 /nobreak >nul
echo.

echo  POST /api/rag/query  -- "What is RAG and how does it reduce hallucinations?"
curl -s -X POST "%BASE_URL%/api/rag/query" ^
     -H "Content-Type: text/plain" ^
     -d "What is Retrieval-Augmented Generation and why does it reduce hallucinations?"
echo.
echo.

echo  POST /api/rag/query  -- "What vector stores are used in RAG?"
curl -s -X POST "%BASE_URL%/api/rag/query" ^
     -H "Content-Type: text/plain" ^
     -d "What vector stores are commonly used in RAG implementations?"
echo.
echo.

echo  [OK] Pipeline 2 complete.
echo  Output: %TEMP%\aibook\rag\
echo.
timeout /t 3 /nobreak >nul

REM ==============================================================
REM  [07] PIPELINE 3 -- REAL-TIME SCORING & CONTEXTUAL ROUTING
REM ==============================================================
echo ==============================================================
echo  [07] Pipeline 3 -- Real-Time Scoring ^& Contextual Routing
echo ==============================================================
echo.

echo  [APPROVE path] score-001: customer-42, low-risk, KYC=VERIFIED
curl -s -X POST "%BASE_URL%/api/score" ^
     -H "Content-Type: application/json" ^
     --data-binary @"%SAMPLES_SCORING%\scoring-payload-001.json"
echo.
echo.

echo  [REVIEW path]  score-002: customer-99, KYC=PENDING, 3 risk flags
curl -s -X POST "%BASE_URL%/api/score" ^
     -H "Content-Type: application/json" ^
     -d "{\"requestId\":\"score-002\",\"entityId\":\"customer-99\",\"entityType\":\"Customer\",\"features\":{\"account_age_days\":15,\"total_transactions\":8,\"avg_transaction_value\":4500.00,\"failed_transactions_30d\":5,\"countries_transacted\":7,\"kyc_status\":\"PENDING\",\"risk_flag_count\":3,\"last_login_days_ago\":0,\"support_tickets_open\":2},\"scoringProfile\":\"credit-risk-v2\"}"
echo.
echo.

echo  [ESCALATE path] score-003: customer-007, UNVERIFIED, 8 risk flags
curl -s -X POST "%BASE_URL%/api/score" ^
     -H "Content-Type: application/json" ^
     -d "{\"requestId\":\"score-003\",\"entityId\":\"customer-007\",\"entityType\":\"Customer\",\"features\":{\"account_age_days\":1,\"total_transactions\":2,\"avg_transaction_value\":9999.99,\"failed_transactions_30d\":12,\"countries_transacted\":15,\"kyc_status\":\"UNVERIFIED\",\"risk_flag_count\":8,\"last_login_days_ago\":0,\"support_tickets_open\":5},\"scoringProfile\":\"credit-risk-v2\"}"
echo.
echo.

echo  [OK] Pipeline 3 complete.
echo  APPROVE: %TEMP%\aibook\scoring\approved\
echo  REVIEW:  forwarded to Pipeline 5 (human review queue)
echo  ESCALATE: dead-letter: %TEMP%\aibook\dead-letter\
echo.
timeout /t 3 /nobreak >nul

REM ==============================================================
REM  [08] PIPELINE 4 -- GRAPH-ENRICHED DECISION
REM ==============================================================
echo ==============================================================
echo  [08] Pipeline 4 -- Graph-Enriched Decision
echo ==============================================================
echo.

echo  Seeding Neo4j entity nodes ...
for %%E in (customer-001 customer-002 customer-003 customer-004 customer-005 customer-006 customer-007 customer-008) do (
    curl -s -X POST "%BASE_URL%/api/graph/ingest/entity" ^
         -H "Content-Type: application/json" ^
         -d "{\"entityId\":\"%%E\",\"entityType\":\"Customer\",\"properties\":{\"name\":\"%%E\",\"status\":\"active\"}}" >nul
)
echo  [OK] 8 entity nodes seeded.

echo  Seeding Neo4j relationships (risk cluster around customer-005) ...
curl -s -X POST "%BASE_URL%/api/graph/ingest/relationship" -H "Content-Type: application/json" -d "{\"fromId\":\"customer-005\",\"toId\":\"customer-001\",\"relationshipType\":\"TRANSACTED_WITH\",\"properties\":{\"strength\":0.85}}" >nul
curl -s -X POST "%BASE_URL%/api/graph/ingest/relationship" -H "Content-Type: application/json" -d "{\"fromId\":\"customer-005\",\"toId\":\"customer-002\",\"relationshipType\":\"TRANSACTED_WITH\",\"properties\":{\"strength\":0.78}}" >nul
curl -s -X POST "%BASE_URL%/api/graph/ingest/relationship" -H "Content-Type: application/json" -d "{\"fromId\":\"customer-005\",\"toId\":\"customer-003\",\"relationshipType\":\"TRANSACTED_WITH\",\"properties\":{\"strength\":0.92}}" >nul
curl -s -X POST "%BASE_URL%/api/graph/ingest/relationship" -H "Content-Type: application/json" -d "{\"fromId\":\"customer-005\",\"toId\":\"customer-006\",\"relationshipType\":\"SAME_DEVICE\",\"properties\":{\"strength\":0.80}}" >nul
curl -s -X POST "%BASE_URL%/api/graph/ingest/relationship" -H "Content-Type: application/json" -d "{\"fromId\":\"customer-005\",\"toId\":\"customer-007\",\"relationshipType\":\"SHARED_IP\",\"properties\":{\"strength\":0.88}}" >nul
curl -s -X POST "%BASE_URL%/api/graph/ingest/relationship" -H "Content-Type: application/json" -d "{\"fromId\":\"customer-005\",\"toId\":\"customer-008\",\"relationshipType\":\"SAME_BENEFICIARY\",\"properties\":{\"strength\":0.75}}" >nul
echo  [OK] 6 relationships seeded.
echo.

echo  [RISK ELEVATED] Enriching customer-005 (high-risk cluster expected) ...
curl -s -X POST "%BASE_URL%/api/graph/enrich" ^
     -H "Content-Type: application/json" ^
     -d "{\"requestId\":\"enrich-001\",\"entityId\":\"customer-005\",\"score\":0.88,\"confidence\":0.62,\"routingDecision\":\"REVIEW\",\"features\":{\"account_age_days\":5,\"kyc_status\":\"UNVERIFIED\",\"failed_transactions_30d\":8}}"
echo.
echo.

echo  [SKIPPED]      Enriching customer-001 (low-risk, traversal skipped expected) ...
curl -s -X POST "%BASE_URL%/api/graph/enrich" ^
     -H "Content-Type: application/json" ^
     -d "{\"requestId\":\"enrich-002\",\"entityId\":\"customer-001\",\"score\":0.15,\"confidence\":0.92,\"routingDecision\":\"APPROVE\",\"features\":{\"account_age_days\":1200,\"kyc_status\":\"VERIFIED\",\"failed_transactions_30d\":0}}"
echo.
echo.

echo  [OK] Pipeline 4 complete.
echo  Neo4j Browser: http://localhost:7474  (neo4j / password)
echo  Audit: %TEMP%\aibook\audit\
echo.
timeout /t 3 /nobreak >nul

REM ==============================================================
REM  [09] PIPELINE 5 -- EXPLANATION, AUDIT & HUMAN-IN-THE-LOOP
REM ==============================================================
echo ==============================================================
echo  [09] Pipeline 5 -- Explanation, Audit ^& Human-in-the-Loop
echo ==============================================================
echo.

echo  Triggering REVIEW path (explain-demo-001) -- explanation -- audit -- human review ...
curl -s -X POST "%BASE_URL%/api/score" ^
     -H "Content-Type: application/json" ^
     -d "{\"requestId\":\"explain-demo-001\",\"entityId\":\"customer-55\",\"entityType\":\"Customer\",\"features\":{\"account_age_days\":2,\"total_transactions\":3,\"avg_transaction_value\":9500.00,\"failed_transactions_30d\":10,\"countries_transacted\":12,\"kyc_status\":\"UNVERIFIED\",\"risk_flag_count\":7,\"last_login_days_ago\":0,\"support_tickets_open\":4},\"scoringProfile\":\"credit-risk-v2\"}"
echo.
echo.

echo  Waiting 10 seconds for async processing (explanation + audit + review queue) ...
timeout /t 10 /nobreak >nul

echo  GET /api/review/queue
curl -s "%BASE_URL%/api/review/queue"
echo.
echo.

echo  POST /api/review/decision/explain-demo-001  (manual APPROVED decision)
curl -s -X POST "%BASE_URL%/api/review/decision/explain-demo-001" ^
     -H "Content-Type: application/json" ^
     -d "{\"taskId\":\"explain-demo-001\",\"decision\":\"APPROVED\",\"reviewerNotes\":\"Verified -- legitimate high-value transaction.\",\"reviewedBy\":\"analyst-007\"}"
echo.
echo.

timeout /t 3 /nobreak >nul

echo  GET /api/audit/explain-demo-001
curl -s "%BASE_URL%/api/audit/explain-demo-001"
echo.
echo.

echo  [OK] Pipeline 5 complete.
echo  Audit:      %TEMP%\aibook\audit\
echo  Feedback:   %TEMP%\aibook\feedback\
echo  Regression: %SCRIPT_DIR%datasets\golden\regression_cases\
echo.

REM ==============================================================
REM  [10] SUMMARY
REM ==============================================================
echo ==============================================================
echo   Complete Demo Finished Successfully!
echo ==============================================================
echo   Pipeline Log Files (%LOG_DIR%\):
echo     Pipeline 1 - Summarization : %LOG_DIR%\pipeline-summarization.log
echo     Pipeline 2 - RAG           : %LOG_DIR%\pipeline-rag.log
echo     Pipeline 3 - Scoring       : %LOG_DIR%\pipeline-scoring.log
echo     Pipeline 4 - Graph         : %LOG_DIR%\pipeline-graph.log
echo     Pipeline 5 - Explanation   : %LOG_DIR%\pipeline-explanation.log
echo     Shared / Core / AI modules : %LOG_DIR%\pipeline-shared.log
echo     Catch-all (root logger)    : %LOG_DIR%\app.log
echo     Gradle/console output      : %LOG_DIR%\bootrun-console.log
echo ==============================================================
echo   Pipeline Output Locations:
echo     Summaries    : %TEMP%\aibook\summaries\
echo     RAG answers  : %TEMP%\aibook\rag\
echo     Approved     : %TEMP%\aibook\scoring\approved\
echo     Dead-letter  : %TEMP%\aibook\dead-letter\
echo     Audit trail  : %TEMP%\aibook\audit\
echo     Feedback     : %TEMP%\aibook\feedback\
echo     Ingest drop  : %TEMP%\aibook\ingest\
echo     Regression   : datasets\golden\regression_cases\
echo ==============================================================
echo   Service UIs:
echo     Spring Boot  : http://localhost:8080/actuator/health
echo     Qdrant       : http://localhost:6333/dashboard
echo     Neo4j        : http://localhost:7474  (neo4j/password)
echo     Ollama       : http://localhost:11434/api/tags
echo ==============================================================
echo   To STOP everything:  run-00-stop-infrastructure.bat
echo   App still running in background window "AI-Camel App"
echo ==============================================================
echo.
goto :EOF

:FAIL
echo.
echo ==============================================================
echo   [FAILED] Demo aborted. See error above.
echo ==============================================================
echo.
exit /b 1
