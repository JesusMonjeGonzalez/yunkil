package yunkil.organico

import yunkil.ia.Explicacion.medida

/**
 * Una parte del contrato contada en español, con el índice que la señala.
 *
 * Es el equivalente orgánico de `LineaExplicada`, y existe por lo mismo: la interfaz
 * enseña estas líneas y devuelve los índices que el usuario aceptó, sin volver a
 * mirar el JSON del modelo. [depende] es el índice de la parte a la que esta se une,
 * para que desmarcar una arrastre a las que colgaban de ella.
 *
 * [esencial] no es una preferencia de estilo: el validador exige un CUERPO y una
 * CABEZA, así que una figura sin ellos no es una figura a medias, es un contrato que
 * no compila. Marcar esas dos líneas como esenciales permite que la interfaz las
 * enseñe sin casilla en vez de dejar desmarcar algo que después se rechaza.
 */
data class LineaOrganica(
    val indice: Int,
    val id: String,
    val texto: String,
    val depende: List<Int> = emptyList(),
    val esencial: Boolean = false,
)

/**
 * Cuenta una figura orgánica en español antes de aceptarla, parte por parte.
 *
 * Hasta aquí el contrato se ofrecía entero: se aceptaba o se descartaba. Y una figura
 * es justo donde eso peor sienta, porque lo que falla casi nunca es la figura —es el
 * cuerno de más, la cola que el modelo se inventó o el detalle que no venía a cuento—.
 * Descartarla entera por eso obliga a volver a pedirla con la esperanza de que salga
 * igual menos una parte, que es exactamente el trato que la aceptación parcial del
 * plan paramétrico vino a quitar.
 *
 * Es una **función pura sobre el contrato**: no toca el documento, no necesita modelo
 * y no puede fallar. Lo que sí puede fallar es la figura que queda al quitar partes, y
 * de eso responde [MotorOrganico.conPartes], que la vuelve a validar y a compilar.
 */
object ExplicacionOrganica {

    fun de(contrato: ContratoOrganico): List<LineaOrganica> {
        val indicePorId = contrato.partes.withIndex().associate { (i, p) -> p.id to i }
        val cuerpo = contrato.partes.indexOfFirst {
            it.rol == RolOrganico.CUERPO && it.modo == ModoOrganico.AGREGAR
        }
        val cabeza = contrato.partes.indexOfFirst {
            it.rol == RolOrganico.CABEZA && it.modo == ModoOrganico.AGREGAR
        }
        return contrato.partes.mapIndexed { i, parte ->
            // Solo se mira hacia atrás: el validador ya exige que `unidoA` nombre una
            // parte anterior, así que el grafo no puede tener ciclos y la poda termina.
            val padre = parte.unidoA?.let { indicePorId[it] }?.takeIf { it < i }
            LineaOrganica(
                indice = i,
                id = parte.id,
                texto = contar(parte),
                depende = listOfNotNull(padre),
                esencial = i == cuerpo || i == cabeza,
            )
        }
    }

    /** La figura entera en un bloque de texto, una línea por parte. */
    fun texto(contrato: ContratoOrganico): String =
        de(contrato).joinToString("\n") { it.texto }

    /**
     * Quita de lo marcado toda parte que se quedaría colgando de otra que no está.
     *
     * Es lo que hace del aceptar parcial una función y no una trampa. Si se descarta
     * un brazo, la mano que se unía a él no puede quedarse: sin su padre no toca nada
     * y el compilador la rechaza —o peor, flota—. Es transitiva, igual que en el plan.
     *
     * Las partes esenciales entran siempre. Desmarcar la cabeza no produce una figura
     * sin cabeza: produce un contrato inválido, y eso no es una opción que ofrecer.
     */
    fun podar(contrato: ContratoOrganico, marcadas: Set<Int>): Set<Int> {
        val lineas = de(contrato)
        var vivas = (marcadas + lineas.filter { it.esencial }.map { it.indice })
            .filter { it in lineas.indices }
            .toMutableSet()
        while (true) {
            val caen = vivas.filter { i -> lineas[i].depende.any { it !in vivas } }
            if (caen.isEmpty()) return vivas
            vivas = (vivas - caen.toSet()).toMutableSet()
        }
    }

    /**
     * Añade a lo marcado todo lo que hace falta para que se sostenga.
     *
     * La otra mitad de [podar], y la que usa la interfaz al **marcar**: pedir la mano
     * es pedir el brazo del que cuelga. Sin esto, marcar una casilla la vería
     * desaparecer por obra de [podar] y parecería que la casilla está rota.
     */
    fun completar(contrato: ContratoOrganico, marcadas: Set<Int>): Set<Int> {
        val lineas = de(contrato)
        val vivas = (marcadas + lineas.filter { it.esencial }.map { it.indice })
            .filter { it in lineas.indices }
            .toMutableSet()
        while (true) {
            val faltan = vivas.flatMap { lineas[it].depende }.filter { it !in vivas }
            if (faltan.isEmpty()) return vivas
            vivas += faltan
        }
    }

    // -------------------------------------------------------------------------

    private fun contar(p: ParteOrganica): String {
        val que = when (p.modo) {
            ModoOrganico.AGREGAR -> etiqueta(p.rol)
            // Una parte en QUITAR no es una parte de la figura: es un hueco. Contarla
            // como «Detalle» dejaría al usuario marcando algo que no entiende.
            ModoOrganico.QUITAR -> "Hueco (${etiqueta(p.rol).lowercase()})"
        }
        val union = p.unidoA?.let { ", unida a $it" }.orEmpty()
        return "$que «${p.id}»: ${forma(p)}$union"
    }

    private fun forma(p: ParteOrganica): String = when (p.forma) {
        FormaOrganica.ESFERA -> "esfera de ${medida(p.radio)} mm de radio"
        FormaOrganica.CAPSULA ->
            "cápsula de ${medida(largo(p))} mm de largo y ${medida(p.radio)} mm de radio"
        FormaOrganica.TRONCO ->
            "tronco de ${medida(largo(p))} mm, de ${medida(p.radioA)} a ${medida(p.radioB)} mm"
        FormaOrganica.CURVA -> {
            val puntos = p.puntos.size / 3
            val gruesos = p.radios.filter { it > 0f }
            val grosor = if (gruesos.isEmpty()) ""
            else " de ${medida(gruesos.min())} a ${medida(gruesos.max())} mm"
            "curva de $puntos puntos$grosor"
        }
    }

    private fun largo(p: ParteOrganica): Float {
        if (p.a.size != 3 || p.b.size != 3) return 0f
        val dx = p.b[0] - p.a[0]
        val dy = p.b[1] - p.a[1]
        val dz = p.b[2] - p.a[2]
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun etiqueta(rol: RolOrganico): String = when (rol) {
        RolOrganico.CUERPO -> "Cuerpo"
        RolOrganico.CABEZA -> "Cabeza"
        RolOrganico.EXTREMIDAD -> "Extremidad"
        RolOrganico.OREJA -> "Oreja"
        RolOrganico.COLA -> "Cola"
        RolOrganico.OJO -> "Ojo"
        RolOrganico.DETALLE -> "Detalle"
    }
}
