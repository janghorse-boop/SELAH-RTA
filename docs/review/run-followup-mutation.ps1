param(
    [string]$Repository = 'D:\Cowork\SELAH-RTA',
    [string]$Java = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe',
    [string]$DependencyCache = 'C:\Users\jangh\.gradle\caches\modules-2\files-2.1'
)
# DSP + app JVM unit tests and Controller probe. No production/global settings changed.
# Expected exit code: 1; the same-settings MAX test must fail under the deliberate always-restart mutation.
$ErrorActionPreference = 'Stop'
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('selah-independent-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
$archive = Join-Path $scratch 'e7c55e5.zip'
$snapshot = Join-Path $scratch 'source'
$classes = Join-Path $scratch 'classes'
$repoPath = (Resolve-Path -LiteralPath $Repository).Path.Replace('\','/')
& git -c "safe.directory=$repoPath" -C $Repository archive e7c55e5 -o $archive
if ($LASTEXITCODE -ne 0) { throw 'Could not archive review target e7c55e5' }
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
# Use original AAR classes.jar: transformed runtime jars may omit Kotlin metadata.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$aarPaths = @('androidx.core\core\1.15.0','androidx.lifecycle\lifecycle-viewmodel-android\2.8.7','androidx.datastore\datastore-android\1.1.1','androidx.datastore\datastore-core-android\1.1.1','androidx.datastore\datastore-preferences-android\1.1.1')
$idx = 0
foreach ($rel in $aarPaths) {
    $aar = Get-ChildItem (Join-Path $DependencyCache $rel) -Recurse -Filter '*.aar' | Select-Object -First 1
    $dest = Join-Path $scratch ('aar' + $idx++)
    [IO.Compression.ZipFile]::ExtractToDirectory($aar.FullName, $dest)
    $jars += Join-Path $dest 'classes.jar'
}
$jars += Find-Jar 'androidx.datastore\datastore-preferences-core-jvm\1.1.1'
$jars += Find-Jar 'androidx.lifecycle\lifecycle-common-jvm\2.8.7'
$jars += 'C:\Users\jangh\.gradle\caches\8.14.3\transforms\958c64a85cbb3cb0861135a327d8a04d\transformed\android.jar'
$cp = $jars -join ';'
$files = @(Get-ChildItem "$snapshot\dsp\src" -Recurse -Filter '*.kt' | ForEach-Object FullName)
$mainApp = Join-Path $snapshot 'app\src\main\java\kr\joa\selahrta'
foreach ($dir in @('audio','domain','calibration','settings')) { $files += @(Get-ChildItem (Join-Path $mainApp $dir) -Filter '*.kt' | ForEach-Object FullName) }
$files += Join-Path $mainApp 'ui\CaptureViewModel.kt'
$files += Join-Path $mainApp 'ui\CaptureController.kt'
$files += @(Get-ChildItem (Join-Path $snapshot 'app\src\test') -Recurse -Filter '*.kt' | ForEach-Object FullName)

$files += Join-Path $PSScriptRoot 'FollowupProbe.kt'
$target = Join-Path $mainApp 'ui\CaptureController.kt'
$original = [IO.File]::ReadAllText($target)
$changed = $original.Replace('old.timeWeight != new.timeWeight || old.leqWindow != new.leqWindow', 'true')
if ($original -eq $changed) { throw 'Mutation target missing' }
[IO.File]::WriteAllText($target, $changed)
& $Java -cp $cp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $cp -d $classes @files
if ($LASTEXITCODE -ne 0) { throw 'Verification compilation failed' }
$tests = @(Get-ChildItem "$snapshot\dsp\src\test" -Recurse -Filter '*Test.kt' | ForEach-Object { 'kr.joa.selahrta.dsp.' + $_.BaseName })
$tests += @(Get-ChildItem (Join-Path $snapshot 'app\src\test') -Recurse -Filter '*Test.kt' | ForEach-Object { if ($_.Directory.Name -eq 'ui') { 'kr.joa.selahrta.ui.' + $_.BaseName } else { 'kr.joa.selahrta.audio.' + $_.BaseName } })
& $Java -cp "$classes;$cp" org.junit.runner.JUnitCore kr.joa.selahrta.ui.CaptureControllerOrderingTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output "Independent verification scratch directory: $scratch"
& $Java -cp "$classes;$cp" kr.joa.selahrta.ui.FollowupProbeKt
exit $LASTEXITCODE












