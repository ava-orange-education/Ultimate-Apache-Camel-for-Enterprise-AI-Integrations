@echo off
REM ============================================================
REM  run-01-summarization.bat
REM  Pipeline 1 — Intelligent Email & Document Summarization
REM
REM  What it does:
REM    1. Builds the summarization route module (and its deps)
REM    2. Runs all unit + integration tests for the summarization pipeline
REM    3. Starts the Spring Boot app in the background
REM    4. Sends a sample email payload via curl (REST smoke test)
REM    5. Sends a sample document payload via curl (REST smoke test)
REM
REM  Endpoints tested:
REM    POST http://localhost:8080/camel/api/summarize/email
REM    POST http://localhost:8080/camel/api/summarize/document
REM
REM  Output written to: %TEMP%\aibook\summaries\
REM
REM  Prerequisites:
REM    - Java 21 on PATH
REM    - Ollama running with llama3.2 pulled (run-00-infrastructure.bat)
REM    - curl on PATH (ships with Windows 10+)
REM ============================================================
setlocal

set "SCRIPT_DIR=%~dp0"
set "BASE_URL=http://localhost:8080/camel"
set "SAMPLE_EMAIL=%SCRIPT_DIR%datasets\synthetic\text\email-001.json"
set "SAMPLE_DOC=%SCRIPT_DIR%datasets\synthetic\text\document-001.json"

echo ============================================================
echo  Pipeline 1 — Email ^& Document Summarization
echo ============================================================
echo.

REM ── Step 1: Build + test ──────────────────────────────────────
echo [1/4] Building and testing routes:summarization ...
echo.
call "%SCRIPT_DIR%gradlew.bat" :routes:summarization:clean :routes:summarization:test --info
if errorlevel 1 (
    echo.
    echo [ERROR] Build or tests failed. See output above.
    exit /b 1
)
echo.
echo  [OK] Tests passed.
echo.

REM ── Step 2: Start app ─────────────────────────────────────────
echo [2/4] Starting Spring Boot application ...
echo       (This takes ~15 seconds. Waiting for port 8080 ...)
echo.

REM Kill any existing instance on port 8080
for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    echo  Stopping existing process on port 8080 (PID %%p)...
    taskkill /PID %%p /F >nul 2>&1
)

start "AI-Camel App (Pipeline 1)" /B cmd /c "%SCRIPT_DIR%gradlew.bat :app:bootRun > "%SCRIPT_DIR%logs\app-p1.log" 2>&1"

REM Wait for port 8080
:WAIT_APP
timeout /t 3 /nobreak >nul
curl -s --fail http://localhost:8080/actuator/health >nul 2>&1
if errorlevel 1 goto WAIT_APP
echo  [OK] Application is up on port 8080.
echo.

REM ── Step 3: Smoke test — email ────────────────────────────────
echo [3/4] Sending sample email payload ...
echo  File: %SAMPLE_EMAIL%
echo.
curl -s -X POST "%BASE_URL%/api/summarize/email" ^
     -H "Content-Type: application/json" ^
     -H "correlationId: test-email-001" ^
     --data-binary @"%SAMPLE_EMAIL%"
echo.
echo  [OK] Email payload submitted (async — check logs for LLM response).
echo.

REM ── Step 4: Smoke test — document ────────────────────────────
echo [4/4] Sending sample document payload ...
echo  File: %SAMPLE_DOC%
echo.
curl -s -X POST "%BASE_URL%/api/summarize/document" ^
     -H "Content-Type: application/json" ^
     -H "correlationId: test-doc-001" ^
     --data-binary @"%SAMPLE_DOC%"
echo.
echo  [OK] Document payload submitted (async — check logs for LLM response).
echo.

echo ============================================================
echo  Pipeline 1 smoke tests complete.
echo  Summary files written to: %TEMP%\aibook\summaries\
echo  App log: %SCRIPT_DIR%logs\app-p1.log
echo  Press Ctrl+C in this window or close "AI-Camel App" window to stop.
echo ============================================================
echo.

endlocal
