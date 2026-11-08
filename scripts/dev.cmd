@echo off
setlocal
set "PROJECT_ROOT=%~dp0.."
set "JAVA_HOME=%PROJECT_ROOT%\.tools\java\jdk25.0.5_0"
set "PATH=%JAVA_HOME%\bin;%PROJECT_ROOT%\.tools\node\node-v24.22.2-win-x64;%PROJECT_ROOT%\.tools\maven\apache-maven-3.9.16\bin;%APPDATA%\npm;%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin;%PATH%"
set "npm_config_cache=%PROJECT_ROOT%\.local\npm-cache"
if /i "%~1"=="build" goto build
if /i "%~1"=="run" goto run
if /i "%~1"=="setup-identity" goto identity
if /i "%~1"=="check-login" goto login
if /i "%~1"=="setup-store" goto store
if /i "%~1"=="check-accounts" goto accounts
if /i "%~1"=="test-store" goto teststore
if /i "%~1"=="start-localstack" goto localstack
if /i "%~1"=="check-endpoints" goto endpoints
if /i "%~1"=="check-endpoint-mutations" goto mutations
echo Usage: scripts\dev.cmd build ^| run ^| start-localstack ^| setup-identity ^| check-login ^| setup-store ^| check-accounts ^| check-endpoints ^| check-endpoint-mutations ^| test-store
exit /b 2
:build
call mvn.cmd -B -ntp "-Dmaven.repo.local=%PROJECT_ROOT%\.local\maven-repository" -f "%PROJECT_ROOT%\backend\pom.xml" verify
exit /b %ERRORLEVEL%
:identity
docker compose --project-directory "%PROJECT_ROOT%" -f "%PROJECT_ROOT%\compose.yaml" up -d keycloak
if errorlevel 1 exit /b %ERRORLEVEL%
node "%PROJECT_ROOT%\scripts\setup-local-identity.mjs"
exit /b %ERRORLEVEL%
:run
node "%PROJECT_ROOT%\scripts\run-api.mjs"
exit /b %ERRORLEVEL%
:login
node "%PROJECT_ROOT%\scripts\check-owner-login.mjs"
exit /b %ERRORLEVEL%
:store
node "%PROJECT_ROOT%\scripts\setup-local-store.mjs"
exit /b %ERRORLEVEL%
:accounts
node "%PROJECT_ROOT%\scripts\check-owner-login.mjs" --accounts
exit /b %ERRORLEVEL%
:teststore
node "%PROJECT_ROOT%\scripts\test-account-store.mjs"
exit /b %ERRORLEVEL%
:localstack
call lstk.cmd start
exit /b %ERRORLEVEL%
:endpoints
node "%PROJECT_ROOT%\scripts\check-owner-login.mjs" --endpoints
exit /b %ERRORLEVEL%
:mutations
node "%PROJECT_ROOT%\scripts\check-owner-login.mjs" --endpoint-mutations
exit /b %ERRORLEVEL%
