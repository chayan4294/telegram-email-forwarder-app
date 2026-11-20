@echo off
title Push Android App to GitHub
echo ======================================================
echo    Telegram Email Forwarder - GitHub APK Publisher
echo ======================================================
echo.

set PATH=%PATH%;d:\antigravity\mingit\cmd;d:\antigravity\gh

echo Step 1: Login to GitHub
echo ------------------------------------------------------
gh auth status >nul 2>&1
if %errorlevel% neq 0 (
    echo Please log in to your GitHub account:
    gh auth login -w -p https
) else (
    echo [OK] Already logged in to GitHub!
)

echo.
echo Step 2: Creating Repository and Pushing App...
echo ------------------------------------------------------
cd /d d:\antigravity\telegram-email-forwarder-android

gh repo create telegram-email-forwarder-app --public --source=. --remote=origin --push

if %errorlevel% equ 0 (
    echo.
    echo ======================================================
    echo  [SUCCESS] App code pushed to GitHub!
    echo.
    echo  GitHub Actions is now compiling your APK!
    echo  Opening GitHub Actions page in your browser...
    echo ======================================================
    gh repo view --web
) else (
    echo.
    echo [NOTE] If repository already exists, pushing updates...
    git push -u origin main
)

pause
