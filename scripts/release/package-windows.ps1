[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$GradleBat,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$PackagingJavaHome,
    [string]$WixDir = 'build/wix311',
    [string]$RuntimeImage = 'build/verification-captcha/runtime',
    [string]$ReadOnlyDependencyCache
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Set-Location -LiteralPath $projectRoot
$version = (Select-String -LiteralPath 'gradle.properties' -Pattern '^app.version=(\d+\.\d+\.\d+)$').Matches.Groups[1].Value
if (!$version) { throw 'app.version must contain a Windows-compatible three-part version' }
$releaseDir = Join-Path $projectRoot "build/release/$version"
$installerDir = Join-Path $releaseDir 'installer'
$installerPath = Join-Path $installerDir "CCTRCloudExportDesktop-$version.exe"
if (Test-Path -LiteralPath $installerPath) { throw "Installer already exists: $installerPath" }

function Get-WorkspaceDirectory([string]$Path) {
    if (![IO.Path]::IsPathRooted($Path)) { $resolved = [IO.Path]::GetFullPath((Join-Path $projectRoot $Path)) }
    else { $resolved = [IO.Path]::GetFullPath($Path) }
    if (!$resolved.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Packaging input must stay inside the workspace: $resolved"
    }
    return $resolved
}
$wixPath = Get-WorkspaceDirectory $WixDir
$runtimePath = Get-WorkspaceDirectory $RuntimeImage
foreach ($required in @($GradleBat, (Join-Path $JavaHome 'bin/java.exe'), (Join-Path $PackagingJavaHome 'bin/jpackage.exe'), (Join-Path $wixPath 'candle.exe'), (Join-Path $wixPath 'light.exe'), (Join-Path $runtimePath 'release'))) {
    if (!(Test-Path -LiteralPath $required)) { throw "Required local tool or runtime not found: $required" }
}
$tempDir = Join-Path $releaseDir 'tmp'
$toolHome = Join-Path $releaseDir 'tool-home'
New-Item -ItemType Directory -Force -Path $installerDir, $tempDir, $toolHome | Out-Null
$env:JAVA_HOME = $JavaHome
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle/agent-home'
if ($ReadOnlyDependencyCache) { $env:GRADLE_RO_DEP_CACHE = $ReadOnlyDependencyCache }
$env:TEMP = $tempDir
$env:TMP = $tempDir
$gradleArgs = @(
    '--no-daemon', '--console=plain',
    '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=GBK',
    "-Duser.home=$toolHome", "-Djava.io.tmpdir=$tempDir",
    "-Porg.gradle.java.installations.paths=$JavaHome,$PackagingJavaHome",
    '-Porg.gradle.java.installations.auto-download=false',
    '-Pkotlin.compiler.execution.strategy=in-process',
    '--init-script', 'scripts/release/windows-local.init.gradle',
    ':modules:cctr-api:test',
    '--tests', 'top.kagg886.cctr.api.CaptchaLoginTest',
    '--tests', 'top.kagg886.cctr.api.PasswordEncryptionTest',
    'compileKotlin', 'copyWindowsReleaseRuntime'
)
& $GradleBat @gradleArgs 2>&1 | Tee-Object -FilePath (Join-Path $releaseDir 'gradle.log')
if ($LASTEXITCODE -ne 0) { throw "Build or tests failed: $LASTEXITCODE" }

# Pass arguments directly: Compose 1.6's jpackage argument file fails on this
# Windows checkout's non-ASCII path. Existing WiX and runtime need no downloads.
$env:PATH = "$wixPath;$env:PATH"
$inputDir = Join-Path $releaseDir 'input'
$jpackageArgs = @(
    "-J-Duser.home=$toolHome", "-J-Djava.io.tmpdir=$tempDir",
    '--type', 'exe', '--input', $inputDir, '--dest', $installerDir,
    '--name', 'CCTRCloudExportDesktop', '--app-version', $version,
    '--main-jar', "CCTRCloudExportDesktop-$version.jar",
    '--main-class', 'top.kagg886.cctr.desktop.MainKt',
    '--runtime-image', $runtimePath, '--temp', (Join-Path $releaseDir 'jpackage-temp'),
    '--win-shortcut', '--win-menu', '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-Dcompose.application.configure.swing.globals=true'
)
& (Join-Path $PackagingJavaHome 'bin/jpackage.exe') @jpackageArgs 2>&1 | Tee-Object -FilePath (Join-Path $releaseDir 'jpackage.log')
if ($LASTEXITCODE -ne 0) { throw "Installer packaging failed: $LASTEXITCODE" }
if (!(Test-Path -LiteralPath $installerPath)) { throw 'jpackage did not produce the expected EXE installer' }
Get-FileHash -LiteralPath $installerPath -Algorithm SHA256
