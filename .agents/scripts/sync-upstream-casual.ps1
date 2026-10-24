# Synchronizes upstream casual git repositories locally under .upstream/
# Enables core.longpaths true to avoid Windows MAX_PATH (260 char) truncation.

param(
    [string]$TargetDir = ".upstream"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $TargetDir)) {
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
}

$repos = @{
    "casual-java"    = "https://github.com/casualcore/casual-java.git"
    "casual-quarkus" = "https://github.com/casualcore/casual-quarkus.git"
}

foreach ($repoName in $repos.Keys | Sort-Object) {
    $repoUrl = $repos[$repoName]
    $dest = Join-Path $TargetDir $repoName

    if (-not (Test-Path (Join-Path $dest ".git"))) {
        Write-Host "[CASUAL-SYNC] Cloning $repoName into $dest..." -ForegroundColor Cyan
        git clone -c core.longpaths=true --filter=blob:none $repoUrl $dest
        git -C $dest config core.longpaths true
    } else {
        Write-Host "[CASUAL-SYNC] Fetching updates for $repoName in $dest..." -ForegroundColor Green
        git -C $dest fetch --tags origin
    }
}

Write-Host "[CASUAL-SYNC] Upstream Casual repositories ready in $TargetDir." -ForegroundColor Green
