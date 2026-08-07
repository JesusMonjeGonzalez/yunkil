package yunkil.doc

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import yunkil.ia.Conversacion
import yunkil.ia.PlanDeModelado
import yunkil.kernel.AcuerdoLocal
import yunkil.kernel.Axis
import yunkil.kernel.Barrido
import yunkil.kernel.ModoDeAcuerdo
import yunkil.kernel.CampoDeMalla
import yunkil.kernel.Caja
import yunkil.kernel.Capsula
import yunkil.kernel.Cilindro
import yunkil.kernel.Cono
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.Extrusion
import yunkil.kernel.Perfil2D
import yunkil.kernel.Punto2
import yunkil.kernel.Revolucion
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

    EXTRUSION("Extrusión", false, false),
    REVOLUCION("Revolución", false, false),
    BARRIDO("Barrido", false, false),

    /**
     * Geometría traída de fuera: un STL horneado a campo de distancias.
     *
     * No tiene parámetros que acotar porque su forma no es una fórmula, son cien mil
     * triángulos. Lo que sí admite es todo lo demás: restarle, ahuecarla, taladrarla,
     * acotarla y exportarla verificada, que es justo lo que justifica traerla.
     */
    MALLA("Malla importada", false, false),

    UNION("Unión", true, true),
    DIFERENCIA("Diferencia", true, true),
    INTERSECCION("Intersección", true, true),

    VACIADO("Vaciado", true, true),
    SIMETRIA("Simetría", true, true),
    REPETICION("Repetición", true, true);

    /**
     * Parámetros editables, en el orden en que deben aparecer en el inspector.
     *
     * Los máximos llegan a 600 mm de lado y 300 de radio, y ese número no es
     * arbitrario: **un panel de rack de 19 pulgadas mide 482,6 mm**. Con el tope
     * anterior de 400, la anchura se recortaba en silencio a 400, los agujeros de
     * `patron` caían fuera de la pieza y el resultado era una bandeja de rack sin
     * agujeros. De nada sirve tener el catálogo de estándares si las primitivas no
     * llegan a la cota que la norma exige, así que los límites los fija lo que hay
     * que poder construir y no un número redondo.
     *
     * Que no quepa en el plato es otra conversación, y de esa ya avisa el analizador.
     */
    val parametros: List<DefinicionParametro>
        get() = when (this) {
            ESFERA -> listOf(p("radio", "Radio", 0.5f, 300f, 10f))
            CAJA -> listOf(
                p("anchura", "Anchura", 0.5f, 600f, 40f),
                p("altura", "Altura", 0.5f, 600f, 20f),
                p("profundidad", "Profundidad", 0.5f, 600f, 30f),
                p("redondeo", "Redondeo", 0f, 30f, 2f),
            )
            CILINDRO -> listOf(
                p("radio", "Radio", 0.5f, 300f, 8f),
                p("altura", "Altura", 0.5f, 600f, 30f),
                p("redondeo", "Redondeo", 0f, 20f, 0f),
            )
            CONO -> listOf(
                p("radioInferior", "Radio inferior", 0f, 300f, 15f),
                p("radioSuperior", "Radio superior", 0f, 300f, 5f),
                p("altura", "Altura", 0.5f, 600f, 30f),
            )
            TORO -> listOf(
                p("radioMayor", "Radio mayor", 1f, 300f, 20f),
                p("radioMenor", "Radio menor", 0.5f, 100f, 5f),
            )
            CAPSULA -> listOf(
                p("radio", "Radio", 0.5f, 300f, 6f),
                p("altura", "Altura", 0f, 600f, 20f),
            )
            EXTRUSION -> listOf(
                p("altura", "Altura", 0.2f, 600f, 10f),
                p("redondeo", "Redondeo", 0f, 20f, 0f),
            )
            REVOLUCION -> listOf(
                p("desplazamiento", "Separación del eje", 0f, 300f, 0f),
            )
            // Su forma viene del archivo, no de mandos. Acotarla se hace con `acotar`
            // o con la escala, igual que cualquier otra pieza.
            MALLA -> emptyList()
            BARRIDO -> listOf(
                p("radio", "Radio de la sección", 0.2f, 100f, 3f),
                // Un tubo se dibuja abierto y un marco cerrado, y no hay forma de
                // adivinar cuál quiere quien lo pide: es un mando, no una heurística.
                p("cerrado", "Camino cerrado", 0f, 1f, 1f),
            )
            // `fusion` redondea todo el encuentro entre los hijos. Con `acuerdoRadio`
            // mayor que cero, ese mismo redondeo queda limitado a una esfera alrededor
            // del punto que se pinchó: es el filete de una arista concreta. A cero se
            // comporta exactamente como siempre, así que los archivos anteriores no
            // cambian de forma.
            UNION, DIFERENCIA, INTERSECCION ->
                listOf(
                    p("fusion", "Acuerdo", 0f, 30f, 0f),
                    p("acuerdoRadio", "Alcance del filete", 0f, 200f, 0f),
                    DefinicionParametro("acuerdoX", "Filete X", -600f, 600f, 0f),
                    DefinicionParametro("acuerdoY", "Filete Y", -600f, 600f, 0f),
                    DefinicionParametro("acuerdoZ", "Filete Z", -600f, 600f, 0f),
                )
            VACIADO -> listOf(p("grosor", "Grosor", 0.2f, 40f, 2.4f))
            SIMETRIA -> emptyList()
            REPETICION -> listOf(p("paso", "Paso", 0.5f, 200f, 20f))
        }

    private fun p(clave: String, etiqueta: String, minimo: Float, maximo: Float, defecto: Float) =
        DefinicionParametro(clave, etiqueta, minimo, maximo, defecto)

    /**
     * Parámetros reales de la pieza, contando la forma del perfil cuando la tiene.
     *
     * Una extrusión no tiene un juego fijo de mandos: los suyos dependen de si el
     * contorno es un rectángulo, una ranura o una escuadra. Devolverlos aquí
     * permite que el inspector siga construyéndose solo, sin un panel por caso.
     */
    fun parametrosCon(forma: FormaDePerfil?): List<DefinicionParametro> =
        if (forma == null || (this != EXTRUSION && this != REVOLUCION && this != BARRIDO)) parametros
        else forma.parametros + parametros
}

/**
 * Familias de contorno que se pueden acotar con números.
 *
 * Son las formas que aparecen una y otra vez en piezas impresas funcionales. Un
 * boceto libre siempre es posible con `LIBRE`, pero teclear seis coordenadas para
 * dibujar una ranura es exactamente el trabajo que una herramienta debería
 * ahorrar.
 */
@Serializable
enum class FormaDePerfil(val etiqueta: String) {
    RECTANGULO("Rectángulo"),
    CIRCULO("Círculo"),
    POLIGONO("Polígono regular"),
    RANURA("Ranura"),
    ELE("Escuadra en L"),
    ESTRELLA("Estrella"),
    LIBRE("Contorno libre");

    val parametros: List<DefinicionParametro>
        get() = when (this) {
            RECTANGULO -> listOf(
                d("anchoPerfil", "Ancho del perfil", 0.5f, 400f, 40f),
                d("altoPerfil", "Alto del perfil", 0.5f, 400f, 30f),
                d("radioEsquina", "Radio de esquina", 0f, 100f, 3f),
            )
            CIRCULO -> listOf(d("radioPerfil", "Radio del perfil", 0.5f, 200f, 15f))
            POLIGONO -> listOf(
                d("ladosPerfil", "Lados", 3f, 60f, 6f, ""),
                d("radioPerfil", "Radio del perfil", 0.5f, 200f, 15f),
            )
            RANURA -> listOf(
                d("largoPerfil", "Largo", 1f, 400f, 30f),
                d("anchoPerfil", "Ancho", 0.5f, 200f, 8f),
            )
            ELE -> listOf(
                d("anchoPerfil", "Ancho", 1f, 400f, 40f),
                d("altoPerfil", "Alto", 1f, 400f, 40f),
                d("grosorPerfil", "Grosor del ala", 0.5f, 100f, 6f),
            )
            ESTRELLA -> listOf(
                d("puntasPerfil", "Puntas", 3f, 40f, 5f, ""),
                d("radioPerfil", "Radio exterior", 1f, 200f, 20f),
                d("radioInterior", "Radio interior", 0.5f, 200f, 9f),
            )
            LIBRE -> emptyList()
        }

    private fun d(clave: String, etiqueta: String, min: Float, max: Float, defecto: Float, unidad: String = "mm") =
        DefinicionParametro(clave, etiqueta, min, max, defecto, unidad)
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
    /** Forma del contorno, para las piezas que se construyen a partir de un perfil. */
    val forma: FormaDePerfil = FormaDePerfil.RECTANGULO,
    /** Contorno explícito. Solo se usa con `FormaDePerfil.LIBRE`. */
    val puntos: List<Punto2> = emptyList(),
    /** De dónde salió una MALLA. Se guarda para poder volver a hornearla al abrir. */
    val rutaDeMalla: String? = null,
    /**
     * El campo horneado. No se guarda en el archivo —son megas— y por eso al abrir un
     * proyecto llega a nulo: el editor lo vuelve a hornear desde [rutaDeMalla]. Si el
     * archivo original ya no está, la pieza se queda vacía y se dice, en vez de
     * desaparecer sin explicación.
     */
    @Transient
    val campoDeMalla: CampoDeMalla? = null,
) {
    fun parametro(clave: String): Float =
        parametros[clave]
            ?: tipo.parametrosCon(forma).firstOrNull { it.clave == clave }?.defecto
            ?: 0f

    /**
     * El contorno acotado que le corresponde a esta pieza.
     *
     * Se construye a partir de los parámetros en vez de guardarse ya resuelto: así
     * cambiar una cota sigue siendo mover un número, que es lo que distingue una
     * pieza paramétrica de una malla.
     */
    fun perfil(): Perfil2D = when (forma) {
        FormaDePerfil.RECTANGULO -> Perfil2D.rectangulo(
            parametro("anchoPerfil"), parametro("altoPerfil"), parametro("radioEsquina"),
        )
        FormaDePerfil.CIRCULO -> Perfil2D.circulo(parametro("radioPerfil"))
        FormaDePerfil.POLIGONO -> Perfil2D.poligonoRegular(
            parametro("ladosPerfil").toInt(), parametro("radioPerfil"),
        )
        FormaDePerfil.RANURA -> Perfil2D.ranura(parametro("largoPerfil"), parametro("anchoPerfil"))
        FormaDePerfil.ELE -> Perfil2D.ele(
            parametro("anchoPerfil"), parametro("altoPerfil"), parametro("grosorPerfil"),
        )
        FormaDePerfil.ESTRELLA -> Perfil2D.estrella(
            parametro("puntasPerfil").toInt(), parametro("radioPerfil"), parametro("radioInterior"),
        )
        FormaDePerfil.LIBRE -> Perfil2D.poligono(puntos)
    }

    companion object {
        private var contador = 0

        fun nueva(tipo: TipoPieza, nombre: String? = null): Pieza {
            contador++
            return Pieza(
                id = "${tipo.name.lowercase()}-$contador",
                nombre = nombre ?: tipo.etiqueta,
                tipo = tipo,
                parametros = tipo.parametrosCon(FormaDePerfil.RECTANGULO)
                    .associate { it.clave to it.defecto },
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

        TipoPieza.EXTRUSION -> perfil().takeIf { it.esValido }?.let {
            Extrusion(it, parametro("altura"), parametro("redondeo"))
        }

        TipoPieza.REVOLUCION -> perfil().takeIf { it.esValido }?.let {
            Revolucion(it, parametro("desplazamiento"))
        }

        // Un barrido necesita dos puntos, no tres: una recta con sección circular es
        // un tubo perfectamente legítimo, y `esValido` exige contorno cerrado.
        TipoPieza.MALLA -> campoDeMalla

        TipoPieza.BARRIDO -> perfil().takeIf { it.poligono.size >= 2 }?.let {
            Barrido(it, parametro("radio"), cerrado = parametro("cerrado") >= 0.5f)
        }

        TipoPieza.UNION -> combinar(hijos) { a, b -> booleana(ModoDeAcuerdo.UNION, a, b) }

        TipoPieza.DIFERENCIA -> {
            val compilados = hijos.mapNotNull { it.compilar() }
            when {
                compilados.isEmpty() -> null
                compilados.size == 1 -> compilados.first()
                else -> {
                    // El primer hijo es el minuendo; el resto se resta uno a uno.
                    val sustraendos = compilados.drop(1).reduce { a, b -> Union(a, b, 0f) }
                    booleana(ModoDeAcuerdo.DIFERENCIA, compilados.first(), sustraendos)
                }
            }
        }

        TipoPieza.INTERSECCION -> combinar(hijos) { a, b -> booleana(ModoDeAcuerdo.INTERSECCION, a, b) }

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

/**
 * La booleana que le toca a esta pieza: exacta, redondeada del todo, o con el filete
 * limitado a un sitio.
 *
 * Con `acuerdoRadio` a cero sale el nodo de siempre, y por eso ningún archivo anterior
 * cambia de forma al abrirse: un nodo distinto solo aparece cuando alguien pide un
 * filete local.
 */
private fun Pieza.booleana(modo: ModoDeAcuerdo, a: SdfNode, b: SdfNode): SdfNode {
    val fusion = parametro("fusion")
    val radio = parametro("acuerdoRadio")
    if (radio <= 0f) {
        return when (modo) {
            ModoDeAcuerdo.UNION -> Union(a, b, fusion)
            ModoDeAcuerdo.DIFERENCIA -> Diferencia(a, b, fusion)
            ModoDeAcuerdo.INTERSECCION -> Interseccion(a, b, fusion)
        }
    }
    return AcuerdoLocal(
        a = a,
        b = b,
        modo = modo,
        centro = Vec3(parametro("acuerdoX"), parametro("acuerdoY"), parametro("acuerdoZ")),
        radio = radio,
        // El límite del gradiente medido vale mientras la mezcla no sea más ancha que su
        // alcance. Se recorta aquí en vez de confiar en quien llame.
        fusion = fusion.coerceAtMost(radio),
    )
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
    /**
     * Planes de la IA que se aplicaron y quedaron en el documento, en orden.
     *
     * Es la mitad navegable del historial: el deshacer guarda instantáneas, que dan
     * un hilo lineal y nada más. Estos planes se pueden *reproducir* —ejecutarlos
     * sobre un documento vacío devuelve exactamente la geometría— y eso permite
     * volver a una operación de hace veinte pasos, cambiarle un número y rehacer el
     * resto sin deshacer nada. «Los agujeros eran M3, ponlos M4» sin deshacer
     * treinta pasos.
     *
     * Se guarda el plan tal como lo emitió el modelo, con sus alias: el `Aplicador`
     * ya sabe resolverlos al ejecutar, así que la reproducción usa la misma
     * maquinaria que la primera vez.
     */
    val planesAplicados: List<PlanDeModelado> = emptyList(),
    /**
     * El hilo de la conversación que produjo esta pieza.
     *
     * Va en el documento y no en los ajustes de la aplicación porque pertenece a la
     * pieza: abrir un `.yunkil` de hace dos semanas y poder decir «hazle el asa más
     * gruesa» solo funciona si el modelo puede leer de qué se hablaba. Es intención,
     * no geometría —eso lo pone el contexto, que se mide cada vez—, así que ocupa
     * poco y no puede desincronizarse con el árbol.
     */
    val conversacion: Conversacion = Conversacion(),
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

/** Copia del árbol con la visibilidad que decida [regla] para cada pieza. */
fun Pieza.conVisibilidad(regla: (Pieza) -> Boolean): Pieza =
    copy(visible = regla(this), hijos = hijos.map { it.conVisibilidad(regla) })

/** Recorrido en profundidad emparejado con el nivel de anidamiento. */
fun Pieza.aplanar(profundidad: Int = 0): List<Pair<Pieza, Int>> =
    listOf(this to profundidad) + hijos.flatMap { it.aplanar(profundidad + 1) }
