@echo off
cd /d "%~dp0"
title Compiling BrokVN GUI Editor to EXE...
echo [1/3] Compiling Java classes...
if not exist build\classes mkdir build\classes
if not exist build\jar mkdir build\jar

javac -d build\classes BrokVnClickAreaWindow.java Main.java
if %errorlevel% neq 0 (
    echo [ERROR] Compilation failed!
    pause
    exit /b %errorlevel%
)

echo [2/3] Creating executable JAR...
jar cfe build\jar\BrokVnGuiEditor.jar BrokVnClickAreaWindow -C build\classes .
if %errorlevel% neq 0 (
    echo [ERROR] JAR packaging failed!
    pause
    exit /b %errorlevel%
)

echo [3/3] Creating standalone Windows EXE package via jpackage...
taskkill /F /IM "BrokVnGuiEditor.exe" >nul 2>nul
taskkill /F /IM "BrokVnClickAreaWindow.exe" >nul 2>nul
timeout /t 1 /nobreak >nul 2>nul
if exist dist\BrokVnGuiEditor rmdir /s /q dist\BrokVnGuiEditor
if exist dist\BrokVnClickAreaWindow rmdir /s /q dist\BrokVnClickAreaWindow

jpackage --type app-image --input build\jar --main-jar BrokVnGuiEditor.jar --main-class BrokVnClickAreaWindow --name "BrokVnGuiEditor" --dest dist

if %errorlevel% neq 0 (
    echo [ERROR] jpackage failed!
    pause
    exit /b %errorlevel%
)

if exist logo.png copy /y logo.png dist\BrokVnGuiEditor\ >nul
if exist icon.png copy /y icon.png dist\BrokVnGuiEditor\ >nul
if exist glueit.exe copy /y glueit.exe dist\BrokVnGuiEditor\ >nul
if exist "GlueIT 1.06.exe" copy /y "GlueIT 1.06.exe" dist\BrokVnGuiEditor\ >nul

echo.
echo ========================================================
echo SUCCESS! Your standalone EXE is ready:
echo dist\BrokVnGuiEditor\BrokVnGuiEditor.exe
echo ========================================================
