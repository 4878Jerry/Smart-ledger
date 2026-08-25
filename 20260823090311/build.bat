@echo off
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" x64
if errorlevel 1 goto :err
cl /EHsc /std:c++17 /O2 /utf-8 expense_analyzer.cpp /Fe:expense_analyzer.exe user32.lib gdi32.lib gdiplus.lib comdlg32.lib comctl32.lib shell32.lib
if errorlevel 1 goto :err
echo.
echo BUILD OK
goto :eof
:err
echo BUILD FAILED
