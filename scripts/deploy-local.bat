@echo off
REM Flash Sale System - Local Deployment Script (Windows)
REM This script helps you quickly deploy the Flash Sale system locally using Docker Compose

setlocal enabledelayedexpansion

echo =====================================
echo Flash Sale System - Local Deployment
echo =====================================
echo.

REM Check if Docker is installed
docker --version >nul 2>&1
if errorlevel 1 (
    echo Error: Docker is not installed. Please install Docker Desktop first.
    exit /b 1
)

REM Check if Docker Compose is installed
docker-compose --version >nul 2>&1
if errorlevel 1 (
    echo Error: Docker Compose is not installed. Please install Docker Desktop with Compose.
    exit /b 1
)

REM Check if Maven is installed
mvn --version >nul 2>&1
if errorlevel 1 (
    echo Error: Maven is not installed. Please install Maven 3.9+ first.
    exit /b 1
)

echo Step 1: Creating .env file...
if not exist .env (
    (
        echo # Database Configuration
        echo DB_PASSWORD=flashsale_pass_local
        echo.
        echo # Redis Configuration
        echo REDIS_PASSWORD=redis_pass_local
        echo.
        echo # AWS Configuration ^(for local testing^)
        echo AWS_ACCESS_KEY_ID=test
        echo AWS_SECRET_ACCESS_KEY=test
    ) > .env
    echo [OK] .env file created
) else (
    echo [OK] .env file already exists
)

echo.
echo Step 2: Building application JAR...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo Error: Maven build failed
    exit /b 1
)
echo [OK] Application built successfully

echo.
echo Step 3: Starting Docker containers...
docker-compose up -d
if errorlevel 1 (
    echo Error: Failed to start Docker containers
    exit /b 1
)
echo [OK] Docker containers started

echo.
echo Step 4: Waiting for services to be ready...
echo This may take 1-2 minutes...

REM Wait for Application
echo Waiting for Application to start...
set /a counter=0
:wait_app
set /a counter+=1
curl -s http://localhost:8080/actuator/health >nul 2>&1
if errorlevel 1 (
    if !counter! lss 60 (
        timeout /t 2 /nobreak >nul
        goto wait_app
    ) else (
        echo Warning: Application health check timed out
        echo Please check logs: docker-compose logs -f app
    )
) else (
    echo [OK] Application is ready
)

echo.
echo =====================================
echo   Deployment Successful!
echo =====================================
echo.
echo Services are now running:
echo.
echo   Application:     http://localhost:8080
echo   Health Check:    http://localhost:8080/actuator/health
echo   Kafka UI:        http://localhost:8081
echo   PostgreSQL:      localhost:5432
echo   Redis:           localhost:6379
echo   Kafka:           localhost:9093
echo.
echo Useful commands:
echo   View logs:       docker-compose logs -f app
echo   Stop services:   docker-compose down
echo   Restart:         docker-compose restart app
echo.
echo Note: Check DEPLOYMENT.md for more detailed instructions
echo.

endlocal
