@echo off
REM ============================================================
REM  run-03-scoring.bat
REM  Pipeline 3 — Real-Time Scoring and Contextual Routing
REM
REM  What it does:
REM    1. Builds and tests routes:scoring
REM    2. Starts the Spring Boot app
REM    3. Sends three scoring requests that exercise all routing paths:
REM         score-001 → APPROVE  (low-risk, high confidence)
REM         score-002 → REVIEW   (high-risk, medium confidence)
REM         score-003 → ESCALATE (very high-risk, low confidence)
REM
REM  Endpoint tested:
REM    POST http://localhost:8080/camel/api/score
REM
REM  Output:
REM    APPROVE  → %TEMP%\aibook\scoring\approved\{requestId}.json
REM    REVIEW   → forwarded to Pipeline 5 (human review queue)
REM    ESCALATE → dead-letter log
REM
REM  Prerequisites:
REM    - Java 21 on PATH
REM    - Ollama running with llama3.2 (LLM scoring, with rule-based fallback)
REM ============================================================
setlocal

set "SCRIPT_DIR=%~dp0"
set "BASE_URL=http://localhost:8080/camel"
set "SAMPLES=%SCRIPT_DIR%datasets\synthetic\scoring"

echo ============================================================
echo  Pipeline 3 — Real-Time Scoring and Contextual Routing
echo ============================================================
echo.

REM ── Step 1: Build + test ──────────────────────────────────────
echo [1/5] Building and testing routes:scoring ...
echo.
call "%SCRIPT_DIR%gradlew.bat" :routes:scoring:clean :routes:scoring:test --info
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
start "AI-Camel App (Pipeline 3)" /B cmd /c "%SCRIPT_DIR%gradlew.bat :app:bootRun > "%SCRIPT_DIR%logs\app-p3.log" 2>&1"

:WAIT_APP
timeout /t 3 /nobreak >nul
curl -s --fail http://localhost:8080/actuator/health >nul 2>&1
if errorlevel 1 goto WAIT_APP
echo  [OK] Application is up on port 8080.
echo.

REM ── Step 3: Score — APPROVE path ─────────────────────────────
echo [3/5] Scoring request (expected: APPROVE) ...
echo  score-001: customer-42, low risk, KYC VERIFIED, 730 days old account
echo.
curl -s -X POST "%BASE_URL%/api/score" ^
     -H "Content-Type: application/json" ^
     --data-binary @"%SAMPLES%\scoring-payload-001.json"
echo.
echo.

REM ── Step 4: Score — REVIEW path ──────────────────────────────
echo [4/5] Scoring request (expected: REVIEW) ...
echo  score-002: customer-99, new account, KYC PENDING, multiple risk flags
echo.
curl -s -X POST "%BASE_URL%/api/score" ^
     -H "Content-Type: application/json" ^
     -d "{\"requestId\":\"score-002\",\"entityId\":\"customer-99\",\"entityType\":\"Customer\",\"features\":{\"account_age_days\":15,\"total_transactions\":8,\"avg_transaction_value\":4500.00,\"failed_transactions_30d\":5,\"countries_transacted\":7,\"kyc_status\":\"PENDING\",\"risk_flag_count\":3,\"last_login_days_ago\":0,\"support_tickets_open\":2},\"scoringProfile\":\"credit-risk-v2\"}"
echo.
echo.

REM ── Step 5: Score — ESCALATE path ────────────────────────────
echo [5/5] Scoring request (expected: ESCALATE) ...
echo  score-003: customer-007, brand new, UNVERIFIED, extreme risk flags
echo.
curl -s -X POST "%BASE_URL%/api/score" ^
     -H "Content-Type: application/json" ^
     -d "{\"requestId\":\"score-003\",\"entityId\":\"customer-007\",\"entityType\":\"Customer\",\"features\":{\"account_age_days\":1,\"total_transactions\":2,\"avg_transaction_value\":9999.99,\"failed_transactions_30d\":12,\"countries_transacted\":15,\"kyc_status\":\"UNVERIFIED\",\"risk_flag_count\":8,\"last_login_days_ago\":0,\"support_tickets_open\":5},\"scoringProfile\":\"credit-risk-v2\"}"
echo.
echo.

echo ============================================================
echo  Pipeline 3 smoke tests complete.
echo  APPROVE results:  %TEMP%\aibook\scoring\approved\
echo  REVIEW tasks:     forwarded to Pipeline 5 (human review queue)
echo  ESCALATE:         dead-letter log: %TEMP%\aibook\dead-letter\
echo  App log:          %SCRIPT_DIR%logs\app-p3.log
echo ============================================================
echo.

endlocal
