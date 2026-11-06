# Launch one Minecraft client instance for in-game testing.
#
# Usage:
#   .\run-test.ps1
#   .\run-test.ps1 -GlFinish
#   .\run-test.ps1 --info
#
# Extra arguments are passed to the Gradle runClient task.

param(
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

if ($GlFinish) {
    $env:JAVA_TOOL_OPTIONS = '-Dcinema.video.glFinish=true'
    Write-Host 'NVIDIA glFinish diagnostics enabled for this client.'
}

$gradlew = Join-Path $PSScriptRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) {
    throw "gradlew.bat not found: $gradlew"
}

Write-Host 'Launching Minecraft test instance...'
& $gradlew runClient --console=plain @GradleArgs
exit $LASTEXITCODE
