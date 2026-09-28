# Start Lead Management SaaS Application
# This script starts the Spring Boot application and waits for it to be ready

Write-Host "=== Starting Lead Management SaaS Application ===" -ForegroundColor Cyan

# Check if PostgreSQL and Redis are running
Write-Host "`nChecking Docker containers..." -ForegroundColor Yellow
$containers = docker ps --format "table {{.Names}}\t{{.Status}}" | Select-String -Pattern "leadmgmt"
if ($containers) {
    Write-Host $containers -ForegroundColor Green
} else {
    Write-Host "WARNING: Docker containers not found. Starting them now..." -ForegroundColor Red
    docker-compose up -d
    Start-Sleep -Seconds 5
}

# Kill any existing Java process
Write-Host "`nStopping any existing Java processes..." -ForegroundColor Yellow
Stop-Process -Name java -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# Build the application
Write-Host "`nBuilding application..." -ForegroundColor Yellow
./mvnw clean package -DskipTests

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed! Check the error messages above." -ForegroundColor Red
    exit 1
}

# Start the application
Write-Host "`nStarting application..." -ForegroundColor Yellow
$appProcess = Start-Process -FilePath "java" -ArgumentList "-jar","target/lead-management-saas-0.0.1-SNAPSHOT.jar" -PassThru -NoNewWindow

Write-Host "Application starting (PID: $($appProcess.Id))..." -ForegroundColor Green

# Wait for application to be ready (check health endpoint)
Write-Host "`nWaiting for application to start (checking health endpoint)..." -ForegroundColor Yellow
$maxAttempts = 30
$attempt = 0
$started = $false

while ($attempt -lt $maxAttempts -and -not $started) {
    $attempt++
    Start-Sleep -Seconds 2

    try {
        $response = Invoke-WebRequest -Uri "http://localhost:8080/actuator/health" -Method Get -TimeoutSec 2 -ErrorAction Stop
        if ($response.StatusCode -eq 200) {
            $started = $true
            Write-Host "`n✓ Application started successfully!" -ForegroundColor Green
            Write-Host "Health check response:" -ForegroundColor Cyan
            Write-Host $response.Content -ForegroundColor White
        }
    } catch {
        Write-Host "." -NoNewline
    }
}

if (-not $started) {
    Write-Host "`n✗ Application failed to start within 60 seconds" -ForegroundColor Red
    Write-Host "Check logs for errors. The process may still be starting..." -ForegroundColor Yellow
    exit 1
}

# Show application info
Write-Host "`n=== Application Ready ===" -ForegroundColor Cyan
Write-Host "Base URL:     http://localhost:8080" -ForegroundColor White
Write-Host "Health:       http://localhost:8080/actuator/health" -ForegroundColor White
Write-Host "API Endpoint: http://localhost:8080/api/v1/leads" -ForegroundColor White
Write-Host "`nUse 'test-api.ps1' to test the lead creation endpoint" -ForegroundColor Yellow
Write-Host "Use Ctrl+C in the terminal to stop the application (or kill the Java process)" -ForegroundColor Yellow

