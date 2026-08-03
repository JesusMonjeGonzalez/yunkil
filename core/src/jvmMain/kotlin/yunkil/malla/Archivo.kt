package yunkil.malla

import java.io.File

actual fun escribirArchivo(ruta: String, datos: ByteArray): Boolean = try {
    File(ruta).writeBytes(datos)
    true
} catch (e: Exception) {
    false
}
