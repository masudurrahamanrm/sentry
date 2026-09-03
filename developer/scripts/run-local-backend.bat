@echo off
echo ============================================================
echo   Starting Local Developer Backend (Port 4000)
echo   Connected to MongoDB Beta: kinetix_sentry_beta
echo ============================================================
echo.

cd /d "%~dp0..\..\backend"

npm run dev
