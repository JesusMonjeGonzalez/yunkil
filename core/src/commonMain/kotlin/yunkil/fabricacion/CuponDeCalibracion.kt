package yunkil.fabricacion

import yunkil.doc.Documento
import yunkil.doc.Pieza
import yunkil.doc.TipoPieza
import yunkil.kernel.Transform
import yunkil.kernel.Vec3

/** Una estación del cupón: un agujero con la holgura que se está probando. */
data class EstacionDeCupon(val indice: Int, val holgura: Float) {
    fun diametro(nominal: Float): Float = nominal + 2f * holgura
}

/**
 * La probeta que convierte la promesa de Yunkil en un dato de tu máquina.
 *
 * Yunkil garantiza que la geometría exportada **tiene la holgura declarada**. Lo que no
 * puede garantizar es que esa holgura sea la buena, porque eso depende de cuánto engorda
 * tu impresora al depositar el plástico, y eso solo lo dice la impresora. Por eso
 * [OrigenDelPerfil.CALIBRADO] llevaba desde el principio en el enum sin que nada lo
 * produjera: era un estado inalcanzable, y una etiqueta que no se puede alcanzar es una
 * etiqueta que no significa nada.
 *
 * ### Por qué así y no de otra manera
 *
 * Una placa con agujeros escalonados y **un solo pasador** de la medida nominal. Se
 * imprime, se prueba el pasador agujero por agujero y el primero en el que entra da la
 * holgura real. La alternativa —una fila de pasadores de distintas medidas contra un
 * agujero— mediría dos cosas a la vez: cuánto engorda el agujero y cuánto engorda el
 * pasador, y no habría forma de despejar ninguna. Con un pasador único, todo lo que varía
 * está en la placa.
 *
 * Las estaciones **no llevan el número grabado**, y no por descuido: el texto paramétrico
 * no existe todavía en el núcleo. Lo que lleva la placa es una ranura en el extremo más
 * apretado, y las estaciones se cuentan desde ahí. Una marca que se ve desde cualquier
 * ángulo es más fiable que un número de 3 mm impreso en FDM, que a esa escala se lee mal
 * aunque exista.
 *
 * El cupón se comprueba contra el analizador del perfil que viene a calibrar: una probeta
 * que el propio Yunkil rechazaría mediría los defectos de la probeta y no los de la
 * máquina.
 */
object CuponDeCalibracion {

    /** Diámetro del pasador de referencia. */
    const val NOMINAL = 8f

    /** Salto entre estaciones. Por debajo de esto el dedo no nota la diferencia. */
    const val PASO = 0.05f

    /** Cuántas estaciones lleva la placa. */
    const val ESTACIONES = 8

    /** Cuántas quedan por debajo del valor de fábrica. */
    private const val POR_DEBAJO = 3

    /** Espesor de la placa. Un agujero corto no dice si entra: dice si asoma. */
    private const val ESPESOR = 6f

    /**
     * Las holguras que se van a probar, escalonadas alrededor de la que trae el perfil.
     *
     * Centradas en el valor de fábrica porque es la mejor estimación de partida que hay:
     * empezar en cero gastaría media placa en agujeros que no entran nunca.
     */
    fun estaciones(perfil: PerfilFabricacion): List<EstacionDeCupon> {
        val primera = maxOf(PASO, perfil.holguraEncaje - POR_DEBAJO * PASO)
        return (0 until ESTACIONES).map { i ->
            EstacionDeCupon(indice = i + 1, holgura = primera + i * PASO)
        }
    }

    fun nombreDeEstacion(estacion: EstacionDeCupon): String = "Estación ${estacion.indice}"

    /** Separación entre centros de agujero. Deja pared de sobra entre estación y estación. */
    private fun separacion(perfil: PerfilFabricacion): Float =
        NOMINAL + maxOf(8f, perfil.grosorMinimoPared * 6f)

    /**
     * El cupón entero, listo para exportar y llevar al laminador.
     *
     * La placa y sus agujeros van en una `DIFERENCIA`; el pasador va aparte con un pie que
     * le da base de apoyo. Sin el pie, un cilindro de 8 mm apoya sobre 50 mm² y el propio
     * analizador avisaría de que se despega, lo cual sería un aviso correcto sobre una
     * pieza que Yunkil propone. Que la herramienta se contradiga a sí misma en la primera
     * pieza que te ofrece es la forma más rápida de que dejes de creerle.
     */
    fun documento(perfil: PerfilFabricacion): Documento {
        val estaciones = estaciones(perfil)
        val separacion = separacion(perfil)
        val largo = separacion * (estaciones.size + 1)
        val fondo = NOMINAL + 16f

        val placa = pieza(
            TipoPieza.CAJA, "Placa",
            "anchura" to largo, "altura" to ESPESOR, "profundidad" to fondo, "redondeo" to 1f,
        )

        val primerCentro = -largo * 0.5f + separacion
        val agujeros = estaciones.map { estacion ->
            pieza(
                TipoPieza.CILINDRO, nombreDeEstacion(estacion),
                "radio" to estacion.diametro(NOMINAL) * 0.5f,
                "altura" to ESPESOR * 2f,
                "redondeo" to 0f,
            ).copy(
                transform = Transform.IDENTITY.copy(
                    translation = Vec3(primerCentro + separacion * (estacion.indice - 1), 0f, 0f),
                ),
            )
        }

        // La marca del extremo apretado: una ranura pasante junto a la estación 1. Se
        // cuenta desde ella, así que basta con que se distinga, no con que se lea.
        val marca = pieza(
            TipoPieza.CAJA, "Marca del extremo apretado",
            "anchura" to 2f, "altura" to ESPESOR * 2f, "profundidad" to fondo * 0.5f, "redondeo" to 0f,
        ).copy(
            transform = Transform.IDENTITY.copy(
                translation = Vec3(primerCentro - separacion * 0.5f, 0f, 0f),
            ),
        )

        val cuerpo = Pieza.nueva(TipoPieza.DIFERENCIA, "Placa de holguras")
            .copy(hijos = listOf(placa) + agujeros + marca)

        val pie = pieza(
            TipoPieza.CILINDRO, "Pie del pasador",
            "radio" to NOMINAL, "altura" to 1.6f, "redondeo" to 0f,
        ).copy(transform = Transform.IDENTITY.copy(translation = Vec3(0f, -ESPESOR * 0.5f + 0.8f, 0f)))

        val vastago = pieza(
            TipoPieza.CILINDRO, "Pasador",
            "radio" to NOMINAL * 0.5f, "altura" to 14f, "redondeo" to 0f,
        ).copy(
            // El vástago se hunde medio milímetro en el pie en vez de apoyar en su cara.
            // Dos caras exactamente coincidentes en una unión son el caso que peor lleva
            // el contorneado: el certificado detectaba la superficie cruzándose consigo
            // misma a unas resoluciones sí y a otras no.
            transform = Transform.IDENTITY.copy(
                translation = Vec3(0f, -ESPESOR * 0.5f + 1.6f + 7f - 0.5f, 0f),
            ),
        )

        val pasador = Pieza.nueva(TipoPieza.UNION, "Pasador de referencia")
            .copy(
                hijos = listOf(pie, vastago),
                transform = Transform.IDENTITY.copy(
                    translation = Vec3(0f, 0f, fondo * 0.5f + NOMINAL + 6f),
                ),
            )

        val raiz = Pieza.nueva(TipoPieza.UNION, "Cupón de calibración")
            .copy(hijos = listOf(cuerpo, pasador))
        return Documento(raiz = raiz, seleccionado = raiz.id)
    }

    private fun pieza(tipo: TipoPieza, nombre: String, vararg cotas: Pair<String, Float>): Pieza =
        Pieza.nueva(tipo, nombre).let { it.copy(parametros = it.parametros + cotas.toMap()) }
}
