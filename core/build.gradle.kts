plugins {
    kotlin("multiplatform") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

kotlin {
    jvmToolchain(21)

    jvm()

    listOf(macosArm64(), iosArm64(), iosSimulatorArm64()).forEach { objetivo ->
        objetivo.binaries.framework {
            baseName = "YunkilCore"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

/** Vuelca los casos de paridad para que el arnés de Metal los verifique. */
tasks.register<JavaExec>("volcarParidad") {
    group = "verification"
    description = "Genera shaders y distancias de referencia en build/paridad"
    val compilacion = kotlin.jvm().compilations.getByName("main")
    dependsOn(compilacion.compileTaskProvider)
    classpath(compilacion.output.allOutputs, compilacion.runtimeDependencyFiles)
    mainClass.set("yunkil.tools.VolcadoKt")
    args(layout.buildDirectory.dir("paridad").get().asFile.absolutePath)
}
