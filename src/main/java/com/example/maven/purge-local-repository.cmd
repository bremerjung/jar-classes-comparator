@echo off
setlocal EnableDelayedExpansion

:: Pfadliste als Parameter oder Standard
set "PURGE_LIST=%~1"
if "%PURGE_LIST%"=="" set "PURGE_LIST=purge-paths.txt"

if not exist "%PURGE_LIST%" (
    echo Keine Pfadliste gefunden: %PURGE_LIST%
    echo Bitte zuerst das Maven-Goal ausfuehren:
    echo   mvn com.company:purge-plugin:purge-local-repository
    exit /b 1
)

echo Lese Pfadliste: %PURGE_LIST%
set /a COUNT=0
set /a ERRORS=0

for /f "usebackq delims=" %%P in ("%PURGE_LIST%") do (
    if exist "%%P\*" (
        echo   Loesche Verzeichnis: %%P
        rmdir /s /q "%%P" 2>nul
        if exist "%%P" (
            echo   FEHLER: Konnte nicht geloescht werden: %%P
            set /a ERRORS+=1
        ) else (
            set /a COUNT+=1
        )
    ) else if exist "%%P" (
        echo   Loesche Datei: %%P
        del /f /q "%%P" 2>nul
        if exist "%%P" (
            echo   FEHLER: Konnte nicht geloescht werden: %%P
            set /a ERRORS+=1
        ) else (
            set /a COUNT+=1
        )
    ) else (
        echo   Bereits entfernt: %%P
    )
)

del /f /q "%PURGE_LIST%" 2>nul

echo.
echo Fertig: !COUNT! geloescht, !ERRORS! Fehler.
if !ERRORS! gtr 0 (
    echo Tipp: Sicherstellen, dass keine IDE oder JVM die Dateien sperrt.
    exit /b 1
)
