# REQA-SQL-MCP diagnostics - read-only, Windows PowerShell 5.1 compatible
param([string]$ProjectPath = (Get-Location).Path)
$ErrorActionPreference = 'Continue'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$report = Join-Path $ProjectPath "REQA-Diagnostics-$stamp.txt"
function Section($name) { "`r`n===== $name =====" | Out-File -FilePath $report -Append -Encoding UTF8 }
function Run($name, [scriptblock]$command) {
    Section $name
    try { (& $command 2>&1 | Out-String -Width 240) | Out-File -FilePath $report -Append -Encoding UTF8 }
    catch { $_.Exception.Message | Out-File -FilePath $report -Append -Encoding UTF8 }
}
"REQA-SQL-MCP diagnostic report: $(Get-Date)" | Out-File -FilePath $report -Encoding UTF8
"Project path: $ProjectPath" | Out-File -FilePath $report -Append -Encoding UTF8
Run 'Docker version' { docker version }
Run 'Compose services' { docker compose config --services }
Run 'Compose container status' { docker compose ps -a }
Run 'API recent logs (last 120 lines)' { docker compose logs --no-color --tail=120 api }
Run 'APP recent logs (last 80 lines)' { docker compose logs --no-color --tail=80 app }
Run 'A2A recent logs (last 80 lines)' { docker compose logs --no-color --tail=80 a2a }
Run 'Local API TCP connection' { Test-NetConnection 127.0.0.1 -Port 8080 -InformationLevel Detailed }
Run 'Local API HTTP health' {
    try { $r=Invoke-WebRequest -Uri 'http://127.0.0.1:8080/health' -UseBasicParsing -TimeoutSec 8; "HTTP $($r.StatusCode)"; $r.Content }
    catch { "HTTP request failed: $($_.Exception.Message)" }
}
Run 'Local APP TCP connection' { Test-NetConnection 127.0.0.1 -Port 8501 -InformationLevel Detailed }
Run 'A2A SDK dependency references' {
    Get-ChildItem -Path $ProjectPath -Recurse -File -Include requirements*.txt,pyproject.toml -ErrorAction SilentlyContinue |
      Select-String -Pattern 'a2a-sdk|a2a_sdk|a2a==' | Select-Object Path,LineNumber,Line
}
Run 'A2A import references' {
    Get-ChildItem -Path $ProjectPath -Recurse -File -Filter '*.py' -ErrorAction SilentlyContinue |
      Where-Object { $_.FullName -notmatch '[\\/](\.venv|venv|node_modules|\.git)[\\/]' } |
      Select-String -Pattern 'a2a\.server\.apps|A2AStarletteApplication|\bresp\b' |
      Select-Object -First 70 Path,LineNumber,Line
}
"`r`nReport saved: $report" | Tee-Object -FilePath $report -Append
Write-Host "Review report for passwords, tokens, URLs or private data before sharing." -ForegroundColor Yellow
