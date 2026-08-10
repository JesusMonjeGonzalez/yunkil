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
) {
    /** Cómo se lee en el inspector: «Ø20,0 mm con calibre». */
    fun descripcion(): String = "$nombre: ${redondeado(valor)} mm ${procedencia.etiqueta}"
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
)

// --------------------------------------------------------------- la resolución

/** La holgura que le toca a este encaje con este perfil, en milímetros. */
fun Encaje.holguraCon(perfil: PerfilFabricacion): Float = perfil.holguraEncaje * clase.factor

/**
 * La cota que debe tener la pieza para que el encaje se cumpla.
 *
 * Dos holguras, una por cada lado, que es la misma cuenta que ya hace
 * [yunkil.fabricacion.Roscas] para el agujero de paso.
 */
fun Encaje.cotaDestino(nominal: Float, perfil: PerfilFabricacion): Float {
    val margen = 2f * holguraCon(perfil)
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
            "${redondeado(2f * encaje.holguraCon(perfil))} mm de holgura"
    }
    return null
}

fun Documento.medidaDe(id: String): Medida? = medidas.firstOrNull { it.id == id }

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
    val conEncaje = raiz.aplanar().map { it.first }.filter { it.encaje != null }.map { it.id }
    if (conEncaje.isEmpty()) return this

    var doc = this
    for (id in conEncaje) {
        val pieza = doc.buscar(id) ?: continue
        val encaje = pieza.encaje ?: continue
        if (doc.motivoParaNoEncajar(encaje, perfil) != null) continue
        val nominal = doc.medidaDe(encaje.medida)?.valor ?: continue
        val destino = encaje.cotaDestino(nominal, perfil)

        val cotas = doc.cotasEnMundoDe(id) ?: continue
        val actual = when (encaje.eje) {
            EjeNombrado.X -> cotas.size.x
            EjeNombrado.Y -> cotas.size.y
            EjeNombrado.Z -> cotas.size.z
        }
        // Una pieza sin espesor en ese eje daría un factor infinito y un sólido de
        // tamaño arbitrario que nadie ha pedido.
        if (actual <= 1e-4f || !actual.isFinite()) continue

        val escala = (pieza.transform.scale * (destino / actual)).coerceIn(1e-3f, 1e4f)
        if (escala == pieza.transform.scale) continue
        doc = doc.copy(
            raiz = doc.raiz.mapear(id) { it.copy(transform = it.transform.copy(scale = escala)) },
        )
    }
    return doc
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
