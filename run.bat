@echo off
cd /d "%~dp0"
title BrokVN GUI Editor Runner
echo Compiling BrokVN GUI Editor...
javac BrokVnClickAreaWindow.java Main.java
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Compilation failed!
    pause
    exit /b %errorlevel%
)

echo Starting BrokVN GUI Editor...
start "" javaw BrokVnClickAreaWindow
