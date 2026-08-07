package yunkil.ia

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.fabricacion.PerfilFabricacion

/**
 * Qué pasó al aplicar un plan.
 *
 * Se distingue entre una operación *omitida* y un plan *fallido* a propósito: que
 * el modelo se invente un parámetro no debe tirar abajo las otras veintinueve
 * operaciones correctas, pero tampoco puede desaparecer sin dejar rastro. Las
 * omisiones se cuentan, se enseñan al usuario y se le devuelven al modelo si hay
 * ronda de corrección.
 */
data class ResultadoDeAplicacion(
    val aplicadas: Int,
    val omitidas: List<String>,
    val error: String? = null,
    val alias: Map<String, String> = emptyMap(),
    val piezasCreadas: List<String> = emptyList(),
) {
    val exito: Boolean get() = error == null && aplicadas > 0

    val resumen: String
        get() = when {
            error != null -> "No se aplicó nada: $error"
            omitidas.isEmpty() -> "$aplicadas operaciones aplicadas"
            else -> "$aplicadas aplicadas · ${omitidas.size} omitidas"
        }
}

/**
 * Aplica un plan validado sobre el editor, dentro de una única transacción.
 *
 * El modelo no toca el documento: propone, y esta clase ejecuta usando los mismos
 * métodos públicos que la interfaz. Si algo revienta a mitad, el documento vuelve
 * exactamente a donde estaba; no existe un estado intermedio observable.
 */
class Aplicador(
    private val editor: Editor,
    private val perfil: PerfilFabricacion = PerfilFabricacion.PREDETERMINADO,
) {

    fun aplicar(plan: PlanDeModelado): ResultadoDeAplicacion {
        val punto: Documento = editor.abrirTransaccion()
        val alias = HashMap<String, String>()
        val omitidas = ArrayList<String>()
        val creadas = ArrayList<String>()
        var aplicadas = 0
        aliasDelPlan = alias

        try {
            if (plan.reemplazar) editor.reemplazarDocumento(Documento.vacio())

            for ((indice, op) in plan.operaciones.withIndex()) {
                val problema = ejecutar(op, alias, creadas, omitidas)
                if (problema == null) aplicadas++ else omitidas.add("operación ${indice + 1}: $problema")
            }

            if (aplicadas == 0) {
                editor.revertirTransaccion(punto)
                return ResultadoDeAplicacion(
                    aplicadas = 0,
                    omitidas = omitidas,
                    error = "ninguna operación pudo aplicarse" +
                        if (omitidas.isEmpty()) "" else " (${omitidas.first()})",
                )
            }

            editor.cerrarTransaccion()
            if (plan.operaciones.isNotEmpty()) editor.registrarPlanAplicado(plan)
            return ResultadoDeAplicacion(aplicadas, omitidas, null, alias, creadas)
        } catch (e: Throwable) {
            editor.revertirTransaccion(punto)
            return ResultadoDeAplicacion(0, omitidas, "error al aplicar: ${e.message}")
        }
    }

    /**
     * Devuelve `null` si la operación se aplicó, o el motivo por el que no.
     *
     * Un parámetro que el modelo se inventa no invalida la pieza: se anota en
     * [anotaciones] y la operación sigue contando como aplicada. Lo contrario haría
     * que un plan bueno con una errata se revirtiera entero.
     */
    private fun ejecutar(
        op: Operacion,
        alias: MutableMap<String, String>,
        creadas: MutableList<String>,
        anotaciones: MutableList<String>,
    ): String? = when (op) {

        is Crear -> {
            val padre = resolver(op.padre) ?: editor.idDeLaRaiz
            val problema = intentar { editor.anadir(op.tipo, padre) }
            val id = editor.seleccionado
            when {
                problema != null -> problema
                id == null -> "la pieza se creó pero no se pudo identificar"
                else -> {
                    creadas.add(id)
                    op.alias?.let { alias[it] = id }
                    aplicarAtributos(id, op.nombre, op.parametros, op.posicion, op.giro, op.escala)
                        ?.let { anotaciones.add("${op.tipo}: $it") }
                    null
                }
            }
        }

        is Envolver -> conObjetivo(op.objetivo) { objetivo ->
            val problema = intentar { editor.envolver(objetivo, op.tipo) }
            val id = editor.seleccionado
            when {
                problema != null -> problema
                id == null -> "la operación se creó pero no se pudo identificar"
                else -> {
                    op.alias?.let { alias[it] = id }
                    aplicarAtributos(id, op.nombre, op.parametros, null, null, null)
                        ?.let { anotaciones.add("${op.tipo}: $it") }
                    null
                }
            }
        }

        is Fijar -> conObjetivo(op.objetivo) { id -> intentar { editor.fijarParametro(id, op.clave, op.valor) } }

        is Mover -> conObjetivo(op.objetivo) { id -> intentar { editor.mover(id, op.x, op.y, op.z, op.absoluto) } }

        is Girar -> conObjetivo(op.objetivo) { id -> intentar { editor.girarPieza(id, op.x, op.y, op.z, op.absoluto) } }

        is Escalar -> conObjetivo(op.objetivo) { id -> intentar { editor.escalarPieza(id, op.factor) } }

        is Acotar -> conObjetivo(op.objetivo) { id -> intentar { editor.escalarACota(id, op.eje, op.medida) } }

        is Renombrar -> conObjetivo(op.objetivo) { id -> intentar { editor.renombrar(id, op.nombre) } }

        is Eliminar -> conObjetivo(op.objetivo) { id -> intentar { editor.eliminar(id) } }

        is Duplicar -> conObjetivo(op.objetivo) { id ->
            val problema = intentar { editor.duplicar(id) }
            if (problema == null) {
                editor.seleccionado?.let { nuevo ->
                    creadas.add(nuevo)
                    op.alias?.let { alias[it] = nuevo }
                }
            }
            problema
        }

        is Colocar -> conObjetivo(op.objetivo) { objetivo ->
            val referencia = resolver(op.referencia)
            if (referencia == null) "no se encuentra la referencia «${op.referencia}»"
            else intentar { editor.colocar(objetivo, referencia, op.cara.name, op.holgura, op.centrar) }
        }

        is Alinear -> conObjetivo(op.objetivo) { objetivo ->
            val referencia = resolver(op.referencia)
            if (referencia == null) "no se encuentra la referencia «${op.referencia}»"
            else intentar { editor.alinear(objetivo, referencia, op.eje.name, op.modo.name) }
        }

        is DefinirPerfil -> conObjetivo(op.objetivo) { id ->
            val problema = if (op.forma.uppercase() == "LIBRE" || op.puntos.isNotEmpty()) {
                intentar { editor.fijarPuntosDelPerfil(id, op.puntos.flatten()) }
            } else {
                intentar { editor.fijarForma(id, op.forma) }
            }
            if (problema == null) {
                // Las cotas del contorno se aplican después de fijar la forma: antes
                // no existirían como parámetros y se rechazarían una por una.
                for ((clave, valor) in op.parametros) {
                    editor.fijarParametro(id, clave, valor)
                    editor.ultimoError?.let { anotaciones.add("perfil: $it") }
                }
            }
            problema
        }

        is Patron -> conObjetivo(op.objetivo) { objetivo ->
            intentar {
                editor.aplicarPatron(
                    objetivoId = objetivo,
                    estandar = op.estandar,
                    unidades = op.unidades,
                    ejeNombre = op.eje.name,
                    ajusteNombre = op.ajuste.name,
                    nombrePerfil = perfil.nombre,
                )
            }
        }

        is Taladro -> conObjetivo(op.objetivo) { objetivo ->
            val problema = intentar {
                editor.taladrar(
                    objetivoId = objetivo,
                    designacion = op.designacion,
                    diametro = op.diametro,
                    ajusteNombre = op.ajuste.name,
                    ejeNombre = op.eje.name,
                    desplazamientoA = (if (op.punto.isNotEmpty()) op.punto else op.desplazamiento)
                        .getOrElse(0) { 0f },
                    desplazamientoB = (if (op.punto.isNotEmpty()) op.punto else op.desplazamiento)
                        .getOrElse(1) { 0f },
                    enCoordenadasDePerfil = op.punto.isNotEmpty(),
                    nombrePerfil = perfil.nombre,
                )
            }
            if (problema == null) {
                // El alias nombra la broca y no lo que quedó seleccionado, que es la
                // pieza taladrada: quien pone un alias a un taladro quiere volver a
                // tocar el agujero, no la pieza.
                editor.ultimoTaladro?.let { broca ->
                    creadas.add(broca)
                    op.alias?.let { alias[it] = broca }
                }
            }
            problema
        }

        is Pared -> conObjetivo(op.objetivo) { objetivo ->
            val problema = intentar { editor.ahuecar(objetivo, op.grosor, perfil.nombre) }
            if (problema == null) {
                editor.seleccionado?.let { hueco -> op.alias?.let { alias[it] = hueco } }
                editor.avisoDeAhuecado?.let { anotaciones.add(it) }
            }
            problema
        }

        is Asentar -> intentar { editor.asentarEnPlato() }

        is Filete -> conObjetivo(op.objetivo) { objetivo ->
            val contra = op.contra?.let { resolver(it) }
            if (op.contra != null && contra == null) {
                "no se sabe qué es «${op.contra}»"
            } else {
                intentar { editor.filetearEntre(objetivo, contra, op.radio) }
            }
        }

        is Apoyar -> conObjetivo(op.objetivo) { objetivo ->
            intentar { editor.apoyarCaraEnElPlato(objetivo, op.cara.name) }
        }

        is Seleccionar -> conObjetivo(op.objetivo) { id ->
            editor.seleccionar(id)
            null
        }
    }

    /**
     * Ejecuta una edición y devuelve su motivo de rechazo, o `null` si valió.
     *
     * No se puede usar el booleano que devuelve el editor: ese booleano significa
     * «hay que recompilar el shader», no «salió bien». Añadir una operación vacía o
     * mover un grupo todavía sin hijos son cambios legítimos que no alteran el campo
     * y devuelven `false`. Leerlo como fallo hacía perder alias y desmontaba planes
     * correctos; la señal buena es `ultimoError`.
     */
    private inline fun intentar(accion: () -> Unit): String? {
        accion()
        return editor.ultimoError
    }

    private inline fun conObjetivo(referencia: String, accion: (String) -> String?): String? {
        val id = resolver(referencia) ?: return "no se encuentra «$referencia»"
        return accion(id)
    }

    private fun aplicarAtributos(
        id: String,
        nombre: String?,
        parametros: Map<String, Float>,
        posicion: List<Float>?,
        giro: List<Float>?,
        escala: Float?,
    ): String? {
        val fallos = ArrayList<String>()
        nombre?.takeIf { it.isNotBlank() }?.let { editor.renombrar(id, it) }

        for ((clave, valor) in parametros) {
            editor.fijarParametro(id, clave, valor)
            editor.ultimoError?.let { fallos.add(it) }
        }

        if (posicion != null || giro != null || escala != null) {
            val p = posicion.orEmpty()
            val g = giro.orEmpty()
            editor.fijarTransform(
                id,
                p.getOrElse(0) { 0f }, p.getOrElse(1) { 0f }, p.getOrElse(2) { 0f },
                g.getOrElse(0) { 0f }, g.getOrElse(1) { 0f }, g.getOrElse(2) { 0f },
                escala?.takeIf { it > 0f } ?: 1f,
            )
        }
        // La pieza existe aunque algún parámetro no le cuadre. Se devuelve el detalle
        // para que quede anotado, pero quien llama la cuenta igual como aplicada.
        return fallos.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * Traduce una referencia del plan a un identificador real.
     *
     * El orden importa: un alias declarado en el plan gana a un id existente que se
     * llame igual, porque el modelo acaba de decir a qué se refiere.
     */
    private fun resolver(referencia: String?): String? {
        if (referencia.isNullOrBlank()) return editor.seleccionado ?: editor.idDeLaRaiz
        val limpia = referencia.trim().removePrefix("#")
        return when (limpia.lowercase()) {
            "raiz", "raíz", "root", "modelo", "documento" -> editor.idDeLaRaiz
            "seleccion", "selección", "selected", "selection", "actual" ->
                editor.seleccionado ?: editor.idDeLaRaiz
            else -> aliasDelPlan[limpia]
                ?: if (editor.existe(limpia)) limpia else editor.idPorNombre(limpia)
        }
    }

    /** Alias declarados por el plan en curso. Vive solo durante `aplicar`. */
    private var aliasDelPlan: Map<String, String> = emptyMap()
}
