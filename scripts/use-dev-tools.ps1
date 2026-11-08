# Dot-source from PowerShell: . ./scripts/use-dev-tools.ps1
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = Join-Path $projectRoot '.tools/java/jdk25.0.5_0'
$nodeBin = Join-Path $projectRoot '.tools/node/node-v24.22.2-win-x64'
$mavenBin = Join-Path $projectRoot '.tools/maven/apache-maven-3.9.16/bin'
if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'Project-local Java is missing. See docs/07-initial-setup.md.'
}
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$nodeBin;$mavenBin;$env:Path"
$env:npm_config_cache = Join-Path $projectRoot '.local/npm-cache'
$env:MAVEN_OPTS = "-Dmaven.repo.local=$projectRoot/.local/maven-repository"
Write-Host 'Project development tools activated for this PowerShell session.'
