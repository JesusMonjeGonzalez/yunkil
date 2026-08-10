package yunkil.imagen

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import yunkil.doc.Editor
import yunkil.ia.PlanDeModelado

/**
 * Las cuatro vistas de un plan como bloque de bytes, listas para mandárselas al modelo
 * con visión.
 *
 * Existe por lo mismo que `CampoEnShader.comoDatos`: un `ByteArray` cruza el puente como
 * `KotlinByteArray` y solo se lee elemento a elemento. Un PNG de 640×640 son unos cien
 * mil bytes, y cien mil llamadas al puente por cada propuesta es un peaje que no hay
 * ninguna razón para pagar cuando una sola copia lo resuelve.
 *
 * Devuelve `null` con la misma regla que el resto del crítico visual: si el plan no se
 * puede aplicar o no deja geometría, no hay nada que enseñar y no pasa nada.
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
fun Editor.vistasDelPlanComoDatos(
    plan: PlanDeModelado,
    lado: Int = 320,
    nombrePerfil: String? = null,
): NSData? {
    val png = vistasDelPlan(plan, lado, nombrePerfil) ?: return null
    return png.usePinned { fijado ->
        NSData.create(bytes = fijado.addressOf(0), length = png.size.toULong())
    }
}
