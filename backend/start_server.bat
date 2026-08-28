@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo 正在启动 SmartButler 后端...
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
pause
