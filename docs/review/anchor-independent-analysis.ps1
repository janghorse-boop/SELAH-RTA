$ErrorActionPreference='Stop'
$rows=@(Get-Content 'D:\Cowork\SELAH-RTA\docs\spec\2026-09-21-anchor-raw-s23-48k.txt' | ForEach-Object {
 if($_ -match 'fs=(\d+) capFrames=(\d+) tsFrames=(\d+) tsNanos=(\d+) nowNanos=(\d+)') {
 [pscustomobject]@{fs=[long]$Matches[1];cap=[long]$Matches[2];f=[long]$Matches[3];t=[long]$Matches[4];now=[long]$Matches[5]}
 } else {throw 'Parse failed'}
})
function Fit($r) {
 $ox=$r[0].f; $oy=$r[0].t
 $x=@($r|ForEach-Object {[double]($_.f-$ox)}); $y=@($r|ForEach-Object {[double]($_.t-$oy)})
 $mx=($x|Measure-Object -Average).Average; $my=($y|Measure-Object -Average).Average
 $sxx=0.0; $sxy=0.0
 for($i=0;$i -lt $x.Count;$i++){$sxx+=($x[$i]-$mx)*($x[$i]-$mx);$sxy+=($x[$i]-$mx)*($y[$i]-$my)}
 $b=$sxy/$sxx
 [pscustomobject]@{b=$b;a=$my-$b*$mx;ox=$ox;oy=$oy}
}
function Stats($v){ $a=@($v|Sort-Object); $p=($a.Count-1)*0.95; $lo=[int][math]::Floor($p); $hi=[int][math]::Ceiling($p); [pscustomobject]@{n=$a.Count;min=$a[0];median=($a[[int][math]::Floor(($a.Count-1)/2)]+$a[[int][math]::Ceiling(($a.Count-1)/2)])/2;p95=$a[$lo]+($p-$lo)*($a[$hi]-$a[$lo]);max=$a[-1]} }
$fit=Fit $rows
$errors=@();$two=@();$endpoint=@();$steps=@();$pairFs=@()
for($i=1;$i -lt $rows.Count;$i++) {
 $steps+=($rows[$i].cap-$rows[$i-1].cap)
 $pairFs+=1e9*($rows[$i].f-$rows[$i-1].f)/($rows[$i].t-$rows[$i-1].t)
 if($i -lt 2){continue}
 $epSlope=($rows[$i-1].t-$rows[0].t)/[double]($rows[$i-1].f-$rows[0].f)
$endpoint += [math]::Abs(($epSlope*($rows[$i].f-$rows[0].f)-($rows[$i].t-$rows[0].t))/1e6)
$m=Fit $rows[0..($i-1)]
 $errors += [math]::Abs(($m.a+$m.b*($rows[$i].f-$m.ox)-($rows[$i].t-$m.oy))/1e6)
 $m=Fit $rows[($i-2)..($i-1)]
 $two += [math]::Abs(($m.a+$m.b*($rows[$i].f-$m.ox)-($rows[$i].t-$m.oy))/1e6)
}
$pairPpm=@($pairFs|ForEach-Object {($_/48000-1)*1e6})
[pscustomobject]@{pairPpm=Stats $pairPpm;pairOver200=@($pairPpm|Where-Object {[math]::Abs($_)-gt 200}).Count;count=$rows.Count;durationNs=($rows[-1].t-$rows[0].t);fsOLS=1e9/$fit.b;ppm=(1e9/$fit.b/48000-1)*1e6;fsEndpoint=1e9*($rows[-1].f-$rows[0].f)/($rows[-1].t-$rows[0].t);capSteps=@($steps|Sort-Object -Unique);holdoutExpandingEndpoints=Stats $endpoint;holdoutExpandingOLS=Stats $errors;holdoutLastTwo=Stats $two;pairFs=Stats $pairFs;ageMs=Stats @($rows|ForEach-Object {($_.now-$_.t)/1e6});frameOffset=Stats @($rows|ForEach-Object {$_.f-$_.cap})} | ConvertTo-Json -Depth 5


