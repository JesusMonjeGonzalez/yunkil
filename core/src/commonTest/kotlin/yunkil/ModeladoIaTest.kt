package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.Vocabulario
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Que un modelo de lenguaje pueda modelar **bien**, y no solo modelar.
 *
 * La lección más cara del puente está anotada en PROXIMOS-PASOS y se repite aquí porque
 * gobierna todo este archivo: *una operación nombrada en una lista no se usa; una
 * operación vista aplicada sí*. Por eso no basta con que `filete` y `apoyar` existan:
 * tienen que estar en el vocabulario, tener alias para lo que los modelos escriben de
 * verdad, aparecer en el orden de trabajo, y aplicarse sin que el modelo tenga que
 * inventarse una coordenada ni un cuaternión.
 *
 * Las dos cosas que un modelo hace mal por naturaleza y que estas operaciones le quitan de
 * encima: **dar un punto en el espacio** (el canto) y **acertar el signo de un giro** (la
 * orientación de impresión).
 */
class ModeladoIaTest {

    private fun editorConPlan(plan: String): Editor {
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(plan.trimIndent())
        val interpretado = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
        val resultado = editor.aplicarPlan(interpretado, null)
        assertTrue(resultado.exito, "no se aplicó: ${resultado.resumen}")
        assertTrue(resultado.omitidas.isEmpty(), "se cayeron operaciones: ${resultado.omitidas}")
        return editor
    }

    // ------------------------------------------------------------------ filete

    @Test
    fun `el modelo puede redondear el canto entre dos piezas sin dar coordenadas`() {
        // Lo que el modelo escribe es «el encuentro de la base y el respaldo». El punto lo
        // mide Yunkil intersecando las envolventes: es exactamente el dato que un modelo de
        // lenguaje no puede producir bien.
        val editor = editorConPlan(
            """
            {"resumen":"Escuadra","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"base","nombre":"Base",
               "parametros":{"anchura":60,"altura":8,"profundidad":40}},
              {"op":"crear","tipo":"CAJA","alias":"respaldo","nombre":"Respaldo",
               "parametros":{"anchura":60,"altura":40,"profundidad":8}},
              {"op":"colocar","objetivo":"respaldo","referencia":"base","cara":"arriba"},
              {"op":"filete","objetivo":"respaldo","contra":"base","radio":3}
            ]}
            """,
        )
        assertTrue("aU" in editor.huellaTopologica, "el plan tenía que dejar un acuerdo local puesto")
    }

    @Test
    fun `filete sin contra redondea todos los encuentros`() {
        val editor = editorConPlan(
            """
            {"resumen":"Muñeco","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"ESFERA","alias":"cuerpo","parametros":{"radio":14}},
              {"op":"crear","tipo":"ESFERA","alias":"cabeza","parametros":{"radio":9}},
              {"op":"colocar","objetivo":"cabeza","referencia":"cuerpo","cara":"arriba"},
              {"op":"filete","objetivo":"cabeza","radio":6}
            ]}
            """,
        )
        // Sin «contra» es el acuerdo global: la unión de siempre con su fusión, no el nodo
        // de filete local.
        assertTrue("aU" !in editor.huellaTopologica, "sin «contra» no hay filete local")
        assertTrue("u" in editor.huellaTopologica)
    }

    @Test
    fun `los alias que escriben los modelos llegan a filete`() {
        for (escrito in listOf("fillet", "redondear", "chaflan", "roundEdge")) {
            val editor = Editor(Documento.vacio())
            val leido = editor.interpretarPlan(
                """
                {"resumen":"x","reemplazar":true,"operaciones":[
                  {"op":"crear","tipo":"CAJA","alias":"a"},
                  {"op":"crear","tipo":"CAJA","alias":"b"},
                  {"op":"colocar","objetivo":"b","referencia":"a","cara":"arriba"},
                  {"op":"$escrito","objetivo":"b","contra":"a","radio":2}
                ]}
                """.trimIndent(),
            )
            assertNotNull(leido.plan, "«$escrito» no se entendió: ${leido.motivoDelRechazo}")
        }
    }

    // ------------------------------------------------------------------ apoyar

    @Test
    fun `el modelo elige la cara que va contra el plato y no el giro`() {
        val editor = editorConPlan(
            """
            {"resumen":"Cuña","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"cuerpo","nombre":"Cuerpo",
               "parametros":{"anchura":60,"altura":20,"profundidad":30}},
              {"op":"apoyar","objetivo":"cuerpo","cara":"derecha"}
            ]}
            """,
        )
        val cuerpo = assertNotNull(editor.filas().lastOrNull { !it.esOperacion }).id
        val n = assertNotNull(editor.direccionEnElMundo(cuerpo, 1f, 0f, 0f))
        assertTrue(n[1] < -0.99f, "la cara derecha tenía que quedar mirando al plato: $n")
        assertTrue(abs(editor.cotaMinima[1]) < 0.01f, "y la pieza apoyada, no flotando")
    }

    @Test
    fun `apoyar ya no se confunde con asentar`() {
        // La tabla de alias mandaba «apoyar» a «asentar», que baja el modelo entero sin
        // girar nada y tira el objetivo por el camino. Un modelo que pedía apoyar una cara
        // obtenía un movimiento vertical y ninguna queja.
        val editor = Editor(Documento.vacio())
        val leido = assertNotNull(
            editor.interpretarPlan(
                """
                {"resumen":"x","reemplazar":true,"operaciones":[
                  {"op":"crear","tipo":"CAJA","alias":"a","parametros":{"altura":30}},
                  {"op":"apoyar","objetivo":"a","cara":"arriba"}
                ]}
                """.trimIndent(),
            ).plan,
        )
        assertTrue(
            leido.operaciones.last() is yunkil.ia.Apoyar,
            "«apoyar» tenía que quedarse en Apoyar y quedó en ${leido.operaciones.last()::class.simpleName}",
        )
    }

    @Test
    fun `los alias de orientacion llegan a apoyar`() {
        for (escrito in listOf("orient", "orientar", "layFlat", "tumbar")) {
            val editor = Editor(Documento.vacio())
            val leido = editor.interpretarPlan(
                """
                {"resumen":"x","reemplazar":true,"operaciones":[
                  {"op":"crear","tipo":"CAJA","alias":"a"},
                  {"op":"$escrito","objetivo":"a","cara":"detras"}
                ]}
                """.trimIndent(),
            )
            assertNotNull(leido.plan, "«$escrito» no se entendió: ${leido.motivoDelRechazo}")
        }
    }

    @Test
    fun `asentar sigue funcionando por sus propios nombres`() {
        for (escrito in listOf("asentar", "ground", "al_plato", "dropToPlate")) {
            val editor = Editor(Documento.vacio())
            val leido = editor.interpretarPlan(
                """
                {"resumen":"x","reemplazar":true,"operaciones":[
                  {"op":"crear","tipo":"CAJA","alias":"a"},
                  {"op":"$escrito"}
                ]}
                """.trimIndent(),
            )
            val plan = assertNotNull(leido.plan, "«$escrito» no se entendió: ${leido.motivoDelRechazo}")
            assertTrue(plan.operaciones.last() is yunkil.ia.Asentar, "«$escrito» tenía que ser Asentar")
        }
    }

    // ---------------------------------------------------- lo que el modelo lee

    @Test
    fun `el vocabulario declara las operaciones nuevas`() {
        assertTrue("filete" in Vocabulario.OPERACIONES)
        assertTrue("apoyar" in Vocabulario.OPERACIONES)
    }

    @Test
    fun `cada operacion del vocabulario aparece usada en las instrucciones`() {
        // La regla de esta casa: una operación nombrada en una lista no se usa; vista
        // aplicada sí. Si una operación existe y no hay ni un ejemplo suyo en el prompt, el
        // modelo pequeño no la va a tocar jamás.
        val instrucciones = Vocabulario.instrucciones()
        for (operacion in Vocabulario.OPERACIONES) {
            assertTrue(
                "\"op\":\"$operacion\"" in instrucciones,
                "«$operacion» está en el vocabulario pero no hay ni un ejemplo suyo en el prompt",
            )
        }
    }

    @Test
    fun `las instrucciones traen el orden de trabajo y los principios`() {
        val instrucciones = Vocabulario.instrucciones()
        assertTrue("EL ORDEN DE TRABAJO" in instrucciones, "falta el orden de trabajo")
        assertTrue("PRINCIPIOS DE MODELADO" in instrucciones, "faltan los principios de modelado")

        // El orden importa y tiene que estar dicho en el orden correcto: acotar antes de
        // taladrar, no después, o los M3 dejan de ser M3.
        val acotar = instrucciones.indexOf("5. ACOTAR")
        val taladrar = instrucciones.indexOf("6. TALADRAR")
        val acabar = instrucciones.indexOf("7. ACABAR")
        assertTrue(acotar in 1..<taladrar, "acotar tiene que ir antes de taladrar")
        assertTrue(taladrar < acabar, "el acabado va al final")
    }

    @Test
    fun `los principios nombran las cuatro cosas que arruinan una pieza impresa`() {
        val instrucciones = Vocabulario.instrucciones()
        assertTrue("UN SOLO CUERPO" in instrucciones, "las piezas flotando son el fallo número uno")
        assertTrue("voladizo" in instrucciones.lowercase(), "el voladizo tiene que estar dicho")
        assertTrue("filete" in instrucciones.lowercase(), "los cantos vivos también")
        assertTrue("SIMETRÍA Y REPETICIÓN" in instrucciones, "duplicar a mano es el fallo que más cuesta corregir")
    }

    @Test
    fun `el prompt sigue cabiendo en el contexto de un modelo local`() {
        // No es una prueba de estilo: con 16K de contexto y un modelo que razona antes de
        // escribir, cada carácter del mensaje de sistema es uno que no puede gastar en
        // pensar. Ya pasó una vez que el plan salía entero y correcto en el razonamiento y
        // se cortaba antes de emitir el JSON.
        val caracteres = Vocabulario.instrucciones().length
        assertTrue(caracteres < 14_000, "el mensaje de sistema mide $caracteres caracteres, demasiado")
    }

    @Test
    fun `la cadena entera sigue funcionando con el orden de trabajo completo`() {
        // Un plan que hace los siete pasos: forma, sitio, quitar, ahuecar, acotar,
        // taladrar y acabar. Es la prueba de que el orden que se le pide al modelo se puede
        // ejecutar de verdad y no es una recomendación de papel.
        val editor = editorConPlan(
            """
            {"resumen":"Caja con tapa atornillada","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"cuerpo","nombre":"Cuerpo",
               "parametros":{"anchura":60,"altura":30,"profundidad":40,"redondeo":2}},
              {"op":"pared","objetivo":"cuerpo","grosor":2.4},
              {"op":"acotar","objetivo":"modelo","eje":"X","medida":80},
              {"op":"taladro","objetivo":"seleccion","designacion":"M3","desplazamiento":[25,15]},
              {"op":"apoyar","objetivo":"seleccion","cara":"abajo"},
              {"op":"asentar"}
            ]}
            """,
        )
        assertNull(editor.ultimoError)
        assertTrue(abs(editor.cotaMaxima[0] - editor.cotaMinima[0] - 80f) < 0.5f, "acotar dejó el ancho en 80")
        assertTrue(abs(editor.cotaMinima[1]) < 0.01f, "y asentar dejó la pieza en el plato")
    }
}
