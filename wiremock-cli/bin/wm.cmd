@echo off
set SCRIPT_DIR=%~dp0
if exist "%SCRIPT_DIR%..\src\main\java\com\github\mattiasgreen\wiremock\cli\WireMockCli.java" (
    java "%SCRIPT_DIR%..\src\main\java\com\github\mattiasgreen\wiremock\cli\WireMockCli.java" %*
) else (
    java "%SCRIPT_DIR%..\WireMockCli.java" %*
)
