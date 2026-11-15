# REQA A2A focused read-only SQL test and diagnostics. Does not modify Docker or source.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$out = Join-Path $HOME 'Downloads\REQA_A2A_FOCUSED_TEST_RESULTS.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
@("Recorded: $(Get-Date -Format o)", 'Container: reqa-a2a', 'Test: SELECT * FROM context_ol_assoc LIMIT 1', '----') | Set-Content -LiteralPath $out -Encoding UTF8
function Report([string]$text) {
    Write-Host $text
    Add-Content -LiteralPath $out -Value $text -Encoding UTF8
}
try {
    Report '[1/3] Verifying the actual Python patch inside Docker...'
    $verify = & docker exec reqa-a2a python -c "from pathlib import Path; p=Path('/app/a2a_nl2sql_agent.py'); s=p.read_text(); print('File:',p); print('Plain SQL support:', '_normalize_a2a_user_text' in s); print('Nonblocking worker:', 'await asyncio.to_thread' in s)" 2>&1
    foreach ($line in $verify) { Report "$line" }
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the running A2A container.' }
    $since = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    Report '[2/3] Sending one read-only JSON-RPC SQL request (60-second limit)...'
    $body = @{
        jsonrpc = '2.0'
        id = [guid]::NewGuid().ToString()
        method = 'message/send'
        params = @{message = @{role = 'user'; messageId = [guid]::NewGuid().ToString(); parts = @(@{kind = 'text'; text = 'SELECT * FROM context_ol_assoc LIMIT 1'})}}
    } | ConvertTo-Json -Depth 12 -Compress
    try {
        $response = Invoke-WebRequest 'http://127.0.0.1:5022/' -Method POST -Body $body -ContentType 'application/json' -TimeoutSec 60 -UseBasicParsing
        Report "HTTP STATUS: $($response.StatusCode)"
        $raw = [string]$response.Content
        if ($raw.Length -gt 3500) { $raw = $raw.Substring(0,3500) + ' ... [truncated]' }
        Report "RESPONSE: $raw"
        try {
            $parsed = $response.Content | ConvertFrom-Json
            if ($parsed.error) { Report "JSON-RPC ERROR: $($parsed.error.message)" }
            if ($parsed.result -and $parsed.result.status) {
                Report "A2A TASK STATE: $($parsed.result.status.state)"
                if ($parsed.result.status.message) {
                    Report "TASK MESSAGE: $($parsed.result.status.message | ConvertTo-Json -Depth 8 -Compress)"
                }
            }
        } catch { Report "Response is not parseable JSON: $($_.Exception.Message)" }
    } catch {
        Report "REQUEST FAILED: $($_.Exception.Message)"
        if ($_.Exception.Response) {
            try {
                $r = $_.Exception.Response
                Report "HTTP ERROR STATUS: $([int]$r.StatusCode)"
                $stream = $r.GetResponseStream()
                if ($stream) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    $errorBody = $reader.ReadToEnd()
                    if ($errorBody.Length -gt 3500) { $errorBody = $errorBody.Substring(0,3500) + ' ... [truncated]' }
                    Report "HTTP ERROR BODY: $errorBody"
                }
            } catch { Report "Could not read error body: $($_.Exception.Message)" }
        }
    }
    Report '[3/3] Capturing only new Docker logs from this test...'
    $rawlogs = @(cmd.exe /d /c "docker logs --since $since --timestamps reqa-a2a 2>&1")
    $warnings = @($rawlogs | Where-Object { $_ -match 'Unsupported upgrade request' }).Count
    Report "Ignored routine upgrade warnings: $warnings"
    $relevant = @($rawlogs | Where-Object { $_ -notmatch 'Unsupported upgrade request' })
    if ($relevant.Count -eq 0) { Report 'No SQL-related log lines captured after test began.' }
    foreach ($line in ($relevant | Select-Object -Last 120)) { Report "$line" }
} catch { Report "DIAGNOSTIC ERROR: $($_.Exception.Message)" }
Report "RESULT FILE: $out"
