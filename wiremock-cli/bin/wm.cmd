@echo off
set SCRIPT_DIR=%~dp0
java "%SCRIPT_DIR%..\src\main\java\com\github\mattiasgreen\wiremock\cli\WireMockCli.java" %*
