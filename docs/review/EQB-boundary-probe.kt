import kr.joa.selahrta.data.instrument.InstrumentCatalog
fun main() {
 val cases = listOf(
 Triple("synth.lead_brass", "attack", 2000.0 to 6000.0),
 Triple("edrums.snare", "attack", 3000.0 to 6000.0),
 Triple("drums.snare", "attack", 3000.0 to 6000.0),
 Triple("edrums.hihat", "rhythm", 3000.0 to 7000.0),
 Triple("drums.hihat", "rhythm", 3000.0 to 7000.0),
 Triple("edrums.cymbal", "attack", 3000.0 to 7000.0),
 Triple("drums.overhead", "stick", 3000.0 to 7000.0))
 var mismatches=0
 for ((id,rid,expected) in cases) {
  val r=InstrumentCatalog.find(id)!!.regions.single { it.id==rid }
  check(r.symptomTags.isNotEmpty())
  val actual=r.range.lowHz to r.range.highHz
  if(actual != expected) mismatches++
  println("$id/$rid symptom expected=$expected actual=$actual match=${actual==expected}")
 }
 println("SPEC_BOUNDARY_MISMATCHES=$mismatches/${cases.size}")
 check(mismatches==7)
}
