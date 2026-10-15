@echo off
rem 一键起 MySQL + 后端 + 前端（本机开发形态）
rem 数据、密码、会话都落在项目内的 mysql/ 目录，不装系统服务。

setlocal
cd /d "%~dp0"

set MYSQL_DIR=%~dp0mysql\mysql-8.4.9-winx64
set MYSQL_INI=%~dp0mysql\my.ini

rem 1. MySQL（已在跑就跳过）
netstat -ano | findstr /r ":3306 .*LISTENING" >nul 2>&1
if %errorlevel%==0 (
  echo [mysql] already running on 3306
) else (
  echo [mysql] starting...
  start "coding-agent-mysql" /b "%MYSQL_DIR%\bin\mysqld.exe" --defaults-file="%MYSQL_INI%"
  timeout /t 5 /nobreak >nul
)

rem 2. 后端（8280）
echo [backend] starting on 8280...
start "coding-agent-backend" cmd /k "cd /d %~dp0backend && set CODING_AGENT_SECRET_KEY=dev-secret-do-not-use-in-prod&& set CODING_AGENT_DEFAULT_PROVIDER=mimo&& mvn spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--server.port=8280"

rem 3. 前端（5173）
echo [frontend] starting on 5173...
start "coding-agent-frontend" cmd /k "cd /d %~dp0frontend && npm run dev"

echo.
echo MySQL   127.0.0.1:3306  (user=coding_agent / db=coding_agent)
echo Backend http://localhost:8280
echo Frontend http://localhost:5173
echo Login   admin / admin123
endlocal
