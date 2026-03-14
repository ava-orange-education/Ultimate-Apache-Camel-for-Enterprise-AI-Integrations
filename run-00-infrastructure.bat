@echo off
REM ============================================================
REM  run-00-infrastructure.bat
REM  Starts Qdrant, Neo4j, and Ollama via Docker Compose.
REM  Wait for health checks to pass before running pipeline bats.
REM ============================================================
setlocal

set "SCRIPT_DIR=%~dp0"
set "COMPOSE_FILE=%SCRIPT_DIR%docker\docker-compose.yml"

echo ============================================================
echo  AI-Camel Project — Infrastructure Startup
echo  File: %COMPOSE_FILE%
echo ============================================================
echo.

where docker >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker not found on PATH. Please install Docker Desktop.
    exit /b 1
)

docker compose -f "%COMPOSE_FILE%" up -d --remove-orphans
if errorlevel 1 (
    echo [ERROR] docker compose up failed. Check Docker is running.
    exit /b 1
)

echo.
echo ============================================================
echo  Containers started. Waiting for health checks...
echo  This may take 30-60 seconds on first run.
echo ============================================================
echo.

:WAIT_QDRANT
echo  Checking Qdrant health (http://localhost:6333/healthz)...
curl -s --fail http://localhost:6333/healthz >nul 2>&1
if errorlevel 1 (
    timeout /t 5 /nobreak >nul
    goto WAIT_QDRANT
)
echo  [OK] Qdrant is healthy.

:WAIT_NEO4J
echo  Checking Neo4j health (http://localhost:7474)...
curl -s --fail http://localhost:7474 >nul 2>&1
if errorlevel 1 (
    timeout /t 5 /nobreak >nul
    goto WAIT_NEO4J
)
echo  [OK] Neo4j is healthy.

:WAIT_OLLAMA
echo  Checking Ollama health (http://localhost:11434/api/tags)...
curl -s --fail http://localhost:11434/api/tags >nul 2>&1
if errorlevel 1 (
    timeout /t 5 /nobreak >nul
    goto WAIT_OLLAMA
)
echo  [OK] Ollama is healthy.

echo.
echo ============================================================
echo  All services are healthy.
echo    Qdrant  : http://localhost:6333  (gRPC: 6334)
echo    Neo4j   : http://localhost:7474  (Bolt: 7687)
echo    Ollama  : http://localhost:11434
echo ============================================================
echo.
echo  TIP: Pull the LLM model if not already done:
echo    docker exec -it aibook-ollama ollama pull llama3.2
echo.

endlocal
