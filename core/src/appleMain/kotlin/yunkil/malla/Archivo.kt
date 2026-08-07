package yunkil.malla

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.remove
import platform.posix.rename

@OptIn(ExperimentalForeignApi::class)
actual fun escribirArchivo(ruta: String, datos: ByteArray): Boolean {
    if (datos.isEmpty()) return false
    val temporal = "$ruta.tmp"
    remove(temporal)
    val archivo = fopen(temporal, "wb") ?: return false
    val completo = try {
        val escritos = datos.usePinned { fijado ->
            fwrite(fijado.addressOf(0), 1u, datos.size.toULong(), archivo)
        }
        escritos.toInt() == datos.size
    } finally {
        fclose(archivo)
    }
    if (!completo) {
        remove(temporal)
        return false
    }
    if (rename(temporal, ruta) != 0) {
        remove(temporal)
        return false
    }
    return true
}

@OptIn(ExperimentalForeignApi::class)
actual fun anadirLinea(ruta: String, linea: String): Boolean {
    val archivo = fopen(ruta, "a") ?: return false
    return try {
        fputs(linea + "\n", archivo) >= 0
    } finally {
        fclose(archivo)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun leerArchivo(ruta: String): ByteArray? {
    val archivo = fopen(ruta, "rb") ?: return null
    return try {
        fseek(archivo, 0, SEEK_END)
        val tamano = ftell(archivo)
        if (tamano <= 0L) return null
        fseek(archivo, 0, SEEK_SET)
        val datos = ByteArray(tamano.toInt())
        val leidos = datos.usePinned { fijado ->
            fread(fijado.addressOf(0), 1u, tamano.toULong(), archivo)
        }
        if (leidos.toLong() == tamano) datos else null
    } finally {
        fclose(archivo)
    }
}
