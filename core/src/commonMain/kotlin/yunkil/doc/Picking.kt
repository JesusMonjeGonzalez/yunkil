package yunkil.doc

import yunkil.kernel.Vec3
import yunkil.kernel.normal
import yunkil.kernel.pasoSeguro
import kotlin.math.abs
import kotlin.math.max

/**
 * Lo que hay debajo del cursor.
 *
 * Cruza a Swift por el XCFramework, así que va en flotantes sueltos como el resto de
 * la frontera (`transformDe`, `cotaMinima`): un tipo compuesto más solo añadiría
 * ruido al puente.
 */
data class Impacto(
    val piezaId: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val nx: Float,
    val ny: Float,
    val nz: Float,
    /** Distancia recorrida desde el origen del rayo, en milímetros. */
    val distancia: Float,
)

/** Tope de pasos del trazado. Constante por la misma razón que en el shader. */
private const val MAXIMO_DE_PASOS = 256

/**
 * Trazado del rayo contra el campo del documento.
 *
 * Se hace en CPU y no en el shader a propósito. `evaluar` es la verdad de referencia
 * del sistema: si el picking se resolviera en la GPU, señalar podría discrepar de lo
 * que mide el analizador y de lo que sale por el exportador, y habría que sostener un
 * segundo invariante de paridad para algo que solo ocurre cuando alguien hace clic.
 * De paso funciona sobre una `MALLA` horneada, que el generador de MSL todavía no
 * sabe emitir. Un clic son unos cientos de evaluaciones: se paga en microsegundos.
 */
fun Documento.impactar(origen: Vec3, direccion: Vec3): Impacto? {
    val nodo = compilar() ?: return null

    val largo = direccion.length()
    if (largo < 1e-6f || !largo.isFinite()) return null
    val dir = direccion / largo

    val cotas = nodo.cotas()
    val radio = max(cotas.radius, 1e-3f)
    // El avance sale de la propia distancia, así que el criterio de parada también
    // tiene que escalar con la pieza: un épsilon fijo o no llega nunca en una pieza de
    // 400 mm o se pasa de largo en una de 2.
    val epsilon = (radio * 1e-4f).coerceIn(1e-4f, 0.01f)
    val alcance = (cotas.center - origen).length() + radio * 2f

    // El mismo freno que el shader, y por el mismo motivo. Con una brocha de alisado o
    // de arrastre el campo ya no es una distancia verdadera: avanzarlo entero se salta
    // la superficie. En pantalla eso sería un agujero; aquí sería peor —señalar sobre la
    // zona esculpida devolvería el punto de detrás, y la brocha siguiente caería ahí—.
    val freno = nodo.pasoSeguro()

    var t = 0f
    repeat(MAXIMO_DE_PASOS) {
        val p = origen + dir * t
        val d = nodo.evaluar(p)
        if (d < epsilon) {
            val n = nodo.normal(p, max(epsilon, 1e-3f))
            val pieza = atribuir(p) ?: return null
            return Impacto(pieza, p.x, p.y, p.z, n.x, n.y, n.z, t)
        }
        t += max(d * freno, epsilon)
        if (t > alcance) return null
    }
    return null
}

/**
 * De qué pieza es la superficie que pasa por [punto].
 *
 * No es una política, es un hecho geométrico: se compara el contorno de cada pieza
 * —el suyo, con los modificadores de sus padres aplicados— y gana aquella cuyo
 * contorno pasa por ese punto. De ahí sale sin escribir ninguna excepción lo que uno
 * espera al pinchar la pared de un agujero: se selecciona el taladro, y su diámetro
 * aparece en el inspector. Un laminador no puede hacer eso porque solo tiene
 * triángulos; aquí la respuesta está en el árbol.
 */
fun Documento.atribuir(punto: Vec3): String? {
    val cotas = compilar()?.cotas() ?: return null
    // Con acuerdo (`fusion`) la superficie visible no cae exactamente sobre ninguno de
    // los dos contornos, así que la holgura no puede ser cero.
    val holgura = max(0.25f, cotas.radius * 0.02f)

    var mejor: String? = null
    var mejorDistancia = Float.MAX_VALUE

    for ((pieza, _) in raiz.aplanar()) {
        if (pieza.tipo.esOperacion) continue
        val campo = raiz.solo(pieza.id)?.compilar() ?: continue
        val d = abs(campo.evaluar(punto))
        if (d < mejorDistancia) {
            mejorDistancia = d
            mejor = pieza.id
        }
    }

    return if (mejorDistancia <= holgura) mejor else null
}

/**
 * El árbol podado a la única rama que lleva hasta [id].
 *
 * Es la maquinaria de la atribución. Podar en vez de compilar la pieza suelta importa:
 * una esfera dentro de un `VACIADO` no tiene su superficie donde está su contorno, sino
 * a medio grosor de él, y lo mismo pasa con la copia reflejada de una `SIMETRIA` o con
 * las de una `REPETICION`. Pasando por los mismos padres, el contorno que se compara es
 * el que de verdad se ve.
 */
fun Pieza.solo(id: String): Pieza? {
    if (this.id == id) return this
    for (h in hijos) {
        val rama = h.solo(id) ?: continue
        return copy(hijos = listOf(rama))
    }
    return null
}
