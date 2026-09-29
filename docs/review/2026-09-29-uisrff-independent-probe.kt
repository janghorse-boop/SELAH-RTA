package kr.joa.selahrta.ui

import androidx.lifecycle.ViewModel
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kr.joa.selahrta.audio.*
import kr.joa.selahrta.calibration.*
import kr.joa.selahrta.domain.ChurchSegment
import kr.joa.selahrta.dsp.*
import kr.joa.selahrta.recording.*
import kr.joa.selahrta.settings.MeterSettings

/** No production edits. Real Controller, DSP, ViewModel methods, and recorder.
 * Replace hardware, clock and main dispatch; skip unrelated Android constructor wiring.
 * Expected known-bug observations use check() so this executable is a reproducible probe,
 * not a claim that desired product behavior passes.
 */
private class ManualSource(device: InputDeviceInfo?, hooks: SourceHooks, val clock: FakeClock) :
    AudioSource by FakeSource(device, hooks, clock=clock) {
    private val delegate = FakeSource(device, hooks, clock=clock)
    private lateinit var callback: (AudioBlock, kr.joa.selahrta.dsp.BlockStats)->Unit
    private var frames=0L
    override fun start(onBlock: (AudioBlock, kr.joa.selahrta.dsp.BlockStats)->Unit) {
        callback=onBlock; delegate.start(onBlock)
    }
    fun tone(amplitude: Double=.1, count: Int=960, advanceTime: Boolean=true) {
        val pcm=FloatArray(count){ (amplitude*sin(2*PI*1000*(frames+it)/48000)).toFloat() }
        frames+=count
        if(advanceTime) clock.ns+=count*1_000_000_000L/48000
        callback(AudioBlock(pcm,count,48000,clock.ns),blockStats(pcm,count))
    }
}

private fun ManualSource.emptyBlock(rig:Rig) {
    val field=ManualSource::class.java.getDeclaredField("callback").apply{isAccessible=true}
    @Suppress("UNCHECKED_CAST")
    val callback=field.get(this) as (AudioBlock,BlockStats)->Unit
    rig.clock.advanceMs(400)
    callback(AudioBlock(floatArrayOf(),0,48000,rig.clock.ns),BlockStats(0.0,0.0,0))
}

private class HeldMain : MainCoroutineDispatcher() {
    val queue=LinkedBlockingQueue<Runnable>()
    override val immediate: MainCoroutineDispatcher get()=this
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
    fun next(): Runnable=checkNotNull(queue.poll(5,TimeUnit.SECONDS)){"No main continuation"}
    fun drain(){while(true) (queue.poll() ?: return).run()}
}

private fun setField(target:Any,name:String,value:Any?) {
    target.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(target,value)
}

private fun bareVm(c:CaptureController, state:MutableStateFlow<CaptureUiState>):CaptureViewModel {
    val f=sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply{isAccessible=true}
    val vm=(f.get(null) as sun.misc.Unsafe).allocateInstance(CaptureViewModel::class.java) as CaptureViewModel
    // Preserve real ViewModel scope implementation; don't emulate coroutine behavior.
    val seed=object:ViewModel(){}
    ViewModel::class.java.declaredFields.filter{!java.lang.reflect.Modifier.isStatic(it.modifiers)}.forEach {
        it.isAccessible=true; it.set(vm,it.get(seed))
    }
    setField(vm,"controller",c); setField(vm,"state",state)
    return vm
}

private class Rig(val fast:Boolean=false) {
    val clock=FakeClock(1_000_000_000L)
    lateinit var source:ManualSource
    var result:RecordedSession?=null
    val controller=CaptureController(
        listDevices={listOf(builtInMic())},
        openSource={d,h->ManualSource(d,h,clock).also{source=it}},
        post={it()},onRouteConfirmedHook={},onStoppedHook={},
        onRecordingFinished={r,_->result=r}, nowNs={clock.ns},
    )
    val state=MutableStateFlow(CaptureUiState())
    val vm=bareVm(controller,state)
    init {controller.update{it.copy(meterSettings=it.meterSettings.copy(timeWeight=if(fast) TimeWeight.Fast else TimeWeight.Slow))};controller.start()}
    fun refresh(){state.value=controller.baseState.value.withMeasurement(controller.measurement.value)}
    fun tone(blocks:Int,amplitude:Double=.1){repeat(blocks){source.tone(amplitude)};refresh()}
    fun gate():CalibrationGate=CaptureViewModel::class.java.getDeclaredMethod("gateFor",CalibrationSource::class.java,CalibrationEvidence::class.java)
        .apply{isAccessible=true}.invoke(vm,CalibrationSource.Calibrator,controller.calibrationEvidence()) as CalibrationGate
}

private fun recordSegment(main:HeldMain,to:ChurchSegment?,after:ChurchSegment?) {
    val r=Rig()
    val initial=r.controller.baseState.value.meterSettings.copy(segment=ChurchSegment.Sermon,
        segments=setOf(ChurchSegment.Sermon,ChurchSegment.Worship))
    r.controller.update{it.copy(meterSettings=initial)}
    setField(r.vm,"sessionStore",SessionStore(File(System.getProperty("review.dir"),"segment-${System.nanoTime()}")))
    r.vm.startRecording(false)
    val attach=main.next()
    val selected=if(to==null) initial.copy(segments=emptySet()) else initial.copy(segment=to)
    r.controller.update{it.copy(meterSettings=selected)}
    val note=CaptureViewModel::class.java.getDeclaredMethod("noteSegmentToRecording",ChurchSegment::class.java,MeterSettings::class.java).apply{isAccessible=true}
    note.invoke(r.vm,selected.activeSegment,selected)
    r.source.tone()
    attach.run();main.drain();r.tone(5)
    if(after!=null) {
        val new=initial.copy(segment=after)
        r.controller.update{it.copy(meterSettings=new)}
        note.invoke(r.vm,new.activeSegment,new)
    }
    r.tone(55);r.controller.stopRecording();r.source.tone()
    val events=r.result!!.events.filter{it.kind==SessionEventKind.SegmentChange}
    check(events.first().atMs==0L && events.first().segment==to)
    check(events.size==if(after==null)1 else 2)
    if(after!=null)check(events.last().segment==after && events.last().atMs>0)
    println("SEGMENT to=$to after=$after events=$events")
    r.controller.stop()
}
@Suppress("UNCHECKED_CAST")
@OptIn(ExperimentalCoroutinesApi::class)
private fun watchdog() = kotlinx.coroutines.test.runTest {
    val main=kotlinx.coroutines.test.StandardTestDispatcher(testScheduler);Dispatchers.setMain(main)
    // Execute the COMPILED production lambda, not a copied implementation.
    fun launchActual(vm:CaptureViewModel):Job {
        val type=Class.forName("kr.joa.selahrta.ui.CaptureViewModel\$4")
        val ctor=type.declaredConstructors.single().apply{isAccessible=true}
        val block=ctor.newInstance(vm,null) as Function2<CoroutineScope,kotlin.coroutines.Continuation<Unit>,Any?>
        val suspending=block as (suspend CoroutineScope.()->Unit)
        return backgroundScope.launch(main,block=suspending)
    }
    val none=Rig();val tick=launchActual(none.vm);testScheduler.runCurrent()
    repeat(25){none.clock.advanceMs(400);testScheduler.advanceTimeBy(400);testScheduler.runCurrent()}
    val age=none.controller.baseState.value.lastInputAgeMs
    check(none.controller.running && age==if(java.lang.Boolean.getBoolean("expect.safety.fixed"))10000.0 else null)
    println("NO_FIRST_BLOCK running=${none.controller.running} elapsedMs=10000 age=$age warning=${captureWarningKo(none.controller.composed().diagnostics,age)}")
    tick.cancel();testScheduler.runCurrent();none.controller.stop()
    val blanks=Rig(); val bt=launchActual(blanks.vm);testScheduler.runCurrent()
    repeat(25){blanks.source.emptyBlock(blanks);testScheduler.advanceTimeBy(400);testScheduler.runCurrent()}
    val blankAge=blanks.controller.baseState.value.lastInputAgeMs
    check(blankAge==10000.0)
    check(blanks.controller.calibrationEvidence()!!.cleanSpl==null)
    check(captureWarningKo(blanks.controller.composed().diagnostics,blankAge)!=null)
    println("FIRST_EMPTY_BLOCKS count=25 ageMs=$blankAge cleanSpl=null warning=true")
    blanks.tone(1);testScheduler.advanceTimeBy(400);testScheduler.runCurrent()
    check(blanks.controller.baseState.value.lastInputAgeMs==0.0)
    println("FIRST_EMPTY_RECOVERY ageMs=0.0 cleanMs=${blanks.controller.calibrationEvidence()!!.cleanMs}")
    bt.cancel();testScheduler.runCurrent();blanks.controller.stop()
    val warm=Rig();warm.tone(250);val tick2=launchActual(warm.vm);testScheduler.runCurrent()
    repeat(4){warm.clock.advanceMs(400);testScheduler.advanceTimeBy(400);testScheduler.runCurrent()}
    val age2=warm.controller.baseState.value.lastInputAgeMs
    check(age2==1600.0)
    check(captureWarningKo(warm.controller.composed().diagnostics,age2)!=null)
    println("AFTER_FIRST_BLOCK stalledAge=$age2 warning=${captureWarningKo(warm.controller.composed().diagnostics,age2)}")
    warm.tone(1);testScheduler.advanceTimeBy(400);testScheduler.runCurrent()
    check(warm.controller.baseState.value.lastInputAgeMs==0.0)
    tick2.cancel();testScheduler.runCurrent();warm.controller.stop();Dispatchers.resetMain()
}

private val saveRoot=File(System.getProperty("review.dir"),"store-${System.nanoTime()}")
private val saveStore by lazy { CalibrationStore(TestApplication(saveRoot)) }
private fun save(main:HeldMain,r:Rig):GlobalCalibration {
    val root=saveRoot;val store=saveStore;setField(r.vm,"store",store)
    val expected=r.controller.calibrationEvidence()!!.measuredDbfs(r.state.value.meterSettings.splWeighting)
    r.vm.saveSimpleCalibration(94.0,CalibrationSource.Calibrator);main.drain()
    val key=CalibrationKey.of(r.controller.confirmedFormat()!!)
    return runBlocking {
        val read=async(Dispatchers.Default){withTimeout(5000){store.watch(key).first{it?.measuredDbfs==expected}!!}}
        withTimeout(5000){while(!read.isCompleted){main.drain();delay(10)}}
        read.await().also{check(File(root,"datastore/calibration.preferences_pb").isFile)}
    }.also{main.drain()}
}
private fun phaseBoundary() {
    for(fs in listOf(44100,48000)) {
        val n=fs/100 // 10 ms
        var at=1_000_000_000L;var position=0L
        val w=CleanWindow(fs)
        fun feed(amplitude:Double,blocks:Int) { repeat(blocks){
            val pcm=FloatArray(n){(amplitude*sin(2*PI*1000*(position+it)/fs)).toFloat()}
            position+=n;at+=10_000_000;w.observe(pcm,n,at,false)
        } }
        feed(.9,502);feed(.009,300)
        val at3=w.value(at,Weighting.Z)!!
        feed(.009,10);val at31=w.value(at,Weighting.Z)!!
        check(if(java.lang.Boolean.getBoolean("expect.window.fixed")) kotlin.math.abs(at3-at31)<.05 else at3-at31>18.0)
        println("WINDOW_BOUNDARY fs=$fs after3000=$at3 after3100=$at31 residual=${at3-at31}")
    }
}
private fun untouchedLivePath() {
    val r=Rig()
    val expected=MultiWeightEngine(48000,TimeWeight.Slow)
    var samples=0L;var comparisons=0
    for(i in 0 until 500) {
        val amp=when(i){in 0..99 -> 1.0; in 100..249 -> .01; else -> .1}
        val pcm=FloatArray(960){(amp*sin(2*PI*1000*(samples+it)/48000)).toFloat()};samples+=960
        val wanted=expected.process(pcm,960)
        r.source.tone(amp)
        val got=r.controller.measurement.value!!
        if(got.atMonotonicMs==r.clock.ns/1_000_000){check(got.spl==wanted);comparisons++}
    }
    println("LIVE_METRICS identicalSnapshots=$comparisons includes=A_C_Z_current_MAX_Peak_Leq")
    r.controller.stop()
}
private fun lateOldBlock() {
    val r=Rig();r.tone(160)
    val entered=java.util.concurrent.CountDownLatch(1);val resume=java.util.concurrent.CountDownLatch(1)
    r.controller.postToCapture{ s -> s.rta.addSpectrumSink(SpectrumSink {
        entered.countDown();check(resume.await(5,TimeUnit.SECONDS))
    }) }
    val oldSource=r.source
    val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val worker=Thread{try{oldSource.tone(.2,count=4096)}catch(t:Throwable){failure.set(t)}}
    worker.start();check(entered.await(5,TimeUnit.SECONDS))
    try{
        r.controller.stop();check(r.controller.calibrationEvidence()==null)
        r.controller.start();check(r.controller.calibrationEvidence()==null)
        r.tone(155)
        val current=r.controller.calibrationEvidence()!!
        resume.countDown();worker.join(3000);check(!worker.isAlive);failure.get()?.let{throw it}
        check(r.controller.calibrationEvidence()===current)
        println("LATE_OLD_BLOCK currentSession=${current.session} sameEvidence=true")
    }finally{resume.countDown();r.controller.stop()}
}
@OptIn(ExperimentalCoroutinesApi::class)
fun main() {
    val main=HeldMain();Dispatchers.setMain(main)
    try{
        val clipped=Rig();clipped.tone(250,1.0);clipped.tone(155,.01)
        check(clipped.gate()==CalibrationGate.Save)
        val used=clipped.controller.calibrationEvidence()!!.measuredDbfs(Weighting.A)!!
        val saved=save(main,clipped)
        check(saved.measuredDbfs==used)
        clipped.tone(1000,.01)
        val final=clipped.controller.calibrationEvidence()!!.measuredDbfs(Weighting.A)!!
        check(kotlin.math.abs(used-final)<.05)
        println("CLIP_FIX saved=$used final=$final error=${used-final} offset=${saved.offsetDb}")
        val gap=Rig();gap.tone(250,1.0);gap.clock.advanceMs(3100);gap.tone(1,.01)
        check(gap.gate() is CalibrationGate.Reject)
        println("GAP_FIX cleanMs=${gap.controller.calibrationEvidence()!!.cleanMs} gate=${gap.gate()}")
        val phase=Rig();phase.tone(251,.9);phase.tone(151,.009)
        check(phase.gate()==CalibrationGate.Save)
        val contaminated=save(main,phase)
        phase.tone(4,.009)
        val trueValue=phase.controller.calibrationEvidence()!!.measuredDbfs(Weighting.A)!!
        println("WINDOW_DEBUG initial=${contaminated.measuredDbfs} final=$trueValue delta=${contaminated.measuredDbfs-trueValue}"); check(if(java.lang.Boolean.getBoolean("expect.window.fixed")) kotlin.math.abs(contaminated.measuredDbfs-trueValue)<.05 else contaminated.measuredDbfs-trueValue>18)
        println("WINDOW_SAVE after3020=${contaminated.measuredDbfs} after3100=$trueValue savedOffset=${contaminated.offsetDb} resultingSpl=${trueValue+contaminated.offsetDb}")
        val empty=Rig();empty.tone(160);val lastGood=empty.clock.ns
        repeat(5){empty.source.emptyBlock(empty)}
        val reported=empty.controller.inputWaitAgeMs()
        check(reported==if(java.lang.Boolean.getBoolean("expect.valid.clock.fixed"))2000.0 else 0.0)
        println("EMPTY_BLOCKS actualValidInputAgeMs=${(empty.clock.ns-lastGood)/1e6} reportedAgeMs=$reported cleanMs=${empty.controller.calibrationEvidence()!!.cleanMs} warning=${captureWarningKo(empty.controller.composed().diagnostics,reported)}")
        val fast=Rig(true);fast.tone(250,1.0);fast.tone(155,.01)
        check(fast.controller.calibrationEvidence()!!.measuredDbfs(Weighting.A)==used)
        println("FAST_SLOW_CALIBRATION same=true")
        recordSegment(main,ChurchSegment.Worship,null)
        recordSegment(main,null,null)
        recordSegment(main,ChurchSegment.Worship,ChurchSegment.Sermon)
        untouchedLivePath();lateOldBlock();phaseBoundary()
        listOf(clipped,gap,phase,empty,fast).forEach{it.controller.stop()}
    }finally{Dispatchers.resetMain()}
    watchdog()
}





