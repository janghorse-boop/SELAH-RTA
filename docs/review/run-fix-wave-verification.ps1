param(
    [string]$Repository = 'D:\Cowork\SELAH-RTA',
    [string]$Java = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe',
    [string]$DependencyCache = 'C:\Users\jangh\.gradle\caches\modules-2\files-2.1'
)
# DSP only. No production files or global Git/Gradle settings are changed.
# Expected exit code on b0695ef: 0. The probe prints residual accuracy limits.
$ErrorActionPreference = 'Stop'
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('selah-independent-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
$archive = Join-Path $scratch 'b0695ef.zip'
$snapshot = Join-Path $scratch 'source'
$classes = Join-Path $scratch 'classes'
$repoPath = (Resolve-Path -LiteralPath $Repository).Path.Replace('\','/')
& git -c "safe.directory=$repoPath" -C $Repository archive b0695ef -o $archive
if ($LASTEXITCODE -ne 0) { throw 'Could not archive review target b0695ef' }
Expand-Archive -LiteralPath $archive -DestinationPath $snapshot
New-Item -ItemType Directory -Path $classes | Out-Null
function Find-Jar([string]$RelativePath) {
    $found = Get-ChildItem (Join-Path $DependencyCache $RelativePath) -Recurse -Filter '*.jar' | Select-Object -First 1
    if ($null -eq $found) { throw "Missing cached dependency: $RelativePath" }
    return $found.FullName
}
$jars = @(
    (Find-Jar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.2.20'),
    (Find-Jar 'org.jetbrains.kotlin\kotlin-stdlib\2.2.20'),
    (Find-Jar 'org.jetbrains.kotlin\kotlin-script-runtime\2.2.20'),
    (Find-Jar 'org.jetbrains.kotlin\kotlin-reflect\2.2.0'),
    (Find-Jar 'org.jetbrains.kotlin\kotlin-daemon-embeddable\2.2.20'),
    (Find-Jar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.8.0'),
    (Find-Jar 'org.jetbrains\annotations\13.0'),
    (Find-Jar 'junit\junit\4.13.2'),
    (Find-Jar 'org.hamcrest\hamcrest-core\1.3')
)
$cp = $jars -join ';'
$files = @(Get-ChildItem "$snapshot\dsp\src" -Recurse -Filter '*.kt' | ForEach-Object FullName)
$files += Join-Path $PSScriptRoot 'FixWaveProbe.kt'
& $Java -cp $cp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $cp -d $classes @files
if ($LASTEXITCODE -ne 0) { throw 'DSP compilation failed' }
$tests = @(Get-ChildItem "$snapshot\dsp\src\test" -Recurse -Filter '*Test.kt' | ForEach-Object { 'kr.joa.selahrta.dsp.' + $_.BaseName })
& $Java -cp "$classes;$cp" org.junit.runner.JUnitCore @tests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output "Independent verification scratch directory: $scratch"
& $Java -cp "$classes;$cp" FixWaveProbeKt
exit $LASTEXITCODE

