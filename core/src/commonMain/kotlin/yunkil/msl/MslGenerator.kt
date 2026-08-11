package yunkil.msl

import yunkil.kernel.AcuerdoLocal
import yunkil.kernel.AlisadoLocal
import yunkil.kernel.Axis
import yunkil.kernel.Barrido
import yunkil.kernel.Caja
import yunkil.kernel.CampoDeMalla
import yunkil.kernel.Capsula
import yunkil.kernel.Cilindro
import yunkil.kernel.Cono
import yunkil.kernel.Cordon
import yunkil.kernel.Diferencia
import yunkil.kernel.Desfase
import yunkil.kernel.Esfera
import yunkil.kernel.Extrusion
import yunkil.kernel.Interseccion
import yunkil.kernel.ESTRELLA_TETRAEDRO
import yunkil.kernel.ModoDeAcuerdo
import yunkil.kernel.MoverLocal
import yunkil.kernel.PellizcoLocal
import yunkil.kernel.Repeticion
import yunkil.kernel.RepeticionCircular
import yunkil.kernel.Revolucion
import yunkil.kernel.SdfNode
import yunkil.kernel.PerfilDeAcuerdo
import yunkil.kernel.Simetria
import yunkil.kernel.Toro
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.pasoSeguro
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
    /**
     * Los campos horneados que el shader lee como textura, en el orden en que hay que
     * enlazarlos.
     *
     * Va aquí y no lo busca el renderizador por su cuenta porque el orden es el del
     * preorden del árbol, que lo conoce el generador: si el renderizador lo dedujera
     * recorriendo el documento por su lado, el día que cambie el orden de emisión se
     * pintaría una malla con los datos de otra y nada lo diría.
     *
     * Lista vacía es el caso normal. Y entonces el shader se emite **exactamente igual
     * que antes**, sin parámetro de texturas: así los 24 casos del arnés de paridad no
     * cambian ni una letra y esta función no puede haber roto lo que ya funcionaba.
     */
    val campos: List<CampoEnShader> = emptyList(),
)

/** Un campo horneado listo para subir a una textura 3D. */
data class CampoEnShader(
    val nx: Int,
    val ny: Int,
    val nz: Int,
    /** `nx · ny · nz` distancias en milímetros, en orden `(k · ny + j) · nx + i`. */
    val muestras: FloatArray,
) {
    // `data class` con un array compara por identidad y avisa. Aquí es lo que se
    // quiere: dos campos son el mismo si son el mismo objeto, y comparar 7 millones
    // de floats en cada refresco de la interfaz sería absurdo.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = muestras.size * 31 + nx
}

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
    private val VERSION_DEL_GENERADOR = "v11"

    /**
     * Las ocho esquinas de una celda, en el mismo orden que las lee
     * `CampoDeMalla.evaluar`: c000, c100, c010, c110, c001, c101, c011, c111.
     *
     * El orden no es cosmético. Las mezclas se hacen en ese orden y en punto flotante
     * la suma no es asociativa, así que reordenarlas separaría el resultado del de la
     * CPU justo en los decimales que comprueba el arnés de paridad.
     */
    private val ESQUINAS = listOf(
        intArrayOf(0, 0, 0), intArrayOf(1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(1, 1, 0),
        intArrayOf(0, 0, 1), intArrayOf(1, 0, 1), intArrayOf(0, 1, 1), intArrayOf(1, 1, 1),
    )

    /**
     * @param fantasma árbol de la propuesta que se está mirando, o `null`.
     *
     * Con fantasma el shader lleva **dos** árboles: el documento en `yk_map` —que sigue
     * siendo la verdad y la que comprueba la paridad— y la propuesta en `yk_fantasma`.
     * El trazado avanza por el mínimo de los dos y el color de cada impacto sale de
     * comparar los dos signos justo por debajo de la superficie: dentro de los dos es
     * material que se queda, dentro solo del fantasma es material que se añade, dentro
     * solo del documento es material que se quita. Eso a un B-rep le cuesta dos
     * booleanas y aquí es restar dos distancias.
     *
     * Los dos buffers de uniforms viajan **concatenados**, en el mismo orden en que los
     * empaquetan los dos árboles, así que el fantasma emite sus índices desplazados por
     * el tamaño entero del primero.
     */
    fun generar(raiz: SdfNode, fantasma: SdfNode? = null): ShaderGenerado {
        // El preorden es el orden canónico: el de los uniforms y el de las cajas.
        // El mapa nodo → índice de caja lo comparten las dos versiones del cuerpo.
        val orden = raiz.preorden()
        val indiceDeCaja = HashMap<SdfNode, Int>().apply {
            orden.forEachIndexed { i, n -> put(n, i) }
        }
        val totalEscalares = orden.sumOf { it.escalares.size }

        // Los campos horneados, numerados también por preorden. Se numeran una vez y se
        // consultan desde los dos cuerpos: si cada cuerpo los fuera registrando por su
        // cuenta, el podado —que no baja por las ramas que descarta— asignaría índices
        // distintos y pintaría una malla con los datos de otra.
        val campos = orden.filterIsInstance<CampoDeMalla>()
        val indiceDeCampo = HashMap<SdfNode, Int>().apply {
            campos.forEachIndexed { i, n -> put(n, i) }
        }

        val cuerpo = StringBuilder()
        val estado = Estado()
        val resultado =
            emitir(raiz, "p", cuerpo, estado, podar = false, indiceDeCaja, totalEscalares, indiceDeCampo)

        val cuerpoPodado = StringBuilder()
        val estadoPodado = Estado()
        val resultadoPodado =
            emitir(raiz, "p", cuerpoPodado, estadoPodado, podar = true, indiceDeCaja, totalEscalares, indiceDeCampo)

        // Las cajas de los nodos viajan al final del buffer, en el orden del preorden:
        // primero todos los escalares —que es el orden que ya comprueba la paridad— y
        // después 6 floats por nodo (mínimo y máximo). Como ambos lados usan el mismo
        // preorden, el empaquetado y el shader no pueden desalinearse.
        val baseDeCajas = totalEscalares
        val numeroDeNodos = orden.size
        val uniformsDelDocumento = totalEscalares + 6 * numeroDeNodos

        // El fantasma se emite con el cursor arrancado donde acaba el buffer del
        // documento —escalares **y** cajas—, que es exactamente donde lo deja la
        // concatenación de los dos `empaquetarUniforms`. Sus campos horneados se numeran
        // a continuación de los del documento por la misma razón que dentro de un árbol:
        // el orden de enlace lo publica el generador, y si cada lado los numerara por su
        // cuenta se pintaría una malla con los datos de otra.
        val ordenFantasma = fantasma?.preorden() ?: emptyList()
        val camposFantasma = ordenFantasma.filterIsInstance<CampoDeMalla>()
        val cuerpoFantasma = StringBuilder()
        var resultadoFantasma = ""
        var uniformsDelFantasma = 0
        if (fantasma != null) {
            val escalaresFantasma = ordenFantasma.sumOf { it.escalares.size }
            uniformsDelFantasma = escalaresFantasma + 6 * ordenFantasma.size
            val cajas = HashMap<SdfNode, Int>().apply {
                ordenFantasma.forEachIndexed { i, n -> put(n, i) }
            }
            val deCampo = HashMap<SdfNode, Int>().apply {
                camposFantasma.forEachIndexed { i, n -> put(n, campos.size + i) }
            }
            val estadoFantasma = Estado().apply { cursorUniforms = uniformsDelDocumento }
            resultadoFantasma = emitir(
                fantasma, "p", cuerpoFantasma, estadoFantasma, podar = false,
                cajas, uniformsDelDocumento + escalaresFantasma, deCampo,
            )
        }

        // El orden importa: primero el fantasma —que **añade llamadas**— y después las
        // texturas, que es quien le pone el parámetro a todas las llamadas que haya. Al
        // revés, las del fantasma nacerían sin él y el shader no compilaría.
        val fuente = conCampos(
            conFantasma(
                buildString {
                    append(PRELUDIO)
                    append("\nfloat yk_map(float3 p, constant float *u) {\n")
                    append(cuerpo)
                    append("    return $resultado;\n}\n")
                    append("\nfloat yk_marcha(float3 p, constant float *u) {\n")
                    append(cuerpoPodado)
                    append("    return $resultadoPodado;\n}\n")
                    if (fantasma != null) {
                        append("\nfloat yk_fantasma(float3 p, constant float *u) {\n")
                        append(cuerpoFantasma)
                        append("    return $resultadoFantasma;\n}\n")
                    }
                    append(RAYMARCHER)
                },
                fantasma != null,
            ),
            campos.size + camposFantasma.size,
        )

        return ShaderGenerado(
            fuente = fuente,
            numeroDeUniforms = uniformsDelDocumento + uniformsDelFantasma,
            // Las mallas entran en la huella por su número **y su tamaño en celdas**: dos
            // documentos con la misma forma de árbol pero rejillas distintas necesitan
            // shaders distintos, porque las dimensiones van literales en el muestreo.
            huellaTopologica = VERSION_DEL_GENERADOR + huellaDe(raiz) +
                campos.joinToString("") { "T${it.anchoEnCeldas}x${it.altoEnCeldas}x${it.fondoEnCeldas}" } +
                // El fantasma va en la huella porque es quien manda recompilar: sin esto
                // la propuesta se enseñaría con el shader anterior —o sea, no se
                // enseñaría— y nada lo diría.
                (fantasma?.let {
                    "F" + huellaDe(it) +
                        camposFantasma.joinToString("") { c -> "T${c.anchoEnCeldas}x${c.altoEnCeldas}x${c.fondoEnCeldas}" }
                } ?: ""),
            // El trazado avanza por el mínimo de los dos campos, así que manda el más
            // exigente: quedarse con el del documento dejaría agujeros justo en el canto
            // que propone el fantasma.
            pasoSeguro = minOf(pasoSeguroDe(raiz), fantasma?.let { pasoSeguroDe(it) } ?: 1f),
            campos = (campos + camposFantasma).map {
                CampoEnShader(it.anchoEnCeldas, it.altoEnCeldas, it.fondoEnCeldas, it.muestras)
            },
        )
    }

    /**
     * Enchufa el fantasma al raymarcher, si lo hay.
     *
     * Por sustitución y no con una plantilla aparte, por lo mismo que las texturas: una
     * segunda copia del raymarcher se queda desactualizada el día que alguien toque la
     * primera, y esa divergencia no se nota hasta que la pantalla dibuja mal.
     *
     * No hay bandera en la escena a propósito. El shader con fantasma solo existe
     * mientras hay una propuesta en pantalla —al aceptarla o descartarla se regenera sin
     * él—, así que un `if` por píxel sería pagar en todos los fotogramas por un estado
     * que ya está en la huella. Y sin fantasma la fuente no cambia ni un byte.
     */
    private fun conFantasma(fuente: String, hay: Boolean): String {
        if (!hay) return fuente
        return fuente
            // La marcha y la distancia van por la unión de los dos campos: lo que se ve
            // es lo que hay más lo que se propone, y lo que se propone quitar sigue
            // ahí —en rojo— hasta que alguien acepte.
            .replace(
                "float d = yk_map(p, u);",
                "float d = min(yk_map(p, u), yk_fantasma(p, u));",
            )
            .replace(
                "float d = yk_marcha(p, u);",
                "float d = min(yk_marcha(p, u), yk_fantasma(p, u));",
            )
            // El color. Se lee un cuarto de milímetro **por dentro** de la superficie y
            // no en el punto de impacto: ahí los dos campos valen casi cero y el signo no
            // distingue nada. Por dentro sí: material que se queda está dentro de los
            // dos, material que se añade solo del fantasma, material que se quita solo
            // del documento.
            .replace(
                "float3 base = float3(0.82f, 0.80f, 0.76f);",
                """float3 base = float3(0.82f, 0.80f, 0.76f);
                bool ykCambio = false;
                {
                    float3 dentro = p - n * 0.25f;
                    float dDoc = yk_map(dentro, u);
                    float dProp = yk_fantasma(dentro, u);
                    if (dProp < 0.0f && dDoc >= 0.0f) { base = float3(0.22f, 0.74f, 0.38f); ykCambio = true; }
                    else if (dDoc < 0.0f && dProp >= 0.0f) { base = float3(0.88f, 0.26f, 0.20f); ykCambio = true; }
                }""",
            )
            // Y en la cara de corte manda el fantasma, no el tinte de grosor de pared.
            //
            // Esto no es un detalle: cuatro caras exteriores no enseñan lo que se quita
            // por dentro —la boca de un taladro de 5 mm son cincuenta píxeles, medidos—,
            // así que la sección **es** la forma de ver un vaciado propuesto. Con el
            // tinte de pared por encima, cortar por el agujero enseñaría lo bien o mal
            // que está la pared de una pieza que todavía no existe.
            .replace(
                "if (escena.plano.w > 0.0f && abs(dot(n, escena.planoN.xyz)) > 0.995f) {",
                "if (!ykCambio && escena.plano.w > 0.0f && abs(dot(n, escena.planoN.xyz)) > 0.995f) {",
            )
    }

    /**
     * Añade el parámetro de texturas a todas las firmas y llamadas, si hace falta.
     *
     * Se hace por sustitución sobre el shader ya montado y **solo cuando hay campos**.
     * Con cero campos la fuente sale sin tocar, byte a byte igual que antes de que esto
     * existiera: los 24 casos del arnés de paridad no se mueven, y eso convierte «no he
     * roto nada» en un hecho comprobable en vez de una promesa.
     *
     * Va por sustitución y no con dos plantillas porque una segunda copia del
     * raymarcher se queda desactualizada el día que alguien toque la primera, y ese es
     * el tipo de divergencia que aquí no se nota hasta que la pantalla dibuja mal.
     */
    private fun conCampos(fuente: String, cuantos: Int): String {
        if (cuantos == 0) return fuente
        val tipo = "array<texture3d<float>, $cuantos>"
        return fuente
            // 1. Las definiciones de las dos funciones generadas. Van primero para que
            //    la regla 4 no las confunda con una llamada.
            .replace(
                "float yk_map(float3 p, constant float *u)",
                "float yk_map(float3 p, constant float *u, $tipo yk_campos)",
            )
            .replace(
                "float yk_marcha(float3 p, constant float *u)",
                "float yk_marcha(float3 p, constant float *u, $tipo yk_campos)",
            )
            .replace(
                "float yk_fantasma(float3 p, constant float *u)",
                "float yk_fantasma(float3 p, constant float *u, $tipo yk_campos)",
            )
            // 2. Firmas de las funciones auxiliares del raymarcher.
            .replace(
                "constant float *u, constant YkEscena &escena",
                "constant float *u, $tipo yk_campos, constant YkEscena &escena",
            )
            // 3. La entrada del fragmento, que es la única que declara el enlace real.
            .replace(
                "constant YkEscena &escena [[buffer(2)]])",
                "constant YkEscena &escena [[buffer(2)]],\n" +
                    "                                        $tipo yk_campos [[texture(0)]])",
            )
            // 4. Las llamadas. Por expresión regular y no por texto literal: la primera
            //    versión sustituía «yk_map(p, u)» tal cual y se dejó fuera la llamada de
            //    la marcha de grosor de pared de la sección, que pasa otro punto
            //    (`yk_map(p - normal * avance, u)`). No compiló, que es la forma buena
            //    de enterarse, pero solo porque el arnés de paridad lo intentó: en la
            //    aplicación el error habría acabado en el registro y la pantalla se
            //    habría quedado con el shader anterior.
            .replace(Regex("""(yk_map|yk_marcha|yk_fantasma)\(([^;\n]*?), u\)""")) {
                "${it.groupValues[1]}(${it.groupValues[2]}, u, yk_campos)"
            }
            .replace(", u, escena)", ", u, yk_campos, escena)")
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
        indiceDeCampo: Map<SdfNode, Int>,
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
                sb.append("${sangria}float ${v}p = yk_perfil(float2($punto.x, $punto.z), u, ${b + 3}, ${nodo.perfil.poligono.size}, ${u(2)}, YK_SIN_EJE);\n")
                sb.append("${sangria}float ${v}d = ${v}p + ${u(1)};\n")
                sb.append("${sangria}float ${v}y = abs($punto.y) - ${u(0)} * 0.5f + ${u(1)};\n")
                sb.append("${sangria}float $d = min(max(${v}d, ${v}y), 0.0f) + length(max(float2(${v}d, ${v}y), float2(0.0f))) - ${u(1)};\n")
            }

            is CampoDeMalla -> {
                // La caja envolvente sigue emitiéndose desde uniforms como todo lo demás
                // —así mover la malla no recompila— y encima de ella va el muestreo de la
                // textura. Fuera de la caja se devuelve la distancia a la caja más la
                // banda, que es exactamente lo que hace `CampoDeMalla.evaluar`: una cota
                // **inferior** de la distancia real, que es lo que hace seguro avanzar a
                // saltos. Dentro, trilineal, que es lo que da el filtro lineal de Metal.
                val v = e.nuevaVariable("mm")
                val campo = indiceDeCampo[nodo] ?: 0
                sb.append("${sangria}float3 ${v}lo = float3(${u(0)}, ${u(1)}, ${u(2)});\n")
                sb.append("${sangria}float3 ${v}hi = float3(${u(3)}, ${u(4)}, ${u(5)});\n")
                sb.append("${sangria}float3 ${v}q = max(${v}lo - $punto, $punto - ${v}hi);\n")
                sb.append("${sangria}float ${v}f = length(max(${v}q, float3(0.0f))) + min(max(${v}q.x, max(${v}q.y, ${v}q.z)), 0.0f);\n")
                sb.append("${sangria}float $d;\n")
                sb.append("${sangria}if (${v}f > 0.0f) { $d = ${v}f + ${nodo.bandaDelCampo}f; } else {\n")
                // Trilineal **a mano**, con lecturas sin filtrar.
                //
                // El filtro lineal del muestreador de Metal hace esta misma cuenta y es
                // una línea en vez de doce, pero interpola con pesos de precisión
                // reducida: medido contra la CPU daba 3,8 µm de desvío en el 10 % de los
                // puntos del arnés. Son 3,8 µm y no se ven en la pantalla, y aun así se
                // descarta, porque el invariante del proyecto es que `evaluar` y el MSL
                // dan **el mismo número**. Un invariante con asterisco deja de servir
                // para lo que existe: distinguir un fallo real del ruido de siempre.
                //
                // Se calca la CPU paso a paso, incluido el orden de las mezclas: en
                // punto flotante, `(a+b)+c` y `a+(b+c)` no son lo mismo.
                sb.append("${sangria}    float3 ${v}g = clamp(($punto - ${v}lo) / ${nodo.resolucion}f, float3(0.0f), float3(${nodo.anchoEnCeldas - 1}.0f, ${nodo.altoEnCeldas - 1}.0f, ${nodo.fondoEnCeldas - 1}.0f));\n")
                sb.append("${sangria}    float3 ${v}b = min(floor(${v}g), float3(${nodo.anchoEnCeldas - 2}.0f, ${nodo.altoEnCeldas - 2}.0f, ${nodo.fondoEnCeldas - 2}.0f));\n")
                sb.append("${sangria}    float3 ${v}t = ${v}g - ${v}b;\n")
                sb.append("${sangria}    uint3 ${v}i = uint3(${v}b);\n")
                for ((k, esquina) in ESQUINAS.withIndex()) {
                    sb.append(
                        "${sangria}    float ${v}c$k = yk_campos[$campo].read(${v}i + uint3(${esquina[0]}, ${esquina[1]}, ${esquina[2]})).x;\n",
                    )
                }
                sb.append("${sangria}    float ${v}x0 = ${v}c0 + (${v}c1 - ${v}c0) * ${v}t.x;\n")
                sb.append("${sangria}    float ${v}x1 = ${v}c2 + (${v}c3 - ${v}c2) * ${v}t.x;\n")
                sb.append("${sangria}    float ${v}x2 = ${v}c4 + (${v}c5 - ${v}c4) * ${v}t.x;\n")
                sb.append("${sangria}    float ${v}x3 = ${v}c6 + (${v}c7 - ${v}c6) * ${v}t.x;\n")
                sb.append("${sangria}    float ${v}y0 = ${v}x0 + (${v}x1 - ${v}x0) * ${v}t.y;\n")
                sb.append("${sangria}    float ${v}y1 = ${v}x2 + (${v}x3 - ${v}x2) * ${v}t.y;\n")
                sb.append("${sangria}    $d = ${v}y0 + (${v}y1 - ${v}y0) * ${v}t.z;\n")
                sb.append("${sangria}}\n")
            }

            is Barrido -> {
                // El camino también vive en XZ, igual que el perfil de una extrusión:
                // se dibuja el recorrido sobre la mesa y la sección lo engorda.
                sb.append(
                    "${sangria}float $d = yk_barrido($punto, u, ${b + 1}, " +
                        "${nodo.perfil.poligono.size}, ${nodo.tramos}, ${u(0)});\n",
                )
            }

            is Cordon -> {
                // Sin escalar de cabecera: los vértices empiezan en la propia base, en
                // cuartetos (x, y, z, radio), que es justo lo que emite `escalares`.
                sb.append("${sangria}float $d = yk_cordon($punto, u, $b, ${nodo.puntos.size});\n")
            }

            is Revolucion -> {
                val v = e.nuevaVariable("rv")
                sb.append("${sangria}float2 ${v}q = float2(length($punto.xz) - ${u(0)}, $punto.y);\n")
                sb.append("${sangria}float $d = yk_perfil(${v}q, u, ${b + 2}, ${nodo.perfil.poligono.size}, ${u(1)}, -${u(0)});\n")
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
                val a = emitir(nodo.a, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
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
                    val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo, cursorYaReservado = true)
                    sb.append("$sangria    $d = yk_smin($a, $c, ${u(0)});\n")
                    sb.append("${sangria}}\n")
                } else {
                    val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                    sb.append("${sangria}float $d = yk_smin($a, $c, ${u(0)});\n")
                }
            }

            is Diferencia -> {
                val a = emitir(nodo.a, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                val cajaB = if (podar) podaDeCaja(nodo.b, punto, sb, e, indiceDeCaja, baseDeCajas) else null
                if (cajaB != null) {
                    // B está tan lejos que `-dB` no puede pasar de `a`: la resta no
                    // quita nada y el resultado es `a` exacto.
                    sb.append("${sangria}float $d;\n")
                    sb.append("${sangria}if ($cajaB > -$a + ${u(0)}) {\n")
                    sb.append("$sangria    $d = $a;\n")
                    sb.append("${sangria}} else {\n")
                    val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo, cursorYaReservado = true)
                    sb.append("$sangria    $d = yk_smax($a, -$c, ${u(0)});\n")
                    sb.append("${sangria}}\n")
                } else {
                    val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                    sb.append("${sangria}float $d = yk_smax($a, -$c, ${u(0)});\n")
                }
            }

            is Interseccion -> {
                // La intersección es el único booleano que no se puede podar con la
                // caja: necesita saber si B está *cerca* (dB pequeño), y la caja solo
                // acota por abajo la distancia al sólido. Se evalúa siempre.
                val a = emitir(nodo.a, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
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
                val a = emitir(nodo.a, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
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
                        val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo, cursorYaReservado = true)
                        val expresion = mezclaLocal(nodo, a, c, "${v}k")
                        sb.append("$sangria    $d = $expresion;\n")
                        sb.append("${sangria}}\n")
                    } else {
                        val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo, cursorYaReservado = true)
                        val expresion = mezclaLocal(nodo, a, c, "${v}k")
                        sb.append("${sangria}float $d = $expresion;\n")
                    }
                } else {
                    val c = emitir(nodo.b, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                    val expresion = mezclaLocal(nodo, a, c, "${v}k")
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
                val hijo = emitir(nodo.hijo, q, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                sb.append("${sangria}float $d = $hijo * ${u(13)};\n")
            }

            is Vaciado -> {
                val hijo = emitir(nodo.hijo, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                sb.append("${sangria}float $d = abs($hijo) - ${u(0)} * 0.5f;\n")
            }

            is Desfase -> {
                val hijo = emitir(nodo.hijo, punto, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                sb.append("${sangria}float $d = $hijo - ${u(0)};\n")
            }

            is AlisadoLocal -> {
                // El único nodo que evalúa a su hijo más de una vez, y por eso el único
                // que se emite con un bucle en vez de desenrollado: cinco copias del
                // subárbol entero multiplicarían por cinco el tamaño del shader y el
                // tiempo de compilación de una escultura de doscientas partes. El tope
                // del bucle es constante, que es la regla que no se negocia.
                val v = e.nuevaVariable("al")
                sb.append("${sangria}float ${v}w = 0.0f;\n")
                for (i in nodo.sitios.indices) {
                    val s = 1 + 5 * i
                    sb.append(
                        "$sangria${v}w = max(${v}w, ${u(s + 4)} * yk_caida(length($punto - " +
                            "float3(${u(s)}, ${u(s + 1)}, ${u(s + 2)})), ${u(s + 3)}));\n",
                    )
                }
                emitirMascara(nodo.mascara, 1 + 5 * nodo.sitios.size, v, punto, sb, ::u)
                if (nodo.mascara.isNotEmpty()) {
                    sb.append("$sangria${v}w = ${v}w * (1.0f - ${v}m);\n")
                }
                sb.append("${sangria}float ${v}c = 0.0f;\n")
                sb.append("${sangria}float ${v}s = 0.0f;\n")
                // La primera entrada es el punto sin desplazar, así que `punto + 0 * paso`
                // devuelve el punto exacto y el campo sin alisar sale bit a bit igual.
                sb.append(
                    "${sangria}const float3 ${v}e[5] = {float3(0.0f), " +
                        estrellaMsl() + "};\n",
                )
                sb.append("${sangria}for (int ${v}i = 0; ${v}i < 5; ++${v}i) {\n")
                sb.append("$sangria    if (${v}i > 0 && ${v}w <= 0.0f) { break; }\n")
                val q = e.nuevaVariable("aq")
                sb.append("$sangria    float3 $q = $punto + ${v}e[${v}i] * ${u(0)};\n")
                val hijo = emitir(nodo.hijo, q, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                sb.append("$sangria    if (${v}i == 0) { ${v}c = $hijo; } else { ${v}s += $hijo; }\n")
                sb.append("${sangria}}\n")
                sb.append(
                    "${sangria}float $d = (${v}w <= 0.0f) ? ${v}c : " +
                        "${v}c + ${v}w * (${v}s * 0.25f - ${v}c);\n",
                )
            }

            is PellizcoLocal -> {
                val v = e.nuevaVariable("pz")
                sb.append("${sangria}float3 ${v}t = float3(0.0f);\n")
                for (i in nodo.sitios.indices) {
                    val s = 8 * i
                    val radial = e.nuevaVariable("pr")
                    val eje = "float3(${u(s + 5)}, ${u(s + 6)}, ${u(s + 7)})"
                    sb.append(
                        "$sangria" + "float3 $radial = $punto - float3(${u(s)}, ${u(s + 1)}, ${u(s + 2)});\n",
                    )
                    // Solo la parte perpendicular al eje: apretar también a lo largo de él
                    // engordaría la zona en vez de afilarla.
                    sb.append(
                        "$sangria" + "float3 ${radial}t = $radial - $eje * dot($radial, $eje);\n",
                    )
                    sb.append(
                        "$sangria${v}t += ${radial}t * (${u(s + 4)} * " +
                            "yk_caida(length($radial), ${u(s + 3)}));\n",
                    )
                }
                emitirMascara(nodo.mascara, 8 * nodo.sitios.size, v, punto, sb, ::u)
                val factor = if (nodo.mascara.isEmpty()) "" else " * (1.0f - ${v}m)"
                sb.append("${sangria}float3 ${v}q = $punto + ${v}t$factor;\n")
                val hijo = emitir(nodo.hijo, "${v}q", sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                sb.append("${sangria}float $d = $hijo;\n")
            }

            is MoverLocal -> {
                val v = e.nuevaVariable("mv")
                sb.append("${sangria}float3 ${v}t = float3(0.0f);\n")
                for (i in nodo.sitios.indices) {
                    val s = 7 * i
                    sb.append(
                        "$sangria${v}t += float3(${u(s + 4)}, ${u(s + 5)}, ${u(s + 6)}) * " +
                            "yk_caida(length($punto - float3(${u(s)}, ${u(s + 1)}, ${u(s + 2)})), ${u(s + 3)});\n",
                    )
                }
                emitirMascara(nodo.mascara, 7 * nodo.sitios.size, v, punto, sb, ::u)
                val factor = if (nodo.mascara.isEmpty()) "" else " * (1.0f - ${v}m)"
                sb.append("${sangria}float3 ${v}q = $punto - ${v}t$factor;\n")
                val hijo = emitir(nodo.hijo, "${v}q", sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                sb.append("${sangria}float $d = $hijo;\n")
            }

            is Simetria -> {
                val q = e.nuevaVariable("m")
                val expr = when (nodo.eje) {
                    Axis.X -> "float3(abs($punto.x), $punto.y, $punto.z)"
                    Axis.Y -> "float3($punto.x, abs($punto.y), $punto.z)"
                    Axis.Z -> "float3($punto.x, $punto.y, abs($punto.z))"
                }
                sb.append("${sangria}float3 $q = $expr;\n")
                val hijo = emitir(nodo.hijo, q, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
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
                        val hijo = emitir(nodo.hijo, q, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                        sb.append("$sangria    $d = min($d, $hijo);\n")
                        sb.append("${sangria}}\n")
                    } else {
                        val hijo = emitir(nodo.hijo, q, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                        sb.append("${sangria}$d = min($d, $hijo);\n")
                    }
                    cursorTrasElHijo = e.cursorUniforms
                }
                e.cursorUniforms = cursorTrasElHijo
            }

            is RepeticionCircular -> {
                sb.append("${sangria}float $d = 1e30f;\n")
                val cursorAntesDelHijo = e.cursorUniforms
                var cursorTrasElHijo = cursorAntesDelHijo
                for (i in 0 until nodo.cuenta) {
                    e.cursorUniforms = cursorAntesDelHijo
                    val factor = when {
                        nodo.cuenta == 1 -> 0f
                        kotlin.math.abs(nodo.angulo) >= 359.999f -> i.toFloat() / nodo.cuenta
                        else -> i.toFloat() / (nodo.cuenta - 1)
                    }
                    val q = e.nuevaVariable("rc")
                    val a = e.nuevaVariable("ra")
                    sb.append("${sangria}float $a = ${lit(factor)} * ${u(0)} * 0.017453292519943295f;\n")
                    val expr = when (nodo.eje) {
                        Axis.X -> "float3($punto.x, cos($a) * $punto.y + sin($a) * $punto.z, -sin($a) * $punto.y + cos($a) * $punto.z)"
                        Axis.Y -> "float3(cos($a) * $punto.x + sin($a) * $punto.z, $punto.y, -sin($a) * $punto.x + cos($a) * $punto.z)"
                        Axis.Z -> "float3(cos($a) * $punto.x + sin($a) * $punto.y, -sin($a) * $punto.x + cos($a) * $punto.y, $punto.z)"
                    }
                    sb.append("${sangria}float3 $q = $expr;\n")
                    val hijo = emitir(nodo.hijo, q, sb, e, podar, indiceDeCaja, baseDeCajas, indiceDeCampo)
                    sb.append("${sangria}$d = min($d, $hijo);\n")
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
    /**
     * La mezcla de un acuerdo local, en MSL.
     *
     * Está aquí y no repetida tres veces —una por cada camino de poda— porque ya lo
     * estaba, y añadir el chaflán habría hecho seis. La correspondencia con
     * `AcuerdoLocal.evaluar` es lo que comprueba el arnés de paridad, y una expresión
     * copiada tres veces es una que se arregla dos.
     */
    private fun mezclaLocal(nodo: AcuerdoLocal, a: String, c: String, k: String): String {
        val redondo = nodo.perfil == PerfilDeAcuerdo.REDONDEO
        val menor = if (redondo) "yk_smin" else "yk_cmin"
        val mayor = if (redondo) "yk_smax" else "yk_cmax"
        return when (nodo.modo) {
            ModoDeAcuerdo.UNION -> "$menor($a, $c, $k)"
            ModoDeAcuerdo.DIFERENCIA -> "$mayor($a, -$c, $k)"
            ModoDeAcuerdo.INTERSECCION -> "$mayor($a, $c, $k)"
        }
    }

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
    private fun pasoSeguroDe(raiz: SdfNode): Float = raiz.pasoSeguro()

    /**
     * El peso de la máscara, común a las tres brochas.
     *
     * Se emite en una variable aparte y **solo cuando hay zonas protegidas**: sin máscara
     * la fuente sale exactamente igual que antes de que esto existiera, y eso convierte
     * «no he tocado lo que ya funcionaba» en algo que comprueba el arnés de paridad en vez
     * de una promesa.
     */
    private fun emitirMascara(
        mascara: List<*>,
        base: Int,
        v: String,
        punto: String,
        sb: StringBuilder,
        u: (Int) -> String,
    ) {
        if (mascara.isEmpty()) return
        sb.append("    float ${v}m = 0.0f;\n")
        for (i in mascara.indices) {
            val s = base + 5 * i
            sb.append(
                "    ${v}m = max(${v}m, ${u(s + 4)} * yk_caida(length($punto - " +
                    "float3(${u(s)}, ${u(s + 1)}, ${u(s + 2)})), ${u(s + 3)}));\n",
            )
        }
    }

    /**
     * El estencil del alisado, en literales.
     *
     * Sale de la misma lista que usa `AlisadoLocal.evaluar`, así que los cuatro puntos
     * que promedia la GPU son los cuatro que promedia la CPU. Escribirlos a mano en el
     * shader sería el sitio perfecto para un signo cambiado que la paridad tardaría en
     * cazar y que solo se vería como un alisado ligeramente torcido.
     */
    private fun estrellaMsl(): String =
        ESTRELLA_TETRAEDRO.joinToString(", ") { "float3(${lit(it.x)}, ${lit(it.y)}, ${lit(it.z)})" }

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
                    is Desfase -> "o"
                    // El número de sitios gobierna las líneas desenrolladas del peso y
                    // del desplazamiento, así que añadir uno sí recompila; mover uno ya
                    // puesto, o cambiarle la fuerza, no.
                    is AlisadoLocal -> "z${n.sitios.size}:${n.mascara.size}"
                    is PellizcoLocal -> "n${n.sitios.size}:${n.mascara.size}"
                    is MoverLocal -> "w${n.sitios.size}:${n.mascara.size}"
                    is Simetria -> "s${n.eje.name}"
                    is Repeticion -> "r${n.cuenta}${n.eje.name}"
                    is RepeticionCircular -> "rc${n.cuenta}${n.eje.name}"
                    is CampoDeMalla -> "M"
                    is Extrusion -> "X${n.perfil.poligono.size}"
                    // El número de tramos gobierna el bucle del shader, así que
                    // abrir o cerrar el camino sí es un cambio de topología.
                    is Barrido -> "B${n.perfil.poligono.size}:${n.tramos}"
                    is Revolucion -> "V${n.perfil.poligono.size}"
                    // El número de vértices gobierna el bucle del cordón; moverlos o
                    // cambiarles el grosor, no: eso solo reescribe uniforms.
                    is Cordon -> "D${n.puntos.size}"
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

            // Tope de vértices de un cordón. Coincide con Cordon.MAXIMO_DE_PUNTOS.
            constant int YK_MAX_CORDON = 64;

            // Perfil que no gira, y tolerancia del eje. Copian Perfil2D.SIN_EJE y
            // Perfil2D.TOLERANCIA_DEL_EJE: el mismo número a los dos lados o no hay paridad.
            constant float YK_SIN_EJE = 3.4e38f;
            constant float YK_TOLERANCIA_EJE = 1e-5f;

            // Distancia con signo a un polígono cerrado cuyos vértices vienen en el
            // buffer de uniforms a partir de `base`, en pares (x, y).
            //
            // El signo sale de contar cruces de un rayo horizontal, no del sentido de
            // giro del contorno: así un perfil escrito al revés no invierte el sólido.
            // `xEje` es la abscisa del eje de revolución, o YK_SIN_EJE si el perfil no
            // gira. Las aristas que caen enteras sobre esa vertical no cuentan para la
            // distancia: al girar se colapsan en el eje y no barren superficie alguna.
            // Es la misma excepción que hace Perfil2D.evaluar, y tiene que serlo:
            // si aquí y allí no sale el mismo número, el visor enseña una pieza y el
            // analizador razona sobre otra.
            inline float yk_perfil(float2 p, constant float *u, int base, int n, float redondeo, float xEje) {
                float mejor = 3.4e38f;
                bool dentro = false;
                for (int i = 0; i < YK_MAX_VERTICES; ++i) {
                    if (i >= n) break;
                    int j = (i + 1 == n) ? 0 : i + 1;
                    float2 a = float2(u[base + i * 2], u[base + i * 2 + 1]);
                    float2 b = float2(u[base + j * 2], u[base + j * 2 + 1]);

                    bool enElEje = xEje != YK_SIN_EJE &&
                        abs(a.x - xEje) <= YK_TOLERANCIA_EJE && abs(b.x - xEje) <= YK_TOLERANCIA_EJE;
                    if (!enElEje) {
                        float2 arista = b - a;
                        float2 hacia = p - a;
                        float t = clamp(dot(hacia, arista) / max(dot(arista, arista), 1e-20f), 0.0f, 1.0f);
                        float2 cercano = hacia - arista * t;
                        mejor = min(mejor, dot(cercano, cercano));
                    }

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

            // Distancia exacta al casco convexo de dos bolas. Es la traducción literal
            // de `conoRedondeado` en Sdf.kt; la paridad comprueba que siguen diciendo lo
            // mismo. `sign` vale cero en el cero en los dos lados, y de ese cero dependen
            // las dos tapas.
            inline float yk_cono_redondeado(float3 p, float3 a, float3 b, float ra, float rb) {
                float3 ba = b - a;
                float l2 = dot(ba, ba);
                float rr = ra - rb;
                float a2 = l2 - rr * rr;
                if (l2 <= 1e-12f || a2 <= 1e-9f) {
                    return min(length(p - a) - ra, length(p - b) - rb);
                }
                float il2 = 1.0f / l2;
                float3 pa = p - a;
                float y = dot(pa, ba);
                float z = y - l2;
                float3 xp = pa * l2 - ba * y;
                float x2 = dot(xp, xp);
                float y2 = y * y * l2;
                float z2 = z * z * l2;
                float k = sign(rr) * rr * rr * x2;
                if (sign(z) * a2 * z2 > k) return sqrt(x2 + z2) * il2 - rb;
                if (sign(y) * a2 * y2 < k) return sqrt(x2 + y2) * il2 - ra;
                return (sqrt(x2 * a2 * il2) + y * rr) * il2 - ra;
            }

            // Cordón: mínimo de conos redondeados a lo largo de una polilínea 3D. Los
            // vértices vienen en el buffer desde `base`, en cuartetos (x, y, z, radio).
            inline float yk_cordon(float3 p, constant float *u, int base, int n) {
                float mejor = 1e20f;
                for (int i = 0; i < YK_MAX_CORDON; ++i) {
                    if (i + 1 >= n) break;
                    int j = base + i * 4;
                    float3 a = float3(u[j], u[j + 1], u[j + 2]);
                    float3 b = float3(u[j + 4], u[j + 5], u[j + 6]);
                    mejor = min(mejor, yk_cono_redondeado(p, a, b, u[j + 3], u[j + 7]));
                }
                return mejor;
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

            inline float yk_cmin(float a, float b, float k) {
                if (k <= 0.0f) return min(a, b);
                float exacto = min(a, b);
                float cruzado = (a + b - k) * 0.70710678f;
                return max(min(exacto, cruzado), exacto - k * 0.70710678f);
            }

            inline float yk_cmax(float a, float b, float k) {
                return -yk_cmin(-a, -b, k);
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
