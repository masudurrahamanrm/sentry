@echo off
echo ============================================================
echo   Checking Developer Cloud Backend Health...
echo   URL: https://sentry-devloper-version.onrender.com/health
echo ============================================================
echo.

curl -s -i https://sentry-devloper-version.onrender.com/health

echo.
echo.
pause
