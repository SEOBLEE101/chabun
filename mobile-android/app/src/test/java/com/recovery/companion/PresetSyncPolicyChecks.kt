package com.recovery.companion

object PresetSyncPolicyChecks {
    @JvmStatic fun main(args: Array<String>) {
        var count=0
        fun verify(value: Boolean) { check(value); count++ }
        fun rejects(value: String) { verify(runCatching { PresetSyncPolicy.origin(value) }.isFailure) }
        verify(PresetSyncPolicy.origin("https://127.0.0.1:9447") == "https://127.0.0.1:9447")
        verify(PresetSyncPolicy.origin("https://localhost:9447/") == "https://localhost:9447")
        verify(PresetSyncPolicy.origin("https://10.0.2.2:9447") == "https://10.0.2.2:9447")
        listOf("http://127.0.0.1:9447","https://127.0.0.1:9443","https://example.com:9447","https://localhost.evil:9447","https://user@localhost:9447","https://localhost:9447/path","https://localhost:9447?token=x","https://localhost:9447#x","https://localhost").forEach(::rejects)
        verify(!PresetSyncPolicy.accepts(1,2,"a","a",3,4))
        verify(!PresetSyncPolicy.accepts(2,2,"a","a",4,3))
        verify(PresetSyncPolicy.accepts(2,2,"a","a",4,4))
        verify(PresetSyncPolicy.accepts(2,2,"a","a",4,5))
        verify(PresetSyncPolicy.accepts(2,2,"a","b",4,0))
        verify(PresetSyncPolicy.accepts(2,2,null,"b",-1,0))
        println("PresetSyncPolicyChecks: $count checks passed")
        println("AppearancePolicyChecks: " + AppearancePolicyChecks.run() + " checks passed")
        println("PresetRevocationChecks: ${PresetRevocationChecks.run()} checks passed")
    }
}
