@echo off
chcp 65001 >nul 2>&1
:: ============================================================
::  编码: 本文件必须以 UTF-8 **带 BOM** 保存 (并保留上面的 chcp 65001).
::
::  不这么做的后果不是"中文显示成乱码"这么轻. 批处理文件没有 BOM 时,
::  cmd 会按系统 ANSI 代码页(简中是 936/GBK)去解析文件的字节, UTF-8 的中文
::  被拆成半个字, 解析器从中间断开 —— 实测: `echo 某某` 里的 echo 会变成
::  `ho`, 然后报 "ho 不是内部或外部命令". 也就是说中文行会被当成**命令**
::  执行, 而不是被当成文本显示.
::
::  改这个文件时用能保留 BOM 的编辑器; 别"另存为 ANSI".
:: ============================================================
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
::
:: -Dspring-boot.run.profiles=dev is REQUIRED, not a convenience:
:: application.yml no longer sets a default profile. Without an explicit
:: dev here the backend would refuse to start (no JWT_SECRET in .env by
:: default) - that is intentional, so that a deployment which forgets to
:: configure anything fails loudly instead of quietly coming up with the
:: dev H2 database, the public admin/admin123 account and swagger.
echo [1] Java Backend (port 8080, profile=dev)
start "Java-Backend-8080" cmd /k "cd /d %~dp0anime-tracker\backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev"
echo    Waiting for port 8080 (first boot takes 30-60s, giving up after 120s)...
::
:: 这里原来是个 while($true) 的死等. 后端启动失败时(常见三种: .env 里的
:: JWT_SECRET 没填、8080 被别的程序占用、上一次的 java 没退干净导致 H2 文件库
:: 被独占锁住), 用户看到的是这行字永远停在那里 —— 没有报错, 没有超时,
:: 也没有任何线索指向"该去看 Java-Backend-8080 那个窗口". 加个上限之后,
:: 最坏情况是等两分钟, 然后明确告诉他去看哪里.
powershell -NoProfile -Command "$end=(Get-Date).AddSeconds(120); while((Get-Date) -lt $end){ try{ $c=New-Object Net.Sockets.TcpClient('localhost',8080); $c.Close(); exit 0 } catch { Start-Sleep -Seconds 5 } }; exit 1" >nul 2>&1
if errorlevel 1 (
    echo    [!!] Waited 120s but port 8080 never came up - the backend probably failed to start.
    echo         Look at the window titled Java-Backend-8080. Usual causes:
    echo           JWT_SECRET missing from .env / port 8080 taken / a leftover java process
    echo           still holding the H2 file lock.
    echo         The frontend will still start, but every API call will fail.
) else (
    echo    Java ready.
)

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
