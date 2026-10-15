@echo off
chcp 65001 > nul
title Bushido CloudStream - Eklenti Yukleyici
echo ========================================================
echo       BUSHIDO CLOUDSTREAM - OTOMATIK EKLENTI YUKLEYICI
echo ========================================================
echo.
echo Bu arac 'prebuilt' klasorundeki .cs3 eklenti dosyalarini
echo otomatik olarak okur, hash ve boyutlarini hesaplar ve
echo GitHub reposunun 'builds' dalina (CloudStream havuzuna) yukler.
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\deploy_all.ps1"

echo.
echo ========================================================
echo Islem tamamlandi. Cikmak icin bir tusa basin...
pause > nul
