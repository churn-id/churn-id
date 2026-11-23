import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import java.util.Base64
import javax.inject.Inject
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
}

// With -Pcoverage, the debug build is the coverage build, which counts which of its code runs, and the unit tests
// count too. -Pcoverage=<folder> names the folder with a run's coverage files for coverageReport (CONTRIBUTING.md,
// "Building").
val coverage = providers.gradleProperty("coverage")

android {
    namespace = "id.churn"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.churn"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    // For the version code that step 3 records on the router.
    buildFeatures.buildConfig = true

    // For local builds, the upload key comes from the environment and is never
    // committed. Without it, bundleRelease produces an unsigned bundle, as in
    // CI, which signs the bundle in a job of its own (CONTRIBUTING.md).
    val keystorePath: String? = System.getenv("CHURN_KEYSTORE_PATH")

    signingConfigs {
        if (keystorePath != null) {
            create("upload") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("CHURN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CHURN_KEY_ALIAS")
                keyPassword = System.getenv("CHURN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            enableAndroidTestCoverage = coverage.isPresent
            enableUnitTestCoverage = coverage.isPresent
        }
        release {
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Each Bouncy Castle jar carries this file.
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

dependencies {
    implementation("com.hierynomus:sshj:0.41.2")
    // sshj also brings Bouncy Castle's bcpkix and bcutil, which must match
    // bcprov's version. Bouncy Castle's BOM puts all three on one version.
    implementation(platform("org.bouncycastle:bc-jdk18on-bom:1.84"))
    // Comes with sshj; ChurnApp registers its provider.
    implementation("org.bouncycastle:bcprov-jdk18on")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a stub in unit tests.
    testImplementation("org.json:json:20260814")
}

// ChecksTest reads docs/router-tests.md, and RulesTest the rules in README.md, docs/design.md and strings.xml, so a
// change there runs the tests again.
tasks.withType<Test>().configureEach {
    inputs.file("../../docs/router-tests.md")
    inputs.file("../../README.md")
    inputs.file("../../docs/design.md")
    inputs.file("src/main/res/values/strings.xml")
}

/**
 * Gradle's JaCoCo report, of the debug build's classes from the Android Gradle plugin. It leaves out the classes that
 * the plugin generates for the resources' IDs and the build's settings, which the code only reads constants from.
 */
abstract class CoverageReport : JacocoReport() {

    @get:Classpath
    abstract val jars: ListProperty<RegularFile>

    @get:Classpath
    abstract val dirs: ListProperty<Directory>

    @get:Inject
    abstract val archives: ArchiveOperations

    init {
        val generated = listOf("**/R.class", "**/R$*.class", "**/BuildConfig.class")
        classDirectories.from(
            jars.map { it.map { jar -> archives.zipTree(jar).matching { exclude(generated) } } },
            dirs.map { it.map { dir -> dir.asFileTree.matching { exclude(generated) } } },
        )
    }
}

/** Turns the coverage files that a run shared, a line of Base64 for each save (Coverage.kt), into JaCoCo's data. */
abstract class DecodeCoverage : DefaultTask() {

    @get:InputFiles
    abstract val shared: ConfigurableFileCollection

    @get:OutputFile
    abstract val data: RegularFileProperty

    @TaskAction
    fun decode() {
        check(!shared.isEmpty) { "The folder holds no churn-coverage-*.txt files from Share coverage" }
        data.get().asFile.outputStream().use { out ->
            shared.forEach { file ->
                try {
                    file.forEachLine { out.write(Base64.getDecoder().decode(it)) }
                } catch (e: IllegalArgumentException) {
                    throw GradleException("$file holds other lines than Share coverage writes", e)
                }
            }
        }
    }
}

if (coverage.isPresent) {
    apply(plugin = "jacoco")
    // The version that the Android Gradle plugin counts with.
    configure<JacocoPluginExtension> { toolVersion = android.testCoverage.jacocoVersion }
    // CoverageTest then expects JaCoCo's agent in the unit tests (enableUnitTestCoverage).
    tasks.withType<Test>().configureEach { systemProperty("coverage", "true") }

    val decodeCoverage = tasks.register<DecodeCoverage>("decodeCoverage") {
        shared.from(
            coverage.map {
                require(it.isNotEmpty()) { "Name the folder with the coverage files: -Pcoverage=<folder>" }
                fileTree(rootDir.resolve(it)) { include("churn-coverage-*.txt") }
            },
        )
        data = layout.buildDirectory.file("coverage/run.ec")
    }
    tasks.withType<CoverageReport>().configureEach {
        executionData.from(decodeCoverage.flatMap { it.data })
        sourceDirectories.from("src/main/kotlin", "src/debug/kotlin")
        reports.xml.required = true
    }
    val coverageReport = tasks.register<CoverageReport>("coverageReport")
    // The unit tests count against the same classes, so what this report shows as missed, no test reaches.
    val coverageReportWithUnitTests = tasks.register<CoverageReport>("coverageReportWithUnitTests") {
        val unitTests = tasks.named("testDebugUnitTest")
        // executionData(TaskCollection) calls tasks.all, which Gradle refuses while it creates this task.
        executionData(unitTests.get())
        dependsOn(unitTests)
    }
    androidComponents {
        onVariants(selector().withBuildType("debug")) { variant ->
            for (report in listOf(coverageReport, coverageReportWithUnitTests)) {
                variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
                    .use(report)
                    .toGet(ScopedArtifact.CLASSES, CoverageReport::jars, CoverageReport::dirs)
            }
        }
    }
}
