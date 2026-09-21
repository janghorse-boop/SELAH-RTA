import kr.joa.selahrta.recording.*
import kr.joa.selahrta.dsp.*
import java.io.*
import kotlin.math.*
fun main() {
 fun table()=EpochTable().apply {add(RecordingEpoch(0,0,100.0,false,60000));add(RecordingEpoch(1,12000,120.0,false,60000))}
 fun s(v:Double)=RowSample(v,v,v,false,FloatArray(31))
 fun trip(a:RowAggregator,t:EpochTable):Pair<TimelineReader,List<TimelineRow>> {val b=ByteArrayOutputStream();val w=TimelineWriter(b,TimelineHeader(48000,500));a.finish().forEach{w.write(it)};val r=TimelineReader(ByteArrayInputStream(b.toByteArray()),t);return r to r.all()}
 val t=table();val a=RowAggregator(48000,t)
 val cross=runCatching{a.add(0,24000,0,s(-20.0))}.exceptionOrNull()
 println("EPOCH_CROSS accepted=${cross==null} calibrated=${trip(a,t).let{it.first.calibratedMax(it.second[0])}}")
 val b=RowAggregator(48000,t);b.add(12000,12000,1,s(-30.0));b.add(0,12000,0,s(-20.0));println("REVERSE current=${b.finish().single().currentRaw} epoch=${b.finish().last().currentEpoch}")
 val c=RowAggregator(48000,t);c.add(0,100,0,s(-20.0));println("FINISH first=${c.finish().size} second=${c.finish().size}")
 val d=RowAggregator(48000,t);d.add(0,100,0,s(-20.0));d.add(1000,100,0,s(-30.0));println("PARTIAL_GAP missing=${d.finish().single().missing}")
 val ids=EpochTable().apply{add(RecordingEpoch(0,0,100.0,false,60000));add(RecordingEpoch(65536,12000,120.0,false,60000))};val idAgg=RowAggregator(48000,ids);idAgg.add(12000,12000,65536,s(-30.0));val result=trip(idAgg,ids);println("ID_TRUNCATE expected=90 actual=${result.first.calibratedMax(result.second.single())}")
 val bad=ByteArrayOutputStream();TimelineWriter(bad,TimelineHeader(0,0));println("BAD_HEADER accepted=${runCatching{TimelineReader(ByteArrayInputStream(bad.toByteArray()),t)}.isSuccess}")
 for(fs in listOf(48000,44100)){val ba=BandAnalyzer(4096,fs);val changes=(0 until 31).filter{ba.bandResolved[it] != ((ThirdOctave.upperEdge(it)-ThirdOctave.lowerEdge(it)>=3.0*fs/4096)&&(ThirdOctave.upperEdge(it)<=fs/2.0))};println("BANDS fs=$fs differences=${changes.map{ThirdOctave.CENTERS_HZ[it]}}")}
 val target=listOf(12,13,14);val refs=listOf(10,11,15,16)
 fun prominence(exact:Boolean,density:Boolean):Double {fun x(i:Int)=log2(if(exact)ThirdOctave.exactCenter(i) else ThirdOctave.CENTERS_HZ[i])
 fun q(i:Int):Double {val bw=ThirdOctave.upperEdge(i)-ThirdOctave.lowerEdge(i);val power=1.0+bw*0.01;return 10*log10(power/(if(density)bw else 1.0))}
 val mx=refs.map{ x(it)}.average();val my=refs.map{q(it)}.average();val slope=refs.sumOf{(x(it)-mx)*(q(it)-my)}/refs.sumOf{(x(it)-mx).pow(2)};return target.map{q(it)-(my+slope*(x(it)-mx))}.sorted()[1]}
 for(exact in listOf(true,false))println("DENSITY exactCenter=$exact difference=${prominence(exact,true)-prominence(exact,false)}")
}

