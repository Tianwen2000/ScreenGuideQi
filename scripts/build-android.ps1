param(
    [ValidateSet('Release', 'Debug')][string]$Variant = 'Release',
    [switch]$Verify
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskCache = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.cache/screenguideqi/toolchains'
$taskOldJava = $env:JAVA_HOME
$taskOldOptions = $env:JAVA_TOOL_OPTIONS
$taskJava = Get-ChildItem -LiteralPath (Join-Path $taskCache 'jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($taskJava) { $env:JAVA_HOME = $taskJava.FullName }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to JDK 17 before building. See docs/setup.md.'
}
Push-Location $taskRoot
try {
    # Limit this build process only; the host JDK has previously crashed in C2.
    $env:JAVA_TOOL_OPTIONS = '-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=256m'
    $taskArgs = @(':app:assemble' + $Variant)
    if ($Verify) { $taskArgs += @(':app:testDebugUnitTest', ':app:lintDebug', ':app:lintRelease') }
    $taskArgs += @('--console=plain', '--no-daemon', '--max-workers=2',
        '-Pkotlin.compiler.execution.strategy=in-process',
        '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=256m')
    & './gradlew.bat' @taskArgs
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    Write-Host "APK: app/build/outputs/apk/$($Variant.ToLower())/app-$($Variant.ToLower()).apk"
} finally {
    Pop-Location
    $env:JAVA_HOME = $taskOldJava
    $env:JAVA_TOOL_OPTIONS = $taskOldOptions
}
