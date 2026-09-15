@echo off
cd /d "%~dp0"
javaw -jar ADAM-Signal-Monitor.jar
if errorlevel 1 (
  echo Could not start ADAM Signal Monitor.
  echo Install Java 17 or newer and try again.
  pause
)
