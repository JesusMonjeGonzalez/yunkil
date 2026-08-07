package yunkil.msl

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * El campo horneado como bloque de bytes, para subirlo a una textura de Metal.
 *
 * Hace falta porque un `FloatArray` cruza el puente a Swift como `KotlinFloatArray`,
 * que solo se lee elemento a elemento. Una malla a 192³ son siete millones de
 * muestras, y siete millones de llamadas al puente tardan segundos: la aplicación se
 * congelaría al abrir cada STL.
 *
 * Aquí se hace **una** copia de 28 MB y se entrega un `NSData` que Metal escribe
 * directo en la textura. Vive en `appleMain` porque `NSData` no existe en el resto de
 * plataformas, y como extensión y no como campo de [CampoEnShader] para que la clase
 * común no tenga que saber que existe Metal.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
fun CampoEnShader.comoDatos(): NSData = muestras.usePinned { fijado ->
    NSData.create(
        bytes = fijado.addressOf(0),
        length = (muestras.size * 4).toULong(),
    )
}
