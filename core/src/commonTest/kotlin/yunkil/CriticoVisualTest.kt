package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.CriticoVisual
import yunkil.ia.Mirada
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El crítico visual: lo que un revisor de números no puede ver.
 *
 * Hasta ahora el bucle medía cotas, contactos y estanqueidad, y **nadie miraba la
 * pieza**. Los fallos que quedan en el banco son justo los de esa clase: un gancho que
 * no abraza la puerta no tiene ningún defecto medible —el plan es válido, la geometría
 * está limpia, las cotas cuadran— y aun así no sirve.
 *
 * La regla que gobierna todo el diseño es la que ya dejó escrita el revisor
 * determinista: **un crítico que protesta de más convierte el bucle en un generador de
 * reintentos infinitos**. Por eso aquí la duda siempre se resuelve a favor de la pieza.
 */
class CriticoVisualTest {

    @Test
    fun `el critico con referencia distingue objetivo y resultado`() {
        val texto = CriticoVisual.instruccionesConReferencia("un soporte con dos agujeros")

        assertTrue("DOS imágenes" in texto)
        assertTrue("referencia original" in texto)
        assertTrue("Cuatro vistas" in texto)
        assertTrue("no estimes medidas" in texto)
    }

    // ------------------------------------------------------------------ veredicto

    @Test
    fun `un cumple limpio se lee como cumple`() {
        val v = CriticoVisual.leer("VEREDICTO: CUMPLE\nLa pieza es una caja abierta por arriba.")
        assertTrue(v.cumple)
        assertTrue(v.reparos.isEmpty())
    }

    @Test
    fun `un no cumple con reparos los recoge`() {
        val v = CriticoVisual.leer(
            """
            VEREDICTO: NO CUMPLE
            - la tapa está flotando por encima de la caja
            - falta el agujero del centro
            """.trimIndent()
        )
        assertFalse(v.cumple)
        assertEquals(2, v.reparos.size)
        assertTrue("flotando" in v.reparos[0], "se perdió el reparo: ${v.reparos}")
    }

    /**
     * Es la regla que evita el bucle infinito: una queja sin un fallo que nombrar no es
     * un fallo, es un modelo de visión dubitativo. Se lee como cumple.
     */
    @Test
    fun `un no cumple sin ningun reparo nombrado se lee como cumple`() {
        val v = CriticoVisual.leer("VEREDICTO: NO CUMPLE\nNo estoy seguro de que esté del todo bien.")
        assertTrue(v.cumple, "sin un reparo concreto no hay nada que corregir")
    }

    /**
     * «Pasa» y «lo he mirado y está bien» no son lo mismo, y la interfaz las cuenta
     * distinto: la primera deja seguir al bucle, la segunda firma una revisión. Un visor
     * que protesta sin saber decir qué deja pasar la pieza y **no** la aprueba.
     */
    @Test
    fun `la mirada distingue aprobar de dejar pasar`() {
        val aprobada = CriticoVisual.leer("VEREDICTO: CUMPLE\nEs una caja abierta por arriba.")
        assertEquals(Mirada.APROBADA, aprobada.mirada)
        assertTrue(aprobada.mirada.esAprobacion)

        val conReparos = CriticoVisual.leer("VEREDICTO: NO CUMPLE\n- falta el agujero del centro")
        assertEquals(Mirada.CON_REPAROS, conReparos.mirada)
        assertFalse(conReparos.mirada.esAprobacion, "con reparos no se aprueba nada")

        val dudosa = CriticoVisual.leer("VEREDICTO: NO CUMPLE\nNo estoy seguro.")
        assertTrue(dudosa.cumple, "sigue dejando pasar, que es la política")
        assertEquals(Mirada.DUDOSA, dudosa.mirada)
        assertFalse(dudosa.mirada.esAprobacion, "protestar sin decir qué no es aprobar")
        assertTrue(dudosa.mirada.seMiro, "pero mirarla, la miró")
    }

    @Test
    fun `no haber podido mirar la pieza no se cuenta como haberla mirado`() {
        // Los tres modos en que el crítico se cae. Todos dejan pasar la pieza —es un
        // revisor de más— y ninguno puede decir que la haya visto nadie.
        for (sin in listOf(Mirada.SIN_DIBUJO, Mirada.SIN_VISOR, Mirada.SIN_RESPUESTA)) {
            assertFalse(sin.seMiro, "$sin no debería contar como mirada")
            assertFalse(sin.esAprobacion, "$sin no aprueba nada")
            assertTrue(sin.etiqueta.startsWith("sin mirar"), "y hay que poder leerlo: ${sin.etiqueta}")
        }
    }

    @Test
    fun `una respuesta que no dice veredicto se lee como cumple`() {
        val v = CriticoVisual.leer("La imagen muestra cuatro vistas de una pieza gris sobre fondo oscuro.")
        assertTrue(v.cumple, "ante la duda, la pieza pasa")
    }

    @Test
    fun `una respuesta vacia se lee como cumple`() {
        assertTrue(CriticoVisual.leer("").cumple)
    }

    @Test
    fun `el veredicto se reconoce en minusculas y sin dos puntos`() {
        // Los modelos escriben lo que les da la gana; el formato es una petición, no
        // una garantía.
        val v = CriticoVisual.leer("veredicto no cumple\n- el soporte no toca el suelo")
        assertFalse(v.cumple)
    }

    // ------------------------------------------------------------------ instrucciones

    @Test
    fun `las instrucciones traen la peticion y el sesgo a favor de la pieza`() {
        val texto = CriticoVisual.instrucciones("un gancho para colgar de una puerta de 4 cm")
        assertTrue("gancho" in texto, "el crítico tiene que saber qué se pidió")
        assertTrue("CUMPLE" in texto, "y en qué formato responder")
        assertTrue(
            "duda" in texto.lowercase(),
            "el sesgo a favor de la pieza tiene que estar dicho: si no, protesta de más",
        )
    }

    // ------------------------------------------------------------------ las vistas

    @Test
    fun `un plan aplicable produce las cuatro vistas en png`() {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(
            """
            {"resumen":"Caja","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"c","nombre":"Caja",
               "parametros":{"anchura":40,"altura":20,"profundidad":30}}
            ]}
            """.trimIndent()
        )
        val plan = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")

        val png = assertNotNull(editor.vistasDelPlan(plan), "un plan que se aplica se puede dibujar")
        // La firma de un PNG, para no dar por buena una imagen que no lo es.
        val firma = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        assertTrue(png.size > firma.size && png.copyOfRange(0, 4).contentEquals(firma), "no es un PNG")
    }

    @Test
    fun `un plan que no se aplica no produce imagen`() {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(
            """
            {"resumen":"Nada","reemplazar":true,"operaciones":[
              {"op":"mover","objetivo":"no-existe","x":1,"y":0,"z":0}
            ]}
            """.trimIndent()
        )
        val plan = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
        assertNull(editor.vistasDelPlan(plan), "sin pieza no hay nada que enseñarle a nadie")
    }
}
