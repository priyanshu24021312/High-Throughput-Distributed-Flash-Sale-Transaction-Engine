# FLASH PULSE Launcher Script
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "       FLASH PULSE - HIGH-CONCURRENCY ENGINE            " -ForegroundColor White
Write-Host "========================================================" -ForegroundColor Cyan

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location "$scriptDir\FlashSaleProject"

# Free port 8080 if already in use
$conn = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
if ($conn) {
    Write-Host "Releasing port 8080 from process PID $($conn.OwningProcess)..." -ForegroundColor Yellow
    Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
}

Write-Host "Compiling Java sources..." -ForegroundColor Yellow
javac -cp ".;mysql-connector-j-26.7.0.jar" *.java

if ($LASTEXITCODE -eq 0) {
    Write-Host "Starting WebServer on http://localhost:8080..." -ForegroundColor Green
    Start-Process "http://localhost:8080"
    java -cp ".;mysql-connector-j-26.7.0.jar" WebServer 8080
} else {
    Write-Host "Compilation failed!" -ForegroundColor Red
}
