package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La resta que no corta era el modo de fallo que salía **sin arreglo**.
 *
 * Los sólidos sueltos ya venían con su `colocar` escrito, y eso subió la geometría
 * limpia de 6/8 a 7/8. La otra mitad del catálogo —un sustraendo que se queda a 44 mm
 * de la pieza que tenía que perforar— llevaba un aviso redactado y nada más: dependía
 * de que el modelo tradujera la frase a una operación y de gastar una ronda entera.
 */
class CoserRestaTest {

    /**
     * Una caja con un cilindro dentro de una DIFERENCIA, pero fuera de la caja.
     *
     * Es la forma exacta que produce un modelo real cuando se equivoca: el árbol queda
     * como él lo describió, el plan es válido, la operación se aplica y no hay agujero.
     */
    private fun planConRestaQueNoCorta() = PlanDeModelado(
        operaciones = listOf(
            Crear(
                tipo = "CAJA", alias = "c", nombre = "Cuerpo",
                parametros = mapOf("anchura" to 40f, "altura" to 20f, "profundidad" to 40f),
            ),
            Envolver(objetivo = "c", tipo = "DIFERENCIA", alias = "d", nombre = "Perforado"),
            Crear(
                tipo = "CILINDRO", alias = "t", nombre = "Taladro", padre = "d",
                parametros = mapOf("radio" to 3f, "altura" to 60f),
                // A 60 mm en X: la caja llega a 20, así que el cilindro no la toca.
                posicion = listOf(60f, 0f, 0f),
            ),
        ),
    )

    /** El mismo plan, con el cilindro donde sí corta. Sirve de control. */
    private fun planConRestaQueSiCorta() = PlanDeModelado(
        operaciones = planConRestaQueNoCorta().operaciones.map {
            if (it is Crear && it.alias == "t") it.copy(posicion = listOf(0f, 0f, 0f)) else it
        },
    )

    private fun editorConLaResta(): Editor =
        Editor(Documento.vacio()).also { it.aplicarPlan(planConRestaQueNoCorta()) }

    @Test
    fun `el revisor denuncia la resta y trae la operacion que la arregla`() {
        val e = editorConLaResta()
        val nodo = e.documentoActual.compilar() ?: error("no compila")
        val reparos = RevisorDeGeometria.reparos(e.documentoActual, nodo)

        val resta = reparos.firstOrNull { it.clase == ClaseDeFallo.RESTA_SIN_EFECTO }
        assertNotNull(resta, "no se denunció la resta inútil: $reparos")
        assertTrue(resta.arreglos.isNotEmpty(), "el reparo llegó sin arreglo ejecutable")

        val arreglo = resta.arreglos.single()
        assertTrue(arreglo is Mover, "el arreglo debería ser un mover y es $arreglo")
        // Por nombre y no por id: el arreglo se calcula en un banco y se reaplica en
        // otro, y los identificadores los renumera el aplicador en cada pasada.
        assertEquals("Taladro", (arreglo as Mover).objetivo)
        // Y el motivo trae el JSON literal, que es lo que el modelo copia sin fallar.
        assertContains(resta.motivo, "\"op\":\"mover\"")
    }

    @Test
    fun `el arreglo solo corrige el eje en el que las cajas estan separadas`() {
        val e = editorConLaResta()
        val nodo = e.documentoActual.compilar()!!
        val arreglo = RevisorDeGeometria.reparos(e.documentoActual, nodo)
            .first { it.clase == ClaseDeFallo.RESTA_SIN_EFECTO }.arreglos.single() as Mover

        // Solo X estaba separado. Tocar Y o Z convertiría cuatro agujeros repartidos
        // en cuatro agujeros apilados en el centro.
        assertTrue(arreglo.x != 0f, "no corrigió el eje que estaba mal")
        assertEquals(0f, arreglo.y, "movió un eje que ya estaba bien")
        assertEquals(0f, arreglo.z, "movió un eje que ya estaba bien")
    }

    @Test
    fun `aplicar el arreglo hace que la resta corte de verdad`() {
        val e = editorConLaResta()
        val antes = RevisorDeGeometria.reparos(e.documentoActual, e.documentoActual.compilar()!!)
        assertTrue(antes.any { it.clase == ClaseDeFallo.RESTA_SIN_EFECTO })

        val arreglo = antes.first { it.clase == ClaseDeFallo.RESTA_SIN_EFECTO }.arreglos.single()
        val r = e.aplicarPlan(PlanDeModelado(operaciones = listOf(arreglo)))
        assertTrue(r.exito, r.resumen)

        val despues = RevisorDeGeometria.reparos(e.documentoActual, e.documentoActual.compilar()!!)
        assertTrue(
            despues.none { it.clase == ClaseDeFallo.RESTA_SIN_EFECTO },
            "la resta sigue sin cortar: $despues",
        )
    }

    @Test
    fun `una resta que si corta no se denuncia`() {
        // El aviso alimenta un reintento automático, así que un falso positivo mandaría
        // al modelo a arreglar algo que ya estaba bien y convertiría el bucle en un
        // generador de reintentos.
        val e = Editor(Documento.vacio())
        e.aplicarPlan(
            PlanDeModelado(
                operaciones = listOf(
                    Crear(tipo = "CAJA", alias = "c", nombre = "Cuerpo"),
                    Taladro(objetivo = "c", designacion = "M4"),
                ),
            ),
        )
        val reparos = RevisorDeGeometria.reparos(e.documentoActual, e.documentoActual.compilar()!!)
        assertTrue(
            reparos.none { it.clase == ClaseDeFallo.RESTA_SIN_EFECTO },
            "denunció un taladro que sí corta: $reparos",
        )
    }

    @Test
    fun `coser arregla la resta y lo comprueba midiendo`() {
        val e = Editor(Documento.vacio())
        val cosido = e.coserPlan(planConRestaQueNoCorta())
        assertNotNull(cosido, "el cosido no se disparó en el fallo para el que existe")
        assertTrue(
            cosido.operaciones.size > planConRestaQueNoCorta().operaciones.size,
            "el plan cosido no añadió nada",
        )

        // Y el resultado de aplicarlo tiene que estar limpio de verdad, no en teoría.
        val comprobante = Editor(Documento.vacio())
        comprobante.aplicarPlan(cosido)
        val reparos = RevisorDeGeometria.reparos(
            comprobante.documentoActual,
            comprobante.documentoActual.compilar()!!,
        )
        assertTrue(reparos.isEmpty(), "el cosido dejó fallos: $reparos")
    }

    @Test
    fun `coser no se inventa mejoras cuando no hay nada que arreglar`() {
        // La versión anterior preguntaba solo si quedaban sólidos sueltos, y con un
        // fallo de otra clase esa condición es trivialmente cierta: un cosido que no
        // arreglaba nada pasaba por bueno. Ahora se exige que el número de fallos baje.
        val e = Editor(Documento.vacio())
        assertNull(
            e.coserPlan(planConRestaQueSiCorta()),
            "cosió un plan que ya estaba bien",
        )
    }
}
