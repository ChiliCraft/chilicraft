$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

git -C $repoRoot submodule update --init --recursive
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
