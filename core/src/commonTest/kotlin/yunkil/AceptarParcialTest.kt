package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Aceptar media propuesta tiene que dejar exactamente la media que se aceptó.
 *
 * El riesgo no es que falle ruidosamente: es que el aplicador **omita** en silencio
 * la operación que nombra una pieza descartada y el usuario se quede con un plan a
 * medio montar creyendo que aceptó lo que veía.
 */
class AceptarParcialTest {

    private fun planDeDosPiezas() = PlanDeModelado(
        operaciones = listOf(
            /* 0 */ Crear(tipo = "CAJA", alias = "b", nombre = "Base"),
            /* 1 */ Crear(tipo = "CAJA", alias = "t", nombre = "Tapa"),
            /* 2 */ Colocar(objetivo = "t", referencia = "b"),
            /* 3 */ Filete(objetivo = "t", contra = "b", radio = 1f),
        ),
    )

    private fun editorVacio() = Editor(Documento.vacio())

    /** Las piezas del documento sin la raíz, que existe siempre y no la crea el plan. */
    private fun Editor.nombres(): List<String> =
        filas().filter { it.profundidad > 0 }.map { it.nombre }

    @Test
    fun `aceptar todo deja las mismas piezas que aplicar el plan entero`() {
        val plan = planDeDosPiezas()
        val entero = editorVacio().also { it.aplicarPlan(plan) }
        val parcial = editorVacio().also { it.aplicarParte(plan, plan.operaciones.indices.toList()) }
        assertEquals(entero.nombres(), parcial.nombres())
    }

    @Test
    fun `aceptar solo una pieza deja solo esa pieza`() {
        val e = editorVacio()
        val r = e.aplicarParte(planDeDosPiezas(), listOf(0))
        assertTrue(r.exito, r.resumen)
        assertEquals(listOf("Base"), e.nombres())
    }

    @Test
    fun `descartar la creacion arrastra a lo que la nombraba`() {
        // Se marca todo menos el `crear` de la Tapa. El `colocar` y el `filete` hablan
        // de la Tapa, así que no pueden sobrevivir: sin poda el aplicador los omitiría
        // y el resumen diría «1 aplicada · 2 omitidas» sin que nadie lo hubiera pedido.
        val plan = planDeDosPiezas()
        assertEquals(setOf(0), Explicacion.podar(plan, setOf(0, 2, 3)))

        val e = editorVacio()
        val r = e.aplicarParte(plan, listOf(0, 2, 3))
        assertTrue(r.exito, r.resumen)
        assertTrue(r.omitidas.isEmpty(), "no debería omitir nada: ${r.omitidas}")
        assertEquals(listOf("Base"), e.nombres())
    }

    @Test
    fun `marcar arrastra hacia arriba lo que hace falta`() {
        val plan = planDeDosPiezas()
        // Marcar solo el colocar pide las dos creaciones, no el filete.
        assertEquals(setOf(0, 1, 2), Explicacion.completar(plan, setOf(2)))

        // Y marcar solo el filete pide las dos piezas, **pero no el colocar**. La
        // dependencia que se sigue es «nombra lo que esta creó», que es un hecho leído
        // del plan. «Y además hace falta lo que la colocó donde toca» sería una
        // suposición sobre la intención, y una selección que arrastra cosas que el
        // usuario no pidió es peor que una que se queda corta: lo segundo se ve en la
        // lista antes de aceptar, lo primero aparece en el documento después.
        assertEquals(setOf(0, 1, 3), Explicacion.completar(plan, setOf(3)))
    }

    @Test
    fun `no aceptar nada no toca el documento`() {
        val e = editorVacio()
        val antes = e.aJson()
        val r = e.aplicarParte(planDeDosPiezas(), emptyList())
        assertFalse(r.exito)
        assertEquals(antes, e.aJson())
    }

    @Test
    fun `una aceptacion parcial no vacia el documento aunque el plan reemplace`() {
        // `reemplazar` significa «hazme otra cosa». Si el usuario descarta parte de esa
        // otra cosa, tirar además lo que ya tenía destruye más de lo que se acepta, y
        // el deshacer sería la única forma de enterarse.
        val e = editorVacio()
        e.aplicarPlan(PlanDeModelado(operaciones = listOf(Crear(tipo = "ESFERA", nombre = "Previa"))))

        val plan = planDeDosPiezas().copy(reemplazar = true)
        e.aplicarParte(plan, listOf(0))
        assertTrue("Previa" in e.nombres(), "el trabajo previo desapareció: ${e.nombres()}")
        assertTrue("Base" in e.nombres(), e.nombres().toString())
    }

    @Test
    fun `aceptar el plan entero de un reemplazo si vacia el documento`() {
        val e = editorVacio()
        e.aplicarPlan(PlanDeModelado(operaciones = listOf(Crear(tipo = "ESFERA", nombre = "Previa"))))

        val plan = planDeDosPiezas().copy(reemplazar = true)
        e.aplicarParte(plan, plan.operaciones.indices.toList())
        assertFalse("Previa" in e.nombres(), "el reemplazo completo debía sustituirlo: ${e.nombres()}")
    }

    @Test
    fun `el plan que llega al historial es el que se aplico`() {
        // El historial navegable reproduce los planes guardados. Si guardara el plan
        // propuesto, reproducirlo traería de vuelta las operaciones que el usuario
        // había descartado, y el historial dejaría de contar lo que pasó.
        val e = editorVacio()
        e.aplicarParte(planDeDosPiezas(), listOf(0, 1))
        val guardado = e.planesAplicados.single()
        assertEquals(2, guardado.operaciones.size)
    }

    @Test
    fun `una operacion sobre una pieza que ya existe no depende de nada del plan`() {
        // El grafo de dependencias es solo dentro del plan: taladrar una pieza que ya
        // está en el documento tiene que poder aceptarse sola.
        val e = editorVacio()
        e.aplicarPlan(PlanDeModelado(operaciones = listOf(Crear(tipo = "CAJA", nombre = "Existente"))))
        val id = e.filas().first { it.nombre == "Existente" }.id

        val plan = PlanDeModelado(
            operaciones = listOf(
                Crear(tipo = "ESFERA", alias = "s", nombre = "Nueva"),
                Taladro(objetivo = id, designacion = "M3"),
            ),
        )
        assertEquals(setOf(1), Explicacion.podar(plan, setOf(1)))
        val r = e.aplicarParte(plan, listOf(1))
        assertTrue(r.exito, r.resumen)
        assertFalse("Nueva" in e.nombres(), e.nombres().toString())
    }
}

/**
 * «Redondéale los cantos» a una pieza suelta.
 *
 * Lo destapó el banco de hilo: pedido sobre una tapa cilíndrica, el modelo emite un
 * `filete` de la pieza contra sí misma —es lo único que el vocabulario le deja decir—
 * y antes eso se rechazaba con «aquí no se juntan dos piezas», sin redondeo y sin que
 * el usuario se enterara de por qué.
 */
class FileteDePiezaSueltaTest {

    private fun editorCon(tipo: String): Pair<Editor, String> {
        val e = Editor(Documento.vacio())
        e.aplicarPlan(PlanDeModelado(operaciones = listOf(Crear(tipo = tipo, nombre = "Pieza"))))
        return e to e.filas().first { it.nombre == "Pieza" }.id
    }

    private fun redondeoDe(e: Editor, id: String): Float =
        e.parametrosDe(id).first { it.clave == "redondeo" }.valor

    @Test
    fun `un cilindro suelto redondea sus propios cantos`() {
        val (e, id) = editorCon("CILINDRO")
        // El booleano que devuelve el editor significa «hay que recompilar el shader»,
        // no «salió bien» —cambiar un uniforme no recompila nada—. La señal de éxito es
        // `ultimoError`, que es la que usa el `Aplicador`.
        e.filetearEntre(id, null, 1.5f)
        assertEquals(null, e.ultimoError)
        assertEquals(1.5f, redondeoDe(e, id))
    }

    @Test
    fun `y tambien cuando el modelo lo escribe contra si mismo`() {
        // Es literalmente lo que emitió el modelo local: objetivo y contra, la misma pieza.
        val (e, id) = editorCon("CAJA")
        val plan = PlanDeModelado(
            operaciones = listOf(Filete(objetivo = id, contra = id, radio = 2f)),
        )
        val r = e.aplicarPlan(plan)
        assertTrue(r.exito, r.resumen)
        assertEquals(2f, redondeoDe(e, id))
    }

    @Test
    fun `una pieza sin cantos redondeables lo dice con su nombre`() {
        // La esfera no tiene parámetro de redondeo y no cuelga de ningún booleano.
        val (e, id) = editorCon("ESFERA")
        assertFalse(e.filetearEntre(id, null, 1f))
        assertContains(e.ultimoError ?: "", "Pieza")
    }

    @Test
    fun `filetear el encuentro entre dos piezas sigue haciendo lo de siempre`() {
        // El camino que ya funcionaba no puede cambiar: con `contra` apuntando a OTRA
        // pieza se sigue redondeando el canto del booleano, no los cantos propios.
        val e = Editor(Documento.vacio())
        e.aplicarPlan(
            PlanDeModelado(
                operaciones = listOf(
                    Crear(tipo = "CAJA", alias = "a", nombre = "A"),
                    Crear(tipo = "CAJA", alias = "b", nombre = "B"),
                    Colocar(objetivo = "b", referencia = "a"),
                    Filete(objetivo = "a", contra = "b", radio = 1.5f),
                ),
            ),
        )
        val a = e.filas().first { it.nombre == "A" }.id
        // 2 mm es el redondeo con el que nace una CAJA. Que siga ahí es lo que se
        // comprueba: el filete de encuentro no toca los cantos propios de la pieza.
        assertEquals(2f, redondeoDe(e, a), "no debía tocar el redondeo propio de A")
        assertTrue(
            e.filas().any { it.tipo == "UNION" || it.tipo == "DIFERENCIA" },
            "el filete de encuentro tiene que vivir en el booleano",
        )
    }
}
