import kr.joa.selahrta.dsp.*
import kr.joa.selahrta.calibration.*
import kotlin.math.*
fun main() {
 val n=ThirdOctave.BAND_COUNT
 fun flat(x:Double)=DoubleArray(n){x}
 val s=CalibrationSession()
 for(step in listOf(MeasureStep.ReferenceBefore,MeasureStep.ReferenceAfter)) { s.record(step,flat(40.0));s.record(step,flat(90.0)) }
 repeat(4){s.record(MeasureStep.Target,flat(60.0))}
 val r=s.result()!!
 println("UNSTABLE_REFERENCE spread=${r.referenceBefore.levelSpreadDb} kept=${r.referenceBefore.keptFrames} dropped=${r.referenceBefore.droppedFrames} verdict=${judgeQuality(qualityFromSession(r,flat(10.0),dspVerifiedBySignal=true)).verdict}")
 val s2=CalibrationSession()
 val a=flat(50.0);a[20]=60.0
 val b=flat(50.0);b[24]=60.0
 repeat(4){s2.record(MeasureStep.ReferenceBefore,a);s2.record(MeasureStep.ReferenceAfter,b);s2.record(MeasureStep.Target,a)}
 val r2=s2.result()!!
 println("SPECTRAL_DRIFT drift=${r2.referenceDriftDb} verdict=${judgeQuality(qualityFromSession(r2,flat(10.0),dspVerifiedBySignal=true)).verdict} correctionMax=${calibrateFromSession(r2,null).correction.db.maxOf {abs(it)}}")
 val noise=flat(10.0);noise[20]=a[20]
 val q=qualityFromSession(r2,noise,dspVerifiedBySignal=true)
 val c=calibrateFromSession(r2,CalibrationCurve.of(listOf(CurvePoint(200.0,0.0),CurvePoint(10000.0,0.0))).getOrThrow())
 val ix=c.correction.hz.indices.minBy {abs(ln(c.correction.hz[it]/ThirdOctave.exactCenter(20)))}
 val lo=c.correction.hz.indices.minBy {abs(c.correction.hz[it]-30.0)}
 println("MASK quality=${judgeQuality(q).verdict} snrUsable=${q.usable[20]} correctionValid=${c.correction.valid[ix]} calOutside30HzValid=${c.correction.valid[lo]}")
 val text=encodeCurves(c)
 val bad=text.lineSequence().map { if(it.startsWith("correction.db=")) "correction.db="+c.correction.db.indices.joinToString(","){"NaN"} else it }.joinToString("\n")
 val decoded=decodeCurves(bad)
 println("CODEC_NAN success=${decoded.isSuccess} validNan=${decoded.getOrThrow().correction.db.indices.count {decoded.getOrThrow().correction.valid[it] && decoded.getOrThrow().correction.db[it].isNaN()}}")
 val center=ThirdOctave.exactCenter(17)
 val centerCurve=CalibrationCurve.of(listOf(CurvePoint(900.0,6.0),CurvePoint(center,0.0),CurvePoint(1100.0,-6.0))).getOrThrow()
 val integrated=10*log10((10.0.pow(6.0/10)+10.0.pow(-6.0/10))/2)
 val bandSample=flat(0.0);bandSample[17]=integrated
 println("CENTER_CAL expected=0.0 actual=${applyMicCalibration(bandSample,centerCurve)[17]} centerGain=${centerCurve.gainDbAt(center)}")
}
