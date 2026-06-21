@echo off
REM Stops stale Gradle daemons that may lock app\build\...\R.jar on Windows/OneDrive.
call gradlew.bat --stop
call gradlew.bat assembleDebug %*
