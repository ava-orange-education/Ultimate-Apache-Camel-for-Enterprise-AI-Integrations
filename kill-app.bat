@echo off
echo Killing any process on port 8080...
for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    echo   Killing PID %%p
    taskkill /PID %%p /F >nul 2>&1
)

echo Killing any gradlew/bootRun processes...
for /f "tokens=2" %%p in ('tasklist /FI "IMAGENAME eq java.exe" /FO CSV /NH 2^>nul ^| findstr /V "^$"') do (
    taskkill /PID %%~p /F >nul 2>&1
)

echo Waiting for processes to die...
timeout /t 5 /nobreak >nul

echo Deleting old log...
del /F /Q "logs\app.log" >nul 2>&1

echo Done.
