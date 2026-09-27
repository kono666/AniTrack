@echo off
chcp 65001 >nul 2>&1
title Anime Tracker - Launcher
setlocal
echo ========================================
echo   Anime Tracker - Starting all services
echo ========================================
echo.

:: ------------------------------------------------------------
:: Load .env
:: Spring Boot does NOT read .env on its own - it only reads real
:: environment variables. Without this block, copying .env.example
:: to .env would silently do nothing and the backend would fall back
:: to its built-in defaults (empty API key), which looks like "the AI
:: feature is broken" rather than "the key was never loaded".
:: Variables set here are inherited by the windows started below.
::
:: Only the variable NAMES are echoed: this window stays open, and
:: printing LLM_API_KEY would put the secret on screen for anyone
:: walking past.
:: ------------------------------------------------------------
set "ENV_FILE=%~dp0.env"
if exist "%ENV_FILE%" (
    echo [0] Loading %ENV_FILE%
    for /f "usebackq eol=# tokens=1* delims==" %%a in ("%ENV_FILE%") do (
        if not "%%~b"=="" (
            set "%%a=%%~b"
            echo     set %%a
        )
    )
    echo.
) else (
    echo [0] No .env found - using built-in defaults.
    echo     To configure the AI assistant: copy .env.example to .env and fill it in.
    echo.
)

:: Launch Java Backend
echo [1] Java Backend (port 8080)
start "Java-Backend-8080" cmd /k "cd /d %~dp0anime-tracker\backend && mvn spring-boot:run"
echo    Waiting for port 8080 (first boot takes 30-60s)...
powershell -Command "while($true){Start-Sleep 5;try{$c=New-Object Net.Sockets.TcpClient('localhost',8080);$c.Close();break}catch{}}" >nul 2>&1
echo    Java ready.

:: Launch Vue Frontend
echo [2] Vue Frontend (port 5173)
start "Vue-Frontend-5173" cmd /k "cd /d %~dp0anime-tracker\frontend && npm run dev"

echo.
echo ========================================
echo   All services launched!
echo   Open: http://localhost:5173
echo ========================================
echo.
echo Close this window or press any key...
pause >nul
