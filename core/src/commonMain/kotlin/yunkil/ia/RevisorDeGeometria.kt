package yunkil.ia

import yunkil.doc.Documento
import yunkil.doc.Pieza
import yunkil.doc.TipoPieza
import yunkil.fabricacion.PerfilFabricacion
import yunkil.fabricacion.hojasEnMundo
import yunkil.kernel.Aabb
import yunkil.kernel.SdfNode
import yunkil.kernel.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Las comprobaciones que el analizador de fabricación **no** puede hacer.
 *
 * `AnalizadorFdm` responde a «¿saldrá esto de la impresora?». Eso deja fuera toda
 * una familia de fallos que no son de impresión sino de modelado: una tapa que se
 * quedó flotando sobre el cuerpo tiene material debajo, así que para el analizador
 * es un voladizo de 90° —un aviso naranja que el usuario acepta a diario— cuando en
 * realidad son dos sólidos sueltos y la pieza pedida no existe.
 *
 * Esa distinción es justo la que un modelo de lenguaje no puede hacer solo: acierta
 * las cotas y falla el contacto, porque el contacto no está en los números que se
 * le enseñan, está en el campo.
 */
/** Qué clase de fallo de modelado se encontró. Decide si un arreglo lo resolvió. */
enum class ClaseDeFallo { RESTA_SIN_EFECTO, SOLIDOS_SUELTOS }

/**
 * Un fallo de modelado con, cuando se puede, la operación que lo arregla.
 *
 * El arreglo no es una sugerencia redactada: es la misma operación del vocabulario
 * que aplicaría el usuario, lista para ejecutarse. La diferencia importa porque el
 * destinatario del motivo es un modelo de lenguaje, y un modelo que tiene que
 * transcribir una operación falla al transcribirla.
 */
data class Reparo(
    val clase: ClaseDeFallo,
    val motivo: String,
    val arreglos: List<Operacion> = emptyList(),
)

object RevisorDeGeometria {

    /**
     * Celdas por el lado más largo del modelo.
     *
     * Es un compromiso medido: con 64 el peor caso son 262 144 evaluaciones del
     * campo, el mismo orden que ya paga el analizador, y detecta separaciones a
     * partir de un 1,5 % del tamaño de la pieza. Separaciones más finas que eso son
     * un problema de tolerancias, no un modelo mal montado, y las coge el analizador
     * por otro lado como pared o detalle mínimo.
     */
    private const val CELDAS_POR_LADO = 64

    /**
     * Tope de celdas por eje, para que el coste no se dispare con la pieza.
     *
     * Por encima de esto se acepta perder detalle antes que tardar segundos: son
     * 226 millones de evaluaciones en el peor caso, y esto corre dentro de un bucle
     * de reintentos que se paga tres veces por petición.
     */
    private const val CELDAS_MAXIMAS_POR_LADO = 192

    fun revisar(
        documento: Documento,
        nodo: SdfNode,
        perfil: PerfilFabricacion = PerfilFabricacion.PREDETERMINADO,
    ): List<String> = reparos(documento, nodo, perfil).map { it.motivo }

    /**
     * Lo mismo que [revisar], pero con la operación que arregla cada fallo.
     *
     * Se separa de `revisar` porque los dos consumidores son distintos y quieren
     * cosas distintas: el bucle del modelo quiere el texto para la ronda de
     * corrección, y el cosido quiere la operación para aplicarla y volver a medir.
     */
    fun reparos(
        documento: Documento,
        nodo: SdfNode,
        perfil: PerfilFabricacion = PerfilFabricacion.PREDETERMINADO,
    ): List<Reparo> =
        motivosDeRestasInutiles(documento).map { Reparo(ClaseDeFallo.RESTA_SIN_EFECTO, it) } +
            listOfNotNull(reparoDePiezasSueltas(documento, nodo, perfil))

    /**
     * Diferencias cuyo sustraendo no llega a tocar a lo que debía agujerear.
     *
     * Es el fallo más silencioso del catálogo: el plan es válido, la operación se
     * aplica, el árbol queda como el modelo lo describió y no hay agujero. Ni el
     * analizador de fabricación lo ve, porque una pieza sin el agujero que le
     * faltaba se imprime perfectamente.
     *
     * Se denuncia **solo** cuando las cotas están separadas de verdad, que es una
     * comprobación exacta y no una estimación. Un sustraendo que sí solapa cotas
     * pero falla por la forma del campo se escapa de aquí a propósito: este aviso
     * alimenta un reintento automático, y un falso positivo mandaría al modelo a
     * arreglar algo que ya estaba bien.
     */
    private fun motivosDeRestasInutiles(documento: Documento): List<String> {
        val salida = ArrayList<String>()

        fun visitar(pieza: Pieza) {
            if (!pieza.visible) return
            if (pieza.tipo == TipoPieza.DIFERENCIA && pieza.hijos.size >= 2) {
                val minuendo = documento.nodoEnMundoDe(pieza.hijos[0].id)?.cotas()
                if (minuendo != null) {
                    for (sustraendo in pieza.hijos.drop(1)) {
                        if (!sustraendo.visible) continue
                        val cotas = documento.nodoEnMundoDe(sustraendo.id)?.cotas() ?: continue
                        val hueco = separacionEntre(minuendo, cotas) ?: continue
                        salida.add(
                            "«${sustraendo.nombre}» está dentro de una DIFERENCIA pero no toca a " +
                                "«${pieza.hijos[0].nombre}»: se queda a $hueco mm, así que no quita " +
                                "nada de material y el agujero no existe. Colócalo atravesando la " +
                                "pieza que tiene que perforar.",
                        )
                    }
                }
            }
            pieza.hijos.forEach(::visitar)
        }

        visitar(documento.raiz)
        return salida
    }

    /** Separación entre dos cajas, o `null` si se tocan o se solapan. */
    private fun separacionEntre(a: Aabb, b: Aabb): String? {
        val dx = max(a.min.x - b.max.x, b.min.x - a.max.x)
        val dy = max(a.min.y - b.max.y, b.min.y - a.max.y)
        val dz = max(a.min.z - b.max.z, b.min.z - a.max.z)
        val hueco = max(max(dx, dy), dz)
        if (hueco <= 0f || !hueco.isFinite()) return null
        val mm = (hueco * 10f).roundToInt() / 10f
        return if (mm == mm.toInt().toFloat()) mm.toInt().toString() else mm.toString()
    }

    /**
     * Comprueba que el modelo sea **un solo sólido**.
     *
     * Se etiquetan las celdas con material y se propaga por vecindad a 6. Si sale
     * más de un grupo, el modelo son varias piezas que no se tocan: se conserva la
     * mayor como cuerpo principal y se nombran las demás.
     */
    private fun reparoDePiezasSueltas(
        documento: Documento,
        nodo: SdfNode,
        perfil: PerfilFabricacion,
    ): Reparo? {
        val rejilla = Rejilla.de(nodo, perfil) ?: return null
        val grupos = rejilla.agrupar()
        if (grupos.numeroDeGrupos <= 1) return null

        val principal = grupos.grupoMayor()
        val sueltos = (0 until grupos.numeroDeGrupos).filter { it != principal }

        // Dos nombres por grupo, y no es redundancia: la hoja es lo que se nombra en
        // el aviso —«la Tapa flota»— y la rama es lo que hay que mover, porque mover
        // una hoja suelta dentro de un grupo dejaría atrás el resto de su rama.
        val hojas = nombresPorGrupo(documento, rejilla, grupos)
        val ramas = ramasPorGrupo(documento, rejilla, grupos)
        fun movible(grupo: Int) = ramas[grupo] ?: hojas[grupo]

        val descritos = sueltos.map { grupo ->
            val nombre = hojas[grupo] ?: ramas[grupo]
            val separacion = grupos.separacionHasta(rejilla, grupo, principal)
            when {
                nombre != null -> "«$nombre»"
                else -> "un sólido en ${rejilla.centroDe(grupos, grupo).aTexto()}"
            } + if (separacion != null) " (a $separacion mm de lo más cercano)" else ""
        }

        // Las operaciones que unirían cada sólido suelto al cuerpo, y de ellas sale
        // también el texto que ve el modelo. Una sola fuente: si el arreglo que se
        // aplica y el que se le enseña al modelo pudieran divergir, el día que
        // divergieran nadie se enteraría.
        val arreglos = sueltos.mapNotNull { grupo ->
            unionPara(rejilla, grupos, grupo, principal, ::movible, perfil)
        }
        val receta = if (arreglos.isEmpty()) {
            ""
        } else {
            "Escribe exactamente ${if (arreglos.size > 1) "estas operaciones" else "esta operación"}: " +
                arreglos.joinToString(" ") { it.comoJson() } + ". "
        }

        val motivo = "El modelo son ${grupos.numeroDeGrupos} sólidos sueltos que no se tocan: " +
            descritos.joinToString(", ") + " flota${if (descritos.size > 1) "n" else ""} en el aire. " +
            "Una pieza pedida como una sola tiene que quedar unida. " +
            "NO uses «mover» con coordenadas para arreglarlo: usa «colocar», que lee las cotas " +
            "reales. " + receta +
            "Si las dos partes tienen que solaparse, hazlo al menos el grosor de una pared."
        return Reparo(ClaseDeFallo.SOLIDOS_SUELTOS, motivo, arreglos)
    }

    /**
     * El `colocar` que pegaría un sólido suelto al cuerpo principal.
     *
     * La cara no se adivina: sale de en qué dirección está el sólido suelto respecto al
     * cuerpo. Proponer «arriba» para algo que está a la izquierda manda al modelo a mover
     * la pieza al sitio equivocado con toda la confianza del mundo.
     *
     * La holgura es negativa a propósito. Dos sólidos que se tocan exactamente en un
     * plano son un sólido para el campo, pero un filo para el mallador y una junta a
     * tope para la impresora; solapar el grosor de una pared es lo que dice la propia
     * receta que se le da al modelo, y el arreglo automático cumple su consejo. El
     * solape se limita a un cuarto de lo que mide la pieza en ese eje: pasado ahí ya
     * no se está soldando, se está tragando la pieza y cambiando la cota pedida.
     */
    private fun unionPara(
        rejilla: Rejilla,
        grupos: Grupos,
        suelto: Int,
        principal: Int,
        nombreDe: (Int) -> String?,
        perfil: PerfilFabricacion,
    ): Colocar? {
        val objetivo = nombreDe(suelto) ?: return null
        val referencia = nombreDe(principal) ?: return null
        if (objetivo == referencia) return null

        val centro = rejilla.centroDe(grupos, suelto)
        val centroPrincipal = rejilla.centroDe(grupos, principal)
        val dx = centro.x - centroPrincipal.x
        val dy = centro.y - centroPrincipal.y
        val dz = centro.z - centroPrincipal.z

        val caja = rejilla.cajaDe(grupos, suelto)
        val (cara, grueso) = when {
            abs(dy) >= abs(dx) && abs(dy) >= abs(dz) ->
                (if (dy >= 0f) Cara.ARRIBA else Cara.ABAJO) to caja.size.y
            abs(dx) >= abs(dz) ->
                (if (dx >= 0f) Cara.DERECHA else Cara.IZQUIERDA) to caja.size.x
            else ->
                (if (dz >= 0f) Cara.DELANTE else Cara.DETRAS) to caja.size.z
        }
        val solape = min(perfil.grosorMinimoPared, grueso * 0.25f)

        return Colocar(
            objetivo = objetivo,
            referencia = referencia,
            cara = cara,
            holgura = -solape,
            // Sin centrar: el arreglo mueve la pieza lo mínimo para que toque, y
            // recentrarla desharía un desplazamiento que podía ser intencionado —una
            // oreja de anclaje va en una esquina, no en medio—. Quien cose reintenta
            // con centrado si esto no basta, que es un dato y no una suposición.
            centrar = false,
        )
    }

    /** La operación tal y como se le enseña al modelo para que la copie. */
    private fun Colocar.comoJson(): String {
        val h = ((holgura * 100f).roundToInt() / 100f)
        return """{"op":"colocar","objetivo":"$objetivo","referencia":"$referencia",""" +
            """"cara":"${cara.etiqueta}","holgura":$h,"centrar":$centrar}"""
    }

    /**
     * La rama más alta que cae **entera** dentro de un grupo: lo que se puede mover.
     *
     * Se baja desde la raíz mientras la pieza mezcle sólidos de varios grupos, y se
     * para en cuanto todo lo que cuelga de ella es del mismo. Es la unidad correcta
     * del movimiento: si una tapa suelta es en realidad una DIFERENCIA con su
     * agujero, mover solo el cilindro del agujero deja la tapa donde estaba y
     * además le quita el agujero.
     */
    private fun ramasPorGrupo(
        documento: Documento,
        rejilla: Rejilla,
        grupos: Grupos,
    ): Map<Int, String> {
        val grupoDeHoja = HashMap<String, Int>()
        for (hoja in documento.hojasEnMundo()) {
            if (hoja.esSustraendo) continue
            grupos.grupoEn(rejilla, hoja.nodo.cotas().center)?.let { grupoDeHoja[hoja.piezaId] = it }
        }

        fun gruposBajo(pieza: Pieza): Set<Int> {
            if (!pieza.visible) return emptySet()
            if (pieza.hijos.isEmpty()) return setOfNotNull(grupoDeHoja[pieza.id])
            return pieza.hijos.flatMap { gruposBajo(it) }.toSet()
        }

        val salida = HashMap<Int, String>()
        fun asignar(pieza: Pieza) {
            val suyos = gruposBajo(pieza)
            if (suyos.size == 1) {
                val grupo = suyos.first()
                if (!salida.containsKey(grupo)) salida[grupo] = pieza.nombre
            } else {
                pieza.hijos.forEach(::asignar)
            }
        }
        // Desde los hijos de la raíz: la raíz contiene todos los grupos por
        // definición, y proponerla como objetivo sería proponer mover el modelo entero.
        documento.raiz.hijos.forEach(::asignar)
        return salida
    }

    /**
     * Nombre representativo de cada grupo, tomado de las piezas del documento.
     *
     * Se mira en qué grupo cae el centro de cada hoja aditiva. Es directo y no
     * necesita tolerancias: si el centro de la «Tapa» cae en el grupo 1, el grupo 1
     * es la tapa. Las hojas huecas cuyo centro no tiene material simplemente no
     * nombran a nadie, y el grupo se describe por su posición.
     */
    private fun nombresPorGrupo(
        documento: Documento,
        rejilla: Rejilla,
        grupos: Grupos,
    ): Map<Int, String> {
        val salida = HashMap<Int, String>()
        for (hoja in documento.hojasEnMundo()) {
            if (hoja.esSustraendo) continue
            val grupo = grupos.grupoEn(rejilla, hoja.nodo.cotas().center) ?: continue
            // La primera hoja que caiga en el grupo le da nombre; `putIfAbsent` no
            // existe fuera de la JVM y este núcleo también compila para iPad.
            if (!salida.containsKey(grupo)) salida[grupo] = hoja.nombre
        }
        return salida
    }

    // ---------------------------------------------------------------- rejilla

    /** El campo muestreado en celdas regulares: `true` donde hay material. */
    internal class Rejilla(
        val origen: Vec3,
        val paso: Float,
        val nx: Int,
        val ny: Int,
        val nz: Int,
        val lleno: BooleanArray,
    ) {
        fun indice(x: Int, y: Int, z: Int) = (z * ny + y) * nx + x

        fun centroDeCelda(x: Int, y: Int, z: Int) = Vec3(
            origen.x + (x + 0.5f) * paso,
            origen.y + (y + 0.5f) * paso,
            origen.z + (z + 0.5f) * paso,
        )

        fun celdaDe(p: Vec3): Triple<Int, Int, Int>? {
            val x = ((p.x - origen.x) / paso).toInt()
            val y = ((p.y - origen.y) / paso).toInt()
            val z = ((p.z - origen.z) / paso).toInt()
            if (x !in 0 until nx || y !in 0 until ny || z !in 0 until nz) return null
            return Triple(x, y, z)
        }

        /** Caja envolvente de un grupo, en celdas enteras. Basta para dosificar un solape. */
        fun cajaDe(grupos: Grupos, grupo: Int): Aabb {
            var min = Vec3.splat(Float.MAX_VALUE)
            var max = Vec3.splat(-Float.MAX_VALUE)
            for (z in 0 until nz) for (y in 0 until ny) for (x in 0 until nx) {
                if (grupos.etiqueta[indice(x, y, z)] != grupo) continue
                val c = centroDeCelda(x, y, z)
                min = Vec3(min(min.x, c.x - paso * 0.5f), min(min.y, c.y - paso * 0.5f), min(min.z, c.z - paso * 0.5f))
                max = Vec3(max(max.x, c.x + paso * 0.5f), max(max.y, c.y + paso * 0.5f), max(max.z, c.z + paso * 0.5f))
            }
            return if (min.x > max.x) Aabb(Vec3.ZERO, Vec3.ZERO) else Aabb(min, max)
        }

        fun centroDe(grupos: Grupos, grupo: Int): Vec3 {
            var suma = Vec3.ZERO
            var cuenta = 0
            for (z in 0 until nz) for (y in 0 until ny) for (x in 0 until nx) {
                if (grupos.etiqueta[indice(x, y, z)] == grupo) {
                    suma += centroDeCelda(x, y, z)
                    cuenta++
                }
            }
            return if (cuenta == 0) Vec3.ZERO else suma * (1f / cuenta)
        }

        companion object {
            fun de(nodo: SdfNode, perfil: PerfilFabricacion): Rejilla? {
                val cotas = nodo.cotas()
                val tamano = cotas.size
                val lado = max(max(tamano.x, tamano.y), tamano.z)
                if (!lado.isFinite() || lado <= 0f) return null

                // La celda se ata al grosor mínimo imprimible y no solo al tamaño de
                // la pieza. Dividir el lado entre 64 y punto parecía razonable hasta
                // que una estrella extruida salió partida en tres sólidos sueltos: su
                // talle es más fino que una celda, el relleno no podía cruzarlo y el
                // revisor denunciaba una pieza perfectamente entera. Un falso positivo
                // aquí manda al modelo a arreglar algo que nunca estuvo roto.
                val paso = min(lado / CELDAS_POR_LADO, perfil.grosorMinimoPared * 0.5f)
                    .coerceAtLeast(lado / CELDAS_MAXIMAS_POR_LADO)
                // Un margen de una celda evita que una pieza pegada al borde del
                // volumen se corte y aparente estar suelta.
                val origen = cotas.min - Vec3.splat(paso)
                val nx = ceil(tamano.x / paso).toInt() + 3
                val ny = ceil(tamano.y / paso).toInt() + 3
                val nz = ceil(tamano.z / paso).toInt() + 3

                val lleno = BooleanArray(nx * ny * nz)
                var i = 0
                for (z in 0 until nz) for (y in 0 until ny) for (x in 0 until nx) {
                    val p = Vec3(
                        origen.x + (x + 0.5f) * paso,
                        origen.y + (y + 0.5f) * paso,
                        origen.z + (z + 0.5f) * paso,
                    )
                    lleno[i++] = nodo.evaluar(p) <= 0f
                }
                return Rejilla(origen, paso, nx, ny, nz, lleno)
            }
        }
    }

    /** Etiquetas de componente conexa por celda; `-1` donde no hay material. */
    internal class Grupos(val etiqueta: IntArray, val numeroDeGrupos: Int) {

        fun grupoMayor(): Int {
            val cuentas = IntArray(numeroDeGrupos)
            for (e in etiqueta) if (e >= 0) cuentas[e]++
            var mejor = 0
            for (g in 1 until numeroDeGrupos) if (cuentas[g] > cuentas[mejor]) mejor = g
            return mejor
        }

        fun grupoEn(rejilla: Rejilla, punto: Vec3): Int? {
            val (x, y, z) = rejilla.celdaDe(punto) ?: return null
            return etiqueta[rejilla.indice(x, y, z)].takeIf { it >= 0 }
        }

        /**
         * Distancia mínima entre dos grupos, redondeada a milímetro.
         *
         * Va en el aviso porque el número cambia la corrección: 30 mm es una pieza
         * que el modelo se olvidó de apoyar, y 0,2 mm es un solape que se quedó corto.
         *
         * Solo entran celdas de **borde**: el interior de un sólido nunca puede ser
         * lo más cercano a otro sólido, así que compararlo es tiempo tirado, y en una
         * caja maciza el interior es la inmensa mayoría de las celdas. Comparar todo
         * contra todo costaba segundos, que en un bucle de reintentos se pagan tres
         * veces.
         */
        fun separacionHasta(rejilla: Rejilla, grupo: Int, otro: Int): String? {
            val unos = rejilla.bordeDe(this, grupo)
            val otros = rejilla.bordeDe(this, otro)
            if (unos.isEmpty() || otros.isEmpty()) return null
            var minimo = Float.MAX_VALUE
            for (a in unos) for (b in otros) {
                val d = (a - b).length()
                if (d < minimo) minimo = d
            }
            if (!minimo.isFinite()) return null
            val mm = (minimo * 10f).roundToInt() / 10f
            return if (mm == mm.toInt().toFloat()) mm.toInt().toString() else mm.toString()
        }
    }

    private fun Rejilla.etiquetaEn(grupos: Grupos, x: Int, y: Int, z: Int) =
        grupos.etiqueta[indice(x, y, z)]

    /**
     * Celdas del grupo que asoman a la superficie, submuestreadas si son muchas.
     *
     * El tope existe para que el coste no dependa del tamaño de la pieza: la cifra
     * que sale de aquí se redondea a un decimal de milímetro y se le enseña a un
     * modelo, así que perder una celda de precisión no cambia nada, mientras que
     * quedarse sin tope sí cambia el tiempo de respuesta.
     */
    internal fun Rejilla.bordeDe(grupos: Grupos, grupo: Int): List<Vec3> {
        val todas = ArrayList<Vec3>()
        for (z in 0 until nz) for (y in 0 until ny) for (x in 0 until nx) {
            if (etiquetaEn(grupos, x, y, z) != grupo) continue
            val asoma = VECINOS.any { d ->
                val vx = x + d[0]
                val vy = y + d[1]
                val vz = z + d[2]
                vx !in 0 until nx || vy !in 0 until ny || vz !in 0 until nz || !lleno[indice(vx, vy, vz)]
            }
            if (asoma) todas.add(centroDeCelda(x, y, z))
        }
        if (todas.size <= MAXIMO_DE_CELDAS_DE_BORDE) return todas
        val salto = todas.size / MAXIMO_DE_CELDAS_DE_BORDE + 1
        return todas.filterIndexed { i, _ -> i % salto == 0 }
    }

    private const val MAXIMO_DE_CELDAS_DE_BORDE = 1500

    /**
     * Etiquetado de componentes conexas por vecindad a 6, con pila explícita.
     *
     * La pila es explícita y no recursiva a propósito: una pieza maciza en una
     * rejilla de 64³ encadena decenas de miles de celdas y la recursión desborda.
     */
    internal fun Rejilla.agrupar(): Grupos {
        val etiqueta = IntArray(lleno.size) { -1 }
        var siguiente = 0
        val pila = ArrayDeque<Int>()

        for (inicio in lleno.indices) {
            if (!lleno[inicio] || etiqueta[inicio] >= 0) continue
            val grupo = siguiente++
            etiqueta[inicio] = grupo
            pila.addLast(inicio)

            while (pila.isNotEmpty()) {
                val actual = pila.removeLast()
                val x = actual % nx
                val y = (actual / nx) % ny
                val z = actual / (nx * ny)

                for (d in VECINOS) {
                    val vx = x + d[0]
                    val vy = y + d[1]
                    val vz = z + d[2]
                    if (vx !in 0 until nx || vy !in 0 until ny || vz !in 0 until nz) continue
                    val v = indice(vx, vy, vz)
                    if (!lleno[v] || etiqueta[v] >= 0) continue
                    etiqueta[v] = grupo
                    pila.addLast(v)
                }
            }
        }
        return Grupos(etiqueta, siguiente)
    }

    private val VECINOS = arrayOf(
        intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
        intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
        intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
    )
}
