package yunkil.fabricacion

/**
 * El informe de fabricación contado para **personas ajenas a Yunkil**.
 *
 * Va a Markdown a propósito: lo lee el correo del taller, la nota del encargo y la
 * charla con quien imprime, sin instalar nada. Y como es el mismo `InformeDeFabricacion`
 * que decide si el certificado deja salir el archivo, no hay una segunda verdad: lo
 * que aquí se cuenta es lo que se midió, con el perfil que se midió.
 *
 * La trazabilidad es el punto: cada encaje lleva lo declarado y lo medido, y una
 * medida «a ojo» se dice a propósito. Quien reciba la pieza dentro de un mes tiene
 * derecho a saber de dónde salió cada número.
 */
fun InformeDeFabricacion.aMarkdown(
    estimacion: EstimacionDeImpresion? = null,
    interferencias: List<InterferenciaDeCuerpos> = emptyList(),
    notas: List<String> = emptyList(),
): String = buildString {
    append("# Informe de fabricación\n\n")
    append("- **Perfil:** ${perfil.nombre} (${perfil.impresora})\n")
    append("- **Material:** ${perfil.material} · boquilla ${cifra(perfil.boquilla, 2)} mm · ")
    append("capa ${cifra(perfil.alturaCapa, 2)} mm · procedencia ${perfil.origen.etiqueta}\n")
    append("- **Resultado:** ${resumen} · puntuación $puntuacion/100 · ")
    append(if (aptoParaImprimir) "**apto para imprimir**\n" else "**NO apto**\n\n")

    if (notas.isNotEmpty()) {
        append("## Avisos de procedencia\n\n")
        for (nota in notas) append("- $nota\n")
        append("\n")
    }

    append("## Medidas\n\n")
    val m = metricas
    append("- Volumen: ${cifra(m.volumen, 0)} mm³ · superficie ${cifra(m.areaSuperficie, 0)} mm²\n")
    append("- Altura: ${cifra(m.alturaTotal, 1)} mm · huella ${cifra(m.huella.x, 1)} × ")
    append("${cifra(m.huella.z, 1)} mm\n")
    append("- Superficie en voladizo: ${cifra(m.fraccionEnVoladizo * 100f, 0)} %\n")
    append("- Área de contacto con el plato: ${cifra(m.areaDeContacto, 0)} mm²\n")
    append("- Espesor mínimo: ${cifra(m.espesorMinimo, 2)} mm\n")
    estimacion?.let { append("- **Estimación:** ${it.descripcion()}\n") }
    append("\n")

    if (hallazgos.isNotEmpty()) {
        append("## Hallazgos\n\n")
        append("| Sev. | Hallazgo | Detalle |\n|---|---|---|\n")
        for (h in hallazgos.sortedByDescending { it.severidad.peso }) {
            val pieza = if (h.piezaNombre != null) " (${h.piezaNombre})" else ""
            append("| ${h.severidad.etiqueta} | ${h.titulo}$pieza | ${h.detalle} |\n")
        }
        append("\n")
    }

    if (encajes.isNotEmpty()) {
        append("## Encajes declarados\n\n")
        append("| Pieza | Nominal | Declarada | Medida | Cumple |\n|---|---|---|---|---|\n")
        for (e in encajes) {
            val tolerancia = if (e.toleranciaDeLaMedida > 0f) " ±${cifra(e.toleranciaDeLaMedida, 2)}" else ""
            append("| ${e.piezaNombre} | ${cifra(e.nominal, 2)}$tolerancia mm | ")
            append("${cifra(e.holguraDeclarada, 2)} mm | ")
            if (e.holguraMedida.isNaN()) {
                append("— | no: ${e.motivo} |\n")
            } else {
                append("${cifra(e.holguraMedida, 2)} mm | ")
                append(if (e.cumple) "sí" else "**no**").append(" |\n")
            }
        }
        append("\n")
    }

    if (interferencias.isNotEmpty()) {
        append("## Ensamblajes\n\n")
        for (i in interferencias) {
            when {
                !i.medible ->
                    append("- ${i.nombreA} ↔ ${i.nombreB}: no se pudo medir\n")
                i.interfieren ->
                    append("- ${i.nombreA} ↔ ${i.nombreB}: **interferencia de ${cifra(i.solape ?: 0f, 1)} mm³**\n")
                else ->
                    append("- ${i.nombreA} ↔ ${i.nombreB}: sin contacto, holgura mínima " +
                        "${cifra(i.holguraMinima ?: 0f, 2)} mm\n")
            }
        }
        append("\n")
    }

    append("\nGenerado por Yunkil. La geometría verificada es la del análisis; que la pieza ")
    append("entre depende además de la máquina, y de eso responde el perfil y su calibración.\n")
}
