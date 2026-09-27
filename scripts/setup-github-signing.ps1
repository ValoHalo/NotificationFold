# SPDX-License-Identifier: GPL-3.0-only
[CmdletBinding()]
param(
    [string]$Repository = 'ValoHalo/NotificationFold',
    [string]$KeyStorePath = (Join-Path $PSScriptRoot '..\signing\notificationfold.p12'),
    [string]$PasswordFile = (Join-Path $PSScriptRoot '..\signing\password.txt')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$gh = (Get-Command gh -ErrorAction Stop).Source
& $gh repo view $Repository --json nameWithOwner --jq .nameWithOwner
if ($LASTEXITCODE -ne 0) { throw 'GitHub authentication failed. Run: gh auth login -h github.com -w' }

function Set-RepositorySecret([string]$name, [string]$value) {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $gh
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.StandardInputEncoding = [System.Text.UTF8Encoding]::new($false)
    foreach ($argument in @('secret', 'set', $name, '--repo', $Repository)) {
        $startInfo.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::Start($startInfo)
    try {
        $process.StandardInput.Write($value)
        $process.StandardInput.Close()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw "Could not set GitHub secret: $name" }
    } finally {
        $process.Dispose()
    }
    Write-Output "Configured Actions secret: $name"
}

$password = [System.IO.File]::ReadAllLines((Resolve-Path -LiteralPath $PasswordFile).Path) | Select-Object -First 1
if ([string]::IsNullOrEmpty($password)) { throw 'The password file must contain the keystore password on its first line.' }
$encodedKey = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $KeyStorePath).Path))
try {
    Set-RepositorySecret 'NOTIFICATIONFOLD_KEYSTORE_BASE64' $encodedKey
    Set-RepositorySecret 'NOTIFICATIONFOLD_STORE_PASSWORD' $password
} finally {
    $encodedKey = $null
    $password = $null
}
