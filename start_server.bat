@echo off
title FlashDeals - Flash Sale System (B.Tech 5th Sem Project)
echo ========================================================
echo        FlashDeals - Flash Sale Booking System
echo ========================================================

cd /d "%~dp0FlashSaleProject"

echo Checking and freeing port 8080...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    taskkill /f /pid %%a >nul 2>&1
)

echo Compiling Java source files...
javac -cp ".;mysql-connector-j-26.7.0.jar" *.java
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Compilation failed!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ========================================================
echo Server ready! Opening http://localhost:8080 in browser...
echo ========================================================
start "" "http://localhost:8080"
echo.
java -cp ".;mysql-connector-j-26.7.0.jar" WebServer 8080
pause
