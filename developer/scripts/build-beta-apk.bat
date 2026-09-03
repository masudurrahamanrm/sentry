@echo off
echo ============================================================
echo   Building Beta APKs (Side-by-side isolated packages)
echo   Target: com.example.sentry.beta / com.example.kinetix.beta
echo   Backend: https://sentry-devloper-version.onrender.com/api/v1
echo ============================================================
echo.

cd /d "%~dp0..\.."

call .\gradlew.bat assembleBeta

echo.
echo ============================================================
echo   Build Complete!
echo   SentrY Beta APK: sentry\build\outputs\apk\beta\sentry-beta.apk
echo   Kinetix Beta APK: kinetix\build\outputs\apk\beta\kinetix-beta.apk
echo ============================================================
echo.
pause
