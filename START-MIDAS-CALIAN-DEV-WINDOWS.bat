@echo off
setlocal EnableExtensions
chcp 65001 >nul 2>&1
title MIDAS + Calian AI - Development Startup

rem Run this script from the root of the extracted MIDAS_Calian_Option_B folder.
rem Angular development does NOT require running BUILD-MIDAS-CALIAN-WINDOWS.bat first.
echo.
echo ================================================================
echo  MIDAS + Calian AI - Development launcher (diagnostic edition)
echo ================================================================
echo.

cd /d "%~dp0midas-frontend"
if errorlevel 1 goto :missingfolder
if not exist "package.json" goto :missingfolder

echo [1/4] Checking Node.js...
where node >nul 2>&1
if errorlevel 1 goto :missingnode
call node --version
if errorlevel 1 goto :missingnode

echo.
echo [2/4] Checking pnpm...
where pnpm >nul 2>&1
if errorlevel 1 goto :missingpnpm
call pnpm --version
if errorlevel 1 goto :missingpnpm

echo.
echo [3/4] Checking Angular dependencies...
if exist "node_modules\.bin\ng.cmd" goto :launch

echo Angular dependencies are not installed in this extracted folder.
echo Installing with pnpm now. MIDAS requires private @simfront packages.
echo If installation fails, this window will stay open so you can see why.
echo.
call pnpm install
if errorlevel 1 goto :installfailed
if not exist "node_modules\.bin\ng.cmd" goto :installfailed

:launch
echo.
echo [4/4] Launching MIDAS Angular development server...
echo Calian backend must run separately on 127.0.0.1:8797 for agent calls.
echo When Angular reports a local URL, open that URL in your browser.
echo This window must remain open while using the dev server.
echo.
call pnpm run start:calian
if errorlevel 1 goto :startfailed

echo.
echo Development server ended normally.
goto :done

:missingfolder
echo.
echo ERROR: Cannot find the midas-frontend folder next to this BAT file.
echo Extract the complete ZIP; keep this BAT in MIDAS_Calian_Option_B.
goto :failed

:missingnode
echo.
echo ERROR: Node.js is missing, not on PATH, or cannot run.
echo Install a compatible Node.js version, reopen the terminal, and retry.
goto :failed

:missingpnpm
echo.
echo ERROR: pnpm is missing or not on PATH.
echo If Corepack is available, run: corepack enable
 echo Then reopen this terminal and run this launcher again.
echo Alternatively, install pnpm using your organization's approved method.
goto :failed

:installfailed
echo.
echo ERROR: pnpm install failed. This is NOT a reason to run BUILD first.
echo Check the error printed above. Common causes include:
echo  - no access to private @simfront/common-libs or @simfront/gis-web-angular
 echo  - company registry credentials or VPN missing
 echo  - incompatible Node.js/pnpm versions
 echo  - network/proxy/certificate configuration
 echo Do not paste passwords, tokens, or complete private registry credentials.
goto :failed

:startfailed
echo.
echo ERROR: Angular dev server exited with an error.
echo Review the output above for the FIRST error, such as TSxxxx, NGxxxx, or EADDRINUSE.
echo The build script is not a prerequisite for this command.
goto :failed

:failed
echo.
echo ================================================================
echo Please photograph or copy the FIRST error above, excluding secrets.
echo ================================================================
echo.
pause
exit /b 1

:done
echo.
pause
exit /b 0
