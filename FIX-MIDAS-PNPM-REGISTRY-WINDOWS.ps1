# MIDAS/Calian Option B - targeted pnpm lockfile registry repair.
# Does NOT remove any integrity hashes, change versions, or disable pnpm security verification.
# Run from the MIDAS midas-frontend directory, or pass -MidasPath explicitly.
param([string]$MidasPath = (Get-Location).Path)

$ErrorActionPreference = 'Stop'
$registry = 'https://artifactory.ci.simfront.com/artifactory/api/npm/sf-npm-group/'

try {
  $project = (Resolve-Path -LiteralPath $MidasPath).Path
  $manifest = Join-Path $project 'package.json'
  $lockfile = Join-Path $project 'pnpm-lock.yaml'
  $npmrc = Join-Path $project '.npmrc'
  if (-not (Test-Path -LiteralPath $manifest -PathType Leaf) -or
      -not (Test-Path -LiteralPath $lockfile -PathType Leaf)) {
    throw "Not the MIDAS frontend: package.json or pnpm-lock.yaml missing from: $project"
  }

  Write-Host "MIDAS frontend: $project" -ForegroundColor Cyan
  $manifestData = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
  if ($manifestData.name -ne 'midas-app') {
    throw "Unexpected package name '$($manifestData.name)'. Not modifying the lockfile."
  }

  $utf8 = New-Object System.Text.UTF8Encoding($false)
  $raw = [System.IO.File]::ReadAllText($lockfile)
  $tarballPattern = ', tarball: https://artifactory\.ci\.simfront\.com/artifactory/api/npm/npmjs-org/[^}\r\n]+'
  $matches = [regex]::Matches($raw, $tarballPattern)
  $count = $matches.Count
  if ($count -ne 12 -and $count -ne 0) {
    throw "Unexpected number of legacy npmjs-org URLs: $count (expected 12 or 0). Stopping for review."
  }

  if ($count -eq 12) {
    # Remove ONLY 12 stale explicit tarball URL fields; leave SHA-512 integrity intact.
    # pnpm will construct the URL from the configured registry and verify downloaded bytes.
    $clean = [regex]::Replace($raw, $tarballPattern, '')
    $oldIntegrityCount = ([regex]::Matches($raw, 'integrity: sha512-')).Count
    $newIntegrityCount = ([regex]::Matches($clean, 'integrity: sha512-')).Count
    if ($oldIntegrityCount -ne $newIntegrityCount) { throw 'Integrity count changed unexpectedly; aborting.' }
    $backup = "$lockfile.backup-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Copy-Item -LiteralPath $lockfile -Destination $backup -ErrorAction Stop
    [System.IO.File]::WriteAllText($lockfile, $clean, $utf8)
    Write-Host "Repaired $count legacy tarball URLs. SHA-512 integrity hashes retained." -ForegroundColor Green
    Write-Host "Lockfile backup: $backup"
  } else {
    Write-Host 'No legacy npmjs-org URLs found; lockfile was already repaired.' -ForegroundColor Yellow
  }

  # Configure Artifactory ONLY for this MIDAS project; do not change user's global registry.
  # Do not touch tokens, passwords, or any existing user-level .npmrc.
  if (Test-Path -LiteralPath $npmrc) {
    $existing = [System.IO.File]::ReadAllText($npmrc)
    $otherRegistry = [regex]::Matches($existing, '(?m)^\s*(?:registry|@simfront:registry)\s*=\s*[^\r\n#]+')
    foreach ($setting in $otherRegistry) {
      if ($setting.Value -notmatch [regex]::Escape($registry.TrimEnd('/'))) {
        throw "Project .npmrc already declares a different registry; review it manually: $npmrc"
      }
    }
    if ($otherRegistry.Count -lt 2) {
      $supplement = "`r`nregistry=$registry`r`n@simfront:registry=$registry`r`n"
      [System.IO.File]::AppendAllText($npmrc, $supplement, $utf8)
      Write-Host 'Added local registry settings to existing .npmrc (no credentials changed).'
    }
  } else {
    [System.IO.File]::WriteAllText($npmrc, "registry=$registry`r`n@simfront:registry=$registry`r`n", $utf8)
    Write-Host 'Created project-only .npmrc for approved Simfront Artifactory.' -ForegroundColor Green
  }

  Push-Location -LiteralPath $project
  try {
    if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) {
      throw 'pnpm executable is not available on PATH.'
    }
    Write-Host "Registry: $(pnpm config get registry)"
    Write-Host 'Running pnpm install --frozen-lockfile ...' -ForegroundColor Cyan
    & pnpm install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) {
      throw "pnpm failed with exit code $LASTEXITCODE. See its error above (private package access or another issue may remain)."
    }
    Write-Host 'MIDAS dependency installation succeeded. Run START-MIDAS-CALIAN-DEV-WINDOWS.bat.' -ForegroundColor Green
  } finally { Pop-Location }
} catch {
  Write-Host "FAILED: $($_.Exception.Message)" -ForegroundColor Red
  exit 1
}
