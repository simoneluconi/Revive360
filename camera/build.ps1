# Build g360rd for the camera (ARM EABI5, static) and the host (sim), assemble
# camera\out\sd\, verify the ARM binary. Runs the toolchain in Docker.
#
#   powershell -ExecutionPolicy Bypass -File camera\build.ps1          # build
#   powershell -ExecutionPolicy Bypass -File camera\build.ps1 -Smoke   # build + sim smoke test
#   powershell -ExecutionPolicy Bypass -File camera\build.ps1 -Clean
param([switch]$Smoke, [switch]$Clean)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$image = 'g360r-build:1'

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Error 'Docker daemon not reachable: start Docker Desktop and retry.'
    exit 1
}
docker image inspect $image *> $null
if ($LASTEXITCODE -ne 0) {
    docker build -t $image (Join-Path $here 'docker')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$targets = @('all')
if ($Smoke) { $targets = @('all', 'smoke') }
if ($Clean) { $targets = @('clean') }

docker run --rm -v "${here}:/src" -w /src $image make @targets
exit $LASTEXITCODE
