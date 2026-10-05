@echo off
rem Archives a month of the schedule: archive-schedule.bat [YYYY-MM] [--force] [--dry-run]
cd /d "%~dp0"
java ArchiveSchedule.java %*
echo.
pause
