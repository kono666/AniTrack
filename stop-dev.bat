@echo off
chcp 65001 >nul
:: 编码: 本文件是 UTF-8 带 BOM + CRLF. BOM 不能丢 —— 没有它 cmd 会按 GBK
:: 解析, 中文行会被拆成半截当命令执行(实测 echo 会变成 ho 然后报错).
:: 详细说明见 start-dev.bat 顶部那段.
echo 关闭 Anime Tracker 所有服务...

:: 只按 start-dev.bat 自己起的窗口标题关, 一个一个写清楚.
::
:: 这里原本还有第三条 taskkill /FI "WINDOWTITLE eq Anime*", 已经删掉.
:: 那个通配符匹配的是"标题以 Anime 开头"的**任何**窗口, 包括:
::   - 启动器自己的窗口 ("Anime Tracker - Launcher")
::   - 以及任何恰好在标题里带这几个字的窗口(打开项目文档的编辑器、
::     停在某个标题含 Anime 的页面上的浏览器)
:: 后一类是很糟的误伤: 用户敲一下关闭脚本, 编辑器被强杀了.
:: 而前一类没有意义 —— 启动器只是停在 pause 上, 等用户自己关就行.
:: /T 会连同子进程一起杀, 所以关掉启动器等于把上面两个服务再杀一遍.
taskkill /FI "WINDOWTITLE eq Java-Backend*" /T /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq Vue-Frontend*" /T /F >nul 2>&1

echo 已关闭所有服务窗口.
timeout /t 2 >nul
