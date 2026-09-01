package yunkil.fabricacion

import yunkil.doc.Editor

/**
 * La misma pieza bajo varios perfiles, una fila por cada uno.
 *
 * «¿Esta pieza sale bien en PETG?» no se responde repitiendo el análisis a mano y
 * apuntando las puntuaciones: se pide la comparación y se mira. Cada fila es el
 * informe completo de ese perfil —el mismo examen que decide el certificado—, resumido
 * a lo que hace falta para elegir: apto o no, puntuación y los avisos que más pesan.
 *
 * Es cara a propósito de decirlo: cada fila cuesta un análisis entero, y el análisis
 * muestrea a densidad de boquilla. Es una herramienta de decisión, no un mando que se
 * pide a cada cambio.
 */
data class ComparacionDePerfiles(
    val perfil: String,
    val material: String,
    val boquilla: Float,
    val apto: Boolean,
    val puntuacion: Int,
    val resumen: String,
    /** Los avisos con más peso, ya contados en español. */
    val peoresHallazgos: List<String>,
)

/**
 * Compara la pieza del editor bajo los perfiles pedidos —todos, si no se dice nada—.
 *
 * No toca el documento ni el perfil activo: cada análisis corre sobre el mismo estado,
 * así que las filas son comparables entre sí y con el informe que se pediría después.
 */
fun Editor.compararPerfiles(nombres: List<String> = CatalogoDePerfiles.todos.map { it.nombre }): List<ComparacionDePerfiles> =
    nombres.mapNotNull { nombre ->
        val informe = analizarFabricacion(nombre) ?: return@mapNotNull null
        ComparacionDePerfiles(
            perfil = informe.perfil.nombre,
            material = informe.perfil.material,
            boquilla = informe.perfil.boquilla,
            apto = informe.aptoParaImprimir,
            puntuacion = informe.puntuacion,
            resumen = informe.resumen,
            peoresHallazgos = informe.hallazgos
                .sortedByDescending { it.severidad.peso * 1000f + it.areaAfectada }
                .take(3)
                .map { "${it.severidad.etiqueta}: ${it.titulo}" },
        )
    }.sortedByDescending { it.puntuacion }
