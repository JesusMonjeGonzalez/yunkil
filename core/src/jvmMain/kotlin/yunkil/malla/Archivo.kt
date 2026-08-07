package yunkil.malla

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual fun escribirArchivo(ruta: String, datos: ByteArray): Boolean = try {
    val destino = File(ruta)
    val temporal = File(destino.parentFile ?: File("."), ".${destino.name}.tmp")
    temporal.writeBytes(datos)
    try {
        Files.move(temporal.toPath(), destino.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: Exception) {
        Files.move(temporal.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    true
} catch (e: Exception) {
    false
}

actual fun anadirLinea(ruta: String, linea: String): Boolean = try {
    val destino = File(ruta)
    destino.parentFile?.mkdirs()
    destino.appendText(linea + "\n")
    true
} catch (e: Exception) {
    false
}

actual fun leerArchivo(ruta: String): ByteArray? = try {
    java.io.File(ruta).takeIf { it.isFile }?.readBytes()
} catch (e: Exception) {
    null
}
