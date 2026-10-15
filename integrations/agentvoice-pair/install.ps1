<#
.SYNOPSIS
  Installer for agentvoice-pair (Windows / PowerShell edition; mirrors install.sh).

.DESCRIPTION
  Copies agentvoice-pair.ps1 into $HOME\.local\bin and writes an agentvoice-pair.cmd
  wrapper next to it, so `agentvoice-pair` works from cmd / PowerShell.
  Runs -SelfTest afterwards. When agentvoice-pair.ps1 sits next to this script
  (installing from a checkout) the local file is used and nothing is downloaded.

  Messages are ASCII on purpose: PowerShell 5.1 decodes BOM-less scripts as the local
  ANSI codepage, and non-ASCII text in an installer is easy to break that way.

.PARAMETER InstallDir
  Target directory. Default: $HOME\.local\bin

.PARAMETER RawBase
  Download base. Default: GitHub raw, main branch.

.EXAMPLE
  iwr -useb https://raw.githubusercontent.com/yuga-hashimoto/openclaw-assistant/main/integrations/agentvoice-pair/install.ps1 | iex

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File install.ps1 -InstallDir D:\tools\bin
#>
[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $HOME '.local\bin'),
    [string]$RawBase = 'https://raw.githubusercontent.com/yuga-hashimoto/openclaw-assistant/main',
    [switch]$SkipSelfTest
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$rawUrl = "$RawBase/integrations/agentvoice-pair/agentvoice-pair.ps1"
$target = Join-Path $InstallDir 'agentvoice-pair.ps1'
$local = if ($PSScriptRoot) { Join-Path $PSScriptRoot 'agentvoice-pair.ps1' } else { $null }

if (-not (Test-Path $InstallDir)) { New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null }

if ($local -and (Test-Path $local)) {
    Copy-Item $local $target -Force
    Write-Host "copied local file: $local"
} else {
    Write-Host "downloading: $rawUrl"
    Invoke-WebRequest -Uri $rawUrl -OutFile $target -UseBasicParsing
}

# Wrapper so `agentvoice-pair` resolves from cmd/PowerShell.
# Args are passed through unchanged (PowerShell style: -Code, -Open, -SelfTest);
# run `agentvoice-pair -h` for the full list.
$wrapper = Join-Path $InstallDir 'agentvoice-pair.cmd'
$wrapperBody = @'
@echo off
rem agentvoice-pair - runs the PowerShell edition next to this file (-h for usage, -SelfTest to verify)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0agentvoice-pair.ps1" %*
'@
Set-Content -Path $wrapper -Value $wrapperBody -Encoding ASCII

if (-not $SkipSelfTest) {
    Write-Host 'self-test (-SelfTest) ...'
    & powershell -NoProfile -ExecutionPolicy Bypass -File $target -SelfTest
    if ($LASTEXITCODE -eq 0) {
        Write-Host 'self-test passed'
    } else {
        Write-Warning "self-test FAILED (exit $LASTEXITCODE). Do not trust the generated PNG - run: powershell -File `"$target`" -SelfTest"
        exit 1
    }
}

Write-Host "Installed: $target"
Write-Host "Installed: $wrapper"
if (($env:PATH -split ';') -notcontains $InstallDir) {
    Write-Host 'If agentvoice-pair is not found, add this to your user PATH and reopen the terminal:'
    Write-Host "  $InstallDir"
}
Write-Host "Run: agentvoice-pair   (or: powershell -ExecutionPolicy Bypass -File `"$target`")"
