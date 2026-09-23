import kr.joa.selahrta.dsp.*
fun main() {
 val n=ThirdOctave.BAND_COUNT
 fun flat(x:Double)=DoubleArray(n){x}
 val s=CalibrationSession(referenceCalApplied=true)
 repeat(8){for(step in MeasureStep.entries)s.record(step,flat(70.0))}
 val r=s.result()!!
 val targetNoise=DoubleArray(n){if(it<=18 || it==30) 40.0 else 70.0}
 val refNoise=DoubleArray(n){if(it>=12)40.0 else 70.0}
 val q=qualityFromSession(r,targetNoise,referenceNoiseDb=refNoise,dspVerifiedBySignal=true)
 val judged=judgeQuality(q)
 val out=calibrateFromSession(r,q,20.0..20000.0).getOrThrow()
 println("INTERSECTION target=${q.usableCount} reference=${q.referenceUsable.count{it}} common=${q.bothUsable.count{it}} total=$n verdict=${judged.verdict} auto=${judged.mayAutoApply} correctionValid=${out.correction.validCount} normPoints=${out.normalizeSupportPoints}")
 val all=qualityFromSession(r,flat(40.0),referenceNoiseDb=flat(40.0),dspVerifiedBySignal=true)
 val narrow=calibrateFromSession(r,all,1000.0..1300.0).getOrThrow()
 println("NARROW_CAL verdict=${judgeQuality(all).verdict} auto=${judgeQuality(all).mayAutoApply} correctionValid=${narrow.correction.validCount} normPoints=${narrow.normalizeSupportPoints} bands=${narrow.normalizeBandsUsed}")
}
