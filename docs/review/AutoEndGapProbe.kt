package kr.joa.selahrta.audio
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class GapSink(val mode: Int): SignalSink {
    val entered=CountDownLatch(1)
    val gate=CountDownLatch(1)
    override fun open(sampleRate:Int, frames:Int)=true
    override fun write(buf:FloatArray, offset:Int, frames:Int):Int {
        entered.countDown()
        if(mode>0) check(gate.await(5,TimeUnit.SECONDS))
        return if(mode<2) SignalSink.ERROR_DEAD_OBJECT else frames
    }
    override fun stop() { gate.countDown() }
    override fun release()=false
}
fun main() {
    var hit=false
    var tried=0
    repeat(1000) {
        if(hit) return@repeat
        tried++
        val firstEnded=CountDownLatch(1)
        val secondEnded=CountDownLatch(1)
        val sinks=ArrayList<GapSink>()
        val p=SignalPlayer(onEnded={gen,_-> if(gen==1L) firstEnded.countDown() else if(gen==2L) secondEnded.countDown()}, openSink={GapSink(sinks.size).also{sinks.add(it)}}, warn={})
        p.start(TestSignal.Sine1k, SignalLevel.Low)
        check(firstEnded.await(5,TimeUnit.SECONDS))
        check(p.pendingCount==1)
        p.start(TestSignal.Sine1k, SignalLevel.Low)
        check(sinks[1].entered.await(5,TimeUnit.SECONDS))
        sinks[1].gate.countDown()
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(p.playing!=null && System.nanoTime()<until) Thread.onSpinWait()
        check(p.playing==null)
        val third=p.start(TestSignal.Sine1k, SignalLevel.Low)
        check(secondEnded.await(5,TimeUnit.SECONDS))
        if(third!=SignalPlayer.NONE) {
            p.stop()
            println("DETACH_GAP iteration=$tried opens=${sinks.size} pending=${p.pendingCount} cap=${SignalPlayer.MAX_STUCK_PLAYBACKS}")
            check(p.pendingCount>SignalPlayer.MAX_STUCK_PLAYBACKS)
            hit=true
        }
    }
    println("DETACH_GAP reproduced=$hit attempts=$tried")
}
