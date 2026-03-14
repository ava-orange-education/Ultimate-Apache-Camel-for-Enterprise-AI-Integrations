@echo off
REM ============================================================
REM  run-04-graph.bat
REM  Pipeline 4 — Graph-Enriched Decision
REM
REM  What it does:
REM    1. Builds and tests routes:graph
REM    2. Starts the Spring Boot app
REM    3. Seeds Neo4j with sample entity nodes
REM    4. Seeds Neo4j with sample relationships
REM    5. Sends three graph enrichment requests:
REM         enrich-001 → graphRiskElevated=true  (high-risk cluster)
REM         enrich-002 → graphRiskElevated=false (low-risk, skip traversal)
REM         enrich-003 → graph traversal triggered, medium risk
REM
REM  Endpoints tested:
REM    POST http://localhost:8080/camel/api/graph/ingest/entity
REM    POST http://localhost:8080/camel/api/graph/ingest/relationship
REM    POST http://localhost:8080/camel/api/graph/enrich
REM
REM  Prerequisites:
REM    - Neo4j running (run-00-infrastructure.bat)
REM    - Java 21 on PATH
REM    - curl on PATH
REM ============================================================
setlocal

set "SCRIPT_DIR=%~dp0"
set "BASE_URL=http://localhost:8080/camel"
set "SAMPLES=%SCRIPT_DIR%datasets\synthetic\graph"

echo ============================================================
echo  Pipeline 4 — Graph-Enriched Decision
echo ============================================================
echo.

REM ── Step 1: Build + test ──────────────────────────────────────
echo [1/6] Building and testing routes:graph ...
echo.
call "%SCRIPT_DIR%gradlew.bat" :routes:graph:clean :routes:graph:test --info
if errorlevel 1 (
    echo.
    echo [ERROR] Build or tests failed. See output above.
    exit /b 1
)
echo.
echo  [OK] Tests passed.
echo.

REM ── Step 2: Start app ─────────────────────────────────────────
echo [2/6] Starting Spring Boot application ...
echo       (Waiting for port 8080 ...)

for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    echo  Stopping existing process on port 8080 (PID %%p)...
    taskkill /PID %%p /F >nul 2>&1
)

if not exist "%SCRIPT_DIR%logs" mkdir "%SCRIPT_DIR%logs"
start "AI-Camel App (Pipeline 4)" /B cmd /c "%SCRIPT_DIR%gradlew.bat :app:bootRun > "%SCRIPT_DIR%logs\app-p4.log" 2>&1"

:WAIT_APP
timeout /t 3 /nobreak >nul
curl -s --fail http://localhost:8080/actuator/health >nul 2>&1
if errorlevel 1 goto WAIT_APP
echo  [OK] Application is up on port 8080.
echo.

REM ── Step 3: Seed Neo4j — entity nodes ────────────────────────
echo [3/6] Seeding Neo4j with entity nodes ...
echo.

for %%E in (customer-001 customer-002 customer-003 customer-004 customer-005 customer-006 customer-007 customer-008) do (
    curl -s -X POST "%BASE_URL%/api/graph/ingest/entity" ^
         -H "Content-Type: application/json" ^
         -d "{\"entityId\":\"%%E\",\"entityType\":\"Customer\",\"properties\":{\"name\":\"%%E\",\"status\":\"active\"}}" >nul
    echo  Seeded node: %%E
)
echo.
echo  [OK] Entity nodes seeded.
echo.

REM ── Step 4: Seed Neo4j — relationships ───────────────────────
echo [4/6] Seeding Neo4j with relationships (risk cluster around customer-005) ...
echo.

REM Build a cluster: customer-005 connected to many peers with high strength
curl -s -X POST "%BASE_URL%/api/graph/ingest/relationship" ^
     -H "Content-Type: application/json" ^
     -d "{\"fromId\":\"customer-005\",\"toId\":\"customer-001\",\"relationshipType\":\"TRANSACTED_WITH\",\"properties\":{\"strength\":0.85}}" >nul
curl -s -X POST "%BASE_URL%/api/graph/ingest/relationship" ^
     -H "Content-Type: application/json" ^
     -d "{\"fromId\":\"customer-005\",\"toId\":\"customer-002\",\"relationshipType\":\"TRANSACTED_WITH\",\"properties\":{\"strength\":0.78}}" >nul
curl -s -X POST "%BASE_URL%/api/graph/ingest/relationship" ^
     -H "Content-Type: application/json" ^
     -d "{\"fromId\":\"customer-005\",\"toId\":\"customer-003\",\"relationshipType\":\"TRANSACTED_WITH\",\"properties\":{\"strength\":0.92}}" >nul
curl -s -X POST "%BASE_URL%/api/graph/ingest/relationship" ^
     -H "Content-Type: application/json" ^
     -d "{\"fromId\":\"customer-005\",\"toId\":\"customer-006\",\"relationshipType\":\"SAME_DEVICE\",\"properties\":{\"strength\":0.80}}" >nul
curl -s -X POST "%BASE_URL%/api/graph/ingest/relationship" ^
     -H "Content-Type: application/json" ^
     -d "{\"fromId\":\"customer-005\",\"toId\":\"customer-007\",\"relationshipType\":\"SHARED_IP\",\"properties\":{\"strength\":0.88}}" >nul
curl -s -X POST "%BASE_URL%/api/graph/ingest/relationship" ^
     -H "Content-Type: application/json" ^
     -d "{\"fromId\":\"customer-005\",\"toId\":\"customer-008\",\"relationshipType\":\"SAME_BENEFICIARY\",\"properties\":{\"strength\":0.75}}" >nul

echo  [OK] Relationships seeded (customer-005 in high-risk cluster).
echo.

REM ── Step 5: Enrich — risk cluster ────────────────────────────
echo [5/6] Graph enrichment — enrich-001 (expected: graphRiskElevated=true) ...
echo  customer-005 — high-risk cluster, connectedEntityCount^>5, avgStrength^>0.7
echo.
curl -s -X POST "%BASE_URL%/api/graph/enrich" ^
     -H "Content-Type: application/json" ^
     --data-binary @"%SAMPLES%\sample-enrichment-requests.json" 2>&1 | more
echo.

REM ── Step 6: Enrich — low risk, skip traversal ────────────────
echo [6/6] Graph enrichment — enrich-002 (expected: graphRiskElevated=false) ...
echo  customer-001 — established customer, high confidence, traversal skipped
echo.
curl -s -X POST "%BASE_URL%/api/graph/enrich" ^
     -H "Content-Type: application/json" ^
     -d "{\"requestId\":\"enrich-002\",\"entityId\":\"customer-001\",\"score\":0.15,\"confidence\":0.92,\"routingDecision\":\"APPROVE\",\"features\":{\"account_age_days\":1200,\"kyc_status\":\"VERIFIED\",\"failed_transactions_30d\":0}}"
echo.
echo.

echo ============================================================
echo  Pipeline 4 smoke tests complete.
echo  Neo4j Browser: http://localhost:7474
echo    Username: neo4j / Password: password
echo    Query:    MATCH (n)-[r]->(m) RETURN n,r,m LIMIT 50
echo  App log:   %SCRIPT_DIR%logs\app-p4.log
echo  Audit:     %TEMP%\aibook\audit\
echo ============================================================
echo.

endlocal
