package yunkil.ia

import yunkil.fabricacion.InformeDeFabricacion
import yunkil.fabricacion.Regla

/**
 * El veredicto sobre un plan **ya aplicado en un banco de pruebas**.
 *
 * Existe porque validar el esquema no es validar la pieza. `Interprete` responde a
 * «¿esto se puede ejecutar?»; esto responde a «¿lo que sale de ejecutarlo se
 * sostiene?». Un modelo que solo recibe la primera respuesta modela a ciegas: sus
 * planes son JSON impecable describiendo tapas que flotan, y nadie se lo dice.
 *
 * Los motivos van en castellano llano y con la medida dentro, porque es lo que
 * vuelve al modelo en la ronda de corrección, y un modelo corrige bien cuando se le
 * dice *qué* midió mal y no que estuvo mal.
 */
data class RevisionDePlan(
    val motivos: List<String>,
    val informe: InformeDeFabricacion?,
) {
    val aceptable: Boolean get() = motivos.isEmpty()

    /** Los motivos como se le entregan al modelo. Vacío cuando el plan pasa. */
    val informeParaModelo: String
        get() = if (motivos.isEmpty()) {
            ""
        } else {
            motivos.joinToString("\n") { "- $it" }
        }

    companion object {
        /** Un plan que ni siquiera llegó a aplicarse. */
        fun noAplicable(motivo: String) = RevisionDePlan(listOf(motivo), null)

        /**
         * Reglas cuyo incumplimiento **el modelo puede arreglar rehaciendo el plan**.
         *
         * La distinción no es de severidad sino de naturaleza, y costó descubrirlo:
         * filtrando solo por `FALLARA` se rechazaban una esfera por 18 mm² de
         * material en el aire, un toro por 1 mm² y un cono por tener punta. Ninguna
         * de esas tres cosas es un plan mal hecho —son piezas que piden soporte, o
         * rasgos inherentes a la forma— y devolvérselas al modelo lo manda a
         * deformar la geometría para esquivar algo que no tiene arreglo geométrico,
         * quemando de paso tres rondas de inferencia por pieza redonda.
         *
         * Un voladizo se resuelve con soportes o girando la pieza, y el analizador ya
         * le ofrece esos arreglos al usuario con un botón. Una pared de 0,3 mm, en
         * cambio, no se arregla ni con soportes ni girando: solo cambiando la cota.
         * Eso es lo que vuelve al modelo.
         */
        private val DE_MODELADO = setOf(
            Regla.GROSOR_PARED,
            Regla.DETALLE_MINIMO,
        )

        /**
         * Fracción de la superficie a partir de la cual un rasgo deja de ser un borde.
         *
         * Sale de dos medidas reales, no de la intuición. La punta de un cono da un
         * `DETALLE_MINIMO` que afecta a 26 mm² de 2771, un 0,9 %: es la punta, y no
         * hay nada que corregir. Una chapa de 0,3 mm da un aviso de pared sobre 3200
         * mm² de 3280, un 97,6 %: ahí la pieza *entera* es el error. Entre 0,9 % y
         * 97,6 % cabe cualquier umbral; el 10 % está lejos de los dos.
         */
        private const val FRACCION_QUE_DELATA = 0.10f

        /**
         * Los motivos que el modelo puede arreglar rehaciendo el plan.
         *
         * El filtro original miraba solo la severidad, y con eso quedaba justo al
         * revés de lo que hacía falta: bloqueaba el cono —`FALLARA` sobre el 0,9 % de
         * la superficie— y dejaba pasar la chapa de 0,3 mm, que sale como `PROBABLE`
         * porque el analizador reserva `FALLARA` para lo que ha medido con certeza.
         * Para decidir si un plan hay que rehacerlo importa más *cuánta pieza* está
         * mal que cómo de seguro esté el analizador de que fallará.
         *
         * `VOLUMEN_DE_IMPRESION` va aparte porque no tiene fracción que valer: o cabe
         * en la máquina o no cabe, y si no cabe hay que rehacerlo con otras cotas.
         */
        fun de(informe: InformeDeFabricacion): RevisionDePlan {
            val superficie = informe.metricas.areaSuperficie
            val motivos = ArrayList<String>()

            // Lo primero, porque es lo que decide si la pieza llega a existir como
            // archivo. Un sólido puede estar perfecto en el campo —piezas unidas,
            // restas que cortan, paredes de sobra— y dar una malla abierta que
            // ningún laminador acepta. Sin esta comprobación el plan se aprobaba y
            // el fallo aparecía al exportar, cuando ya no hay ronda de corrección.
            informe.topologia?.let { topologia ->
                if (!topologia.esCerrada) {
                    motivos.add(
                        "La malla de esta pieza no cierra: quedan ${topologia.aristasAbiertas} " +
                            "aristas abiertas y no se puede exportar a STL. Suele pasar en las " +
                            "esquinas entrantes de un contorno; prueba a redondearlas o a montar " +
                            "la pieza combinando primitivas en vez de con un contorno en punta.",
                    )
                }
            }

            motivos += informe.hallazgos
                .filter { hallazgo ->
                    when {
                        hallazgo.regla == Regla.VOLUMEN_DE_IMPRESION -> true
                        hallazgo.regla !in DE_MODELADO -> false
                        superficie <= 0f -> false
                        else -> hallazgo.areaAfectada / superficie >= FRACCION_QUE_DELATA
                    }
                }
                .map { "${it.titulo}: ${it.detalle}" }
            return RevisionDePlan(motivos, informe)
        }
    }
}
