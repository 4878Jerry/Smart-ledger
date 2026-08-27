@echo off
cd /d "%~dp0"
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" x64
if errorlevel 1 goto :err

echo ============================================
echo [1/2] 编译预算规划模块（独立校验）
echo ============================================
cl /EHsc /std:c++17 /O2 /utf-8 /DBUDGET_PLANNER_MAIN budget_planner.cpp /Fe:budget_planner.exe user32.lib gdi32.lib comctl32.lib
if errorlevel 1 goto :err

echo.
echo ============================================
echo [2/2] 编译主程序（合并预算规划模块）
echo ============================================
cl /EHsc /std:c++17 /O2 /utf-8 expense_analyzer.cpp /Fe:expense_analyzer.exe user32.lib gdi32.lib gdiplus.lib comdlg32.lib comctl32.lib shell32.lib
if errorlevel 1 goto :err

echo.
echo BUILD OK  (expense_analyzer.exe + budget_planner.exe)
goto :eof
:err
echo BUILD FAILED
