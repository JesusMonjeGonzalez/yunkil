package yunkil.doc

import yunkil.kernel.Vec3

/**
 * Una cara de la que se puede tirar.
 *
 * Los nombres son los mismos que usa `yunkil.ia.Cara` a propósito —«arriba» es el mismo
 * arriba para la persona, para el modelo y para el gesto—, pero son cosas distintas y por
 * eso no son el mismo tipo: aquella es el vocabulario con el que un modelo dice «la tapa
 * va encima del cuerpo», y esta es un mando físico sobre una primitiva.
 *
 * `CONTORNO` no es una cara sino la envolvente: tirar de la pared de un cilindro cambia
 * su radio, y un radio crece a los dos lados a la vez.
 */
enum class Asa(val etiqueta: String) {
    DERECHA("derecha"),
    IZQUIERDA("izquierda"),
    ARRIBA("arriba"),
    ABAJO("abajo"),
    DELANTE("delante"),
    DETRAS("detrás"),
    CONTORNO("contorno");

    /** Dirección hacia fuera, en el espacio local de la pieza. */
    val haciaFuera: Vec3
        get() = when (this) {
            DERECHA -> Vec3(1f, 0f, 0f)
            IZQUIERDA -> Vec3(-1f, 0f, 0f)
            ARRIBA -> Vec3(0f, 1f, 0f)
            ABAJO -> Vec3(0f, -1f, 0f)
            DELANTE -> Vec3(0f, 0f, 1f)
            DETRAS -> Vec3(0f, 0f, -1f)
            CONTORNO -> Vec3.ZERO
        }

    companion object {
        /**
         * El asa cuya dirección se parece más a [normal], que llega en el espacio local
         * de la pieza.
         *
         * Es lo que convierte el impacto del picking en el mando que hay que mover. Se
         * elige por producto escalar y no por la componente mayor: en una cara inclinada
         * —el lateral de un cono, un chaflán— la componente mayor puede ser la de un eje
         * que ni siquiera tiene asa.
         *
         * Si ninguna cara plana se parece lo suficiente y el tipo tiene contorno, es el
         * contorno: es el caso de la pared curva de un cilindro, cuya normal no se
         * parece a ningún eje.
         */
        fun masParecidaA(normal: Vec3, entre: Set<Asa>): Asa? {
            var mejor: Asa? = null
            var mejorParecido = 0.5f
            for (asa in entre) {
                if (asa == CONTORNO) continue
                val d = asa.haciaFuera.let { it.x * normal.x + it.y * normal.y + it.z * normal.z }
                if (d > mejorParecido) {
                    mejorParecido = d
                    mejor = asa
                }
            }
            return mejor ?: CONTORNO.takeIf { it in entre }
        }
    }
}

/**
 * Qué parámetro gobierna cada cara, por tipo de pieza.
 *
 * Se declara aquí por el mismo motivo que `TipoPieza.parametros`: el gesto se resuelve
 * desde la declaración, así que añadir una primitiva al kernel no obliga a tocar el
 * código del arrastre. Que sea un mapa y no una lista hace imposible declarar dos asas
 * para la misma cara, que sería un mando con dos efectos.
 *
 * Las operaciones no tienen asas: una unión no es una superficie, es una regla. La
 * `MALLA` tampoco: su forma son cien mil triángulos y no hay número que mover; se acota y
 * se escala, no se empuja.
 */
val TipoPieza.asas: Map<Asa, String>
    get() = when (this) {
        TipoPieza.ESFERA -> mapOf(Asa.CONTORNO to "radio")

        TipoPieza.CAJA -> mapOf(
            Asa.DERECHA to "anchura",
            Asa.IZQUIERDA to "anchura",
            Asa.ARRIBA to "altura",
            Asa.ABAJO to "altura",
            Asa.DELANTE to "profundidad",
            Asa.DETRAS to "profundidad",
        )

        TipoPieza.CILINDRO -> mapOf(
            Asa.ARRIBA to "altura",
            Asa.ABAJO to "altura",
            Asa.CONTORNO to "radio",
        )

        // Un tronco de cono tiene dos radios distintos, así que su pared no obedece a un
        // solo número: tirar de ella no tiene respuesta única y se queda fuera.
        TipoPieza.CONO -> mapOf(
            Asa.ARRIBA to "altura",
            Asa.ABAJO to "altura",
        )

        TipoPieza.CAPSULA -> mapOf(
            Asa.ARRIBA to "altura",
            Asa.ABAJO to "altura",
            Asa.CONTORNO to "radio",
        )

        TipoPieza.EXTRUSION -> mapOf(
            Asa.ARRIBA to "altura",
            Asa.ABAJO to "altura",
        )

        // El contorno de un toro son dos radios acoplados y el de una revolución o un
        // barrido sale del perfil: en los tres casos el gesto sería ambiguo.
        TipoPieza.TORO,
        TipoPieza.REVOLUCION,
        TipoPieza.BARRIDO,
        TipoPieza.MALLA,
        TipoPieza.ESCULTURA,
        // La forma de un cable son sus puntos; no hay un número al que tirar.
        TipoPieza.CABLE,
        TipoPieza.UNION,
        TipoPieza.DIFERENCIA,
        TipoPieza.INTERSECCION,
        TipoPieza.VACIADO,
        TipoPieza.DESFASE,
        TipoPieza.SIMETRIA,
        TipoPieza.REPETICION,
        TipoPieza.REPETICION_CIRCULAR,
        -> emptyMap()
    }

/**
 * Producto de las escalas desde esta pieza hasta [id], ambas incluidas.
 *
 * Hace falta para que un arrastre expresado en milímetros del mundo se convierta en el
 * cambio de cota correcto: dentro de un grupo al doble, 10 mm de pantalla son 5 de
 * parámetro. Sin descontarlo, la cara se despegaría del cursor en cuanto hay una escala
 * por medio.
 */
fun Pieza.escalaHasta(id: String): Float? {
    if (this.id == id) return transform.scale
    for (h in hijos) {
        val resto = h.escalaHasta(id) ?: continue
        return transform.scale * resto
    }
    return null
}
