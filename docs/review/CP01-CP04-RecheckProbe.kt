import kr.joa.selahrta.dsp.*
import kotlin.math.*
fun main() {
 val n=ThirdOctave.BAND_COUNT
 fun flat(x:Double)=DoubleArray(n){x}
 fun session(ref:DoubleArray,target:DoubleArray):SessionResult {
  val s=CalibrationSession(referenceCalApplied=true)
  repeat(8){s.record(MeasureStep.ReferenceBefore,ref);s.record(MeasureStep.ReferenceAfter,ref);s.record(MeasureStep.Target,target)}
  return s.result()!!
 }
 val s=CalibrationSession(referenceCalApplied=true)
 for(step in MeasureStep.entries) {repeat(3){s.record(step,flat(0.0))};repeat(2){s.record(step,flat(50.0))};repeat(3){s.record(step,flat(100.0))}}
 val r=s.result()!!
 val q=qualityFromSession(r,flat(0.0),dspVerifiedBySignal=true)
 println("KEPT_GATE kept=${r.target.keptFrames} total=${r.target.totalFrames} minimum=${r.minFramesPerStep} verdict=${judgeQuality(q).verdict}")
 val r2=session(flat(60.0),flat(50.0))
 val noise=DoubleArray(n){if(ThirdOctave.exactCenter(it) in 250.0..3500.0) 50.0 else 0.0}
 val q2=qualityFromSession(r2,noise,dspVerifiedBySignal=true)
 val o2=calibrateFromSession(r2,q2,20.0..20000.0).getOrThrow()
 println("ZERO_NORMALIZATION verdict=${judgeQuality(q2).verdict} usable=${q2.usableCount}/$n pointsUsed=${o2.internalNormalized.pointsUsed} validCount=${o2.correction.validCount} correction=${o2.correction.db.filterIndexed {i,_ -> o2.correction.valid[i]}.firstOrNull()}")
 val slope=DoubleArray(n){50+4*log2(ThirdOctave.exactCenter(it)/1000.0)}
 val r3=session(slope,slope)
 val q3=qualityFromSession(r3,flat(0.0),dspVerifiedBySignal=true)
 val o3=calibrateFromSession(r3,q3,1000.0..16000.0).getOrThrow()
 println("UNEQUAL_SUPPORT verdict=${judgeQuality(q3).verdict} identicalInputs=true correction=${o3.correction.db.filterIndexed {i,_ -> o3.correction.valid[i]}.firstOrNull()} valid=${o3.correction.validCount}")
}
