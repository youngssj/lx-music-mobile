param([ValidateSet('Debug', 'Release')][string]$Variant = 'Release')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
# Gradle's properties do not control the separate JVMs started by prefab/CMake.
$env:JAVA_TOOL_OPTIONS = "$taskPreviousJavaOptions -Xms64m -Xmx512m -XX:ActiveProcessorCount=2".Trim()
Push-Location (Join-Path $taskRoot 'android')
try {
    & ./gradlew.bat "assemble$Variant" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Android $Variant build failed (exit $LASTEXITCODE)." }
} finally {
    Pop-Location
    $env:JAVA_TOOL_OPTIONS = $taskPreviousJavaOptions
}
