# Capture logs during PIN test
$adb = "C:\platform-tools\adb.exe"

Write-Host "Clearing logcat..."
& $adb logcat -c

Write-Host "`n=== READY FOR TEST ==="
Write-Host "1. Open Mirror app"
Write-Host "2. Discover and select receiver" 
Write-Host "3. Enter PIN when prompted"
Write-Host "4. Note when disconnect happens"
Write-Host "`nPress ENTER after the disconnect happens..."
Read-Host

Write-Host "`n=== CAPTURING LOGS ==="
$logOutput = & $adb logcat -d -v time

# Save to file
$logOutput | Out-File -FilePath "pin_test_logs.txt" -Encoding UTF8

# Print relevant logs
Write-Host "`n=== PIN-RELATED LOGS ==="
$logOutput | Select-String -Pattern 'Mirror|ProtocolClient|AwaitingPairing|AwaitingProjection|verify-pin|auth|HandshakeFailed|Failed|Error' | Select-Object -Last 100

Write-Host "`nFull logs saved to: pin_test_logs.txt"
