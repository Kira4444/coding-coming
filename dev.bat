@echo off
rem Coding Agent dev service control
rem   dev.bat             show status
rem   dev.bat start       start mysql + backend + frontend
rem   dev.bat stop        stop all
rem   dev.bat restart     restart all
rem   dev.bat logs -Service backend
rem
rem Thin wrapper around dev.ps1. ASCII-only comments + CRLF on purpose:
rem cmd parses .bat as GBK and is buggy with LF-only line endings, so
rem UTF-8 Chinese comments here get mis-parsed and can execute themselves.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev.ps1" %*
