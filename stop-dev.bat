@echo off
chcp 65001 >nul
echo 关闭 Anime Tracker 所有服务...

taskkill /FI "WINDOWTITLE eq Java-Backend*" /T /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq Vue-Frontend*" /T /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq Anime*" /T /F >nul 2>&1

echo 已关闭所有服务窗口.
timeout /t 2 >nul
