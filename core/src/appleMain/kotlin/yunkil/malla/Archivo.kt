package yunkil.malla

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

@OptIn(ExperimentalForeignApi::class)
actual fun escribirArchivo(ruta: String, datos: ByteArray): Boolean {
    if (datos.isEmpty()) return false
    val archivo = fopen(ruta, "wb") ?: return false
    return try {
        val escritos = datos.usePinned { fijado ->
            fwrite(fijado.addressOf(0), 1u, datos.size.toULong(), archivo)
        }
        escritos.toInt() == datos.size
    } finally {
        fclose(archivo)
    }
}
