package yunkil.doc

import kotlinx.serialization.Serializable
import yunkil.fabricacion.PerfilFabricacion
import yunkil.ia.EjeNombrado
import yunkil.ia.cotasEnMundoDe

/**
 * El encaje como relación viva del documento.
 *
 * Una pieza que tiene que encajar con algo real no tiene un radio: tiene una
 * **declaración**. «Esto entra en un agujero de 20 mm medidos con calibre, deslizante.»
 * La cota se deriva de ahí, y por eso cambiar de perfil de fabricación o corregir la
 * medida mueve la pieza sola.
 *
 * Antes esto era un evento: la operación `holgura` del DSL redimensionaba una vez y se
 * olvidaba. Con eso, cambiar de boquilla no movía nada, arrastrar el radio rompía el
 * encaje sin que nadie se enterara, y un `.yunkil` de hace dos semanas no sabía decir
 * por qué su tapón medía 19,6 en vez de 20.
 *
 * Lo que Yunkil promete —y esto sí se puede demostrar sin tener la impresora delante—
 * no es que la pieza entre: es que **la geometría exportada tiene la holgura declarada
 * contra la medida dada**. Que entre depende además de la máquina, y eso lo dice la
 * procedencia del perfil, que hasta que no haya cupón de calibración sigue siendo
 * `de fábrica`.
 */

// ------------------------------------------------------------------- la medida

/**
 * De dónde ha salido un número que describe el mundo.
 *
 * Es la misma idea que [yunkil.fabricacion.OrigenDelPerfil] aplicada a las medidas, y
 * está por el mismo motivo: una cota sin procedencia es una opinión. Un encaje contra
 * algo medido a ojo y otro contra una norma no merecen la misma confianza, y quien
 * mira la pieza dentro de un mes tiene derecho a saber cuál es cuál.
 */
@Serializable
enum class ProcedenciaDeMedida(val etiqueta: String) {
    CALIBRE("con calibre"),
    REGLA("con regla"),
    CATALOGO("de catálogo"),
    NORMA("de norma"),
    A_OJO("a ojo"),
}

/**
 * Una medida del objeto real con el que la pieza tiene que encajar.
 *
 * Vive en el [Documento] y no en la [Pieza] porque varias piezas dependen del mismo
 * número: el tapón y la abrazadera del mismo tubo. Corriges el diámetro una vez y se
 * mueven las dos. Si cada pieza se guardara su copia, corregir sería acordarse de
 * corregir en todos los sitios, que es exactamente como se cuela un error.
 */
@Serializable
data class Medida(
    val id: String,
    val nombre: String,
    val valor: Float,
    val procedencia: ProcedenciaDeMedida = ProcedenciaDeMedida.A_OJO,
    /**
     * Cuánto puede valer menos o más que [valor] sin dejar de ser la misma medida,
     * en mm. Un calibre de estuche mide ±0,02; un agujero desgastado, más. La
     * incertidumbre no es un adorno: se come la holgura, y una holgura de 0,2 mm
     * contra una medida incierta en ±0,3 no puede decirse que se cumpla.
     */
    val tolerancia: Float = 0f,
) {
    /** Cómo se lee en el inspector: «Ø20,0 ±0,1 mm con calibre». */
    fun descripcion(): String = buildString {
        append("$nombre: ${redondeado(valor)} mm")
        if (tolerancia > 0f) append(" ±${redondeado(tolerancia)}")
        append(" ${procedencia.etiqueta}")
    }
}

// ------------------------------------------------------------------- el encaje

/** Hacia qué lado se corrige la cota. El error se comete en los dos sentidos. */
@Serializable
enum class SentidoDeEncaje(val etiqueta: String) {
    /** La pieza va **dentro** de un hueco que mide lo dicho, así que sale más pequeña. */
    ENTRA("entra en"),

    /** La pieza **es** el hueco y tiene que tragarse algo que mide lo dicho: sale mayor. */
    RECIBE("recibe"),
}

/**
 * Cuánto se aparta la cota real de la nominal, como múltiplo de la holgura del perfil.
 *
 * Un solo escalar de holgura por perfil es visiblemente falso: un pasador que se mete a
 * martillo y un paso de cable no llevan la misma. Pero tres escalares independientes no
 * se pueden calibrar, porque el cupón de calibración mide una cosa. Multiplicadores
 * sobre el único número del perfil dejan **una sola perilla** que ajustar el día que
 * haya impresora delante, y siguen distinguiendo los cuatro casos que se distinguen a
 * mano.
 */
@Serializable
enum class ClaseDeAjuste(val etiqueta: String, val factor: Float) {
    PRESION("a presión", 0f),
    AJUSTADO("ajustado", 0.5f),
    DESLIZANTE("deslizante", 1f),
    LIBRE("libre", 2f),
}

/**
 * La declaración que gobierna una cota de la pieza.
 *
 * Apunta a una [Medida] por identificador y nunca a un número suelto. Tener las dos
 * formas —a veces una referencia, a veces un literal— duplicaría el camino de
 * resolución y dejaría medidas invisibles que nadie puede corregir; obligar a que todo
 * encaje nombre su medida es lo que hace que las medidas del proyecto estén a la vista.
 */
@Serializable
data class Encaje(
    /** Identificador de la [Medida] contra la que encaja. */
    val medida: String,
    /** Eje del mundo cuya extensión queda gobernada. */
    val eje: EjeNombrado = EjeNombrado.X,
    val sentido: SentidoDeEncaje = SentidoDeEncaje.ENTRA,
    val clase: ClaseDeAjuste = ClaseDeAjuste.DESLIZANTE,
    /**
     * Añade a la holgura una parte proporcional al diámetro, no solo el piso del perfil.
     *
     * La holgura fija del perfil —la que mide el cupón— es la buena para cotas de
     * un par de centímetros, pero en un agujero de 100 mm las guías de ajuste FDM
     * piden una fracción del diámetro, no un número constante: la misma holgura que
     * en 20 mm es holgura sobra en 100, donde la pieza ya no entra. Con esta marca,
     * la holgura efectiva es el mayor de los dos: el piso de la máquina y la parte
     * proporcional que el diámetro pide.
     *
     * Va desactivada por omisión para que ningún archivo anterior cambie de cota.
     */
    val holguraProporcional: Boolean = false,
    /**
     * Gobierna la cota moviendo **un parámetro** de la pieza en lugar de escalándola.
     *
     * La derivación de siempre escala uniformemente, así que gobernar el diámetro de
     * un cilindro le mueve también la altura: coherente con el sistema, pero deja
     * fuera media mecánica. Con esta marca, la cota se deriva ajustando el parámetro
     * responsable del eje —el `radio`, la `altura`, la `anchura`— y ningún otro número
     * de la pieza se mueve. Una pieza que deba encajar por dos cotas independientes
     * lleva dos encajes de este tipo: el diámetro y la altura, cada uno con su medida.
     *
     * Solo disponible donde hay un parámetro responsable claro
     * (`Pieza.parametroQueGobierna`); un cono o una escultura no tienen uno, y lo
     * dicho ahí vale. En una pieza, o todos sus encajes son por parámetro o ninguno
     * lo es: mezclarlos haría que la escala del segundo pisara al primero.
     */
    val porParametro: Boolean = false,
)

// --------------------------------------------------------------- la resolución

/** La holgura que le toca a este encaje con este perfil, en milímetros. */
fun Encaje.holguraCon(perfil: PerfilFabricacion): Float = perfil.holguraEncaje * clase.factor

/**
 * La holgura efectiva cuando el encaje pide la parte proporcional al diámetro.
 *
 * Es siempre el **mayor** entre el piso de la máquina —lo que midió el cupón— y la
 * fracción del diámetro que la clase de ajuste pide. Nunca resta: una holgura
 * proporcional afloja, no aprieta, porque lo que corrige es justamente el caso en
 * que el número fijo de la máquina se queda corto.
 */
fun Encaje.holguraEfectiva(nominal: Float, perfil: PerfilFabricacion): Float {
    if (!holguraProporcional) return holguraCon(perfil)
    return maxOf(holguraCon(perfil), nominal * yunkil.fabricacion.Estandares.fraccionDeAjuste(clase))
}

/**
 * La cota que debe tener la pieza para que el encaje se cumpla.
 *
 * Dos holguras, una por cada lado, que es la misma cuenta que ya hace
 * [yunkil.fabricacion.Roscas] para el agujero de paso.
 */
fun Encaje.cotaDestino(nominal: Float, perfil: PerfilFabricacion): Float {
    val margen = 2f * holguraEfectiva(nominal, perfil)
    return when (sentido) {
        SentidoDeEncaje.ENTRA -> nominal - margen
        SentidoDeEncaje.RECIBE -> nominal + margen
    }
}

/** El motivo por el que un encaje no se puede cumplir, o `null` si se puede. */
fun Documento.motivoParaNoEncajar(encaje: Encaje, perfil: PerfilFabricacion): String? {
    val medida = medidaDe(encaje.medida)
        ?: return "no existe la medida «${encaje.medida}»"
    if (!medida.valor.isFinite() || medida.valor <= 0f) {
        return "«${medida.nombre}» vale ${medida.valor} y una medida tiene que ser positiva"
    }
    val destino = encaje.cotaDestino(medida.valor, perfil)
    if (destino <= 0f) {
        return "con ${redondeado(medida.valor)} mm no queda nada después de descontar " +
            "${redondeado(2f * encaje.holguraEfectiva(medida.valor, perfil))} mm de holgura"
    }
    return null
}

fun Documento.medidaDe(id: String): Medida? = medidas.firstOrNull { it.id == id }

/**
 * El encaje de la pieza que manda sobre ese parámetro, para saber a quién culpar en un
 * rechazo. Un encaje por parámetro manda si gobierna el eje de ese parámetro; un encaje
 * por escala manda en toda la pieza, porque la escala mueve todos los ejes.
 */
fun Documento.encajeQueGobierna(pieza: Pieza, clave: String): Encaje? =
    pieza.encajes.firstOrNull { it.porParametro && pieza.parametroQueGobierna(it.eje) == clave }
        ?: pieza.encajes.firstOrNull()

/**
 * Reescribe las cotas gobernadas por un encaje. Función pura sobre el documento.
 *
 * Recorre en **preorden** —los ancestros antes que los descendientes— porque la
 * extensión de una pieza se mide en el mundo, y en el mundo la escala del padre ya
 * cuenta. Resolver un hijo antes que su padre lo mediría contra una escala que está a
 * punto de cambiar, y el resultado dependería del orden del árbol.
 *
 * Un encaje que no se puede cumplir se **deja como está** en vez de reventar aquí: el
 * sitio donde se rechaza con su motivo es al declararlo y al cambiar la medida, que es
 * donde hay alguien mirando. Esto se llama también al abrir y al cambiar de perfil, y
 * negarse a abrir un archivo por una holgura imposible sería castigar a quien no ha
 * hecho nada.
 */
fun Documento.resolverEncajes(perfil: PerfilFabricacion): Documento {
    val conEncaje = raiz.aplanar().map { it.first }.filter { it.encajes.isNotEmpty() }.map { it.id }
    if (conEncaje.isEmpty()) return this

    var doc = this
    for (id in conEncaje) {
        val pieza = doc.buscar(id) ?: continue
        // Los por parámetro van primero: mueven un número de la pieza y no su escala,
        // así que no pisarían a nadie. Los de escala van después y solo si la pieza
        // no lleva ninguno por parámetro, porque una escala uniforme movería el eje
        // que el encaje por parámetro acaba de fijar.
        for (encaje in pieza.encajes.filter { it.porParametro }) {
            doc = doc.resolverUnoPorParametro(id, encaje, perfil)
        }
        if (pieza.encajes.none { it.porParametro }) {
            for (encaje in pieza.encajes) {
                doc = doc.resolverUnoPorEscala(id, encaje, perfil)
            }
        }
    }
    return doc
}

/**
 * La derivación de siempre: escalar la pieza entera hasta que el eje gobernado mida lo
 * que dice el encaje. Un encaje por parámetro en la misma pieza la invalidaría, y por
 * eso [resolverEncajes] no llega a llamarla en ese caso.
 */
private fun Documento.resolverUnoPorEscala(id: String, encaje: Encaje, perfil: PerfilFabricacion): Documento {
    val pieza = buscar(id) ?: return this
    if (motivoParaNoEncajar(encaje, perfil) != null) return this
    val nominal = medidaDe(encaje.medida)?.valor ?: return this
    val destino = encaje.cotaDestino(nominal, perfil)

    val cotas = cotasEnMundoDe(id) ?: return this
    val actual = extension(cotas, encaje.eje)
    // Una pieza sin espesor en ese eje daría un factor infinito y un sólido de
    // tamaño arbitrario que nadie ha pedido.
    if (actual <= 1e-4f || !actual.isFinite()) return this

    val escala = (pieza.transform.scale * (destino / actual)).coerceIn(1e-3f, 1e4f)
    if (escala == pieza.transform.scale) return this
    return copy(
        raiz = raiz.mapear(id) { it.copy(transform = it.transform.copy(scale = escala)) },
    )
}

/**
 * La derivación por parámetro: mover **el número responsable** del eje gobernado.
 *
 * La cota de estas piezas es proporcional al parámetro —el radio de un cilindro, la
 * anchura de una caja—, así que un paso de proporción directa la clava: se mide la
 * extensión actual en el mundo, se sabe qué parámetro la produce y el nuevo valor sale
 * de multiplicar por lo que falta. Se acota a los límites del parámetro, y lo que
 * fuera de ellos se queda donde pueda, que el sitio donde se avisa es la verificación,
 * no la derivación silenciosa de cada edición.
 */
private fun Documento.resolverUnoPorParametro(id: String, encaje: Encaje, perfil: PerfilFabricacion): Documento {
    val pieza = buscar(id) ?: return this
    if (motivoParaNoEncajar(encaje, perfil) != null) return this
    val nominal = medidaDe(encaje.medida)?.valor ?: return this
    val destino = encaje.cotaDestino(nominal, perfil)

    val clave = pieza.parametroQueGobierna(encaje.eje) ?: return this
    val definicion = pieza.tipo.parametrosCon(pieza.forma).firstOrNull { it.clave == clave }
        ?: return this
    val valorActual = pieza.parametro(clave)
    // Un parámetro en cero no da una proporción: da un infinito.
    if (valorActual <= 1e-4f || !valorActual.isFinite()) return this

    val cotas = cotasEnMundoDe(id) ?: return this
    val actual = extension(cotas, encaje.eje)
    if (actual <= 1e-4f || !actual.isFinite()) return this

    val nuevo = (valorActual * (destino / actual)).coerceIn(definicion.minimo, definicion.maximo)
    if (nuevo == valorActual) return this
    return copy(
        raiz = raiz.mapear(id) { it.copy(parametros = it.parametros + (clave to nuevo)) },
    )
}

/** La extensión de una caja en el eje que se le diga. */
fun extension(caja: yunkil.kernel.Aabb, eje: EjeNombrado): Float = when (eje) {
    EjeNombrado.X -> caja.size.x
    EjeNombrado.Y -> caja.size.y
    EjeNombrado.Z -> caja.size.z
}

/** Identificador libre para una medida nueva, deducido del propio documento. */
fun Documento.idDeMedidaLibre(): String {
    val usados = medidas.mapNotNull { it.id.substringAfterLast('-', "").toIntOrNull() }
    return "medida-${(usados.maxOrNull() ?: 0) + 1}"
}

internal fun redondeado(v: Float): String {
    val r = kotlin.math.round(v * 100f) / 100f
    return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
}
