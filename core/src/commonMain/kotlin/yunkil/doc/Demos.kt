package yunkil.doc

import yunkil.kernel.Axis
import yunkil.kernel.Quat
import yunkil.kernel.Transform
import yunkil.kernel.Vec3

/**
 * Documentos de ejemplo.
 *
 * Existen para tener algo que abrir al arrancar y para medir el rendimiento sobre
 * piezas con la complejidad de una real, no sobre una esfera suelta. Se construyen
 * con las mismas piezas que crearía el usuario a mano: si un ejemplo no se puede
 * expresar con la interfaz, es que a la interfaz le falta algo.
 */
object ModelosDemo {

    fun soporte(): Documento {
        val base = pieza(
            TipoPieza.VACIADO, "Base hueca", "grosor" to 3f,
            hijos = listOf(
                pieza(
                    TipoPieza.CAJA, "Base",
                    "anchura" to 90f, "altura" to 16f, "profundidad" to 60f, "redondeo" to 4f,
                ),
            ),
        )

        val respaldo = pieza(
            TipoPieza.VACIADO, "Respaldo hueco", "grosor" to 3f,
            transform = Transform(translation = Vec3(0f, 22f, -22f)),
            hijos = listOf(
                pieza(
                    TipoPieza.CAJA, "Respaldo",
                    "anchura" to 90f, "altura" to 60f, "profundidad" to 16f, "redondeo" to 4f,
                ),
            ),
        )

        val refuerzos = pieza(
            TipoPieza.SIMETRIA, "Refuerzos", eje = Axis.X,
            hijos = listOf(
                pieza(
                    TipoPieza.CILINDRO, "Nervio",
                    "radio" to 6f, "altura" to 40f, "redondeo" to 2f,
                    transform = Transform(
                        rotation = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), 0.9f),
                        translation = Vec3(32f, 12f, -10f),
                    ),
                ),
            ),
        )

        val taladros = pieza(
            TipoPieza.REPETICION, "Taladros", "paso" to 26f, eje = Axis.X, cuenta = 3,
            hijos = listOf(
                pieza(TipoPieza.CILINDRO, "Taladro", "radio" to 3.2f, "altura" to 40f),
            ),
        )

        val raiz = pieza(
            TipoPieza.DIFERENCIA, "Soporte", "fusion" to 0f,
            hijos = listOf(
                pieza(TipoPieza.UNION, "Cuerpo", "fusion" to 5f, hijos = listOf(base, respaldo, refuerzos)),
                taladros,
            ),
        )

        return Documento(raiz = raiz, seleccionado = raiz.id)
    }

    /** Pieza mínima, útil para aislar problemas de render. */
    fun esferaSuelta(): Documento {
        val raiz = pieza(
            TipoPieza.UNION, "Modelo", "fusion" to 0f,
            hijos = listOf(pieza(TipoPieza.ESFERA, "Esfera", "radio" to 25f)),
        )
        return Documento(raiz = raiz, seleccionado = raiz.hijos.first().id)
    }

    /** Caso de estrés: muchas copias para medir el techo del raymarcher. */
    fun rejilla(): Documento {
        val celda = pieza(
            TipoPieza.UNION, "Celda", "fusion" to 2f,
            transform = Transform(translation = Vec3(18f, 0f, 0f)),
            hijos = listOf(
                pieza(TipoPieza.ESFERA, "Bola", "radio" to 6f),
                pieza(
                    TipoPieza.CAJA, "Cubo",
                    "anchura" to 10f, "altura" to 10f, "profundidad" to 10f, "redondeo" to 1f,
                ),
            ),
        )

        val raiz = pieza(
            TipoPieza.REPETICION, "Rejilla", "paso" to 20f, eje = Axis.Z, cuenta = 8,
            hijos = listOf(pieza(TipoPieza.SIMETRIA, "Par", eje = Axis.X, hijos = listOf(celda))),
        )
        return Documento(raiz = raiz, seleccionado = raiz.id)
    }

    private fun pieza(
        tipo: TipoPieza,
        nombre: String,
        vararg parametros: Pair<String, Float>,
        transform: Transform = Transform.IDENTITY,
        hijos: List<Pieza> = emptyList(),
        eje: Axis = Axis.X,
        cuenta: Int = 3,
    ): Pieza = Pieza.nueva(tipo, nombre).copy(
        // Se parte de los valores por defecto del tipo para que una clave olvidada
        // no deje la pieza con un cero silencioso.
        parametros = tipo.parametros.associate { it.clave to it.defecto } + parametros.toMap(),
        transform = transform,
        hijos = hijos,
        eje = eje,
        cuenta = cuenta,
    )
}
