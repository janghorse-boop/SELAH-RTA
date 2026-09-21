# Design-contract counterexamples; not production implementation tests.
$ErrorActionPreference='Stop'
$log=@()
# One reservation, then bounded queue offer fails; close still counts it.
$nextSeq=0L
$acceptedSeq=$nextSeq; $nextSeq++
$publishedPackets=0L
$closedCount=$nextSeq; $nextSeq=-($nextSeq+1)
if($closedCount -le $publishedPackets){throw 'Reservation counterexample missing'}
$log += "ADMISSION: reserved=$acceptedSeq closeCount=$closedCount consumed=$publishedPackets; equality cannot be reached without a terminal drop outcome."
# 1024-frame block crossing first 500ms boundary at 48 kHz.
$first=23*1024; $end=$first+1024; $boundary=48000/2
if(-not ($first -lt $boundary -and $end -gt $boundary)){throw 'Expected straddling block'}
$log += "ROW: block=[$first,$end) boundary=$boundary; impulse at 23999 and impulse at 24000 have identical block peak, but belong to different rows."
# A single epoch cannot interpret a mixed-epoch maximum.
$oldRaw=-20; $oldOffset=100; $newRaw=-30; $newOffset=120
$rawMax=[math]::Max($oldRaw,$newRaw)
$trueMax=[math]::Max($oldRaw+$oldOffset,$newRaw+$newOffset)
$log += "EPOCH: trueMax=$trueMax; rawMaxWithOldEpoch=$($rawMax+$oldOffset); rawMaxWithNewEpoch=$($rawMax+$newOffset)."
foreach($v in @(0.5,-0.5,2.5,-2.5)){
  $even=[math]::Round($v,0,[MidpointRounding]::ToEven)
  $away=[math]::Round($v,0,[MidpointRounding]::AwayFromZero)
  $log += "ROUND: scaled=$v tiesEven=$even halfAway=$away"
}
$drift=7200*50/1000000
if([math]::Abs($drift-0.36) -gt 1e-10){throw 'Unexpected drift'}
$log += "DRIFT: 7200 seconds * 50ppm = $drift seconds."
$log += 'Arithmetic/state models only. Kotlin round semantics separately checked in official Kotlin documentation.'
$log | Set-Content -Encoding utf8 -LiteralPath "$PSScriptRoot\recording-supplement-2-counterexamples.txt"
$log
