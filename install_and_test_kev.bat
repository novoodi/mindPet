@echo off
rem Installs the debug APK, pushes the Kev GGUF model, runs the on-device parity test and prints the KevResult lines.
rem Usage: install_and_test_kev.bat [threads]   (default 4)
setlocal
set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set MODEL=%~dp0..\dataset-evaluation\llamacpp\kev-notif-v3-Q6_K.gguf
set THREADS=%1
if "%THREADS%"=="" set THREADS=4

echo 1. Installing MaeumPet APK...
%ADB% install -r -d "%~dp0app\build\outputs\apk\debug\app-debug.apk"
if errorlevel 1 (
    echo Failed to install APK!
    exit /b 1
)

echo 2. Pushing GGUF model to device (skipped if the same size is already there)...
rem The app must create files/kev itself: a folder made by adb (owner shell) hides the model from the app.
%ADB% shell am start -S -n com.maeumpet.app/.kev.KevTestActivity >nul
ping -n 4 127.0.0.1 >nul
for %%F in ("%MODEL%") do set LOCAL_SIZE=%%~zF
for /f %%S in ('%ADB% shell stat -c %%s /sdcard/Android/data/com.maeumpet.app/files/kev/kev-notif-v3-Q6_K.gguf 2^>nul') do set REMOTE_SIZE=%%S
if not "%LOCAL_SIZE%"=="%REMOTE_SIZE%" (
    %ADB% push "%MODEL%" /sdcard/Android/data/com.maeumpet.app/files/kev/kev-notif-v3-Q6_K.gguf
    if errorlevel 1 (
        echo Failed to push model!
        exit /b 1
    )
)

echo 3. Launching KevTestActivity with the parity test (threads=%THREADS%)...
%ADB% logcat -c
%ADB% shell am start -S -n com.maeumpet.app/.kev.KevTestActivity --ei threads %THREADS% --ez autoParity true
echo Waiting for results (up to 3 minutes)...
for /l %%i in (1,1,90) do (
    %ADB% logcat -d -s KevResult:I | findstr /c:"parity agree" /c:"parity_error" /c:"load_error" >nul && goto done
    ping -n 3 127.0.0.1 >nul
)
:done
%ADB% logcat -d -s KevResult:I
endlocal
