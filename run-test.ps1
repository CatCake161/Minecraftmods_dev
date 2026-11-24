# Launch one Minecraft client instance for in-game testing.
#
# Usage:
#   .\run-test.ps1                # CUDA audio on (default)
#   .\run-test.ps1 -NoCuda        # force the CPU audio path
#   .\run-test.ps1 -CudaRT        # CUDA audio + CUDA ray-traced order-3/4 tail
#   .\run-test.ps1 -GlFinish      # CUDA audio + NVIDIA glFinish diagnostics
#   .\run-test.ps1 --info
#
# Extra arguments are passed to the Gradle runClient task.

param(
    [switch]$NoCuda,
    [switch]$CudaRT,
    [switch]$GlFinish,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# Force UTF-8 for this console, PowerShell output and child processes.
try {
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    [Console]::InputEncoding = $utf8
    [Console]::OutputEncoding = $utf8
    $OutputEncoding = $utf8
    & chcp.com 65001 | Out-Null
} catch {
    Write-Warning "Could not switch console to UTF-8: $($_.Exception.Message)"
}

$preferredJavaHome = 'C:\Users\Whuch\AppData\Local\Programs\jdk-25'
if (Test-Path -LiteralPath (Join-Path $preferredJavaHome 'bin\java.exe')) {
    $env:JAVA_HOME = $preferredJavaHome
}
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'JAVA_HOME is not set and java is not on PATH. Install JDK 21+ or set JAVA_HOME.'
}

# CUDA audio is the default. Keep any unrelated JAVA_TOOL_OPTIONS the user
# already set, but replace our own toggles so -NoCuda/-GlFinish are authoritative.
$existingOptions = @()
if ($env:JAVA_TOOL_OPTIONS) {
    $existingOptions = $env:JAVA_TOOL_OPTIONS -split '\s+' |
        Where-Object { $_ -and $_ -notmatch '^-Dcinema\.(audio\.cuda|audio\.cudaRT|video\.glFinish)=' }
}
$cinemaOptions = @()
if (-not $NoCuda) {
    $cinemaOptions += '-Dcinema.audio.cuda=true'
}
if ($CudaRT) {
    $cinemaOptions += '-Dcinema.audio.cudaRT=true'
}
if ($GlFinish) {
    $cinemaOptions += '-Dcinema.video.glFinish=true'
}
$allOptions = @($cinemaOptions + $existingOptions | Where-Object { $_ })
if ($allOptions.Count -gt 0) {
    $env:JAVA_TOOL_OPTIONS = ($allOptions -join ' ')
} else {
    Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
}

if ($NoCuda) {
    Write-Host 'CUDA audio disabled; using the CPU FIR path.'
} else {
    Write-Host 'CUDA audio enabled by default (use -NoCuda for the CPU path).'
}
if ($GlFinish) {
    Write-Host 'NVIDIA glFinish diagnostics enabled for this client.'
}
if ($CudaRT) {
    Write-Host 'CUDA ray-traced order-3/4 late tail enabled.'
}

$gradlew = Join-Path $PSScriptRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) {
    throw "gradlew.bat not found: $gradlew"
}

Write-Host 'Launching Minecraft test instance...'
& $gradlew runClient --console=plain @GradleArgs
exit $LASTEXITCODE
