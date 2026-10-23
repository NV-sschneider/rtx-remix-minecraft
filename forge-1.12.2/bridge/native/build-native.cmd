@echo off
rem SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
rem SPDX-License-Identifier: Apache-2.0
setlocal
if not defined BRIDGE_ROOT exit /b 1
if not defined BRIDGE_JAVA_HOME exit /b 1
if not defined BRIDGE_VISUAL_STUDIO exit /b 1
call "%BRIDGE_VISUAL_STUDIO%\VC\Auxiliary\Build\vcvarsall.bat" x64 >nul 2>&1
if errorlevel 1 exit /b 1
pushd "%BRIDGE_ROOT%\build\native\win64"
if errorlevel 1 exit /b 1
rem Including javac's declarations makes C++ compilation verify every JNI signature.
cl /nologo /O2 /Zi /LD /EHsc /std:c++17 /utf-8 ^
 /I"%BRIDGE_JAVA_HOME%\include" /I"%BRIDGE_JAVA_HOME%\include\win32" ^
 /I"%BRIDGE_ROOT%\native\include" ^
 /I"%BRIDGE_ROOT%\build\headers" ^
 /FI"%BRIDGE_ROOT%\build\headers\com_nvidia_rtxremix_RemixBridge.h" ^
 "%BRIDGE_ROOT%\native\remixbridge.cpp" ^
 "%BRIDGE_ROOT%\native\abi-contract.cpp" ^
 /Feremixbridge.dll /Fdremixbridge.pdb /link /nologo /DEBUG dbghelp.lib
set "BUILD_RESULT=%ERRORLEVEL%"
if not "%BUILD_RESULT%"=="0" goto finish
:finish
popd
exit /b %BUILD_RESULT%
