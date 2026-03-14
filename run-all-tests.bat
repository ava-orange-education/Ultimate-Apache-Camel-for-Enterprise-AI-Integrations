@echo off
REM ============================================================
REM  run-all-tests.bat
REM  Runs ALL unit and integration tests across all pipeline modules.
REM  Does NOT start the application or send HTTP requests.
REM  Use this for a quick CI-style validation of the full codebase.
REM
REM  Modules tested:
REM    :core
REM    :ai
REM    :routes:shared
REM    :routes:summarization   (Pipeline 1)
REM    :routes:rag             (Pipeline 2)
REM    :routes:scoring         (Pipeline 3)
REM    :routes:graph           (Pipeline 4)
REM    :routes:explanation     (Pipeline 5)
REM
REM  Prerequisites:
REM    - Java 21 on PATH
REM    - No external services needed (tests use mocks/stubs)
REM ============================================================
setlocal

set "SCRIPT_DIR=%~dp0"

echo ============================================================
echo  AI-Camel Project — Full Test Suite
echo  Running all pipeline unit ^& integration tests ...
echo ============================================================
echo.

call "%SCRIPT_DIR%gradlew.bat" clean test --info
if errorlevel 1 (
    echo.
    echo ============================================================
    echo  [FAILED] One or more tests failed. See output above.
    echo  HTML reports: build/reports/tests/test/index.html
    echo  (per module: routes/summarization/build/reports/tests/...)
    echo ============================================================
    exit /b 1
)

echo.
echo ============================================================
echo  [PASSED] All tests passed successfully!
echo.
echo  Test reports (HTML):
echo    routes\summarization\build\reports\tests\test\index.html
echo    routes\rag\build\reports\tests\test\index.html
echo    routes\scoring\build\reports\tests\test\index.html
echo    routes\graph\build\reports\tests\test\index.html
echo    routes\explanation\build\reports\tests\test\index.html
echo    core\build\reports\tests\test\index.html
echo    ai\build\reports\tests\test\index.html
echo ============================================================
echo.

endlocal
