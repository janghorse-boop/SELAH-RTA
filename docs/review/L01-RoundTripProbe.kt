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
 println("ROUNDTRIP flags=${o.limitedByMaxCorrection.count{it}}->${decoded.limitedByMaxCorrection.count{it}} maskEqual=${o.correction.valid.contentEquals(decoded.correction.valid)} beforeLimit=${o.unsupportedReasonsKo().any{it.contains("상한")}} afterLimit=${decoded.unsupportedReasonsKo().any{it.contains("상한")}} beforeVerdict=${judgeCalibration(q,o).verdict} afterVerdict=${judgeCalibration(q,decoded).verdict}")
 val tiny=calibrateFromSession(r,q,CalibrationSettings(maxCorrectionDb=0.000001)).getOrThrow()
 println("ZERO_VALID generated=true norm=${tiny.normalizeSupportPoints} valid=${tiny.correction.validCount} final=${judgeCalibration(q,tiny).verdict}")
}
