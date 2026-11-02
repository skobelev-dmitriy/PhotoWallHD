@echo off
rem One manual deploy. Example: deploy.cmd -Device 192.168.1.9:5555
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
