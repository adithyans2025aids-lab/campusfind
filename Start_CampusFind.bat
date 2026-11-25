@echo off
title CampusFind Backend Starter
color 0A
echo ============================================================
echo         CAMPUSFIND: STARTING BACKEND AND TUNNEL
echo ============================================================
echo.

set MYSQL_PASSWORD=LostFound@2026
cd /d "%~dp0"

echo [1/2] Launching Java Backend Server (Port 8080)...
start "CampusFind Java Backend" cmd /k "java -cp src;src\mysql-connector-j-26.10.0.jar LostFoundServer"

timeout /t 3 /nobreak >nul

echo [2/2] Launching Public HTTPS Tunnel (great-suns-kiss)...
start "CampusFind Public Tunnel" cmd /k "npx localtunnel --port 8080 --subdomain great-suns-kiss"

echo.
echo ============================================================
echo  ALL SYSTEMS ONLINE!
echo.
echo  Website: https://adithyans2025aids-lab.github.io/campusfind/
echo  API Tunnel: https://great-suns-kiss.loca.lt
echo.
echo  NOTE: Keep the two opened command windows minimized while
echo  using the website. Closing them disconnects the server.
echo ============================================================
echo.
pause
