package yunkil.fabricacion

import yunkil.doc.Documento
import yunkil.doc.Ensamblaje
import yunkil.doc.Pieza
import yunkil.doc.compilar
import yunkil.kernel.Aabb
import yunkil.kernel.SdfNode
import yunkil.kernel.Vec3
import kotlin.math.max
import kotlin.math.min

/**
 * La interferencia entre cuerpos, medida de verdad.
 *
 * En este documento no hay cuerpos sueltos: la raíz es una unión y el solape es
 * técnica de modelado. Por eso la única pregunta que tiene sentido hacer es «¿se
 * meten una en otra las piezas de **este** ensamblaje?», y la pregunta solo existe
 * donde alguien declaró un [Ensamblaje].
 *
 * La medición es muestral y falla cerrada: lo que no se puede medir se dice, y el
 * solape solo se afirma con muestras de material en los dos campos a la vez.
 */

/** Lo medido entre dos cuerpos de un ensamblaje. */
@kotlinx.serialization.Serializable
data class InterferenciaDeCuerpos(
    val ensamblaje: String,
    val piezaA: String,
    val piezaB: String,
    val nombreA: String,
    val nombreB: String,
    /**
     * Volumen de material común estimado, en mm³. `null` si el par no se pudo medir:
     * una pieza sin geometría o con un campo que no compila no puede afirmar nada.
     */
    val solape: Float?,
    /**
     * La menor separación entre los dos cuerpos, en mm, cuando no hay solape.
     *
     * Es una estimación muestral refinada, no una distancia exacta; sirve para decir
     * «le sobran 0,4 mm» y para distinguir «rozan» de «hay un dedo entre ellas».
     */
    val holguraMinima: Float?,
) {
    val medible: Boolean get() = solape != null
    val interfieren: Boolean get() = (solape ?: 0f) > 0f
}

/**
 * El verificador. Solo lee: compila cada pieza del ensamblaje por su cuenta y
 * muestrea los dos campos. No toca el documento ni guarda nada.
 */
class VerificadorDeEnsamblajes(
    private val documento: Documento,
    /** Paso de la rejilla de muestreo, en mm. El grueso lo pide quien llama. */
    private val paso: Float = 0.8f,
) {
    fun verificar(): List<InterferenciaDeCuerpos> {
        val resultado = ArrayList<InterferenciaDeCuerpos>()
        for (ensamblaje in documento.ensamblajes) {
            val cuerpos = ensamblaje.piezas.mapNotNull { documento.buscar(it) }
            for (i in cuerpos.indices) {
                for (j in i + 1 until cuerpos.size) {
                    resultado.add(medirPar(ensamblaje.nombre, cuerpos[i], cuerpos[j]))
                }
            }
        }
        return resultado
    }

    /**
     * Mide dos piezas cualesquiera, con o sin ensamblaje declarado. Es la misma
     * medición que la de un ensamblaje, puesta al servicio de la pregunta suelta
     * «¿cuánto aire hay entre estas dos piezas?».
     */
    fun medirPar(a: Pieza, b: Pieza): InterferenciaDeCuerpos = medirPar("(sin ensamblaje)", a, b)

    private fun medirPar(ensamblaje: String, a: Pieza, b: Pieza): InterferenciaDeCuerpos {
        val base = InterferenciaDeCuerpos(
            ensamblaje = ensamblaje,
            piezaA = a.id,
            piezaB = b.id,
            nombreA = a.nombre,
            nombreB = b.nombre,
            solape = null,
            holguraMinima = null,
        )
        val campoA = a.compilar() ?: return base
        val campoB = b.compilar() ?: return base
        val cajaA = campoA.cotas()
        val cajaB = campoB.cotas()
        val comun = cajaA.interseccionCon(cajaB) ?: return base.copy(
            solape = 0f,
            holguraMinima = separacionMinima(cajaA, cajaB, campoA, campoB),
        )

        // Muestreo regular sobre la intersección de las cajas. Una celda cuenta como
        // solapada cuando el punto está **dentro de los dos**; es conservador con el
        // volumen —cuenta mm³ de más que de menos—, que es el lado seguro para una
        // comprobación que decide si se imprime.
        var celdas = 0
        val volumenDeCelda = paso * paso * paso
        recorrer(comun, paso) { p ->
            if (campoA.evaluar(p) < 0f && campoB.evaluar(p) < 0f) celdas++
        }
        val solape = celdas * volumenDeCelda
        if (solape > 0f) return base.copy(solape = solape)

        // Sin solape, la holgura que hay: se mide sobre la intersección y, si las
        // cajas ni siquiera se tocan, sobre el hueco entre las dos.
        return base.copy(
            solape = 0f,
            holguraMinima = if (comun.vacio()) {
                separacionMinima(cajaA, cajaB, campoA, campoB)
            } else {
                holguraEnComun(comun, campoA, campoB)
            },
        )
    }

    /**
     * La menor distancia entre los dos cuerpos, a partir del hueco que las separa.
     *
     * `max(dA, dB)` vale cero exactamente donde los sólidos se tocan, y por la
     * desigualdad triangular su mínimo sobre todo el espacio es **la mitad** de la
     * separación entre ellos —se alcanza en el punto medio del tramo más corto que
     * une las dos superficies—. Duplicando el mínimo muestral sale la separación.
     *
     * La muestra puede pasarse de largo (casi: el mínimo muestreado es mayor o igual
     * que el real), así que se descuenta un paso de rejilla para quedarse **corta**,
     * que es el lado seguro para un número que alguien va a usar para decidir.
     */
    private fun separacionMinima(
        cajaA: Aabb,
        cajaB: Aabb,
        campoA: SdfNode,
        campoB: SdfNode,
    ): Float? {
        val todo = cajaA.unionCon(cajaB) ?: return null
        var mejor: Pair<Vec3, Float>? = null
        recorrer(todo, paso) { p ->
            val d = max(campoA.evaluar(p), campoB.evaluar(p))
            if (mejor == null || d < mejor!!.second) mejor = p to d
        }
        val minimoMuestral = mejor?.second ?: return null
        return (2f * minimoMuestral - paso).coerceAtLeast(0f).takeIf { it.isFinite() }
    }

    /** La holgura más estrecha dentro de la zona donde las cajas coinciden. */
    private fun holguraEnComun(comun: Aabb, campoA: SdfNode, campoB: SdfNode): Float? {
        // El mínimo de max(dA, dB) es la mitad de la separación; misma cuenta que en
        // [separacionMinima], descontado un paso para quedarse corta.
        var mejor: Float? = null
        recorrer(comun, paso) { p ->
            val dA = campoA.evaluar(p)
            val dB = campoB.evaluar(p)
            if (dA < 0f || dB < 0f) return@recorrer
            val d = max(dA, dB)
            if (mejor == null || d < mejor!!) mejor = d
        }
        val minimoMuestral = mejor?.takeIf { it.isFinite() } ?: return null
        return (2f * minimoMuestral - paso).coerceAtLeast(0f)
    }

    private inline fun recorrer(caja: Aabb, paso: Float, accion: (Vec3) -> Unit) {
        var z = caja.min.z
        while (z <= caja.max.z) {
            var y = caja.min.y
            while (y <= caja.max.y) {
                var x = caja.min.x
                while (x <= caja.max.x) {
                    accion(Vec3(x, y, z))
                    x += paso
                }
                y += paso
            }
            z += paso
        }
    }
}

/** La caja donde los dos volúmenes coinciden, o `null` si no coinciden. */
private fun Aabb.interseccionCon(otro: Aabb): Aabb? {
    val lo = Vec3(max(min.x, otro.min.x), max(min.y, otro.min.y), max(min.z, otro.min.z))
    val hi = Vec3(min(max.x, otro.max.x), min(max.y, otro.max.y), min(max.z, otro.max.z))
    if (lo.x > hi.x || lo.y > hi.y || lo.z > hi.z) return null
    return Aabb(lo, hi)
}

private fun Aabb.unionCon(otro: Aabb): Aabb = Aabb(
    Vec3(min(min.x, otro.min.x), min(min.y, otro.min.y), min(min.z, otro.min.z)),
    Vec3(max(max.x, otro.max.x), max(max.y, otro.max.y), max(max.z, otro.max.z)),
)

private fun Aabb.vacio(): Boolean = min.x > max.x || min.y > max.y || min.z > max.z
