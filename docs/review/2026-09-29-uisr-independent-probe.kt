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

private class Rig {
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
    init {controller.start()}
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
@OptIn(ExperimentalCoroutinesApi::class)
fun main(){
    val main=HeldMain();Dispatchers.setMain(main)
    try{
        val fresh=Rig();fresh.tone(250);check(fresh.gate()==CalibrationGate.Save)
        fresh.clock.advanceMs(10000);check(fresh.gate() is CalibrationGate.Reject)
        println("STALE_FIX age=${fresh.controller.calibrationEvidence()!!.ageMs(fresh.clock.ns)} gate=${fresh.gate()}")
        val clip=Rig();clip.source.tone(1.0);clip.tone(500)
        check(clip.state.value.meter.peakClipped && clip.gate()==CalibrationGate.Save)
        println("OLD_CLIP_FIX cumulativeClip=${clip.state.value.meter.peakClipped} gate=${clip.gate()}")
        val long=Rig();long.tone(250,1.0);long.tone(155,.01)
        val db=long.controller.calibrationEvidence()!!.measuredDbfs(Weighting.A)
        val gate=long.gate();check(gate==CalibrationGate.Save)
        val saveRoot=File(System.getProperty("review.dir"),"calibration-${System.nanoTime()}")
        val store=CalibrationStore(TestApplication(saveRoot))
        setField(long.vm,"store",store)
        long.vm.saveSimpleCalibration(94.0,CalibrationSource.Calibrator);main.drain()
        val key=CalibrationKey.of(long.controller.confirmedFormat()!!)
        val persisted=runBlocking {
            val read=async(Dispatchers.Default) { withTimeout(5000) { store.watch(key).first { it!=null }!! } }
            withTimeout(5000) { while(!read.isCompleted){main.drain();delay(10)} }
            read.await()
        }
        check(persisted.measuredDbfs==db)
        check(File(saveRoot,"datastore/calibration.preferences_pb").isFile)
        println("PERSISTED reference=${persisted.referenceDb} measured=${persisted.measuredDbfs} offset=${persisted.offsetDb} fileExists=true")
        main.drain()
        long.tone(1000,.01)
        val settled=long.controller.calibrationEvidence()!!.measuredDbfs(Weighting.A)
        check(db-settled>20)
        println("CLIP_RESIDUAL cleanInputMs=3100 gate=$gate usedDbfs=$db eventualDbfs=$settled errorDb=${db-settled}")
        val gap=Rig();gap.tone(250,1.0);gap.clock.advanceMs(3100);gap.tone(1,.01)
        check(gap.gate()==CalibrationGate.Save)
        println("CLIP_GAP cleanInputMs=20 elapsedMs=3120 age=${gap.controller.calibrationEvidence()!!.ageMs(gap.clock.ns)} usedDbfs=${gap.controller.calibrationEvidence()!!.measuredDbfs(Weighting.A)} gate=${gap.gate()}")
        val old=Rig();old.tone(250,.01);val prior=old.controller.calibrationEvidence();old.controller.stop()
        check(if(java.lang.Boolean.getBoolean("expect.safety.fixed")) old.controller.calibrationEvidence()==null else old.controller.calibrationEvidence()===prior)
        old.controller.start();old.refresh()
        check(old.controller.calibrationEvidence()?.session!=old.controller.baseState.value.session)
        val meterGate=calibrationGate(true,true,old.controller.calibrationEvidence(),old.clock.ns,CalibrationSource.Meter,null)
        check(if(java.lang.Boolean.getBoolean("expect.safety.fixed")) meterGate is CalibrationGate.Reject else meterGate==CalibrationGate.Save)
        println("RESTART_EVIDENCE oldSession=${prior!!.session} currentSession=${old.controller.baseState.value.session} age=${prior.ageMs(old.clock.ns)} meterGate=$meterGate calibratorGate=${old.gate()}")
        // Meter is not exposed in CalibrationCard. Do not present this API
        // contract gap as an observed UI cross-microphone save.
        recordSegment(main,ChurchSegment.Worship,null)
        recordSegment(main,null,null)
        recordSegment(main,ChurchSegment.Worship,ChurchSegment.Sermon)
        listOf(fresh,clip,long,gap,old).forEach{it.controller.stop()}
    }finally{Dispatchers.resetMain()}
    watchdog()
}



