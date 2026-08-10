package yunkil

import yunkil.doc.*
import yunkil.fabricacion.*
import yunkil.kernel.*
import kotlin.test.Test

class ScratchTest {
    private val perfil = PerfilFabricacion.PREDETERMINADO
    private fun malo(nodo: SdfNode, r: Float): Boolean =
        AnalizadorFdm(nodo, perfil, resolucion = r).analizar()
            .hallazgos.any { it.severidad == Severidad.FALLARA }

    @Test fun mira() {
        val cupon = CuponDeCalibracion.documento(perfil).compilar()!!
        println("cupón sugerida=${yunkil.malla.resolucionSugerida(cupon)}")
        val res = (20..70 step 5).map { it / 100f }
        println("cupón: " + res.joinToString(" ") { "$it=${if (malo(cupon, it)) "MAL" else "ok"}" })

        // Caso mínimo: una esfera sola.
        val esfera = Esfera(10f)
        println("esfera: " + res.joinToString(" ") { "$it=${if (malo(esfera, it)) "MAL" else "ok"}" })

        // Caso mínimo: un cilindro solo.
        val cil = Cilindro(4f, 14f, 0f)
        println("cilindro: " + res.joinToString(" ") { "$it=${if (malo(cil, it)) "MAL" else "ok"}" })

        // Caso mínimo: una caja con un taladro.
        val placa = Diferencia(Caja(Vec3(20f, 3f, 12f), 0f), Cilindro(4.2f, 8f, 0f), 0f)
        println("placa+taladro: " + res.joinToString(" ") { "$it=${if (malo(placa, it)) "MAL" else "ok"}" })
    }
}
