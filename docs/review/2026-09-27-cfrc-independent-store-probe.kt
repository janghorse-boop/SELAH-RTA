package review
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import android.app.Application
import android.content.Context
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kr.joa.selahrta.calibration.*
import kr.joa.selahrta.audio.CaptureSource
import kr.joa.selahrta.dsp.*

private class StoreApp(private val dir:File):Application(){
 override fun getFilesDir()=dir
 override fun getApplicationContext():Context=this
}
private fun sha(s:String)=java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
fun main(args:Array<String>)=runBlocking {
 val dir=File(System.getProperty("review.dir"),"store-fixture").apply{mkdirs()}
 val context=StoreApp(dir); val store=CurveStore(context)
 val key=CalibrationKey("Usb|reference|fixture",CaptureSource.Unprocessed,0)
 val conflict="# Correction factors\nFrequency (Hz),Response (dB)\n100,3\n1000,0\n20000,0\n"
 suspend fun current(k:CalibrationKey=key)=withTimeout(5000){store.watch(k).first()}!!
 if(args.firstOrNull()=="reopen") {
   val read=current();check(read.enabled && read.readingConfirmed && read.curve.gainDbAt(100.0)==-3.0)
   println("REOPEN confirmed=${read.readingConfirmed} enabled=${read.enabled} gain100=${read.curve.gainDbAt(100.0)}")
   return@runBlocking
 }
 val saved=store.save(key,"conflict.cal",conflict).getOrThrow()
 check(!saved.enabled && current().readingConfirmationNeeded)
 check(store.setEnabled(key,true)!=null && !current().enabled)
 println("TOGGLE_WITHOUT_READING blocked=true")
 // Simulate a legacy enabled state through the original on flag name, without confirmation.
 val getter=Class.forName("kr.joa.selahrta.calibration.CurveStoreKt").getDeclaredMethod("getCurveDataStore",Context::class.java).apply{isAccessible=true}
 @Suppress("UNCHECKED_CAST")
 val dataStore=getter.invoke(null,context) as DataStore<Preferences>
 dataStore.edit { it.remove(stringPreferencesKey(key.storageKey()+"|curveOn")) }
 check(!current().enabled && current().readingConfirmationNeeded)
 println("LEGACY_ON_FLAG_ABSENT held=true")
 val otherContext=StoreApp(File(dir,"other-context").apply{mkdirs()})
 val otherDataStore=getter.invoke(null,otherContext)
 println("DATASTORE_DELEGATE sharedAcrossContexts=${dataStore===otherDataStore}")
 check(dataStore===otherDataStore)
 // In this version confirmation is the only path which should activate this curve.
 check(store.confirmReading(current().confirmationToken!!,CurveReading.Correction)==null)
 val corrected=current();check(corrected.enabled && corrected.readingConfirmed && corrected.curve.gainDbAt(100.0)==-3.0)
 check(store.setEnabled(key,false)==null && !current().enabled)
 check(store.setEnabled(key,true)==null && current().enabled)
 println("CONFIRM_CORRECTION gain100=-3.0 offOnPreserves=true")
 val persisted=File(dir,"curves/"+sha(key.storageKey())+".cal")
 val oldRaw=persisted.readText()
 persisted.writeText(oldRaw.replace("100,3","100,6"))
 check(!current().enabled && !current().readingConfirmed)
 persisted.writeText(oldRaw)
 check(current().enabled && current().readingConfirmed)
 println("FILE_CHANGED_WITH_PROOF_LEFT held=true")
 for((i,header) in listOf("Frequency (Hz),Response (dB)","# no declaration").withIndex()) {
   val nk=key.copy(deviceKey="Usb|normal$i|fixture")
   val normal=store.save(nk,"normal.cal","$header\n100,3\n1000,0\n20000,0\n").getOrThrow()
   check(normal.enabled && !normal.readingConfirmationNeeded)
   check(store.setEnabled(nk,false)==null && !current(nk).enabled)
   check(store.setEnabled(nk,true)==null && current(nk).enabled)
 }
 println("NORMAL_AND_UNKNOWN preserved=true")
 val ch1=key.copy(channelIndex=1)
 store.save(ch1,"same.cal",conflict).getOrThrow()
 check(!current(ch1).enabled && !current(ch1).readingConfirmed)
 println("CROSS_CHANNEL confirmationNotReused=true")
 // Same file name with new bytes invalidates old confirmation.
 store.save(key,"conflict.cal",conflict.replace("100,3","100,6")).getOrThrow()
 check(!current().enabled && !current().readingConfirmed)
 println("REIMPORT_CHANGED_BYTES invalidated=true")
 // A stale confirmation button from A is clicked after B replaces it.
 val shownA=store.save(key,"A.cal",conflict).getOrThrow()
 check(shownA.readingConfirmationNeeded)
 val b="# Frequency response\nFrequency (Hz),Corr (dB)\n100,6\n1000,0\n20000,0\n"
 store.save(key,"B.cal",b).getOrThrow()
 check(!current().enabled)
 val staleResult=store.confirmReading(shownA.confirmationToken!!,CurveReading.Response)
 val stale=current()
 check(staleResult!=null && stale.fileName=="B.cal" && !stale.enabled && !stale.readingConfirmed)
 println("STALE_CONFIRM shown=A.cal current=${stale.fileName} approved=${stale.enabled} reading=${stale.reading}")
 // Unsupported quantity/unit cannot be fixed by a sign choice.
 for((i,h) in listOf("Frequency,Phase,SPL", "Frequency (Hz),Amplitude (Pa)", "Frequency (kHz),Response (dB)","Frequency (Hz),Magnitude (linear)").withIndex()) {
   val k=key.copy(deviceKey="Usb|unsupported$i|fixture")
   val text="$h\n100,3,0\n1000,0,0\n20000,0,0\n"
   val raw=store.save(k,"bad$i.cal",text).getOrThrow()
   check(!raw.enabled && raw.readingUnsupportedKo!=null)
   check(store.confirmReading(raw.confirmationToken!!,CurveReading.Correction)!=null)
   val after=current(k);check(!after.enabled && !after.readingConfirmed)
   println("UNSUPPORTED header=$h importedEnabled=${raw.enabled} confirmedEnabled=${after.enabled}")
 }
 // Rejection must not depend on note order or declaration order.
 val composite=listOf(
   "Frequency (Hz),Response (dB) (correction) (Pa)" to true,
   "Frequency (Hz),Response (dB) (Pa) (correction)" to false,
   "Frequency (Hz),Response (dB) (correction)\nFrequency (kHz),Response (dB)" to true,
   "Frequency (kHz),Response (dB)\nFrequency (Hz),Response (dB) (correction)" to false,
   "Frequency (Hz),Response (dB) (correction)\nFrequency,Phase,SPL" to true,
   "Frequency,Phase,SPL\nFrequency (Hz),Response (dB) (correction)" to false,
   "Frequency (Hz),Response (dB) (correction Pa)" to true,
   "Frequency (Hz),Response (response Pa)" to true,
   "Frequency (Hz),Response (dB) (correction)\nFrequency (Hz),Response (dB)" to true,
 )
 for((i,pair) in composite.withIndex()) {
   val (h,allows)=pair
   val k=key.copy(deviceKey="Usb|composite$i|fixture")
   val raw=store.save(k,"composite$i.cal",h+"\n100,3,0\n1000,0,0\n20000,0,0\n").getOrThrow()
   val reason=store.confirmReading(raw.confirmationToken!!,CurveReading.Correction)
   val after=current(k)
   val columns=columnDeclarationOf(raw.headerLines)
   check((reason==null)==allows && after.enabled==allows)
   println("COMPOSITE index=$i columns=$columns confirmAllowed=${reason==null} enabled=${after.enabled}")
 }
 // Eighth header survives the app owner comment.
 val tail="Frequency (Hz),Response (dB)\n"+(1..6).joinToString(""){"# note $it\n"}+"# Correction factors\n100,3\n1000,0\n20000,0\n"
 val tk=key.copy(deviceKey="Usb|tail|fixture")
 check(!store.save(tk,"tail.cal",tail).getOrThrow().enabled)
 check(current(tk).readingConfirmationNeeded && store.setEnabled(tk,true)!=null)
 println("EIGHTH_HEADER preserved=true")
 // Rule changes revoke an old proof; pure decision plus same raw data.
 val headers=CalibrationFile.load(conflict).getOrThrow().headerLines
 val old=ReadingConfirmationRecord(sha(conflict),"Correction",CURVE_READING_RULES_VERSION-1)
 check(resolveCurveReading(headers,sha(conflict),old).needsPerson)
 println("OLD_RULE confirmationRevoked=true")
 store.save(key,"rules.cal",conflict).getOrThrow();check(store.confirmReading(current().confirmationToken!!,CurveReading.Correction)==null)
 dataStore.edit { it[intPreferencesKey(key.storageKey()+"|curveReadRules")]=CURVE_READING_RULES_VERSION-1 }
 check(!current().enabled && current().readingConfirmationNeeded)
 println("OLD_RULE_DATASTORE enabled=false")
 store.save(key,"persist.cal",conflict).getOrThrow();check(store.confirmReading(current().confirmationToken!!,CurveReading.Correction)==null)
}






