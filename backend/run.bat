@echo off
cd /d "%~dp0"
echo Starting SmartButler Backend...
echo Server: http://127.0.0.1:8000
echo Docs: http://127.0.0.1:8000/docs
echo Press Ctrl+C to stop
venv\Scripts\python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
pause
