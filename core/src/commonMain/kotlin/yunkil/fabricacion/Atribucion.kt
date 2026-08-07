package yunkil.fabricacion

import yunkil.doc.Documento
import yunkil.doc.Pieza
import yunkil.doc.TipoPieza
import yunkil.doc.compilar
import yunkil.kernel.SdfNode
import yunkil.kernel.Transform
import yunkil.kernel.Vec3
import kotlin.math.abs

/**
 * Una pieza hoja del documento con su campo ya llevado a coordenadas del mundo.
 *
 * Sirve para lo que ningún laminador puede hacer: saber que ese voladizo lo
 * produjo *ese* cilindro y no otro, y por tanto poder ofrecer una corrección sobre
 * el parámetro concreto que lo causa en vez de un aviso genérico sobre triángulos.
 */
data class HojaEnMundo(
    val piezaId: String,
    val nombre: String,
    val tipo: TipoPieza,
    val nodo: SdfNode,
    /** `true` si la pieza entra restando: su superficie es la pared de un agujero. */
    val esSustraendo: Boolean,
)

/**
 * Aplana el documento a piezas hoja con su transformación acumulada.
 *
 * `esSustraendo` se propaga desde las diferencias: el segundo hijo de una
 * `Diferencia` y todos sus descendientes quitan material. Distinguirlo importa
 * porque la corrección de un agujero demasiado estrecho es *agrandar* el
 * sustraendo, mientras que la de una pared fina es *engordar* el aditivo.
 */
fun Documento.hojasEnMundo(): List<HojaEnMundo> {
    val salida = ArrayList<HojaEnMundo>()

    fun visitar(pieza: Pieza, acumulada: Transform, restando: Boolean) {
        if (!pieza.visible) return
        val aqui = acumulada.componer(pieza.transform)

        if (!pieza.tipo.esOperacion) {
            // Se recompila la primitiva sola y se la lleva al mundo con la
            // transformación acumulada; así su campo es comparable con el global.
            val local = pieza.copy(transform = Transform.IDENTITY).compilar() ?: return
            salida.add(
                HojaEnMundo(
                    piezaId = pieza.id,
                    nombre = pieza.nombre,
                    tipo = pieza.tipo,
                    nodo = if (aqui == Transform.IDENTITY) local
                    else yunkil.kernel.Transformado(local, aqui),
                    esSustraendo = restando,
                ),
            )
            return
        }

        pieza.hijos.forEachIndexed { indice, hijo ->
            val restaAqui = restando xor (pieza.tipo == TipoPieza.DIFERENCIA && indice > 0)
            visitar(hijo, aqui, restaAqui)
        }
    }

    visitar(raiz, Transform.IDENTITY, false)
    return salida
}

/**
 * Devuelve la hoja cuya superficie pasa más cerca de [punto].
 *
 * Un punto de la superficie del modelo pertenece a la primitiva que lo generó, y
 * esa es la que tiene distancia cero ahí. Las demás están más lejos. Comparar
 * valores absolutos del campo basta y no necesita ninguna estructura auxiliar.
 */
fun List<HojaEnMundo>.atribuir(punto: Vec3, tolerancia: Float): HojaEnMundo? {
    var mejor: HojaEnMundo? = null
    var mejorDistancia = tolerancia
    for (hoja in this) {
        val d = abs(hoja.nodo.evaluar(punto))
        if (d < mejorDistancia) {
            mejorDistancia = d
            mejor = hoja
        }
    }
    return mejor
}
