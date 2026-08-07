package yunkil.msl

import yunkil.kernel.AcuerdoLocal
import yunkil.kernel.Axis
import yunkil.kernel.Barrido
import yunkil.kernel.Caja
import yunkil.kernel.CampoDeMalla
import yunkil.kernel.Capsula
import yunkil.kernel.Cilindro
import yunkil.kernel.Cono
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.Extrusion
import yunkil.kernel.Interseccion
import yunkil.kernel.ModoDeAcuerdo
import yunkil.kernel.Repeticion
import yunkil.kernel.Revolucion
import yunkil.kernel.SdfNode
import yunkil.kernel.Simetria
import yunkil.kernel.Toro
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.preorden
import yunkil.kernel.Vaciado

/**
 * Shader generado a partir de un árbol SDF.
 *
 * `huellaTopologica` identifica la *forma* del árbol ignorando todos sus valores
 * numéricos. Dos árboles con la misma huella comparten shader y solo se distinguen
 * por el buffer de uniforms, que es justo lo que permite arrastrar un deslizador
 * sin recompilar nada.
 */
data class ShaderGenerado(
    val fuente: String,
    val numeroDeUniforms: Int,
    val huellaTopologica: String,
    /**
     * Fracción de la distancia que el trazado puede avanzar sin pasarse de largo.
     *
     * Es 1 mientras todo el árbol sea 1-Lipschitz, que es lo normal. Un `AcuerdoLocal`
     * mezcla con una anchura que cambia con la posición y eso mete algo de gradiente
     * extra: avanzar la distancia entera podría saltarse la superficie y dejar agujeros
     * en el filete. El generador lo publica porque es él quien sabe qué nodos hay en el
     * árbol; el renderizador solo lo aplica.
     */
    val pasoSeguro: Float = 1f,
)

/**
 * Traduce un árbol SDF a Metal Shading Language.
 *
 * Dos reglas gobiernan este generador:
 *
 * 1. Los valores numéricos se emiten como lecturas del buffer `u`, nunca como
 *    literales. El orden de asignación es el preorden del árbol, el mismo que usa
 *    `empaquetarUniforms`, de modo que ambos no pueden desalinearse.
 * 2. Todo bucle que se emite lleva un tope constante de iteraciones. Esta es la
 *    salvaguarda que impide colgar el driver gráfico, y por eso vive en el
 *    generador y no en el criterio de quien escriba el shader.
 */
class MslGenerator {

    /**
     * Marca la versión del generador en la huella topológica.
     *
     * La huella describe la *forma* del árbol, no la versión del código que lo
     * traduce. Cuando el generador cambia —p. ej. al añadir la marcha podada por
     * cotas— un shader viejo compilado con la misma huella seguiría en uso, y el
     * renderizador no lo recompilaría porque la huella no cambió. Esta marca es la
     * que fuerza la recompilación: si la fuente cambia, la huella también.
     */
    private val VERSION_DEL_GENERADOR = "v10"

    fun generar(raiz: SdfNode): ShaderGenerado {
        // El preorden es el orden canónico: el de los uniforms y el de las cajas.
        // El mapa nodo → índice de caja lo comparten las dos versiones del cuerpo.
        val orden = raiz.preorden()
        val indiceDeCaja = HashMap<SdfNode, Int>().apply {
            orden.forEachIndexed { i, n -> put(n, i) }
        }
        val totalEscalares = orden.sumOf { it.escalares.size }

        val cuerpo = StringBuilder()
        val estado = Estado()
        val resultado = emitir(raiz, "p", cuerpo, estado, podar = false, indiceDeCaja, totalEscalares)

        val cuerpoPodado = StringBuilder()
        val estadoPodado = Estado()
        val resultadoPodado = emitir(raiz, "p", cuerpoPodado, estadoPodado, podar = true, indiceDeCaja, totalEscalares)

        // Las cajas de los nodos viajan al final del buffer, en el orden del preorden:
        // primero todos los escalares —que es el orden que ya comprueba la paridad— y
        // después 6 floats por nodo (mínimo y máximo). Como ambos lados usan el mismo
        // preorden, el empaquetado y el shader no pueden desalinearse.
        val baseDeCajas = totalEscalares
        val numeroDeNodos = orden.size

        val fuente = buildString {
            append(PRELUDIO)
            append("\nfloat yk_map(float3 p, constant float *u) {\n")
            append(cuerpo)
            append("    return $resultado;\n}\n")
            append("\nfloat yk_marcha(float3 p, constant float *u) {\n")
            append(cuerpoPodado)
            append("    return $resultadoPodado;\n}\n")
            append(RAYMARCHER)
        }

        return ShaderGenerado(
            fuente = fuente,
            numeroDeUniforms = totalEscalares + 6 * numeroDeNodos,
            huellaTopologica = VERSION_DEL_GENERADOR + huellaDe(raiz),
            pasoSeguro = pasoSeguroDe(raiz),
        )
    }

    private class Estado {
        var cursorUniforms = 0
        var siguienteVariable = 0
        fun nuevaVariable(prefijo: String) = "${prefijo}${siguienteVariable++}"
    }

    /**
     * Emite el código que calcula la distancia de [nodo] en el punto [punto] y
     * devuelve el nombre de la variable que la contiene.
     *
     * Reserva los uniforms del nodo *antes* de descender a sus hijos: así el orden
     * de reserva es exactamente el preorden del árbol.
     *
     * Con [podar] activo se emite además el salto por cotas: antes de evaluar la
     * segunda rama de una booleana se comprueba la caja del nodo, y si está más
     * lejos que lo ya calculado la rama no puede ganar y se salta. Como la caja
     * contiene al sólido, el valor resultante es **exactamente el mismo** —la poda
     * solo ahorra trabajo— y `yk_map` (que comprueba la paridad) queda intacta;
     * la podada es `yk_marcha`, cota inferior usada por el trazado.
     */
    private fun emitir(
        nodo: SdfNode,
        punto: String,
        sb: StringBuilder,
        e: Estado,
        podar: Boolean,
        indiceDeCaja: Map<SdfNode, Int>,
        baseDeCajas: Int,
        cursorYaReservado: Boolean = false,
    ): String {
        // El bloque de este nodo empieza donde estaba el cursor... salvo cuando la
        // poda ya lo adelantó para poder leer la caja antes de decidir si baja por la
        // rama. Entonces el bloque está *detrás* del cursor, y tomarlo tal cual hacía
        // que el nodo leyera los uniforms del siguiente: sin error de compilación,
        // sin fallo en `yk_map` —que no poda— y con hasta 20 mm de diferencia en la
        // marcha, medidos en la GPU. Un rayo que se salta la pieza.
        val b = if (cursorYaReservado) e.cursorUniforms - nodo.escalares.size else e.cursorUniforms
        if (!cursorYaReservado) e.cursorUniforms += nodo.escalares.size
        val d = e.nuevaVariable("d")
        val sangria = "    "

        fun u(offset: Int) = "u[${b + offset}]"

        when (nodo) {
            is Esfera ->
                sb.append("${sangria}float $d = length($punto) - ${u(0)};\n")

            is Caja -> {
                val q = e.nuevaVariable("q")
                sb.append("${sangria}float3 $q = abs($punto) - float3(${u(0)}, ${u(1)}, ${u(2)}) + ${u(3)};\n")
                sb.append(
                    "${sangria}float $d = length(max($q, float3(0.0f))) + " +
                        "min(max($q.x, max($q.y, $q.z)), 0.0f) - ${u(3)};\n",
                )
            }

            is Extrusion -> {
                val v = e.nuevaVariable("ex")
                // El perfil vive en XZ, que es el plano del plato: se dibuja sobre la
                // mesa y se levanta.
                sb.append("${sangria}float ${v}p = yk_perfil(float2($punto.x, $punto.z), u, ${b + 3}, ${nodo.perfil.poligono.size}, ${u(2)});\n")
                sb.append("${sangria}float ${v}d = ${v}p + ${u(1)};\n")
                sb.append("${sangria}float ${v}y = abs($punto.y) - ${u(0)} * 0.5f + ${u(1)};\n")
                sb.append("${sangria}float $d = min(max(${v}d, ${v}y), 0.0f) + length(max(float2(${v}d, ${v}y), float2(0.0f))) - ${u(1)};\n")
            }

            is CampoDeMalla -> {
                // Envolvente, no la pieza: ver `CampoDeMalla.escalares`. Se emite desde
                // uniforms como todo lo demás, así que cuando llegue la textura este
                // nodo no desalinea el buffer ni obliga a recompilar por mover la caja.
                val v = e.nuevaVariable("mm")
                sb.append("${sangria}float3 ${v}c = (float3(${u(0)}, ${u(1)}, ${u(2)}) + float3(${u(3)}, ${u(4)}, ${u(5)})) * 0.5f;\n")
                sb.append("${sangria}float3 ${v}h = (float3(${u(3)}, ${u(4)}, ${u(5)}) - float3(${u(0)}, ${u(1)}, ${u(2)})) * 0.5f;\n")
                sb.append("${sangria}float3 ${v}q = abs($punto - ${v}c) - ${v}h;\n")
                sb.append("${sangria}float $d = length(max(${v}q, float3(0.0f))) + min(max(${v}q.x, max(${v}q.y, ${v}q.z)), 0.0f);\n")
            }

            is Barrido -> {
                // El camino también vive en XZ, igual que el perfil de una extrusión:
                // se dibuja el recorrido sobre la mesa y la sección lo engorda.
                sb.append(
                    "${sangria}float $d = yk_barrido($punto, u, ${b + 1}, " +
                        "${nodo.perfil.poligono.size}, ${nodo.tramos}, ${u(0)});\n",
                )
            }

            is Revolucion -> {
                val v = e.nuevaVariable("rv")
                sb.append("${sangria}float2 ${v}q = float2(length($punto.xz) - ${u(0)}, $punto.y);\n")
                sb.append("${sangria}float $d = yk_perfil(${v}q, u, ${b + 2}, ${nodo.perfil.poligono.size}, ${u(1)});\n")
            }

            is Cilindro -> {
                val v = e.nuevaVariable("c")
                sb.append("${sangria}float2 $v = float2(length($punto.xz) - ${u(0)} + ${u(2)}, abs($punto.y) - ${u(1)} * 0.5f + ${u(2)});\n")
                sb.append("${sangria}float $d = min(max($v.x, $v.y), 0.0f) + length(max($v, float2(0.0f))) - ${u(2)};\n")
            }

            is Cono -> {
                val v = e.nuevaVariable("k")
                sb.append("${sangria}float $d;\n")
                sb.append("${sangria}{\n")
                sb.append("$sangria    float ${v}h = ${u(2)} * 0.5f;\n")
                sb.append("$sangria    float ${v}qx = length($punto.xz);\n")
                sb.append("$sangria    float ${v}qy = $punto.y;\n")
                sb.append("$sangria    float ${v}ax = ${v}qx - min(${v}qx, (${v}qy < 0.0f) ? ${u(0)} : ${u(1)});\n")
                sb.append("$sangria    float ${v}ay = abs(${v}qy) - ${v}h;\n")
                sb.append("$sangria    float ${v}2x = ${u(1)} - ${u(0)};\n")
                sb.append("$sangria    float ${v}2y = 2.0f * ${v}h;\n")
                sb.append("$sangria    float ${v}t = clamp(((${u(1)} - ${v}qx) * ${v}2x + (${v}h - ${v}qy) * ${v}2y) / (${v}2x * ${v}2x + ${v}2y * ${v}2y), 0.0f, 1.0f);\n")
                sb.append("$sangria    float ${v}bx = ${v}qx - ${u(1)} + ${v}2x * ${v}t;\n")
                sb.append("$sangria    float ${v}by = ${v}qy - ${v}h + ${v}2y * ${v}t;\n")
                sb.append("$sangria    float ${v}s = (${v}bx < 0.0f && ${v}ay < 0.0f) ? -1.0f : 1.0f;\n")
                sb.append("$sangria    $d = ${v}s * sqrt(min(${v}ax * ${v}ax + ${v}ay * ${v}ay, ${v}bx * ${v}bx + ${v}by * ${v}by));\n")
                sb.append("${sangria}}\n")
            }

            is Toro -> {
                val v = e.nuevaVariable("t")
                sb.append("${sangria}float2 $v = float2(length($punto.xz) - ${u(0)}, $punto.y);\n")
                sb.append("${sangria}float $d = length($v) - ${u(1)};\n")
            }

            is Capsula -> {
                val v = e.nuevaVariable("s")
                sb.append("${sangria}float ${v}h = ${u(1)} * 0.5f;\n")
                sb.append("${sangria}float ${v}y = $punto.y - clamp($punto.y, -${v}h, ${v}h);\n")
                sb.append("${sangria}float $d = length(float3($punto.x, ${v}y, $punto.z)) - ${u(0)};\n")
            }

            is Union -> {
                val a = emitir(nodo.a, punto, sb, e, podar, indiceDeCaja, baseDeCajas)
                // La caja de B se comprueba y sus uniforms se reservan en el mismo
                // paso: si la poda se cumple, la rama no se emite pero el cursor ya
                // avanzó; si no, `emitir` entra con el cursor ya reservado.
                val cajaB = if (podar) podaDeCaja(nodo.b, punto, sb, e, indiceDeCaja, baseDeCajas) else null
                if (cajaB != null) {
                    // La caja de B está más lejos que `a` más la mezcla: B no puede
                    // ganar ni fundirse, así que el resultado es `a` exacto.
                    // Se decide en el shader, punto a punto, con `a` ya calculado.
                    sb.append("${sangria}float $d;\n")
                    sb.append("${sangria}if ($cajaB > $a + ${u(0)}) {\n")
                    sb.append("$sangria    $d = $a;\n")
                    sb.append("${sangria}} else {\n")
                    val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas, cursorYaReservado = true)
                    sb.append("$sangria    $d = yk_smin($a, $c, ${u(0)});\n")
                    sb.append("${sangria}}\n")
                } else {
                    val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas)
                    sb.append("${sangria}float $d = yk_smin($a, $c, ${u(0)});\n")
                }
            }

            is Diferencia -> {
                val a = emitir(nodo.a, punto, sb, e, podar, indiceDeCaja, baseDeCajas)
                val cajaB = if (podar) podaDeCaja(nodo.b, punto, sb, e, indiceDeCaja, baseDeCajas) else null
                if (cajaB != null) {
                    // B está tan lejos que `-dB` no puede pasar de `a`: la resta no
                    // quita nada y el resultado es `a` exacto.
                    sb.append("${sangria}float $d;\n")
                    sb.append("${sangria}if ($cajaB > -$a + ${u(0)}) {\n")
                    sb.append("$sangria    $d = $a;\n")
                    sb.append("${sangria}} else {\n")
                    val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas, cursorYaReservado = true)
                    sb.append("$sangria    $d = yk_smax($a, -$c, ${u(0)});\n")
                    sb.append("${sangria}}\n")
                } else {
                    val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas)
                    sb.append("${sangria}float $d = yk_smax($a, -$c, ${u(0)});\n")
                }
            }

            is Interseccion -> {
                // La intersección es el único booleano que no se puede podar con la
                // caja: necesita saber si B está *cerca* (dB pequeño), y la caja solo
                // acota por abajo la distancia al sólido. Se evalúa siempre.
                val a = emitir(nodo.a, punto, sb, e, podar, indiceDeCaja, baseDeCajas)
                val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas)
                sb.append("${sangria}float $d = yk_smax($a, $c, ${u(0)});\n")
            }

            is AcuerdoLocal -> {
                val v = e.nuevaVariable("g")
                // La anchura de la mezcla se calcula **antes** de emitir los hijos para
                // que el orden de reserva de uniforms siga siendo el preorden del árbol,
                // que es de lo que depende que el empaquetado no se desalinee.
                sb.append(
                    "${sangria}float ${v}k = ${u(4)} * " +
                        "yk_caida(length($punto - float3(${u(0)}, ${u(1)}, ${u(2)})), ${u(3)});\n",
                )
                val a = emitir(nodo.a, punto, sb, e, podar, indiceDeCaja, baseDeCajas)
                // El acuerdo local puede podar igual que su booleana equivalente: la
                // mezcla local nunca alcanza más lejos que `k` desde el valor de `a`.
                val cajaB = if (podar && nodo.modo != ModoDeAcuerdo.INTERSECCION) {
                    podaDeCaja(nodo.b, punto, sb, e, indiceDeCaja, baseDeCajas)
                } else {
                    null
                }
                if (cajaB != null) {
                    val comparacion = when (nodo.modo) {
                        ModoDeAcuerdo.UNION -> "$cajaB > $a + ${v}k"
                        ModoDeAcuerdo.DIFERENCIA -> "$cajaB > -$a + ${v}k"
                        ModoDeAcuerdo.INTERSECCION -> null
                    }
                    if (comparacion != null) {
                        sb.append("${sangria}float $d;\n")
                        sb.append("${sangria}if ($comparacion) {\n")
                        sb.append("$sangria    $d = $a;\n")
                        sb.append("${sangria}} else {\n")
                        val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas, cursorYaReservado = true)
                        val expresion = when (nodo.modo) {
                            ModoDeAcuerdo.UNION -> "yk_smin($a, $c, ${v}k)"
                            ModoDeAcuerdo.DIFERENCIA -> "yk_smax($a, -$c, ${v}k)"
                            ModoDeAcuerdo.INTERSECCION -> "yk_smax($a, $c, ${v}k)"
                        }
                        sb.append("$sangria    $d = $expresion;\n")
                        sb.append("${sangria}}\n")
                    } else {
                        val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas, cursorYaReservado = true)
                        val expresion = when (nodo.modo) {
                            ModoDeAcuerdo.UNION -> "yk_smin($a, $c, ${v}k)"
                            ModoDeAcuerdo.DIFERENCIA -> "yk_smax($a, -$c, ${v}k)"
                            ModoDeAcuerdo.INTERSECCION -> "yk_smax($a, $c, ${v}k)"
                        }
                        sb.append("${sangria}float $d = $expresion;\n")
                    }
                } else {
                    val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas)
                    val expresion = when (nodo.modo) {
                        ModoDeAcuerdo.UNION -> "yk_smin($a, $c, ${v}k)"
                        ModoDeAcuerdo.DIFERENCIA -> "yk_smax($a, -$c, ${v}k)"
                        ModoDeAcuerdo.INTERSECCION -> "yk_smax($a, $c, ${v}k)"
                    }
                    sb.append("${sangria}float $d = $expresion;\n")
                }
            }

            is Transformado -> {
                val q = e.nuevaVariable("p")
                sb.append("${sangria}float3 ${q}d = $punto - float3(${u(9)}, ${u(10)}, ${u(11)});\n")
                sb.append(
                    "${sangria}float3 $q = float3(" +
                        "${u(0)} * ${q}d.x + ${u(1)} * ${q}d.y + ${u(2)} * ${q}d.z, " +
                        "${u(3)} * ${q}d.x + ${u(4)} * ${q}d.y + ${u(5)} * ${q}d.z, " +
                        "${u(6)} * ${q}d.x + ${u(7)} * ${q}d.y + ${u(8)} * ${q}d.z) * ${u(12)};\n",
                )
                val hijo = emitir(nodo.hijo, q, sb, e, podar, indiceDeCaja, baseDeCajas)
                sb.append("${sangria}float $d = $hijo * ${u(13)};\n")
            }

            is Vaciado -> {
                val hijo = emitir(nodo.hijo, punto, sb, e, podar, indiceDeCaja, baseDeCajas)
                sb.append("${sangria}float $d = abs($hijo) - ${u(0)} * 0.5f;\n")
            }

            is Simetria -> {
                val q = e.nuevaVariable("m")
                val expr = when (nodo.eje) {
                    Axis.X -> "float3(abs($punto.x), $punto.y, $punto.z)"
                    Axis.Y -> "float3($punto.x, abs($punto.y), $punto.z)"
                    Axis.Z -> "float3($punto.x, $punto.y, abs($punto.z))"
                }
                sb.append("${sangria}float3 $q = $expr;\n")
                val hijo = emitir(nodo.hijo, q, sb, e, podar, indiceDeCaja, baseDeCajas)
                sb.append("${sangria}float $d = $hijo;\n")
            }

            is Repeticion -> {
                // Se desenrolla en lugar de emitir un bucle: la cuenta es topología, no
                // parámetro, y desenrollar mantiene la promesa de que ningún bucle
                // generado depende de un valor en tiempo de ejecución.
                sb.append("${sangria}float $d = 1e30f;\n")
                // Las copias comparten nodo, luego comparten uniforms: se reserva una
                // sola vez y se restaura el cursor tras cada copia salvo la última.
                val cursorAntesDelHijo = e.cursorUniforms
                var cursorTrasElHijo = cursorAntesDelHijo
                for (i in 0 until nodo.cuenta) {
                    e.cursorUniforms = cursorAntesDelHijo
                    val factor = i - (nodo.cuenta - 1) * 0.5f
                    val q = e.nuevaVariable("r")
                    val desplazamiento = when (nodo.eje) {
                        Axis.X -> "float3(${lit(factor)} * ${u(0)}, 0.0f, 0.0f)"
                        Axis.Y -> "float3(0.0f, ${lit(factor)} * ${u(0)}, 0.0f)"
                        Axis.Z -> "float3(0.0f, 0.0f, ${lit(factor)} * ${u(0)})"
                    }
                    sb.append("${sangria}float3 $q = $punto - $desplazamiento;\n")
                    // Cada copia se poda contra el mínimo ya acumulado: si su caja está
                    // más lejos que `d`, la copia no puede mejorar el resultado.
                    if (podar) {
                        val caja = e.nuevaVariable("rc")
                        val base = baseDeCajas + 6 * (indiceDeCaja[nodo.hijo] ?: 0)
                        sb.append("${sangria}float $caja = yk_caja($q, u, $base);\n")
                        sb.append("${sangria}if ($caja < $d) {\n")
                        val hijo = emitir(nodo.hijo, q, sb, e, podar, indiceDeCaja, baseDeCajas)
                        sb.append("$sangria    $d = min($d, $hijo);\n")
                        sb.append("${sangria}}\n")
                    } else {
                        val hijo = emitir(nodo.hijo, q, sb, e, podar, indiceDeCaja, baseDeCajas)
                        sb.append("${sangria}$d = min($d, $hijo);\n")
                    }
                    cursorTrasElHijo = e.cursorUniforms
                }
                e.cursorUniforms = cursorTrasElHijo
            }
        }
        return d
    }

    /**
     * Emite la comprobación de la caja de [nodo] y devuelve el nombre de la variable.
     *
     * Los uniforms del nodo se reservan igual que los reservaría `emitir` —aunque la
     * rama se salte, el cursor debe avanzar para que el orden del preorden no se
     * desalinee— y el resultado es un `float` que se compara contra el valor ya
     * calculado de la otra rama.
     */
    private fun podaDeCaja(
        nodo: SdfNode,
        punto: String,
        sb: StringBuilder,
        e: Estado,
        indiceDeCaja: Map<SdfNode, Int>,
        baseDeCajas: Int,
    ): String {
        e.cursorUniforms += nodo.escalares.size
        val caja = e.nuevaVariable("bc")
        val base = baseDeCajas + 6 * (indiceDeCaja[nodo] ?: 0)
        sb.append("    float $caja = yk_caja($punto, u, $base);\n")
        return caja
    }

    private fun lit(v: Float): String = if (v == v.toInt().toFloat()) "${v.toInt()}.0f" else "${v}f"

    /**
     * Identidad estructural del árbol: tipos de nodo y valores que son topología
     * (la cuenta de una repetición, el eje de una simetría), nunca los numéricos
     * que viajan por uniforms.
     */
    /**
     * Cuánto puede avanzar el trazado por cada unidad de distancia devuelta.
     *
     * Un árbol de nodos 1-Lipschitz permite avanzar la distancia entera, que es lo que
     * hace que el raymarching sea rápido. En cuanto hay un acuerdo local hay que ceder:
     * su gradiente puede pasar de 1 y avanzar de más se saltaría la superficie justo en
     * el filete, que es donde más se mira.
     */
    private fun pasoSeguroDe(raiz: SdfNode): Float {
        val peor = raiz.preorden().filterIsInstance<AcuerdoLocal>().maxOfOrNull { it.lipschitz } ?: 1f
        return 1f / peor
    }

    private fun huellaDe(nodo: SdfNode): String = buildString {
        fun visitar(n: SdfNode) {
            append(
                when (n) {
                    is Esfera -> "E"
                    is Caja -> "C"
                    is Cilindro -> "L"
                    is Cono -> "K"
                    is Toro -> "T"
                    is Capsula -> "P"
                    is Union -> "u"
                    is Diferencia -> "d"
                    is Interseccion -> "i"
                    // El modo decide qué expresión se emite, así que cambiarlo sí exige
                    // recompilar; mover el centro del filete, no.
                    is AcuerdoLocal -> "a${n.modo.name.first()}"
                    is Transformado -> "x"
                    is Vaciado -> "v"
                    is Simetria -> "s${n.eje.name}"
                    is Repeticion -> "r${n.cuenta}${n.eje.name}"
                    is CampoDeMalla -> "M"
                    is Extrusion -> "X${n.perfil.poligono.size}"
                    // El número de tramos gobierna el bucle del shader, así que
                    // abrir o cerrar el camino sí es un cambio de topología.
                    is Barrido -> "B${n.perfil.poligono.size}:${n.tramos}"
                    is Revolucion -> "V${n.perfil.poligono.size}"
                },
            )
            if (n.hijos.isNotEmpty()) {
                append("(")
                n.hijos.forEach(::visitar)
                append(")")
            }
        }
        visitar(nodo)
    }

    private companion object {

        val PRELUDIO = """
            #include <metal_stdlib>
            using namespace metal;

            // Topes constantes del raymarcher. Ningún bucle de este shader depende de
            // un valor en tiempo de ejecución: es lo que impide colgar el driver.
            constant int   YK_MAX_PASOS   = 160;
            constant int   YK_PASOS_AO    = 5;
            constant float YK_DIST_MAXIMA = 4000.0f;

            // Topes del bucle de grosor de pared de la cara de corte: pasos fijos con
            // salida temprana, como el contorno de un perfil.
            constant int YK_MAX_GROSOR = 64;

            // Tope del contorno de un perfil 2D. Coincide con Perfil2D.MAXIMO_DE_VERTICES:
            // el bucle se recorre con una condición constante y una salida temprana,
            // así que sigue sin depender de ningún valor en tiempo de ejecución.
            constant int YK_MAX_VERTICES = 256;

            // Distancia con signo a un polígono cerrado cuyos vértices vienen en el
            // buffer de uniforms a partir de `base`, en pares (x, y).
            //
            // El signo sale de contar cruces de un rayo horizontal, no del sentido de
            // giro del contorno: así un perfil escrito al revés no invierte el sólido.
            inline float yk_perfil(float2 p, constant float *u, int base, int n, float redondeo) {
                float2 v0 = float2(u[base], u[base + 1]);
                float mejor = dot(p - v0, p - v0);
                bool dentro = false;
                for (int i = 0; i < YK_MAX_VERTICES; ++i) {
                    if (i >= n) break;
                    int j = (i + 1 == n) ? 0 : i + 1;
                    float2 a = float2(u[base + i * 2], u[base + i * 2 + 1]);
                    float2 b = float2(u[base + j * 2], u[base + j * 2 + 1]);

                    float2 arista = b - a;
                    float2 hacia = p - a;
                    float t = clamp(dot(hacia, arista) / max(dot(arista, arista), 1e-20f), 0.0f, 1.0f);
                    float2 cercano = hacia - arista * t;
                    mejor = min(mejor, dot(cercano, cercano));

                    if ((a.y > p.y) != (b.y > p.y)) {
                        float x = a.x + (p.y - a.y) / (b.y - a.y) * (b.x - a.x);
                        if (p.x < x) dentro = !dentro;
                    }
                }
                float d = sqrt(mejor);
                return (dentro ? -d : d) - redondeo;
            }

            // Barrido de una sección circular por un camino poligonal en XZ.
            //
            // Es el mínimo de las distancias a cada tramo menos el radio, o sea una
            // cadena de cápsulas: los codos empalman redondeados por construcción y
            // el resultado sigue siendo una distancia verdadera, que es lo que el
            // trazador de rayos necesita para poder avanzar a saltos seguros.
            inline float yk_barrido(float3 p, constant float *u, int base, int n, int tramos, float radio) {
                float mejor = 1e20f;
                for (int i = 0; i < YK_MAX_VERTICES; ++i) {
                    if (i >= tramos) break;
                    int j = (i + 1 == n) ? 0 : i + 1;
                    float3 a = float3(u[base + i * 2], 0.0f, u[base + i * 2 + 1]);
                    float3 b = float3(u[base + j * 2], 0.0f, u[base + j * 2 + 1]);

                    float3 arista = b - a;
                    float3 hacia = p - a;
                    float t = clamp(dot(hacia, arista) / max(dot(arista, arista), 1e-20f), 0.0f, 1.0f);
                    float3 cercano = hacia - arista * t;
                    mejor = min(mejor, dot(cercano, cercano));
                }
                return sqrt(mejor) - radio;
            }

            struct YkCamara {
                float4 origen;      // xyz
                float4 frente;      // xyz
                float4 derecha;     // xyz
                float4 arriba;      // xyz
                float4 params;      // x = tan(fov/2), y = epsilon, z = escalaPasos, w = paralela
                float4 resolucion;  // xy en pixeles
            };

            struct YkEscena {
                float4 plato;    // semiancho, semifondo, altura, paso
                float4 plano;    // xyz = punto del plano de sección, w = 1 activo
                float4 planoN;   // xyz = normal del plano, w = grosor mínimo de pared
                float4 planoR;   // x = semilado del rectángulo visible del plano
            };

            inline float yk_smin(float a, float b, float k) {
                if (k <= 0.0f) return min(a, b);
                float h = clamp(0.5f + 0.5f * (b - a) / k, 0.0f, 1.0f);
                return mix(b, a, h) - k * h * (1.0f - h);
            }

            inline float yk_smax(float a, float b, float k) {
                return -yk_smin(-a, -b, k);
            }

            // Peso de la influencia de un acuerdo local: 1 en el centro, 0 a partir del
            // radio. Tiene que ser la misma fórmula que `caidaDeAcuerdo` en Kotlin, o el
            // viewport enseñaría un filete y el analizador razonaría sobre otro.
            inline float yk_caida(float distancia, float radio) {
                if (radio <= 0.0f) return 0.0f;
                float t = distancia / radio;
                if (t >= 1.0f) return 0.0f;
                float u = 1.0f - t * t;
                return u * u;
            }

            // Distancia con signo a la caja alineada de un nodo, cuyas cotas viajan
            // en el buffer al final de los escalares. Sirve para la poda: si la caja
            // de una rama está más lejos que el valor ya calculado, la rama no puede
            // ganar y no hace falta evaluarla. Como la caja contiene al sólido, la
            // poda es exacta —nunca cambia el valor del campo— y el arnés de paridad
            // lo verifica caso a caso.
            inline float yk_caja(float3 p, constant float *u, int base) {
                float3 mn = float3(u[base], u[base + 1], u[base + 2]);
                float3 mx = float3(u[base + 3], u[base + 4], u[base + 5]);
                float3 q = max(mn - p, p - mx);
                return length(max(q, float3(0.0f))) + min(max(q.x, max(q.y, q.z)), 0.0f);
            }

        """.trimIndent()

        val RAYMARCHER = """

            // Distancia al campo recortado por el plano de sección, cuando lo hay.
            //
            // `yk_map` se queda como la verdad del material —el arnés de paridad la
            // comprueba contra el CPU— y el recorte vive aquí, en la escena, porque el
            // plano es una herramienta de vista y no parte del árbol. El corte deja el
            // lado positivo de la normal: `max` con la distancia al semiespacio es
            // exactamente la operación de intersección con el plano. Este bloque se
            // genera después del preludio porque llama a `yk_map`, que nace ahí.
            inline float yk_distancia(float3 p, constant float *u, constant YkEscena &escena) {
                float d = yk_map(p, u);
                if (escena.plano.w > 0.0f) {
                    d = max(d, dot(p - escena.plano.xyz, escena.planoN.xyz));
                }
                return d;
            }

            // La marcha usa la versión podada por cotas: devuelve siempre un valor
            // menor o igual que el campo real —la poda solo ahorra evaluaciones—, así
            // que el trazado nunca se pasa de largo. La normal y la oclusión siguen
            // con la exacta, porque son lecturas locales alrededor del impacto.
            inline float yk_distanciaDeMarcha(float3 p, constant float *u, constant YkEscena &escena) {
                float d = yk_marcha(p, u);
                if (escena.plano.w > 0.0f) {
                    d = max(d, dot(p - escena.plano.xyz, escena.planoN.xyz));
                }
                return d;
            }

            inline float3 yk_normal(float3 p, constant float *u, constant YkEscena &escena) {
                const float e = 0.001f;
                return normalize(float3(
                    yk_distancia(p + float3(e, 0, 0), u, escena) - yk_distancia(p - float3(e, 0, 0), u, escena),
                    yk_distancia(p + float3(0, e, 0), u, escena) - yk_distancia(p - float3(0, e, 0), u, escena),
                    yk_distancia(p + float3(0, 0, e), u, escena) - yk_distancia(p - float3(0, 0, e), u, escena)));
            }

            inline float yk_oclusion(float3 p, float3 n, constant float *u, constant YkEscena &escena) {
                float oclusion = 0.0f;
                float escala = 1.0f;
                for (int i = 0; i < YK_PASOS_AO; ++i) {
                    float h = 0.02f + 0.35f * float(i) / float(YK_PASOS_AO);
                    oclusion += (h - yk_distancia(p + n * h, u, escena)) * escala;
                    escala *= 0.72f;
                }
                return clamp(1.0f - 2.2f * oclusion, 0.0f, 1.0f);
            }

            struct YkVertice {
                float4 posicion [[position]];
                float2 uv;
            };

            vertex YkVertice yk_vertex(uint id [[vertex_id]]) {
                // Triángulo único que cubre la pantalla: sin buffers de vértices.
                float2 v[3] = { float2(-1, -1), float2(3, -1), float2(-1, 3) };
                YkVertice salida;
                salida.posicion = float4(v[id], 0, 1);
                salida.uv = v[id];
                return salida;
            }

            fragment float4 yk_fragment(YkVertice in [[stage_in]],
                                        constant YkCamara &cam [[buffer(0)]],
                                        constant float *u [[buffer(1)]],
                                        constant YkEscena &escena [[buffer(2)]]) {
                float aspecto = cam.resolucion.x / max(cam.resolucion.y, 1.0f);
                float2 ndc = float2(in.uv.x * aspecto, in.uv.y) * cam.params.x;

                // Con `params.w` a cero hay perspectiva; por encima de cero es proyección
                // paralela, donde todos los rayos van en la misma dirección y lo que cambia
                // es de dónde salen. Tiene que coincidir con `CamaraOrbital.rayo`, o
                // señalar con el cursor apuntaría a otro sitio del que se ve.
                float paralela = cam.params.w;
                float3 origen = cam.origen.xyz
                                + cam.derecha.xyz * (ndc.x * paralela)
                                + cam.arriba.xyz  * (ndc.y * paralela);
                float3 rayo = paralela > 0.0f
                    ? cam.frente.xyz
                    : normalize(cam.frente.xyz
                                + cam.derecha.xyz * ndc.x
                                + cam.arriba.xyz  * ndc.y);

                float epsilon = cam.params.y;
                float escalaPasos = cam.params.z;

                float t = 0.0f;
                bool impacto = false;
                for (int i = 0; i < YK_MAX_PASOS; ++i) {
                    float3 p = origen + rayo * t;
                    float d = yk_distanciaDeMarcha(p, u, escena);
                    if (d < epsilon) { impacto = true; break; }
                    t += d * escalaPasos;
                    if (t > YK_DIST_MAXIMA) break;
                }

                float3 cielo = mix(float3(0.10f, 0.11f, 0.13f),
                                   float3(0.04f, 0.045f, 0.055f),
                                   clamp(in.uv.y * 0.5f + 0.5f, 0.0f, 1.0f));

                // Plato de impresión de 256 mm con rejilla de 10/50 mm. Se pinta
                // analíticamente: no añade geometría ni evaluaciones al campo.
                float tPlato = (escena.plato.z - origen.y) / rayo.y;
                float3 pPlato = origen + rayo * tPlato;
                bool vePlato = tPlato > 0.0f && (!impacto || tPlato < t)
                    && abs(pPlato.x) <= escena.plato.x && abs(pPlato.z) <= escena.plato.y;

                // Cruce del rayo con el plano de sección, para pintar su hoja visible.
                float tPlanoDelPlano = -1.0f;
                float3 pPlano = float3(0.0f);
                if (escena.plano.w > 0.0f) {
                    float denom = dot(rayo, escena.planoN.xyz);
                    if (abs(denom) > 1e-5f) {
                        tPlanoDelPlano = dot(escena.plano.xyz - origen, escena.planoN.xyz) / denom;
                        if (tPlanoDelPlano > 0.0f) pPlano = origen + rayo * tPlanoDelPlano;
                    }
                }

                if (vePlato) {
                    float paso = escena.plato.w;
                    float2 menor = abs(fract(pPlato.xz / paso + 0.5f) - 0.5f) * paso;
                    float2 mayor = abs(fract(pPlato.xz / (paso * 5.0f) + 0.5f) - 0.5f) * paso * 5.0f;
                    float ancho = max(tPlato / max(cam.resolucion.y, 1.0f) * 1.8f, 0.08f);
                    float lineaMenor = 1.0f - smoothstep(ancho, ancho * 2.0f, min(menor.x, menor.y));
                    float lineaMayor = 1.0f - smoothstep(ancho * 1.4f, ancho * 2.8f, min(mayor.x, mayor.y));
                    float borde = smoothstep(3.0f, 0.0f, min(escena.plato.x - abs(pPlato.x), escena.plato.y - abs(pPlato.z)));
                    float3 basePlato = float3(0.065f, 0.085f, 0.09f);
                    float3 rejilla = mix(basePlato, float3(0.16f, 0.34f, 0.34f), max(lineaMenor * 0.45f, lineaMayor));
                    rejilla = mix(rejilla, float3(0.95f, 0.52f, 0.22f), borde * 0.65f);
                    return float4(rejilla, 1.0f);
                }

                // Rectángulo visible del plano de sección. El corte es infinito en el
                // campo; la hoja translúcida es solo para que se vea y se pueda agarrar
                // donde no corta material. Se pinta únicamente cuando el rayo cruza el
                // plano antes de tocar nada: donde el material está delante, manda el
                // material, y donde el corte se ve, manda el tinte de la cara de corte.
                if (escena.plano.w > 0.0f && (!impacto || tPlanoDelPlano < t)) {
                    float3 rel = pPlano - escena.plano.xyz;
                    float3 ref = abs(escena.planoN.y) < 0.99f ? float3(0, 1, 0) : float3(1, 0, 0);
                    float3 ejeA = normalize(cross(ref, escena.planoN.xyz));
                    float3 ejeB = cross(escena.planoN.xyz, ejeA);
                    float u = dot(rel, ejeA);
                    float v = dot(rel, ejeB);
                    if (abs(u) <= escena.planoR.x && abs(v) <= escena.planoR.x) {
                        float paso = 10.0f;
                        float2 menor = abs(fract(float2(u, v) / paso + 0.5f) - 0.5f) * paso;
                        float ancho = max(tPlanoDelPlano / max(cam.resolucion.y, 1.0f) * 1.8f, 0.08f);
                        float linea = 1.0f - smoothstep(ancho, ancho * 2.0f, min(menor.x, menor.y));
                        float borde = smoothstep(3.0f, 0.0f, min(escena.planoR.x - abs(u), escena.planoR.x - abs(v)));
                        float3 hoja = float3(0.55f, 0.72f, 0.95f);
                        float3 colorPlano = mix(hoja * 0.10f, hoja * 0.45f, max(linea * 0.5f, borde));
                        return float4(mix(cielo, colorPlano, 0.85f), 1.0f);
                    }
                }
                if (!impacto) return float4(cielo, 1.0f);

                float3 p = origen + rayo * t;
                float3 n = yk_normal(p, u, escena);

                float3 luz = normalize(float3(0.45f, 0.85f, 0.35f));
                float difusa = clamp(dot(n, luz), 0.0f, 1.0f);
                float relleno = clamp(0.5f + 0.5f * dot(n, float3(0, 1, 0)), 0.0f, 1.0f);
                float ao = yk_oclusion(p, n, u, escena);

                float3 base = float3(0.82f, 0.80f, 0.76f);
                float3 color = base * (0.15f * relleno + 0.85f * difusa) * ao;

                // Especular suave: ayuda a leer la curvatura sin pretender realismo.
                float3 media = normalize(luz - rayo);
                color += float3(0.25f) * pow(clamp(dot(n, media), 0.0f, 1.0f), 32.0f) * ao;

                // Cara de corte del plano de sección, cuando el impacto es la propia
                // superficie del plano. La normal decide: sobre el plano la del campo
                // recortado ES la del plano, así que no hace falta ninguna topología.
                // El color lee el grosor de pared hacia dentro del material, con la
                // misma marcha que usa el analizador FDM: rojo por debajo del mínimo
                // del perfil, verde por encima del doble. Eso no lo puede hacer un
                // laminador, que solo ve triángulos.
                if (escena.plano.w > 0.0f && abs(dot(n, escena.planoN.xyz)) > 0.995f) {
                    float limite = escena.planoN.w * 4.0f + 0.4f;
                    float paso = max(escena.planoN.w * 0.25f, 0.05f);
                    float avance = paso;
                    float grosor = limite;
                    for (int i = 0; i < YK_MAX_GROSOR; ++i) {
                        if (avance > limite) break;
                        if (yk_map(p - escena.planoN.xyz * avance, u) >= 0.0f) {
                            grosor = avance;
                            break;
                        }
                        avance += paso;
                    }
                    // Umbral del tinte: por debajo del mínimo del perfil es un fallo
                    // seguro; hasta el doble es mejorable; más allá está bien.
                    float razon = grosor / max(escena.planoN.w, 1e-4f);
                    float3 fino = float3(0.85f, 0.18f, 0.14f);
                    float3 justo = float3(0.95f, 0.62f, 0.12f);
                    float3 sano = float3(0.16f, 0.66f, 0.34f);
                    float3 tintado = mix(fino, justo, smoothstep(0.6f, 1.0f, razon));
                    tintado = mix(tintado, sano, smoothstep(1.0f, 2.0f, razon));
                    color = tintado * (0.25f * relleno + 0.75f * difusa) * ao;
                }

                // Niebla lejana para dar profundidad.
                color = mix(color, cielo, clamp(t / YK_DIST_MAXIMA * 3.0f, 0.0f, 1.0f));

                return float4(color, 1.0f);
            }
        """.trimIndent()
    }
}
