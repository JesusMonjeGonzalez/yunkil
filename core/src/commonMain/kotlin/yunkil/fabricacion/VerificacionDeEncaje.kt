package yunkil.fabricacion

import kotlinx.serialization.Serializable
import yunkil.doc.Documento
import yunkil.doc.Encaje
import yunkil.doc.SentidoDeEncaje
import yunkil.doc.aplanar
import yunkil.doc.holguraCon
import yunkil.doc.holguraEfectiva
import yunkil.doc.medidaDe
import yunkil.ia.EjeNombrado
import yunkil.ia.nodoEnMundoDe
import yunkil.kernel.Aabb
import yunkil.kernel.SdfNode
import yunkil.kernel.Vec3
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Redondeo para texto, con coma. Evita `1.7999998 mm` en un aviso que alguien lee. */
private fun redondear(v: Float, decimales: Int): String =
    AnalizadorFdm.redondear(v, decimales)

/**
 * Lo que se ha medido de un encaje declarado, sobre la geometría final.
 *
 * Va al informe **siempre**, cumpla o no. Un encaje que desaparece del informe cuando no
 * se puede medir es indistinguible de uno que se miró y estaba bien, que es exactamente
 * el agujero que hoy tiene la crítica visual y que aquí no se repite.
 */
@Serializable
data class EncajeMedido(
    val piezaId: String,
    val piezaNombre: String,
    val nominal: Float,
    val holguraDeclarada: Float,
    /** La incertidumbre declarada de la medida del mundo, en mm. Cero si no se dijo. */
    val toleranciaDeLaMedida: Float = 0f,
    /** La holgura que de verdad tiene la pieza construida. Negativa si se pisan. */
    val holguraMedida: Float,
    /** Paso de muestreo con el que se midió: la incertidumbre de la cifra anterior. */
    val resolucion: Float,
    val cumple: Boolean,
    /** Por qué no se pudo medir, o `null` si se midió. */
    val motivo: String? = null,
) {
    fun descripcion(): String = when {
        motivo != null -> "«$piezaNombre»: no se pudo medir ($motivo)"
        cumple -> "«$piezaNombre»: ${redondear(holguraMedida, 2)} mm de holgura, " +
            "declarada ${redondear(holguraDeclarada, 2)}"
        else -> "«$piezaNombre»: declaró ${redondear(holguraDeclarada, 2)} mm y tiene " +
            "${redondear(holguraMedida, 2)}"
    }
}

/**
 * Mide sobre el campo las holguras que el documento declara.
 *
 * Las dos preguntas no son la misma, y por eso no se miden igual:
 *
 *  - `ENTRA` pregunta **cuánto ocupa**. La pieza tiene que caber por un hueco de la
 *    medida nominal, así que se mide la extensión del material a lo largo del eje
 *    gobernado, en la zona de la pieza y un poco más allá. Ese «un poco más allá» es lo
 *    que hace que una pestaña fusionada después —que sobresale y ya no entra— se vea:
 *    mirando solo dentro del volumen que la pieza reclamaba, no se vería nunca.
 *
 *  - `RECIBE` pregunta **cuánto cabe**, que no es lo contrario. Un pasador en el centro
 *    de un agujero deja los dos bordes despejados, así que medir de borde a borde lo da
 *    por bueno. Lo que hay que medir es la mayor bola que entra, y en un campo de
 *    distancias eso es literalmente el máximo del campo dentro del hueco: el valor del
 *    campo en un punto vacío **es** la distancia al material más cercano.
 *
 * La bola se queda corta en un rebaje más ancho que hondo, donde lo que limita es el
 * fondo y no la pared. Se queda corta hacia el lado seguro —dice menos holgura de la que
 * hay— y por eso vale: esta comprobación falla cerrado.
 */
class VerificadorDeEncajes(
    private val documento: Documento,
    private val nodo: SdfNode,
    private val perfil: PerfilFabricacion,
    private val resolucion: Float,
) {

    /** Cuánto se sale del volumen de la pieza para buscar material pegado a ella. */
    private val margen: Float = max(2f, resolucion * 4f)

    private val paso: Float = resolucion.coerceIn(0.05f, 0.5f)

    fun medir(): List<EncajeMedido> = documento.raiz.aplanar()
        .map { it.first }
        .flatMap { pieza -> pieza.encajes.map { medirUno(pieza.id, pieza.nombre, it) } }

    private fun medirUno(id: String, nombre: String, encaje: Encaje): EncajeMedido {
        val medida = documento.medidaDe(encaje.medida)
        // La holgura declarada es la efectiva: con la parte proporcional activada,
        // verificar contra el piso fijo del perfil daría «cumple» falsos por defecto
        // en diámetros grandes, justo donde la marca existe para ayudar.
        val declarada = medida?.let { encaje.holguraEfectiva(it.valor, perfil) }
            ?: encaje.holguraCon(perfil)
        val sinMedir = { motivo: String ->
            EncajeMedido(
                piezaId = id,
                piezaNombre = nombre,
                nominal = medida?.valor ?: 0f,
                holguraDeclarada = declarada,
                toleranciaDeLaMedida = medida?.tolerancia ?: 0f,
                holguraMedida = Float.NaN,
                resolucion = paso,
                cumple = false,
                motivo = motivo,
            )
        }

        if (medida == null) return sinMedir("no existe la medida «${encaje.medida}»")
        // La incertidumbre de la medida se come la holgura: una holgura de 0,2 mm
        // contra una medida incierta en ±0,3 no puede decirse que se cumpla. Falla
        // cerrado, como todo aquí.
        if (medida.tolerancia >= declarada) {
            return sinMedir(
                "la incertidumbre de la medida (±${redondear(medida.tolerancia, 2)} mm) se come " +
                    "la holgura declarada (${redondear(declarada, 2)} mm): mídela con más cuidado",
            )
        }
        val propio = documento.nodoEnMundoDe(id)
            ?: return sinMedir("la pieza no aporta material: está oculta o vacía")

        val bruto = when (encaje.sentido) {
            SentidoDeEncaje.ENTRA -> extensionDelMaterial(propio.cotas(), encaje.eje)
            SentidoDeEncaje.RECIBE -> mayorBolaDentroDe(propio)
        } ?: return sinMedir(
            when (encaje.sentido) {
                SentidoDeEncaje.ENTRA -> "no se encontró material donde está la pieza"
                SentidoDeEncaje.RECIBE -> "no queda hueco donde la pieza dice que lo hay"
            },
        )

        val holgura = when (encaje.sentido) {
            SentidoDeEncaje.ENTRA -> (medida.valor - bruto) * 0.5f
            SentidoDeEncaje.RECIBE -> (bruto - medida.valor) * 0.5f
        }
        // Un cuarto de paso, con suelo en dos centésimas. Medido contra geometría conocida
        // el error real es de una centésima incluso muestreando a 0,6 mm —el borde se
        // afina por bisección y el hueco por búsqueda local, así que la rejilla solo elige
        // por dónde mirar—, de modo que este margen sobra un orden de magnitud a
        // propósito: la cifra que se enseña no debe depender de la resolución del análisis.
        val tolerancia = max(paso * 0.25f, 0.02f)
        return EncajeMedido(
            piezaId = id,
            piezaNombre = nombre,
            nominal = medida.valor,
            holguraDeclarada = declarada,
            toleranciaDeLaMedida = medida.tolerancia,
            holguraMedida = holgura,
            resolucion = paso,
            cumple = kotlin.math.abs(holgura - declarada) <= tolerancia,
        )
    }

    /**
     * Extensión del material del **documento entero** en la zona de la pieza.
     *
     * Del documento y no de la pieza suelta a propósito: lo que tiene que entrar por el
     * agujero es lo que sale de la impresora, no la rama del árbol que alguien declaró.
     */
    private fun extensionDelMaterial(cotas: Aabb, eje: EjeNombrado): Float? {
        val zona = cotas.expanded(margen)
        var topeMayor = -Float.MAX_VALUE
        var topeMenor = Float.MAX_VALUE
        recorrer(zona) { p ->
            if (nodo.evaluar(p) <= 0f) {
                val v = componente(p, eje)
                topeMayor = max(topeMayor, v)
                topeMenor = min(topeMenor, v)
            }
        }
        if (topeMayor < topeMenor) return null

        // La rejilla se queda a un paso de la superficie, y un paso son décimas de
        // milímetro: justo la escala en la que se decide si algo entra. Se afina buscando
        // el corte del campo a lo largo del eje.
        //
        // Y se afinan **todos** los puntos del borde, no el primero que tocó el máximo de
        // la rejilla. Un cilindro tiene decenas de muestras con la misma coordenada máxima
        // repartidas por toda una cuerda, y solo la que pasa por el ecuador llega al radio
        // de verdad: quedarse con cualquiera de las otras medía la pieza más estrecha de
        // lo que es y regalaba holgura que no existe.
        val direccion = unitario(eje)
        var extremoMayor = topeMayor
        var extremoMenor = topeMenor
        recorrer(zona) { p ->
            if (nodo.evaluar(p) <= 0f) {
                val v = componente(p, eje)
                if (v >= topeMayor - paso) {
                    extremoMayor = max(extremoMayor, componente(afinarBorde(p, direccion), eje))
                }
                if (v <= topeMenor + paso) {
                    extremoMenor = min(extremoMenor, componente(afinarBorde(p, direccion * -1f), eje))
                }
            }
        }
        return extremoMayor - extremoMenor
    }

    /**
     * Empuja [dentro] en [direccion] hasta el borde del material y devuelve el punto.
     *
     * Sale del material a saltos de un paso —el campo puede no ser 1-Lipschitz, así que
     * no se puede confiar en su valor como distancia— y luego biseca. Si no llega a
     * salir, devuelve lo último que tenía: mejor una cota corta que una inventada.
     */
    private fun afinarBorde(dentro: Vec3, direccion: Vec3): Vec3 {
        var interior = dentro
        var exterior = dentro + direccion * paso
        var intentos = 0
        while (nodo.evaluar(exterior) <= 0f && intentos++ < 8) {
            interior = exterior
            exterior += direccion * paso
        }
        if (nodo.evaluar(exterior) <= 0f) return interior
        repeat(12) {
            val medio = (interior + exterior) * 0.5f
            if (nodo.evaluar(medio) <= 0f) interior = medio else exterior = medio
        }
        return interior
    }

    private fun unitario(eje: EjeNombrado): Vec3 = when (eje) {
        EjeNombrado.X -> Vec3(1f, 0f, 0f)
        EjeNombrado.Y -> Vec3(0f, 1f, 0f)
        EjeNombrado.Z -> Vec3(0f, 0f, 1f)
    }

    /**
     * Diámetro de la mayor bola que cabe en el hueco que la pieza reclama.
     *
     * El campo del documento en un punto vacío es la distancia al material más cercano,
     * así que el doble del máximo de ese campo dentro del volumen de la pieza es lo que
     * cabe. No hace falta conocer el eje del agujero ni recorrer líneas.
     */
    private fun mayorBolaDentroDe(propio: SdfNode): Float? {
        var mayor = -Float.MAX_VALUE
        var donde: Vec3? = null
        recorrer(propio.cotas()) { p ->
            val libre = libreEn(propio, p)
            if (libre > mayor) {
                mayor = libre
                donde = p
            }
        }
        val centro = donde ?: return null
        if (mayor <= 0f) return null
        // La rejilla rara vez cae en el eje del agujero, y en el eje es donde está el
        // máximo. Tres rondas de búsqueda local alrededor del mejor punto bajan el error
        // de un paso a un paso entre veintisiete, por unos cientos de evaluaciones.
        return 2f * afinarMaximo(propio, centro, mayor)
    }

    /**
     * Lo que cabe en un punto: lo que hay hasta el material **y** hasta el borde del
     * hueco que la pieza reclama, lo que sea menor.
     *
     * El segundo término no es un detalle. Un taladro pasante se modela más largo que la
     * pieza para que la atraviese seguro, así que sus extremos asoman al aire libre,
     * donde el material más cercano está lejísimos. Sin acotar por el propio volumen del
     * taladro, la «mayor bola que cabe» se medía fuera de la pieza y salía un agujero
     * enorme que no existe.
     */
    private fun libreEn(propio: SdfNode, p: Vec3): Float {
        val dentroDelHueco = propio.evaluar(p)
        if (dentroDelHueco > 0f) return -Float.MAX_VALUE
        val hastaElMaterial = nodo.evaluar(p)
        if (hastaElMaterial <= 0f) return -Float.MAX_VALUE
        return min(hastaElMaterial, -dentroDelHueco)
    }

    private fun afinarMaximo(propio: SdfNode, centro: Vec3, valor: Float): Float {
        var mejor = centro
        var mejorValor = valor
        var radio = paso
        repeat(3) {
            val salto = radio / 3f
            for (iz in -1..1) for (iy in -1..1) for (ix in -1..1) {
                val p = mejor + Vec3(ix * salto, iy * salto, iz * salto)
                val v = libreEn(propio, p)
                if (v > mejorValor) {
                    mejorValor = v
                    mejor = p
                }
            }
            radio = salto
        }
        return mejorValor
    }

    private fun componente(p: Vec3, eje: EjeNombrado): Float = when (eje) {
        EjeNombrado.X -> p.x
        EjeNombrado.Y -> p.y
        EjeNombrado.Z -> p.z
    }

    /** Rejilla sobre [caja], con el paso agrandado si el conteo se desmadra. */
    private inline fun recorrer(caja: Aabb, accion: (Vec3) -> Unit) {
        var p = paso
        repeat(5) {
            val nx = ceil(caja.size.x / p).toInt() + 1
            val ny = ceil(caja.size.y / p).toInt() + 1
            val nz = ceil(caja.size.z / p).toInt() + 1
            if (nx.toLong() * ny * nz <= TOPE_DE_MUESTRAS) {
                for (iz in 0 until nz) for (iy in 0 until ny) for (ix in 0 until nx) {
                    accion(Vec3(caja.min.x + ix * p, caja.min.y + iy * p, caja.min.z + iz * p))
                }
                return
            }
            p *= 2f
        }
    }

    private companion object {
        const val TOPE_DE_MUESTRAS = 3_000_000L
    }
}

/** Los avisos que salen de las holguras que no se cumplen o que no se pudieron medir. */
fun avisosDeEncaje(medidos: List<EncajeMedido>): List<Hallazgo> = medidos.mapNotNull { m ->
    if (m.cumple) return@mapNotNull null
    val severidad = when {
        m.motivo != null -> Severidad.PROBABLE
        m.holguraMedida < 0f -> Severidad.FALLARA
        m.holguraMedida < m.holguraDeclarada -> Severidad.PROBABLE
        else -> Severidad.MEJORABLE
    }
    val detalle = when {
        m.motivo != null ->
            "El encaje está declarado y no se ha podido comprobar: ${m.motivo}. " +
                "No se da por bueno."
        m.holguraMedida < 0f ->
            "La pieza construida se pasa ${redondear(-m.holguraMedida, 2)} mm de la medida " +
                "declarada, así que no entra. Algo la ha cambiado después de declarar el encaje."
        m.holguraMedida < m.holguraDeclarada ->
            "Queda ${redondear(m.holguraMedida, 2)} mm de holgura donde se declararon " +
                "${redondear(m.holguraDeclarada, 2)}: entrará forzando, o no entrará."
        else ->
            "Sobra holgura: ${redondear(m.holguraMedida, 2)} mm frente a los " +
                "${redondear(m.holguraDeclarada, 2)} declarados. La pieza bailará."
    }
    Hallazgo(
        regla = Regla.ENCAJE_DECLARADO,
        severidad = severidad,
        titulo = if (m.motivo != null) "«${m.piezaNombre}»: encaje sin comprobar"
        else "«${m.piezaNombre}» no encaja como dice",
        detalle = detalle,
        medido = m.holguraMedida,
        umbral = m.holguraDeclarada,
        unidad = "mm",
        areaAfectada = 0f,
        piezaId = m.piezaId,
        piezaNombre = m.piezaNombre,
    )
}
