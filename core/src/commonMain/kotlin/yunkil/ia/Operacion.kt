package yunkil.ia

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import yunkil.fabricacion.AjusteDeTaladro

/**
 * Las seis caras de una pieza, para poder decir «encima de» sin coordenadas.
 *
 * Un modelo de lenguaje escribe fatal las coordenadas absolutas: se equivoca de
 * signo, olvida que la altura es un semilado o no sabe dónde acaba la pieza de
 * al lado. En cambio acierta casi siempre con «la tapa va encima del cuerpo».
 * Dar una operación que hable en esos términos es lo que convierte un montón de
 * primitivas sueltas en un conjunto que encaja.
 */
@Serializable
enum class Cara(val etiqueta: String) {
    ARRIBA("arriba"),
    ABAJO("abajo"),
    DERECHA("derecha"),
    IZQUIERDA("izquierda"),
    DELANTE("delante"),
    DETRAS("detrás"),
}

@Serializable
enum class EjeNombrado { X, Y, Z }

/**
 * Una operación declarativa sobre el documento.
 *
 * El modelo **nunca ejecuta código**: emite estas estructuras, el núcleo las valida
 * contra el esquema y solo entonces las aplica. Toda la superficie de ataque cabe
 * en este archivo, y ampliarla es una decisión explícita, no un descuido.
 *
 * `objetivo` acepta un id real, un alias declarado antes en el mismo plan, `raiz`
 * o `seleccion`. Resolver alias dentro del plan permite que el modelo construya
 * jerarquías sin conocer los identificadores que aún no existen.
 */
@Serializable
sealed interface Operacion {
    /** Explicación corta de por qué el modelo hace esto. Va al historial legible. */
    val nota: String?
}

@Serializable
@SerialName("crear")
data class Crear(
    val tipo: String,
    val alias: String? = null,
    val padre: String? = null,
    val nombre: String? = null,
    val parametros: Map<String, Float> = emptyMap(),
    val posicion: List<Float>? = null,
    val giro: List<Float>? = null,
    val escala: Float? = null,
    override val nota: String? = null,
) : Operacion

@Serializable
@SerialName("envolver")
data class Envolver(
    val objetivo: String,
    val tipo: String,
    val alias: String? = null,
    val nombre: String? = null,
    val parametros: Map<String, Float> = emptyMap(),
    override val nota: String? = null,
) : Operacion

@Serializable
@SerialName("fijar")
data class Fijar(
    val objetivo: String,
    val clave: String,
    val valor: Float,
    override val nota: String? = null,
) : Operacion

@Serializable
@SerialName("mover")
data class Mover(
    val objetivo: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    /** `true` fija la posición; `false` la suma a la que ya tenía. */
    val absoluto: Boolean = false,
    override val nota: String? = null,
) : Operacion

@Serializable
@SerialName("girar")
data class Girar(
    val objetivo: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val absoluto: Boolean = false,
    override val nota: String? = null,
) : Operacion

@Serializable
@SerialName("escalar")
data class Escalar(
    val objetivo: String,
    val factor: Float,
    override val nota: String? = null,
) : Operacion

/**
 * Escala hasta que una cota concreta valga lo pedido, en vez de por un factor.
 *
 * `escalar` obliga a saber cuánto mide algo para calcular el factor, y esa división
 * es justo donde un modelo se equivoca. `acotar` la hace el núcleo, que sí conoce las
 * cotas reales. Con `objetivo` a `modelo` acota el conjunto entero conservando todas
 * las proporciones, que es lo que se necesita después de modelar a partir de una
 * imagen o de una descripción sin medidas.
 */
@Serializable
@SerialName("acotar")
data class Acotar(
    val objetivo: String = "modelo",
    val eje: EjeNombrado = EjeNombrado.X,
    val medida: Float,
    override val nota: String? = null,
) : Operacion

@Serializable
@SerialName("renombrar")
data class Renombrar(
    val objetivo: String,
    val nombre: String,
    override val nota: String? = null,
) : Operacion

@Serializable
@SerialName("eliminar")
data class Eliminar(
    val objetivo: String,
    override val nota: String? = null,
) : Operacion

@Serializable
@SerialName("duplicar")
data class Duplicar(
    val objetivo: String,
    val alias: String? = null,
    override val nota: String? = null,
) : Operacion

/**
 * Coloca una pieza pegada a una cara de otra, con holgura opcional.
 *
 * Es la operación que más sube la calidad de lo que produce un modelo, porque
 * sustituye la aritmética de semilados —donde falla— por una relación —donde
 * acierta—. El núcleo resuelve las cotas reales en el mundo y calcula el
 * desplazamiento exacto.
 */
@Serializable
@SerialName("colocar")
data class Colocar(
    val objetivo: String,
    val referencia: String,
    val cara: Cara = Cara.ARRIBA,
    /** Separación entre las dos superficies. Negativa las solapa, útil para soldar. */
    val holgura: Float = 0f,
    /** Centra además la pieza en los otros dos ejes. */
    val centrar: Boolean = true,
    override val nota: String? = null,
) : Operacion

/** Alinea una pieza con otra en un eje: por su centro, o por una de sus caras. */
@Serializable
@SerialName("alinear")
data class Alinear(
    val objetivo: String,
    val referencia: String,
    val eje: EjeNombrado,
    val modo: ModoDeAlineacion = ModoDeAlineacion.CENTRO,
    override val nota: String? = null,
) : Operacion

@Serializable
enum class ModoDeAlineacion { CENTRO, MINIMO, MAXIMO }

/**
 * Define el contorno de una extrusión o una revolución.
 *
 * Es la operación que abre las piezas funcionales de verdad a un modelo: una
 * escuadra, una brida o una tapa con su ranura son un contorno acotado y
 * levantado un grosor, y describirlas combinando primitivas obliga a una
 * aritmética en la que los modelos se equivocan.
 */
@Serializable
@SerialName("perfil")
data class DefinirPerfil(
    val objetivo: String,
    val forma: String,
    /** Solo para la forma LIBRE: el contorno como pares [x, y] en milímetros. */
    val puntos: List<List<Float>> = emptyList(),
    val parametros: Map<String, Float> = emptyMap(),
    override val nota: String? = null,
) : Operacion

/**
 * Abre un agujero para tornillo, con el diámetro sacado de tabla.
 *
 * Es la primera operación del puente que no es geometría sino **dominio**: no dice
 * «un cilindro de radio 1,9 de 14 mm de largo en (0, 0, 0)», dice «un M3 pasante».
 * El núcleo resuelve el diámetro normalizado, le suma la holgura que necesita la
 * impresora, calcula el largo para que atraviese de verdad y lo sitúa. Las tres
 * cosas que un modelo de lenguaje se inventa cuando tiene que componerlo a mano.
 */
@Serializable
@SerialName("taladro")
data class Taladro(
    val objetivo: String,
    /** Designación métrica: M3, M4… Tiene preferencia sobre [diametro]. */
    val designacion: String? = null,
    /** Diámetro explícito en milímetros, para agujeros que no son de tornillo. */
    val diametro: Float = 0f,
    val ajuste: AjusteDeTaladro = AjusteDeTaladro.PASANTE,
    /** Eje que atraviesa el agujero. Y es vertical, que es el caso normal. */
    val eje: EjeNombrado = EjeNombrado.Y,
    /** Desplazamiento desde el centro de la pieza, en el plano perpendicular al eje. */
    val desplazamiento: List<Float> = emptyList(),
    /**
     * Punto [u, v] **en las coordenadas del contorno**, para taladrar una extrusión.
     *
     * Existe porque `desplazamiento` cuenta desde el centro de la caja envolvente y
     * ese centro es justo lo que un modelo no puede saber: los contornos arrancan en
     * su esquina, así que el centro de una escuadra en L cae en un sitio que hay que
     * calcular. Puesto a probarlo, un modelo real se atascó ahí y llegó a plantearse
     * abandonar el perfil y volver a apilar cajas, que es peor pieza.
     *
     * Con `punto` dice la coordenada que **acaba de escribir** en `perfil`, y la
     * conversión la hace el núcleo, que sí conoce el contorno. Tiene preferencia
     * sobre [desplazamiento].
     */
    val punto: List<Float> = emptyList(),
    val alias: String? = null,
    override val nota: String? = null,
) : Operacion

/**
 * Abre de una vez todos los agujeros de un patrón de montaje normalizado.
 *
 * Es la diferencia entre «se parece» y «encaja». Un soporte de rack con los agujeros
 * a la separación equivocada no es un soporte peor: no entra en el bastidor. El
 * reparto de un rack de 19" ni siquiera es regular —12,7, 15,875 y 15,875 mm dentro
 * de cada unidad, con la media pulgada a caballo entre dos— y eso ningún modelo lo
 * recuerda bien. Diciendo el nombre del estándar, lo pone el núcleo.
 */
@Serializable
@SerialName("patron")
data class Patron(
    val objetivo: String,
    /** RACK_19, VESA_100, VESA_200… */
    val estandar: String,
    /** Unidades de altura. Solo lo usa el rack. */
    val unidades: Int = 1,
    val eje: EjeNombrado = EjeNombrado.Y,
    val ajuste: AjusteDeTaladro = AjusteDeTaladro.PASANTE,
    override val nota: String? = null,
) : Operacion

/**
 * Ahueca una pieza dejando una pared que la impresora pueda rellenar.
 *
 * Sin esto, una caja hueca obliga al modelo a envolver en VACIADO y acertar el
 * `grosor` de memoria. Los valores que escriben son casi siempre o irreales —0,3 mm,
 * que la boquilla no llega a rellenar— o exagerados. Aquí, omitir el grosor da uno
 * bueno para el perfil activo, y pedir uno imposible se rechaza con el número.
 */
@Serializable
@SerialName("pared")
data class Pared(
    val objetivo: String,
    /** Grosor en milímetros. Omitido, lo decide el perfil de fabricación. */
    val grosor: Float = 0f,
    val alias: String? = null,
    override val nota: String? = null,
) : Operacion

/** Baja el modelo entero hasta que se apoya en el plato. */
@Serializable
@SerialName("asentar")
data class Asentar(
    override val nota: String? = null,
) : Operacion

/**
 * Redondea el canto donde se juntan dos piezas.
 *
 * Un modelo de lenguaje no tiene cursor con el que señalar una arista, así que aquí el
 * canto se nombra por **las dos piezas que lo producen**: «el encuentro de la base y el
 * respaldo». Yunkil sube al booleano que las junta y limita el acuerdo a la zona donde de
 * verdad se tocan, midiendo dónde está: sin eso el modelo tendría que inventarse un punto
 * en el espacio, que es exactamente donde falla.
 *
 * Sin esta operación, la única forma de redondear era `fusion` en la UNION, que redondea
 * *todos* los encuentros de esa unión a la vez. Vale para una figura orgánica y no vale
 * para «redondea el canto que se agarra con la mano».
 */
@Serializable
@SerialName("filete")
data class Filete(
    val objetivo: String,
    /** La otra pieza del encuentro. Si se omite, se redondean todos los de ese booleano. */
    val contra: String? = null,
    /** Radio del redondeo, en milímetros. */
    val radio: Float = 2f,
    override val nota: String? = null,
) : Operacion

/**
 * Gira una pieza para que una de sus caras quede apoyada en el plato.
 *
 * Es la decisión de fabricación que más cambia el resultado de una impresión y la que un
 * modelo de lenguaje puede tomar bien: sabe perfectamente cuál es la cara plana grande de
 * lo que acaba de diseñar, aunque no sepa en qué cuaternión se traduce eso.
 */
/**
 * Añade un nervio de refuerzo en el encuentro de dos piezas.
 *
 * Es la pieza que evita que una escuadra impresa se parta por la esquina, y es la que
 * un modelo de lenguaje no sabe construir: exige un triángulo rectángulo colocado en
 * el plano que forman las dos piezas, con la hipotenusa hacia fuera, y ahí falla en
 * los tres pasos —el plano, el giro y el sitio—. Puesto a intentarlo contra el banco,
 * el modelo local emitió una escuadra en L pelada y la llamó «nervio integrado».
 *
 * El plano lo deduce Yunkil de dónde están las dos piezas. Lo único que se dice es
 * cuáles se refuerzan, y opcionalmente cuánto sube y cuánto engorda.
 */
@Serializable
@SerialName("nervio")
data class Nervio(
    val objetivo: String,
    /** La otra pieza del encuentro. */
    val contra: String,
    /** Cateto del triángulo, en milímetros. Omitido, un tercio de la pieza menor. */
    val tamano: Float = 0f,
    /** Espesor del nervio. Omitido, el doble del grosor mínimo del perfil activo. */
    val grosor: Float = 0f,
    val alias: String? = null,
    override val nota: String? = null,
) : Operacion

@Serializable
@SerialName("apoyar")
data class Apoyar(
    val objetivo: String,
    /** Cara de la pieza que va contra el plato. */
    val cara: Cara = Cara.ABAJO,
    override val nota: String? = null,
) : Operacion

@Serializable
@SerialName("seleccionar")
data class Seleccionar(
    val objetivo: String,
    override val nota: String? = null,
) : Operacion

/**
 * Un plan completo tal y como lo emite el modelo.
 *
 * `reemplazar` distingue «hazme un soporte» de «añádele un agujero», y es la
 * única bandera que puede destruir trabajo previo, así que viaja explícita en el
 * plan en vez de deducirse de la redacción en el momento de aplicar.
 */
@Serializable
data class PlanDeModelado(
    val resumen: String = "",
    val reemplazar: Boolean = false,
    val operaciones: List<Operacion> = emptyList(),
)
