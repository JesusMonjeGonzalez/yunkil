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
tasks.register<JavaExec>("banco") {
    group = "verification"
    description = "Corre peticiones reales contra el modelo local y mide qué sale"
    val compilacion = kotlin.jvm().compilations.getByName("main")
    dependsOn(compilacion.compileTaskProvider)
    classpath(compilacion.output.allOutputs, compilacion.runtimeDependencyFiles)
    mainClass.set("yunkil.tools.BancoKt")
    // El banco no falla la build: que un modelo local acierte 6 de 8 es un dato, no un
    // error de compilación, y encadenarlo a `check` dejaría el proyecto rojo por algo
    // que depende de si el stack está arriba.
    isIgnoreExitValue = true
}

tasks.register<JavaExec>("volcarParidad") {
    group = "verification"
    description = "Genera shaders y distancias de referencia en build/paridad"
    val compilacion = kotlin.jvm().compilations.getByName("main")
    dependsOn(compilacion.compileTaskProvider)
    classpath(compilacion.output.allOutputs, compilacion.runtimeDependencyFiles)
    mainClass.set("yunkil.tools.VolcadoKt")
    args(layout.buildDirectory.dir("paridad").get().asFile.absolutePath)
}

tasks.register<JavaExec>("bancoDeHilo") {
    group = "verification"
    description = "Mide si la memoria de la conversación mejora la corrección del segundo turno"
    val compilacion = kotlin.jvm().compilations.getByName("main")
    dependsOn(compilacion.compileTaskProvider)
    classpath(compilacion.output.allOutputs, compilacion.runtimeDependencyFiles)
    mainClass.set("yunkil.tools.BancoDeHiloKt")
    // Igual que `banco`: que el hilo aporte 2 de 6 es un dato, no un error de build.
    isIgnoreExitValue = true
}

tasks.register<JavaExec>("verMalla") {
    group = "verification"
    description = "Exporta un demo a STL, lo reimporta, lo hornea y dibuja las dos versiones"
    val compilacion = kotlin.jvm().compilations.getByName("main")
    dependsOn(compilacion.compileTaskProvider)
    classpath(compilacion.output.allOutputs, compilacion.runtimeDependencyFiles)
    mainClass.set("yunkil.tools.VerMallaKt")
}
