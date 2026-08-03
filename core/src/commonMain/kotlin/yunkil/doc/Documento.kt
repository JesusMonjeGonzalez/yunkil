package yunkil.doc

import kotlinx.serialization.Serializable
import yunkil.kernel.Axis
import yunkil.kernel.Caja
import yunkil.kernel.Capsula
import yunkil.kernel.Cilindro
import yunkil.kernel.Cono
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.Interseccion
import yunkil.kernel.Repeticion
import yunkil.kernel.SdfNode
import yunkil.kernel.Simetria
import yunkil.kernel.Toro
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vaciado
import yunkil.kernel.Vec3

/**
 * Familia de una pieza. Determina qué parámetros tiene y cómo se compila al árbol
 * de distancias.
 *
 * El documento es una estructura distinta del árbol SDF a propósito: el usuario
 * edita piezas con nombre, dimensiones en milímetros y jerarquía, mientras que el
 * kernel solo entiende de campos. Mantenerlos separados deja el kernel puro y
 * testeable, y permite que el documento crezca (nombres, visibilidad, historial)
 * sin tocar las matemáticas.
 */
@Serializable
enum class TipoPieza(
    val etiqueta: String,
    val esOperacion: Boolean,
    val admiteHijos: Boolean,
) {
    ESFERA("Esfera", false, false),
    CAJA("Caja", false, false),
    CILINDRO("Cilindro", false, false),
    CONO("Cono", false, false),
    TORO("Toro", false, false),
    CAPSULA("Cápsula", false, false),

    UNION("Unión", true, true),
    DIFERENCIA("Diferencia", true, true),
    INTERSECCION("Intersección", true, true),

    VACIADO("Vaciado", true, true),
    SIMETRIA("Simetría", true, true),
    REPETICION("Repetición", true, true);

    /** Parámetros editables, en el orden en que deben aparecer en el inspector. */
    val parametros: List<DefinicionParametro>
        get() = when (this) {
            ESFERA -> listOf(p("radio", "Radio", 0.5f, 200f, 10f))
            CAJA -> listOf(
                p("anchura", "Anchura", 0.5f, 400f, 40f),
                p("altura", "Altura", 0.5f, 400f, 20f),
                p("profundidad", "Profundidad", 0.5f, 400f, 30f),
                p("redondeo", "Redondeo", 0f, 30f, 2f),
            )
            CILINDRO -> listOf(
                p("radio", "Radio", 0.5f, 200f, 8f),
                p("altura", "Altura", 0.5f, 400f, 30f),
                p("redondeo", "Redondeo", 0f, 20f, 0f),
            )
            CONO -> listOf(
                p("radioInferior", "Radio inferior", 0f, 200f, 15f),
                p("radioSuperior", "Radio superior", 0f, 200f, 5f),
                p("altura", "Altura", 0.5f, 400f, 30f),
            )
            TORO -> listOf(
                p("radioMayor", "Radio mayor", 1f, 200f, 20f),
                p("radioMenor", "Radio menor", 0.5f, 100f, 5f),
            )
            CAPSULA -> listOf(
                p("radio", "Radio", 0.5f, 200f, 6f),
                p("altura", "Altura", 0f, 400f, 20f),
            )
            UNION, DIFERENCIA, INTERSECCION ->
                listOf(p("fusion", "Acuerdo", 0f, 30f, 0f))
            VACIADO -> listOf(p("grosor", "Grosor", 0.2f, 40f, 2.4f))
            SIMETRIA -> emptyList()
            REPETICION -> listOf(p("paso", "Paso", 0.5f, 200f, 20f))
        }

    private fun p(clave: String, etiqueta: String, minimo: Float, maximo: Float, defecto: Float) =
        DefinicionParametro(clave, etiqueta, minimo, maximo, defecto)
}

/**
 * Descripción de un parámetro editable. La interfaz construye su inspector a partir
 * de esto en lugar de cablear un panel por tipo, así que añadir una primitiva al
 * kernel no obliga a tocar la interfaz.
 */
@Serializable
data class DefinicionParametro(
    val clave: String,
    val etiqueta: String,
    val minimo: Float,
    val maximo: Float,
    val defecto: Float,
    val unidad: String = "mm",
)

/**
 * Una pieza del documento.
 *
 * Es inmutable: toda edición produce un documento nuevo. Eso hace que deshacer sea
 * guardar una referencia y que no existan estados a medio aplicar.
 */
@Serializable
data class Pieza(
    val id: String,
    val nombre: String,
    val tipo: TipoPieza,
    val parametros: Map<String, Float> = emptyMap(),
    val transform: Transform = Transform.IDENTITY,
    val hijos: List<Pieza> = emptyList(),
    val visible: Boolean = true,
    val eje: Axis = Axis.X,
    val cuenta: Int = 3,
) {
    fun parametro(clave: String): Float =
        parametros[clave] ?: tipo.parametros.firstOrNull { it.clave == clave }?.defecto ?: 0f

    companion object {
        private var contador = 0

        fun nueva(tipo: TipoPieza, nombre: String? = null): Pieza {
            contador++
            return Pieza(
                id = "${tipo.name.lowercase()}-$contador",
                nombre = nombre ?: tipo.etiqueta,
                tipo = tipo,
                parametros = tipo.parametros.associate { it.clave to it.defecto },
            )
        }
    }
}

/**
 * Compila la pieza al árbol de distancias.
 *
 * Devuelve `null` cuando la pieza no aporta material —está oculta, o es una
 * operación sin hijos—. Propagar la ausencia en lugar de inventar un sólido vacío
 * evita que una rama a medio construir ensucie el resultado.
 */
fun Pieza.compilar(): SdfNode? {
    if (!visible) return null

    val nodo: SdfNode? = when (tipo) {
        TipoPieza.ESFERA -> Esfera(parametro("radio"))

        TipoPieza.CAJA -> Caja(
            semilados = Vec3(
                parametro("anchura") * 0.5f,
                parametro("altura") * 0.5f,
                parametro("profundidad") * 0.5f,
            ),
            // El redondeo no puede comerse la pieza: se limita a la menor semiarista.
            redondeo = parametro("redondeo").coerceAtMost(
                minOf(parametro("anchura"), parametro("altura"), parametro("profundidad")) * 0.499f,
            ),
        )

        TipoPieza.CILINDRO -> Cilindro(
            radio = parametro("radio"),
            altura = parametro("altura"),
            redondeo = parametro("redondeo")
                .coerceAtMost(minOf(parametro("radio"), parametro("altura") * 0.5f) * 0.999f),
        )

        TipoPieza.CONO -> Cono(
            radioInferior = parametro("radioInferior"),
            radioSuperior = parametro("radioSuperior"),
            altura = parametro("altura"),
        )

        TipoPieza.TORO -> Toro(
            radioMayor = parametro("radioMayor"),
            radioMenor = parametro("radioMenor"),
        )

        TipoPieza.CAPSULA -> Capsula(
            radio = parametro("radio"),
            altura = parametro("altura"),
        )

        TipoPieza.UNION -> combinar(hijos) { a, b -> Union(a, b, parametro("fusion")) }

        TipoPieza.DIFERENCIA -> {
            val compilados = hijos.mapNotNull { it.compilar() }
            when {
                compilados.isEmpty() -> null
                compilados.size == 1 -> compilados.first()
                else -> {
                    // El primer hijo es el minuendo; el resto se resta uno a uno.
                    val sustraendos = compilados.drop(1).reduce { a, b -> Union(a, b, 0f) }
                    Diferencia(compilados.first(), sustraendos, parametro("fusion"))
                }
            }
        }

        TipoPieza.INTERSECCION -> combinar(hijos) { a, b -> Interseccion(a, b, parametro("fusion")) }

        TipoPieza.VACIADO -> combinar(hijos) { a, b -> Union(a, b, 0f) }
            ?.let { Vaciado(it, parametro("grosor")) }

        TipoPieza.SIMETRIA -> combinar(hijos) { a, b -> Union(a, b, 0f) }
            ?.let { Simetria(it, eje) }

        TipoPieza.REPETICION -> combinar(hijos) { a, b -> Union(a, b, 0f) }
            ?.let {
                Repeticion(it, cuenta.coerceIn(1, Repeticion.MAXIMO), parametro("paso"), eje)
            }
    }

    if (nodo == null) return null
    return if (transform == Transform.IDENTITY) nodo else Transformado(nodo, transform)
}

private inline fun combinar(hijos: List<Pieza>, unir: (SdfNode, SdfNode) -> SdfNode): SdfNode? {
    val compilados = hijos.mapNotNull { it.compilar() }
    if (compilados.isEmpty()) return null
    return compilados.reduce(unir)
}

// ------------------------------------------------------------------ documento

/**
 * El documento completo con su historial.
 *
 * El historial guarda instantáneas en lugar de operaciones inversas. Con un árbol
 * inmutable una instantánea es una referencia, así que sale igual de barato y no
 * puede desincronizarse: es imposible escribir mal la inversa de una operación que
 * no existe.
 */
@Serializable
data class Documento(
    val raiz: Pieza,
    val seleccionado: String? = null,
) {
    fun compilar(): SdfNode? = raiz.compilar()

    fun buscar(id: String): Pieza? = raiz.buscar(id)

    /** Identificador del padre de [id], o `null` si es la raíz o no existe. */
    fun padreDe(id: String): String? = raiz.padreDe(id)

    companion object {
        fun vacio(): Documento {
            val raiz = Pieza.nueva(TipoPieza.UNION, "Modelo")
            return Documento(raiz = raiz, seleccionado = raiz.id)
        }
    }
}

fun Pieza.buscar(id: String): Pieza? {
    if (this.id == id) return this
    for (h in hijos) h.buscar(id)?.let { return it }
    return null
}

fun Pieza.padreDe(id: String): String? {
    if (hijos.any { it.id == id }) return this.id
    for (h in hijos) h.padreDe(id)?.let { return it }
    return null
}

/** Devuelve una copia del árbol con [id] transformada por [cambio]. */
fun Pieza.mapear(id: String, cambio: (Pieza) -> Pieza): Pieza =
    if (this.id == id) cambio(this)
    else copy(hijos = hijos.map { it.mapear(id, cambio) })

/** Devuelve una copia del árbol sin la pieza [id]. La raíz nunca se elimina. */
fun Pieza.sin(id: String): Pieza =
    copy(hijos = hijos.filter { it.id != id }.map { it.sin(id) })

/** Recorrido en profundidad emparejado con el nivel de anidamiento. */
fun Pieza.aplanar(profundidad: Int = 0): List<Pair<Pieza, Int>> =
    listOf(this to profundidad) + hijos.flatMap { it.aplanar(profundidad + 1) }
