@echo off
REM ============================================================
REM  run-05-explanation.bat
REM  Pipeline 5 — AI-Assisted Explanation, Audit, and Human-in-the-Loop
REM
REM  What it does:
REM    1. Builds and tests routes:explanation
REM    2. Starts the Spring Boot app
REM    3. Triggers the scoring pipeline with a REVIEW-bound payload
REM       (which automatically flows into explanation → audit → human review)
REM    4. Polls the human review queue status
REM    5. Submits a manual reviewer decision via REST
REM    6. Fetches the stored audit record to verify the full trail
REM
REM  Endpoints tested:
REM    POST http://localhost:8080/camel/api/score            (triggers pipeline)
REM    GET  http://localhost:8080/camel/api/review/queue     (queue status)
REM    POST http://localhost:8080/camel/api/review/decision/{taskId}
REM    GET  http://localhost:8080/camel/api/audit/{decisionId}
REM
REM  Output:
REM    Audit records:   %TEMP%\aibook\audit\{decisionId}-audit.json
REM    Feedback:        %TEMP%\aibook\feedback\{taskId}-feedback.json
REM    Regression:      datasets\golden\regression_cases\{taskId}-regression.json
REM
REM  Prerequisites:
REM    - Java 21 on PATH
REM    - Ollama running with llama3.2 (LLM narrative generation)
REM    - curl on PATH
REM ============================================================
setlocal

set "SCRIPT_DIR=%~dp0"
set "BASE_URL=http://localhost:8080/camel"

echo ============================================================
echo  Pipeline 5 — AI-Assisted Explanation, Audit ^& Human-in-the-Loop
echo ============================================================
echo.

REM ── Step 1: Build + test ──────────────────────────────────────
echo [1/6] Building and testing routes:explanation ...
echo.
call "%SCRIPT_DIR%gradlew.bat" :routes:explanation:clean :routes:explanation:test --info
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
start "AI-Camel App (Pipeline 5)" /B cmd /c "%SCRIPT_DIR%gradlew.bat :app:bootRun > "%SCRIPT_DIR%logs\app-p5.log" 2>&1"

:WAIT_APP
timeout /t 3 /nobreak >nul
curl -s --fail http://localhost:8080/actuator/health >nul 2>&1
if errorlevel 1 goto WAIT_APP
echo  [OK] Application is up on port 8080.
echo.

REM ── Step 3: Submit scoring request → REVIEW path ─────────────
echo [3/6] Submitting high-risk scoring request (will trigger REVIEW → explanation → audit) ...
echo  Entity: customer-99, risk_flag_count=3, kyc_status=PENDING
echo.
curl -s -X POST "%BASE_URL%/api/score" ^
     -H "Content-Type: application/json" ^
     -d "{\"requestId\":\"explain-demo-001\",\"entityId\":\"customer-99\",\"entityType\":\"Customer\",\"features\":{\"account_age_days\":15,\"total_transactions\":8,\"avg_transaction_value\":4500.00,\"failed_transactions_30d\":5,\"countries_transacted\":7,\"kyc_status\":\"PENDING\",\"risk_flag_count\":3,\"last_login_days_ago\":0,\"support_tickets_open\":2},\"scoringProfile\":\"credit-risk-v2\"}"
echo.
echo.

echo  [INFO] The REVIEW path automatically triggers:
echo    - ExplanationArtifactRoute (builds artifact + LLM narrative)
echo    - AuditTrailRoute          (persists audit JSON)
echo    - HumanReviewRoute         (async queue dispatch + simulator)
echo  Waiting 10 seconds for async processing to complete ...
timeout /t 10 /nobreak >nul
echo.

REM ── Step 4: Check review queue ───────────────────────────────
echo [4/6] Checking human review queue status ...
echo.
curl -s "%BASE_URL%/api/review/queue"
echo.
echo.

REM ── Step 5: Submit manual reviewer decision ───────────────────
echo [5/6] Submitting manual reviewer decision (APPROVED) ...
echo  In production this would come from a human reviewer UI.
echo  taskId used: explain-demo-001 (matches requestId for demo purposes)
echo.
curl -s -X POST "%BASE_URL%/api/review/decision/explain-demo-001" ^
     -H "Content-Type: application/json" ^
     -d "{\"taskId\":\"explain-demo-001\",\"decision\":\"APPROVED\",\"reviewerNotes\":\"Verified with customer — legitimate high-value transaction.\",\"reviewedBy\":\"analyst-007\"}"
echo.
echo.

REM Wait for feedback file write
timeout /t 3 /nobreak >nul

REM ── Step 6: Fetch audit record ────────────────────────────────
echo [6/6] Fetching audit record for decisionId=explain-demo-001 ...
echo.
curl -s "%BASE_URL%/api/audit/explain-demo-001"
echo.
echo.

echo ============================================================
echo  Pipeline 5 smoke tests complete.
echo.
echo  Audit records:  %TEMP%\aibook\audit\
echo  Feedback:       %TEMP%\aibook\feedback\
echo  Regression:     %SCRIPT_DIR%datasets\golden\regression_cases\
echo  App log:        %SCRIPT_DIR%logs\app-p5.log
echo.
echo  Review the audit JSON for:
echo    - ExplanationArtifact with LLM-generated rationale
echo    - Full auditTrail array of AuditRecord entries
echo    - Feedback and regression case documents
echo ============================================================
echo.

endlocal
