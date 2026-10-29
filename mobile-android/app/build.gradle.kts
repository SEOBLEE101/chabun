import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val localTlsCa = providers.gradleProperty("localTlsCa").orNull
// 공모전 제출용 서명 설정 파일(저장소 밖). 없으면 submission 빌드는 서명되지 않는다.
val chabunSigning = providers.gradleProperty("chabunSigning").orNull
android {
    namespace = "com.recovery.companion"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.recovery.companion"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appLabel"] = "차분"
    }
    chabunSigning?.let { path ->
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
        getByName("debug") {
            // 새 디자인 확인용: 기존 com.recovery.companion 설치를 건드리지 않고 따로 설치된다.
            applicationIdSuffix = ".redesign"
            versionNameSuffix = "-redesign"
            manifestPlaceholders["appLabel"] = "차분 새 디자인"
        }
        // 공모전 제출용 release. 이 PC의 다른 설치와 겹치지 않게 패키지 끝에 .chabun을 붙이고,
        // -PchabunSigning=<서명 설정 파일>이 있으면 그 키로 서명한다.
        create("submission") {
            initWith(getByName("release"))
            applicationIdSuffix = ".chabun"
            matchingFallbacks += listOf("release")
            signingConfigs.findByName("submission")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    if (localTlsCa != null) {
        require(localTlsCa.isNotBlank()) { "-PlocalTlsCa must name a public CA PEM file." }
        buildTypes.create("localTlsDebug") {
            initWith(buildTypes.getByName("debug"))
            isDebuggable = true
            matchingFallbacks += listOf("debug")
        }
        sourceSets.getByName("localTlsDebug").res.srcDir(layout.buildDirectory.dir("generated/localTlsDebug/res"))
    }
}

if (localTlsCa != null) {
    val caFile = rootProject.file(localTlsCa)
    val generatedResources = layout.buildDirectory.dir("generated/localTlsDebug/res")
    val generateLocalTlsResources = tasks.register("generateLocalTlsResources") {
        inputs.file(caFile)
        outputs.dir(generatedResources)
        doLast {
            val pem = caFile.readText(Charsets.US_ASCII)
            require(Regex("\\A\\s*-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\r\\n]+-----END CERTIFICATE-----\\s*\\z").matches(pem)) {
                "localTlsCa must contain exactly one public certificate PEM, never private keys."
            }
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(pem.byteInputStream(Charsets.US_ASCII)) as X509Certificate
            require(certificate.basicConstraints >= 0) { "localTlsCa must be a CA certificate." }
            require(certificate.keyUsage?.getOrNull(5) != false) { "localTlsCa does not permit certificate signing." }
            certificate.checkValidity()
            val resources = generatedResources.get().asFile
            resources.resolve("raw/pitin_local_ca.pem").apply { parentFile.mkdirs(); writeText(pem, Charsets.US_ASCII) }
            resources.resolve("values/relay_defaults.xml").apply {
                parentFile.mkdirs()
                writeText("""<resources><string name="relay_default_url" translatable="false">https://127.0.0.1:9443</string></resources>""")
            }
            resources.resolve("xml/pitin_local_tls_network_security_config.xml").apply {
                parentFile.mkdirs()
                writeText("""
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
    }
    tasks.configureEach {
        if (name == "preLocalTlsDebugBuild") dependsOn(generateLocalTlsResources)
    }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

tasks.register<JavaExec>("runPresetDomainChecks") {
    group = "verification"
    description = "Runs the pure Kotlin preset/session checks on the build JVM; no Android device."
    dependsOn("compileDebugUnitTestKotlin")
    classpath(files(layout.buildDirectory.dir("tmp/kotlin-classes/debug"), layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")))
    classpath(configurations.getByName("debugUnitTestRuntimeClasspath"))
    mainClass.set("com.recovery.companion.PresetDomainChecks")
}

tasks.register<JavaExec>("runPresetSyncPolicyChecks") {
    group = "verification"
    dependsOn("compileDebugUnitTestKotlin")
    classpath(files(layout.buildDirectory.dir("tmp/kotlin-classes/debug"), layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")))
    classpath(configurations.getByName("debugUnitTestRuntimeClasspath"))
    mainClass.set("com.recovery.companion.PresetSyncPolicyChecks")
}

tasks.register<JavaExec>("runPresetSyncResponseChecks") {
    group = "verification"
    dependsOn("compileDebugUnitTestKotlin")
    classpath(files(layout.buildDirectory.dir("tmp/kotlin-classes/debug"), layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")))
    classpath(configurations.getByName("debugUnitTestRuntimeClasspath"))
    mainClass.set("com.recovery.companion.PresetSyncResponseChecks")
}
