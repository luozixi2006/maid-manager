@echo off
cd /d "%~dp0"
"%~dp0.venv\Scripts\python.exe" "%~dp0main.py" --pair
if errorlevel 1 (pause & exit /b 1)
notepad.exe "%~dp0.secrets\pairing-code.txt"
