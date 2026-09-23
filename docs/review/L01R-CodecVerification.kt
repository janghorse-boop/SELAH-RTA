import kr.joa.selahrta.dsp.*
import kr.joa.selahrta.calibration.*
fun main(){
 val ref=DoubleArray(31){if(it<2||it>28)70.0 else if(it%2==0)100.0 else 40.0}
 val s=CalibrationSession(referenceCalApplied=true)
 repeat(8){s.record(MeasureStep.ReferenceBefore,ref);s.record(MeasureStep.ReferenceAfter,ref);s.record(MeasureStep.Target,DoubleArray(31){70.0})}
 val r=s.result()!!
 val q=qualityFromSession(r,DoubleArray(31){0.0},DoubleArray(31){0.0},referenceCalRangeHz=20.0..20000.0,dspVerifiedBySignal=true)
 val o=calibrateFromSession(r,q).getOrThrow()
 val decoded=decodeCurves(encodeCurves(o)).getOrThrow()
 println("ROUNDTRIP flags=${o.limitedByMaxCorrection!!.count{it}}->${decoded.limitedByMaxCorrection!!.count{it}} maskEqual=${o.correction.valid.contentEquals(decoded.correction.valid)} beforeLimit=${o.unsupportedReasonsKo().any{it.contains("상한")}} afterLimit=${decoded.unsupportedReasonsKo().any{it.contains("상한")}} beforeVerdict=${judgeCalibration(q,o).verdict} afterVerdict=${judgeCalibration(q,decoded).verdict}")
 check(o.limitedByMaxCorrection!!.contentEquals(decoded.limitedByMaxCorrection!!))
 check(o.unsupportedReasonsKo()==decoded.unsupportedReasonsKo())
 val text=encodeCurves(o)
 val old=text.lineSequence().filterNot{it.startsWith("correction.limited=")}.joinToString("\n")
 val legacy=decodeCurves(old).getOrThrow()
 check(legacy.limitedByMaxCorrection==null)
 check(legacy.unsupportedReasonsKo().any{it.contains("정보 없음")})
 check(!encodeCurves(legacy).contains("correction.limited="))
 val falseText=text.lineSequence().map{if(it.startsWith("correction.limited=")) "correction.limited="+"0".repeat(o.correction.size) else it}.joinToString("\n")
 val allFalse=decodeCurves(falseText).getOrThrow()
 check(allFalse.limitedByMaxCorrection!=null && allFalse.limitedByMaxCorrection!!.none{it})
 for(value in listOf("","1","2".repeat(o.correction.size))){
  val bad=text.lineSequence().map{if(it.startsWith("correction.limited=")) "correction.limited=$value" else it}.joinToString("\n")
  check(decodeCurves(bad).isFailure)
 }
 println("CODEC_CHECKS mask_and_reason_preserved=true absent_is_unknown=true false_is_known=true malformed_rejected=3")
 val tiny=calibrateFromSession(r,q,CalibrationSettings(maxCorrectionDb=0.000001)).getOrThrow()
 println("ZERO_VALID generated=true norm=${tiny.normalizeSupportPoints} valid=${tiny.correction.validCount} final=${judgeCalibration(q,tiny).verdict}")
}

