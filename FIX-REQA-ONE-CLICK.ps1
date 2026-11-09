# REQA-SQL-MCP: one-click API embedding startup repair and diagnostics.
# Run from the directory containing docker-compose.yml / compose.yaml.
# Does not edit source code, .env, databases, or other services.
$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$report = Join-Path $root "REQA-Fix-Report-$stamp.txt"
$override = Join-Path $root 'compose.reqa-embedding-fix.yaml'
$composeNames = @('compose.yaml','compose.yml','docker-compose.yaml','docker-compose.yml')
$base = $composeNames | Where-Object { Test-Path (Join-Path $root $_) } | Select-Object -First 1
if (-not $base) {
    Write-Host 'ERROR: Open PowerShell in your REQA project folder (containing compose.yaml or docker-compose.yml).' -ForegroundColor Red
    exit 1
}
$basePath = Join-Path $root $base
Start-Transcript -Path $report -Force | Out-Null
try {
    Write-Host "Project: $root"
    Write-Host "Report: $report"
    Write-Host 'Checking Docker Desktop...'
    docker info --format '{{.ServerVersion}}' 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Docker engine is unavailable. Start Docker Desktop and run again.' }

    # This small override ensures the environment is passed INSIDE the API container.
    # It is kept on disk so future runs can use it too.
    $yaml = @'
services:
  api:
    environment:
      HF_HUB_OFFLINE: "0"
      TRANSFORMERS_OFFLINE: "0"
'@
    if (Test-Path $override) {
        $existing = Get-Content $override -Raw
        if ($existing -ne $yaml -and $existing.Trim() -ne $yaml.Trim()) {
            throw "Existing override file differs: $override. No file was overwritten."
        }
    } else {
        [System.IO.File]::WriteAllText($override, $yaml, (New-Object System.Text.UTF8Encoding($false)))
    }
    $dc = @('compose','-f',$basePath,'-f',$override)
    Write-Host 'Validating effective Compose configuration...'
    & docker @dc config --quiet 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid. See errors above.' }

    Write-Host 'Checking the API image contains FastEmbed...'
    & docker @dc run --rm --no-deps --entrypoint python api -c 'import fastembed; print("fastembed import OK")' 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { Write-Warning 'FastEmbed import test failed; continuing to collect startup diagnostics.' }

    Write-Host 'Restarting only API with online Hugging Face model lookup enabled...'
    & docker @dc up -d --no-deps --force-recreate api 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'API restart failed.' }

    Write-Host 'Waiting up to 4 minutes for API initialization/model download...'
    $healthy = $false
    for ($i=1; $i -le 24; $i++) {
        Start-Sleep -Seconds 10
        try {
            $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/health' -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -eq 200) {
                Write-Host "API /health HTTP 200: $($r.Content)" -ForegroundColor Green
                $healthy = $true
                break
            }
        } catch {
            if (($i % 3) -eq 0) { Write-Host "Still waiting ($($i * 10) seconds): $($_.Exception.Message)" }
        }
    }
    Write-Host 'Final container status:'
    & docker @dc ps api a2a 2>&1 | Out-Host
    Write-Host 'Recent API logs:'
    & docker @dc logs --no-color --tail 160 api 2>&1 | Out-Host
    Write-Host 'Recent A2A logs:'
    & docker @dc logs --no-color --tail 60 a2a 2>&1 | Out-Host
    if ($healthy) {
        Write-Host 'RESULT: API /health responded successfully. Test the Streamlit UI next.' -ForegroundColor Green
    } else {
        Write-Warning 'RESULT: API health is not confirmed. Please share the report; do not repeat downloads or rebuilds blindly.'
    }
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    try { docker compose ps api a2a 2>&1 | Out-Host } catch {}
} finally {
    Stop-Transcript | Out-Null
    Write-Host "Diagnostic report saved: $report" -ForegroundColor Cyan
    Write-Host 'Review the report for private information before sharing.'
}
