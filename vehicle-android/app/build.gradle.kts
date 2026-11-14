import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.io.File
import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile

plugins { id("com.android.application") }

val includePleos = providers.gradleProperty("includePleos").orNull == "true"
val localTlsCaPath = providers.gradleProperty("localTlsCa").orNull
// 공모전 제출용 서명 설정 파일(저장소 밖). 없으면 submission 빌드는 서명되지 않는다.
val chabunSigningPath = providers.gradleProperty("chabunSigning").orNull

abstract class GenerateGleoSchema : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val templateFile: RegularFileProperty
    @get:Input abstract val packageName: Property<String>
    @get:Input abstract val appVersion: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageValue = packageName.get()
        val versionValue = appVersion.get()
        require(packageValue.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        require(versionValue.matches(Regex("[0-9A-Za-z._+-]+")))
        val output = outputDirectory.get().asFile
        output.mkdirs()
        output.resolve("schema.json").writeText(templateFile.get().asFile.readText()
            .replace("__APPLICATION_ID__", packageValue).replace("__VERSION_NAME__", versionValue))
    }
}

abstract class GenerateLocalTlsResources : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val caPem: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val pem = caPem.get().asFile.readText(Charsets.US_ASCII)
        require(!pem.contains("PRIVATE KEY") && pem.contains("-----BEGIN CERTIFICATE-----")) {
            "localTlsCa must contain a public CA certificate only, never a private key."
        }
        val certificates = pem.byteInputStream(Charsets.US_ASCII).use {
            CertificateFactory.getInstance("X.509").generateCertificates(it)
        }
        require(certificates.size == 1 && (certificates.single() as X509Certificate).basicConstraints >= 0) {
            "localTlsCa must contain exactly one X.509 CA certificate."
        }
        val output = outputDirectory.get().asFile
        output.resolve("raw").mkdirs()
        output.resolve("xml").mkdirs()
        output.resolve("raw/pitin_local_ca.pem").writeText(pem, Charsets.US_ASCII)
        output.resolve("xml/network_security_config.xml").writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <network-security-config>
                <base-config cleartextTrafficPermitted="false">
                    <trust-anchors><certificates src="system" /></trust-anchors>
                </base-config>
                <domain-config cleartextTrafficPermitted="false">
                    <domain includeSubdomains="false">localhost</domain>
                    <domain includeSubdomains="false">127.0.0.1</domain>
                    <domain includeSubdomains="false">10.0.2.2</domain>
                    <trust-anchors><certificates src="@raw/pitin_local_ca" /></trust-anchors>
                </domain-config>
            </network-security-config>
        """.trimIndent())
    }
}

val localTlsResources = localTlsCaPath?.let { path ->
    require(File(path).isAbsolute) { "localTlsCa must be an absolute public CA PEM path." }
    tasks.register<GenerateLocalTlsResources>("generateLocalTlsResources") {
        caPem.set(file(path))
        outputDirectory.set(layout.buildDirectory.dir("generated/localTlsDebug/res"))
    }
}

android {
    namespace = "ai.pitin.vehicle"
    compileSdk = 34
    defaultConfig {
        applicationId = "ai.pitin.vehicle"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        resValue("string", "relay_default_origin", "")
        buildConfigField("boolean", "DEMO_START_PARKED", "false")
        // A rest judged on the car's real values (pleos and pleosReadOnly): SafetyGate decides on real parking values.
        buildConfigField("boolean", "LIVE_REST", "false")
        // 차 안 맞추기 on a real car (pleos flavor only): the build also changes cabin values while parked.
        buildConfigField("boolean", "LIVE_CONTROL", "false")
        testInstrumentationRunner = if (providers.gradleProperty("gleoAcceptance").orNull == "true")
            "ai.pitin.vehicle.GleoInstrumentation" else if (providers.gradleProperty("placeNavAcceptance").orNull == "true")
            "ai.pitin.vehicle.PlaceNavigationInstrumentation" else if (providers.gradleProperty("restFlowAcceptance").orNull == "true")
            "ai.pitin.vehicle.RestFlowInstrumentation" else if (providers.gradleProperty("graphiteAcceptance").orNull == "true")
            "ai.pitin.vehicle.GraphiteInstrumentation" else if (localTlsResources != null)
            "ai.pitin.vehicle.TlsTransportInstrumentation" else "ai.pitin.vehicle.GraphiteInstrumentation"
    }
    buildFeatures { buildConfig = true }
    if (localTlsResources != null) testBuildType = "localTlsDebug"
    // -PtestBuildType=redesign tests the redesign package, so the original demo app on an emulator is left alone.
    providers.gradleProperty("testBuildType").orNull?.let { testBuildType = it }
    if (includePleos) useLibrary("android.car", false)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    flavorDimensions += "environment"
    productFlavors {
        create("demo") {
            dimension = "environment"
            applicationIdSuffix = ".demo"
            buildConfigField("boolean", "SIMULATED_VEHICLE", "true")
            resValue("string", "app_name", "차분 · Demo")
        }
        if (includePleos) {
            // Before Vehicle Control opens: a rest on the car's real state, while the person sets the cabin by hand.
            // No CONTROL_CAR_* permission and no cabin actuator, so this build never changes a vehicle value.
            create("pleosReadOnly") {
                dimension = "environment"
                applicationIdSuffix = ".readonly"
                buildConfigField("boolean", "SIMULATED_VEHICLE", "false")
                buildConfigField("boolean", "LIVE_REST", "true")
                resValue("string", "app_name", "차분 · Read only")
            }
            // 차 안 맞추기: changes climate, seat heating/ventilation and ambient light while parked (Pleos SDK).
            create("pleos") {
                dimension = "environment"
                applicationIdSuffix = ".pleos"
                buildConfigField("boolean", "SIMULATED_VEHICLE", "false")
                buildConfigField("boolean", "LIVE_REST", "true")
                buildConfigField("boolean", "LIVE_CONTROL", "true")
                resValue("string", "app_name", "차분 · 차 연결")
            }
        }
    }
    if (includePleos) {
        sourceSets.getByName("pleosReadOnly").java.srcDir("src/pleosCommon/java")
        sourceSets.getByName("pleos").java.srcDir("src/pleosCommon/java")
    }
    chabunSigningPath?.let { path ->
        val file = File(path)
        val p = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
        signingConfigs.create("submission") {
            storeFile = file.resolveSibling(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            isDebuggable = true
            resValue("string", "relay_default_origin", "http://10.0.2.2:8787")
        }
        release { isMinifyEnabled = false; isDebuggable = false }
        // 공모전 제출용: release와 같고(디버그 아님) 데모 차는 주차 상태로 시작한다. 이 PC의 다른 설치와 겹치지 않게
        // 패키지 끝에 .chabun을 붙이고, -PchabunSigning=<서명 설정 파일>이 있으면 그 키로 서명한다.
        create("submission") {
            initWith(getByName("release"))
            applicationIdSuffix = ".chabun"
            matchingFallbacks += "release"
            buildConfigField("boolean", "DEMO_START_PARKED", "true")
            signingConfigs.findByName("submission")?.let { signingConfig = it }
        }
        // Side-by-side review install of the redesign: ai.pitin.vehicle.demo.redesign, label "차분 새 디자인".
        // Existing ai.pitin.vehicle.demo / .readonly installs are untouched.
        create("redesign") {
            initWith(getByName("debug"))
            isDebuggable = true
            applicationIdSuffix = ".redesign"
            matchingFallbacks += "debug"
            resValue("string", "app_name", "차분 새 디자인")
            buildConfigField("boolean", "DEMO_START_PARKED", "true")
        }
        if (localTlsResources != null) {
            create("localTlsDebug") {
                initWith(getByName("debug"))
                isDebuggable = true
                matchingFallbacks += "debug"
                resValue("string", "relay_default_origin", "https://10.0.2.2:9443")
            }
            // 새 디자인 + 로컬 개발 CA(HTTPS 9443/9447). 패키지는 redesign과 같아 그 설치를 바꿔 끼운다.
            create("redesignTls") {
                initWith(getByName("redesign"))
                matchingFallbacks += "debug"
                resValue("string", "relay_default_origin", "https://10.0.2.2:9443")
            }
        }
    }
}

androidComponents.onVariants { variant ->
    val taskSuffix = variant.name.replaceFirstChar { it.uppercaseChar() }
    val schema = tasks.register<GenerateGleoSchema>("generate${taskSuffix}GleoSchema") {
        // The read-only build lists no cabin-changing action (Gleo allows three actions per app).
        templateFile.set(layout.projectDirectory.file(
            if (variant.flavorName == "pleosReadOnly") "src/main/gleo/schema-readonly.json" else "src/main/gleo/schema.json"))
        packageName.set(variant.applicationId)
        appVersion.set(variant.outputs.first().versionName)
        outputDirectory.set(layout.buildDirectory.dir("generated/gleo/${variant.name}/assets"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(schema, GenerateGleoSchema::outputDirectory)
    // The redesign build type names every install the same; keep the car-connected one recognisable.
    if (variant.flavorName == "pleos" && variant.buildType?.startsWith("redesign") == true) {
        variant.resValues.put(variant.makeResValueKey("string", "app_name"),
            com.android.build.api.variant.ResValue("차분 차 연결", null))
    }
}

if (localTlsResources != null) {
    androidComponents.onVariants { variant ->
        if (variant.buildType == "localTlsDebug" || variant.buildType == "redesignTls") {
            variant.sources.res?.addGeneratedSourceDirectory(localTlsResources, GenerateLocalTlsResources::outputDirectory)
        }
    }
}

dependencies {
    if (includePleos) {
        // Exact coordinates verified against the official POM and AAR on 2026-10-24.
        add("pleosReadOnlyImplementation", "ai.pleos.playground:Vehicle:2.3.2")
        add("pleosImplementation", "ai.pleos.playground:Vehicle:2.3.2")
        // NaviHelper is "Ready to Use" (no approval). Coordinates checked against the POM and AAR on 2026-11-14.
        add("pleosReadOnlyImplementation", "ai.pleos.playground:NaviHelper:3.0.5")
        add("pleosImplementation", "ai.pleos.playground:NaviHelper:3.0.5")
    }
}

val compileVehiclePolicyChecks = tasks.register<JavaCompile>("compileVehiclePolicyChecks") {
    source(fileTree("src/main/java/ai/pitin/vehicle") {
        include("SafetyGate.java", "RestSession.java", "PresetSyncPolicy.java", "PresetSyncResponsePolicy.java", "RestGateMessages.java", "GleoActionPolicy.java", "PendingRevocation.java", "AppearancePublisher.java", "AppearanceResponsePolicy.java", "CabinSettings.java", "CabinControl.java", "CabinActuator.java", "RestInterruption.java", "RestPlace.java")
    })
    source(fileTree("../tests") { include("*.java") })
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("vehicle-policy-checks"))
    sourceCompatibility = "17"
    targetCompatibility = "17"
    options.encoding = "UTF-8"
}
val policyChecks = mapOf("runSafetyGateChecks" to "SafetyGateTest",
    "runPresetSyncPolicyChecks" to "PresetSyncPolicyTest", "runRestGateMessageChecks" to "RestGateMessagesTest",
    "runGleoActionChecks" to "GleoActionPolicyTest", "runPendingRevocationChecks" to "PendingRevocationTest",
    "runAppearancePublisherChecks" to "AppearancePublisherTest",
    "runGleoCabinActionChecks" to "GleoCabinActionTest", "runCabinSettingsChecks" to "CabinSettingsTest",
    "runCabinControlChecks" to "CabinControlTest", "runCabinActuatorChecks" to "CabinActuatorTest",
    "runRestInterruptionChecks" to "RestInterruptionTest", "runRestPlaceChecks" to "RestPlaceTest").map { (task, entry) ->
    tasks.register<JavaExec>(task) {
        group = "verification"
        description = "Runs pure Java policy assertions on the build JVM, without Android stubs."
        dependsOn(compileVehiclePolicyChecks)
        classpath(compileVehiclePolicyChecks.flatMap { it.destinationDirectory })
        mainClass.set("ai.pitin.vehicle.$entry")
        enableAssertions = true
        if (entry == "RestGateMessagesTest") args(file("src/main/java/ai/pitin/vehicle/SafetyGate.java").absolutePath)
    }
}
tasks.register("runVehiclePolicyChecks") { group = "verification"; dependsOn(policyChecks) }
