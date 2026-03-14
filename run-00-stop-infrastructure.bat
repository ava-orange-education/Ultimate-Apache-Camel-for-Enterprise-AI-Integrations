@echo off
REM ============================================================
REM  run-00-stop-infrastructure.bat
REM  Stops and removes all AI-Camel Docker containers.
REM ============================================================
setlocal

set "SCRIPT_DIR=%~dp0"
set "COMPOSE_FILE=%SCRIPT_DIR%docker\docker-compose.yml"

echo ============================================================
echo  AI-Camel Project — Infrastructure Shutdown
echo ============================================================
echo.

docker compose -f "%COMPOSE_FILE%" down
if errorlevel 1 (
    echo [WARN] docker compose down reported an error (containers may already be stopped).
)

echo.
echo  [OK] Infrastructure stopped.
endlocal
