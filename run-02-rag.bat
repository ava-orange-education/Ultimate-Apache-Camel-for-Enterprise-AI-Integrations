@echo off
REM ============================================================
REM  run-02-rag.bat
REM  Pipeline 2 — Retrieval-Augmented Generation (RAG)
REM
REM  What it does:
REM    1. Builds and tests the rag route module
REM    2. Starts the Spring Boot app
REM    3. Ingests a sample knowledge document (POST /api/rag/ingest)
REM       — chunks, embeds (all-MiniLM-L6-v2 ONNX), upserts to Qdrant
REM    4. Submits a natural-language query (POST /api/rag/query)
REM       — embeds query, vector search Qdrant, LLM generates answer
REM    5. Prints the answer JSON to console
REM
REM  Endpoints tested:
REM    POST http://localhost:8080/camel/api/rag/ingest
REM    POST http://localhost:8080/camel/api/rag/query
REM
REM  Output written to: %TEMP%\aibook\rag\{queryId}-answer.json
REM
REM  Prerequisites:
REM    - Qdrant running (run-00-infrastructure.bat)
REM    - Ollama running with llama3.2 pulled
REM    - Java 21 on PATH
REM ============================================================
setlocal

set "SCRIPT_DIR=%~dp0"
set "BASE_URL=http://localhost:8080/camel"
set "SAMPLE_DOC=%SCRIPT_DIR%datasets\synthetic\text\document-001.json"

echo ============================================================
echo  Pipeline 2 — Retrieval-Augmented Generation (RAG)
echo ============================================================
echo.

REM ── Step 1: Build + test ──────────────────────────────────────
echo [1/5] Building and testing routes:rag ...
echo.
call "%SCRIPT_DIR%gradlew.bat" :routes:rag:clean :routes:rag:test --info
if errorlevel 1 (
    echo.
    echo [ERROR] Build or tests failed. See output above.
    exit /b 1
)
echo.
echo  [OK] Tests passed.
echo.

REM ── Step 2: Start app ─────────────────────────────────────────
echo [2/5] Starting Spring Boot application ...
echo       (Waiting for port 8080 ...)

for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    echo  Stopping existing process on port 8080 (PID %%p)...
    taskkill /PID %%p /F >nul 2>&1
)

if not exist "%SCRIPT_DIR%logs" mkdir "%SCRIPT_DIR%logs"
start "AI-Camel App (Pipeline 2)" /B cmd /c "%SCRIPT_DIR%gradlew.bat :app:bootRun > "%SCRIPT_DIR%logs\app-p2.log" 2>&1"

:WAIT_APP
timeout /t 3 /nobreak >nul
curl -s --fail http://localhost:8080/actuator/health >nul 2>&1
if errorlevel 1 goto WAIT_APP
echo  [OK] Application is up on port 8080.
echo.

REM ── Step 3: Ingest document ───────────────────────────────────
echo [3/5] Ingesting knowledge document into Qdrant ...
echo  File: %SAMPLE_DOC%
echo.
curl -s -X POST "%BASE_URL%/api/rag/ingest" ^
     -H "Content-Type: application/json" ^
     --data-binary @"%SAMPLE_DOC%"
echo.
echo  [OK] Document ingested (chunked, embedded, upserted to Qdrant).
echo  Waiting 3 seconds for Qdrant indexing to settle ...
timeout /t 3 /nobreak >nul
echo.

REM ── Step 4: RAG Query 1 ───────────────────────────────────────
echo [4/5] Submitting RAG query 1 ...
echo  Query: "What is Retrieval-Augmented Generation and why does it reduce hallucinations?"
echo.
curl -s -X POST "%BASE_URL%/api/rag/query" ^
     -H "Content-Type: text/plain" ^
     -d "What is Retrieval-Augmented Generation and why does it reduce hallucinations?"
echo.
echo.

REM ── Step 5: RAG Query 2 ───────────────────────────────────────
echo [5/5] Submitting RAG query 2 ...
echo  Query: "What vector stores are commonly used in RAG implementations?"
echo.
curl -s -X POST "%BASE_URL%/api/rag/query" ^
     -H "Content-Type: text/plain" ^
     -d "What vector stores are commonly used in RAG implementations?"
echo.
echo.

echo ============================================================
echo  Pipeline 2 smoke tests complete.
echo  Answer JSON files: %TEMP%\aibook\rag\
echo  App log:           %SCRIPT_DIR%logs\app-p2.log
echo  Qdrant UI:         http://localhost:6333/dashboard
echo ============================================================
echo.

endlocal
