@echo off
rem Runs the watcher without fiddling with the PowerShell execution policy. Example: watch.cmd -Device 192.168.1.9:5555
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0watch.ps1" %*
