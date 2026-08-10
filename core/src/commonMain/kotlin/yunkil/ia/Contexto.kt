package yunkil.ia

import yunkil.doc.Documento
import yunkil.doc.Pieza
import yunkil.doc.compilar
import yunkil.kernel.Aabb
import yunkil.kernel.SdfNode
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Vec3

/**
 * Transformación acumulada desde la raíz hasta [id], **sin incluir** la de la
 * propia pieza. Es el espacio en el que vive su `transform`, y por tanto el que
 * hay que usar para convertir un desplazamiento del mundo en uno que se le pueda
 * escribir.
 */
fun Documento.transformDelPadreDe(id: String): Transform? {
    fun buscar(pieza: Pieza, acumulada: Transform): Transform? {
        if (pieza.id == id) return acumulada
        val dentro = acumulada.componer(pieza.transform)
        for (h in pieza.hijos) buscar(h, dentro)?.let { return it }
        return null
    }
    return buscar(raiz, Transform.IDENTITY)
}

/** El campo de una pieza concreta ya situado en coordenadas del mundo. */
fun Documento.nodoEnMundoDe(id: String): SdfNode? {
    val padre = transformDelPadreDe(id) ?: return null
    val pieza = buscarPieza(id) ?: return null
    val local = pieza.compilar() ?: return null
    return if (padre == Transform.IDENTITY) local else Transformado(local, padre)
}

/** Cotas de una pieza en el mundo, con sus hijos incluidos. */
fun Documento.cotasEnMundoDe(id: String): Aabb? = nodoEnMundoDe(id)?.cotas()

private fun Documento.buscarPieza(id: String): Pieza? {
    fun buscar(p: Pieza): Pieza? {
        if (p.id == id) return p
        for (h in p.hijos) buscar(h)?.let { return it }
        return null
    }
    return buscar(raiz)
}

/**
 * Serializa el documento para que lo lea un modelo de lenguaje.
 *
 * La diferencia con volcar el JSON del documento es deliberada: aquí no se
 * describe cómo se guarda la pieza, sino **dónde está y cuánto ocupa en
 * milímetros**. Un modelo que ve `ocupa x[-30..30] y[0..8]` puede razonar sobre
 * encajes; uno que ve un cuaternión no puede. Y ocupa una fracción de los tokens.
 */
fun Documento.contextoParaModelo(limiteDePiezas: Int = 80): String {
    val salida = StringBuilder()
    val nodo = compilar()
    // Cotas ya mostradas en la cabecera: son las del modelo completo, así que
    // ninguna pieza necesita repetirlas si las suyas coinciden.
    var cotasGlobales: Aabb? = null
    if (nodo == null) {
        salida.append("El documento está vacío. No hay ninguna pieza todavía.\n")
    } else {
        val c = nodo.cotas()
        cotasGlobales = c
        salida.append("Modelo completo: ")
            .append(mm(c.size.x)).append(" × ").append(mm(c.size.y)).append(" × ").append(mm(c.size.z))
            .append(" mm, ocupa ").append(intervalo(c)).append('\n')
        salida.append(
            if (kotlin.math.abs(c.min.y) < 0.01f) "Está apoyado en el plato.\n"
            else "Inferior a " + mm(c.min.y) + " mm del plato.\n",
        )
    }
    // Se dice explícitamente porque el ahorro de omitir lo que está en su valor por
    // defecto solo vale si quien lee sabe interpretarlo. El catálogo de las
    // instrucciones publica esos valores, así que el dato es recuperable, pero un
    // modelo que no sepa que aquí falta algo dará por hecho que no existe.
    salida.append("Piezas (#id; no listado = por omisión):\n")

    var contadas = 0
    fun escribir(pieza: Pieza, profundidad: Int, cotasPadre: Aabb?) {
        if (contadas >= limiteDePiezas) return
        contadas++
        salida.append("  ".repeat(profundidad + 1)).append('#').append(pieza.id)
        salida.append(" \"").append(pieza.nombre).append("\" ").append(pieza.tipo.name)

        // Un parámetro en su valor por defecto no dice nada que el modelo no
        // sepa ya (conoce el catálogo): solo los que alguien ha tocado aportan.
        val parametros = pieza.tipo.parametros
            .filter { mm(pieza.parametro(it.clave)) != mm(it.defecto) }
            .joinToString(" ") { "${it.clave}=${mm(pieza.parametro(it.clave))}" }
        if (parametros.isNotEmpty()) salida.append(' ').append(parametros)

        if (
            pieza.tipo == yunkil.doc.TipoPieza.REPETICION ||
            pieza.tipo == yunkil.doc.TipoPieza.REPETICION_CIRCULAR ||
            pieza.tipo == yunkil.doc.TipoPieza.SIMETRIA
        ) {
            salida.append(" eje=").append(pieza.eje.name)
            if (pieza.tipo == yunkil.doc.TipoPieza.REPETICION || pieza.tipo == yunkil.doc.TipoPieza.REPETICION_CIRCULAR) {
                salida.append(" cuenta=").append(pieza.cuenta)
            }
        }
        if (!pieza.visible) salida.append(" (oculta)")

        val cotas = cotasEnMundoDe(pieza.id)
        val tieneVolumen = cotas != null && cotas.size.x + cotas.size.y + cotas.size.z > 0f
        // Una operación con un solo hijo —o una diferencia que no crece— ocupa
        // exactamente lo mismo que ya se ha impreso un nivel más arriba (o en la
        // cabecera, si es la raíz). Repetirlo es ruido; solo importa cuando una
        // pieza aporta un volumen distinto al de su padre.
        if (tieneVolumen && (cotasPadre == null || intervalo(cotas!!) != intervalo(cotasPadre))) {
            salida.append(" · ocupa ").append(intervalo(cotas!!))
        }
        salida.append('\n')
        pieza.hijos.forEach { escribir(it, profundidad + 1, cotas) }
    }
    escribir(raiz, 0, cotasGlobales)
    if (contadas >= limiteDePiezas) salida.append("  … (documento recortado)\n")

    // La selección es la pieza sobre la que se piden las órdenes cortas —«esta
    // arista», «este canto»—, así que no basta con nombrarla: hay que decir cuánto
    // ocupa, que es lo que un modelo necesita para razonar sin re-leer el árbol.
    seleccionado?.let { id ->
        val cotas = cotasEnMundoDe(id)
        val nombre = buscarPieza(id)?.nombre
        salida.append("Seleccionada: #").append(id)
            .append(if (nombre != null) " «$nombre»" else "")
            .append(if (cotas != null) " · ${intervalo(cotas)}" else "")
            .append(" · máx 3 ops\n")
    }
    return salida.toString()
}

private fun intervalo(c: Aabb): String =
    "x[${mm(c.min.x)}..${mm(c.max.x)}] y[${mm(c.min.y)}..${mm(c.max.y)}] z[${mm(c.min.z)}..${mm(c.max.z)}]"

/** Un decimal es la precisión con la que se imprime; más dígitos solo gastan tokens. */
internal fun mm(v: Float): String {
    if (!v.isFinite()) return "0"
    val r = kotlin.math.round(v * 10f) / 10f
    if (r == r.toInt().toFloat()) return r.toInt().toString()
    return r.toString()
}

internal fun Vec3.aTexto() = "(${mm(x)}, ${mm(y)}, ${mm(z)})"
