# SPDX-License-Identifier: GPL-3.0-only

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$toolsDir = Join-Path $repoRoot 'tools'
$destination = Join-Path $toolsDir 'libxposed-api-102.jar'
$expectedHash = 'A515DD7A53CD7A47C05E101DFF77D61ACB3091A97B20A885B9EA3494412DB985'
$archiveHash = '423484A6E1807E7A423C4B88FCD8176D104318259D91791877FED88FE91479D0'
$url = 'https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar'

if ((Test-Path -LiteralPath $destination) -and (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -eq $expectedHash) {
    Write-Output "libxposed API 102 is ready: $destination"
    return
}
New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
$download = Join-Path $toolsDir ('xposed-api-' + [Guid]::NewGuid().ToString('N') + '.download')
$extracted = $download + '.jar'
try {
    Invoke-WebRequest -Uri $url -OutFile $download
    if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $archiveHash) {
        throw 'libxposed API archive checksum mismatch. The dependency was not installed.'
    }
    $archive = [System.IO.Compression.ZipFile]::OpenRead($download)
    try {
        $entry = $archive.GetEntry('classes.jar')
        if ($null -eq $entry) { throw 'libxposed archive does not contain classes.jar.' }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $extracted)
    } finally {
        $archive.Dispose()
    }
    if ((Get-FileHash -LiteralPath $extracted -Algorithm SHA256).Hash -ne $expectedHash) {
        throw 'libxposed API checksum mismatch. The dependency was not installed.'
    }
    Move-Item -LiteralPath $extracted -Destination $destination -Force
} finally {
    if (Test-Path -LiteralPath $download) { Remove-Item -LiteralPath $download }
    if (Test-Path -LiteralPath $extracted) { Remove-Item -LiteralPath $extracted }
}
Write-Output "libxposed API 102 is ready: $destination"
