# REQA A2A compatibility test - no running containers are changed.
$ErrorActionPreference = 'Stop'
$root = (Get-Location).Path
$report = Join-Path $root ('REQA-A2A-Test-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
Start-Transcript -Path $report | Out-Null
try {
    Write-Host 'REQA A2A compatibility test (non-destructive)' -ForegroundColor Cyan
    if (-not (Test-Path 'compose.yaml') -and -not (Test-Path 'docker-compose.yml') -and -not (Test-Path 'docker-compose.yaml')) { throw 'Run this script from the project folder containing Docker Compose.' }
    if (-not (Test-Path 'requirements.txt')) { throw 'requirements.txt not found.' }
    $original = [System.IO.File]::ReadAllText((Join-Path $root 'requirements.txt'))
    $backup = Join-Path $root ('requirements.txt.backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    Copy-Item 'requirements.txt' $backup
    Write-Host "Requirements backup: $backup"
    if ($original -notmatch '(?m)^\s*a2a-sdk\s*==\s*1\.3\.0\s*$') { throw 'Expected a2a-sdk==1.3.0 was not found. No changes made.' }
    $changed = $false
    try {
        $updated = [regex]::Replace($original, '(?m)^(\s*a2a-sdk\s*==\s*)1\.3\.0(\s*)$', '${1}0.3.22${2}')
        [System.IO.File]::WriteAllText((Join-Path $root 'requirements.txt'), $updated)
        $changed = $true
        Write-Host 'Temporarily testing a2a-sdk==0.3.22 in API image.'
        & docker compose build api
        if ($LASTEXITCODE -ne 0) { throw 'Docker API image build failed.' }
        Write-Host 'Testing imports in isolated, one-off API container.'
        & docker compose run --rm --no-deps --entrypoint python api -c "from a2a.server.apps import A2AStarletteApplication; from a2a.server.request_handlers import DefaultRequestHandler; from a2a.server.tasks import InMemoryTaskStore; from a2a.client import A2ACardResolver, A2AClient, ClientConfig, ClientFactory; from google.adk.a2a.executor.a2a_agent_executor import A2aAgentExecutor, A2aAgentExecutorConfig; print('A2A/ADK imports: PASS')"
        if ($LASTEXITCODE -ne 0) { throw 'A2A/ADK imports failed. Do not deploy.' }
        Write-Host 'Checking application server module import.'
        & docker compose run --rm --no-deps --entrypoint python api -c "import a2a_common.a2a_base_agent_setup; import a2a_common.a2a_base_client_setup; print('Project A2A modules: PASS')"
        if ($LASTEXITCODE -ne 0) { throw 'Project module imports failed. Do not deploy.' }
        Write-Host 'PASS: Test imports succeeded. No running containers were replaced.' -ForegroundColor Green
        Write-Host 'Note: This does not guarantee runtime compatibility. Review report before deployment.'
    } finally {
        if ($changed) { [System.IO.File]::WriteAllText((Join-Path $root 'requirements.txt'), $original); Write-Host 'Original requirements.txt restored.' }
    }
} catch {
    Write-Host ('TEST FAILED: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host 'No running services were restarted or replaced.'
} finally {
    Stop-Transcript | Out-Null
    Write-Host "Report saved: $report"
}
