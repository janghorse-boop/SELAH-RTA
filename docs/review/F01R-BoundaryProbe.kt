import kr.joa.selahrta.dsp.*
fun main(){
 val s=CalibrationSession(referenceCalApplied=true)
 repeat(8){for(step in MeasureStep.entries)s.record(step,DoubleArray(31){70.0})}
 val r=s.result()!!
 val q0=qualityFromSession(r,DoubleArray(31){0.0},DoubleArray(31){0.0},referenceCalRangeHz=20.0..1300.0,dspVerifiedBySignal=true)
 val o0=calibrateFromSession(r,q0).getOrThrow()
 println("CAL_ONLY approved=${q0.approvedCount} supported=${o0.supportedBands.size} maxCorrection=${o0.correction.db.maxOf{kotlin.math.abs(it)}} pre=${judgeQuality(q0).verdict} final=${judgeCalibration(q0,o0).verdict}")
 println(judgeCalibration(q0,o0).reasonsKo)
 var shown=0
 for(lo in 0..12) for(hi in 18..30){
  val range=(ThirdOctave.exactCenter(lo)*0.999)..(ThirdOctave.exactCenter(hi)*1.001)
  val q=qualityFromSession(r,DoubleArray(31){0.0},DoubleArray(31){0.0},referenceCalRangeHz=range,dspVerifiedBySignal=true)
  val os=listOf(6,12,24).map{calibrateFromSession(r,q,CalibrationSettings(pointsPerOctave=it)).getOrNull()}
  if(os.any{it==null})continue
  val counts=os.map{it!!.supportedBands.size}
  val vs=os.map{judgeCalibration(q,it!!).verdict}
  if(counts.distinct().size>1 && shown++<10) println("BOUNDARY lo=$lo hi=$hi count=$counts verdict=$vs")
 }
}

