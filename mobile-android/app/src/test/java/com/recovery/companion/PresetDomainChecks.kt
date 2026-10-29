package com.recovery.companion

/** Portable checks: compile with PresetDomain.kt; not an APK or Android runtime test. */
object PresetDomainChecks {
    @JvmStatic fun main(args: Array<String>) {
        var checks=0
        fun verify(value: Boolean) { check(value); checks++ }
        fun rejects(action: ()->Unit) { var rejected=false; try { action() } catch(_:IllegalArgumentException) { rejected=true }; verify(rejected) }
        val defaults=Preset.builtins()
        verify(defaults.map { it.id }==listOf("cool_down","quiet_rest","gentle_refresh"))
        verify(defaults.all { it.validated()==it })
        verify(defaults[0].vehicle.tempC==23.5 && defaults[0].home.tempC==25.0 && defaults[0].home.fan=="medium")
        verify(defaults[1].vehicle.seat=="keep" && defaults[2].vehicle.seat=="manual_comfort")
        rejects { defaults[0].copy(home=PresetHome(30.5,"auto","on")).validated() }
        rejects { defaults[0].copy(home=PresetHome(24.2,"auto","on")).validated() }
        rejects { defaults[0].copy(home=PresetHome(Double.NaN,"auto","on")).validated() }
        rejects { defaults[0].copy(vehicle=PresetVehicle(29.0,"low","keep")).validated() }
        rejects { defaults[0].copy(name=" ").validated() }
        verify(Preset.recommendation("skip").first==null)
        verify(Preset.recommendation("warm").first=="cool_down")
        verify(Preset.recommendation("tired").first=="quiet_rest" && Preset.recommendation("tired").second.contains("직접 선택"))
        val session=PresetSession(1000000)
        rejects { session.arm(defaults[0],5,5) }
        session.arm(defaults[0],20,5)
        val first=session.plan!!
        session.update(25,5)
        verify(session.plan!!.id==first.id && session.plan!!.version==2 && session.plan!!.snapshot==first.snapshot)
        rejects { session.arm(defaults[1],10,5) }
        val changed=defaults[0].copy(name="수정",home=PresetHome(28.0,"low","keep"))
        verify(first.snapshot.home.tempC==25.0 && changed.home.tempC==28.0)
        verify(first.executeAt==1000000+15*60000L && first.targetRevision==0L)
        session.advanceToExecution()
        verify(session.plan!!.status=="requested" && session.home.power=="off")
        session.confirmObserved()
        verify(session.plan!!.status=="confirmed_demo" && session.home.tempC==25.0 && session.home.power=="on")
        val revision=session.home.revision
        session.advance(10000)
        verify(session.home.revision==revision)
        session.cancel()
        verify(session.plan!!.status=="cancelled" && session.home.power=="on")
        session.arm(defaults[1],10,5)
        verify(session.plan!!.version==3 && session.plan!!.id!=first.id)
        session.manualOverride(26.0)
        verify(session.plan!!.status=="needs_review")
        session.advance(5*60000L)
        verify(session.home.tempC==26.0)
        session.update(10,5)
        session.setOnline(false)
        session.advanceToExecution()
        verify(session.plan!!.status=="failed")
        session.setOnline(true)
        session.advance(1000)
        verify(session.plan!!.status=="failed")
        session.update(10,5)
        session.advance(6*60000L+1)
        verify(session.plan!!.status=="expired")
        session.update(10,5)
        session.cancel(); session.advance(5*60000L)
        verify(session.plan!!.status=="cancelled")
        val uncertain=PresetSession(1000000)
        uncertain.arm(defaults[0],10,5); uncertain.advanceToExecution()
        verify(uncertain.plan!!.requestedAt!=null && uncertain.plan!!.status=="requested")
        uncertain.setOnline(false); uncertain.confirmObserved()
        verify(uncertain.plan!!.status=="failed")
        rejects { uncertain.update(20,5) }
        uncertain.setOnline(true); uncertain.arm(defaults[0],10,5); uncertain.advanceToExecution()
        uncertain.manualOverride(26.0)
        verify(uncertain.plan!!.status=="needs_review")
        rejects { uncertain.update(20,5) }
        uncertain.cancel(); uncertain.arm(defaults[1],10,5)
        verify(uncertain.plan!!.requestedAt==null && uncertain.plan!!.status=="armed")
        println("PASS: $checks pure Kotlin preset/session checks. No Android runtime/APK claim.")
    }
}
