package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.ia.Crear
import yunkil.ia.Interprete
import yunkil.ia.Vocabulario
import yunkil.ia.cotasEnMundoDe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pruebas del puente entre un modelo de lenguaje y el motor.
 *
 * Las respuestas de ejemplo están escritas *mal a propósito*, con las erratas que
 * cometen los modelos de verdad: vallas de markdown, claves en inglés, medidas con
 * unidades pegadas y sinónimos. Un puente que solo funciona con JSON perfecto no
 * funciona.
 */
class IaTest {

    private fun editorConModelo(): Editor = Editor(Documento.vacio())

    // ------------------------------------------------------------------ lectura

    @Test
    fun `lee un plan envuelto en markdown y con claves en ingles`() {
        val respuesta = """
            Claro, aquí tienes el plan:
            ```json
            {"summary":"Una base","replace":true,"actions":[
              {"kind":"add","type":"BOX","alias":"base","name":"Base",
               "parameters":{"width":"60 mm","height":8,"depth":35}}
            ]}
            ```
            Espero que te sirva.
        """.trimIndent()

        val plan = assertNotNull(
            (Interprete.interpretar(respuesta) as? yunkil.ia.ResultadoDeInterpretacion.Aceptado)?.plan,
        )
        assertTrue(plan.reemplazar)
        assertEquals("Una base", plan.resumen)
        val crear = assertNotNull(plan.operaciones.single() as? Crear)
        assertEquals("CAJA", crear.tipo)
        assertEquals(60f, crear.parametros["anchura"])
        assertEquals(8f, crear.parametros["altura"])
        assertEquals(35f, crear.parametros["profundidad"])
    }

    @Test
    fun `entiende numeros escritos como los escribe una persona`() {
        assertEquals(12f, Interprete.comoNumero(kotlinx.serialization.json.JsonPrimitive("12 mm")))
        assertEquals(12.5f, Interprete.comoNumero(kotlinx.serialization.json.JsonPrimitive("12,5")))
        assertEquals(45f, Interprete.comoNumero(kotlinx.serialization.json.JsonPrimitive("45°")))
        assertEquals(3f, Interprete.comoNumero(kotlinx.serialization.json.JsonPrimitive(3)))
        assertEquals(null, Interprete.comoNumero(kotlinx.serialization.json.JsonPrimitive("bastante")))
    }

    @Test
    fun `recorta el JSON aunque el nombre de una pieza tenga llaves`() {
        val texto = """ruido {"resumen":"pieza {rara}","operaciones":[]} más ruido"""
        assertEquals("""{"resumen":"pieza {rara}","operaciones":[]}""", Interprete.extraerJson(texto))
    }

    @Test
    fun `un op inventado se rechaza diciendo cuales existen`() {
        val respuesta = """{"operaciones":[{"op":"extruir","objetivo":"raiz"}]}"""
        val rechazo = Interprete.interpretar(respuesta) as? yunkil.ia.ResultadoDeInterpretacion.Rechazado
        assertNotNull(rechazo)
        assertTrue("crear" in rechazo.motivo, "el motivo debería listar las operaciones: ${rechazo.motivo}")
    }

    @Test
    fun `un numero no finito se rechaza antes de tocar el documento`() {
        val respuesta = """{"operaciones":[{"op":"crear","tipo":"CAJA","escala":0}]}"""
        val rechazo = Interprete.interpretar(respuesta) as? yunkil.ia.ResultadoDeInterpretacion.Rechazado
        assertNotNull(rechazo)
        assertTrue("positiva" in rechazo.motivo, rechazo.motivo)
    }

    @Test
    fun `un alias repetido se rechaza por ambiguo`() {
        val respuesta = """
            {"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"a"},
              {"op":"crear","tipo":"ESFERA","alias":"a"}
            ]}
        """.trimIndent()
        val rechazo = Interprete.interpretar(respuesta) as? yunkil.ia.ResultadoDeInterpretacion.Rechazado
        assertNotNull(rechazo)
        assertTrue("alias" in rechazo.motivo, rechazo.motivo)
    }

    @Test
    fun `un plan desmesurado se rechaza con su cuenta`() {
        val operaciones = (1..80).joinToString(",") { """{"op":"crear","tipo":"CAJA"}""" }
        val rechazo = Interprete.interpretar("""{"operaciones":[$operaciones]}""")
            as? yunkil.ia.ResultadoDeInterpretacion.Rechazado
        assertNotNull(rechazo)
        assertTrue("80" in rechazo.motivo, rechazo.motivo)
    }

    // ------------------------------------------------------------------ modo edición

    @Test
    fun `una orden de edicion acepta hasta tres operaciones`() {
        val plan = """{"operaciones":[
            {"op":"filete","objetivo":"#base-1","radio":1},
            {"op":"filete","objetivo":"#base-1","radio":2},
            {"op":"asentar"}
        ]}"""
        val leido = Interprete.interpretar(plan, edicion = true)
        assertTrue(leido is yunkil.ia.ResultadoDeInterpretacion.Aceptado, "$leido")
    }

    @Test
    fun `una orden de edicion con mas de tres operaciones se rechaza`() {
        val plan = """{"operaciones":[
            {"op":"filete","objetivo":"#base-1","radio":1},
            {"op":"filete","objetivo":"#base-1","radio":2},
            {"op":"mover","objetivo":"#base-1","x":1},
            {"op":"asentar"}
        ]}"""
        val rechazo = Interprete.interpretar(plan, edicion = true)
            as? yunkil.ia.ResultadoDeInterpretacion.Rechazado
        assertNotNull(rechazo)
        assertTrue("3" in rechazo.motivo, rechazo.motivo)
    }

    @Test
    fun `una orden de edicion no puede crear piezas nuevas`() {
        val plan = """{"operaciones":[
            {"op":"crear","tipo":"CAJA","alias":"nueva"},
            {"op":"filete","objetivo":"nueva","radio":1}
        ]}"""
        val rechazo = Interprete.interpretar(plan, edicion = true)
            as? yunkil.ia.ResultadoDeInterpretacion.Rechazado
        assertNotNull(rechazo)
        assertTrue("edición" in rechazo.motivo, rechazo.motivo)
    }

    @Test
    fun `la edicion no limita la generacion cuando el modo es de creacion`() {
        val plan = """{"operaciones":[
            {"op":"crear","tipo":"CAJA","alias":"a"},
            {"op":"crear","tipo":"CAJA","alias":"b"},
            {"op":"crear","tipo":"CAJA","alias":"c"},
            {"op":"crear","tipo":"CAJA","alias":"d"},
            {"op":"colocar","objetivo":"b","referencia":"a","cara":"arriba"}
        ]}"""
        val leido = Interprete.interpretar(plan, edicion = false)
        assertTrue(leido is yunkil.ia.ResultadoDeInterpretacion.Aceptado, "$leido")
    }

    @Test
    fun `el contexto describe la seleccion con sus cotas y su instruccion`() {
        val editor = Editor(Documento.vacio())
        assertTrue(
            editor.aplicarPlan(
                yunkil.ia.PlanDeModelado(
                    operaciones = listOf(
                        yunkil.ia.Crear(
                            tipo = "CAJA", alias = "base",
                            parametros = mapOf("anchura" to 60f, "altura" to 8f, "profundidad" to 35f),
                        ),
                    ),
                ),
                null,
            ).exito,
        )
        val contexto = editor.contextoParaModelo()
        val seleccionada = editor.seleccionado ?: ""
        assertTrue("#$seleccionada" in contexto, "no nombra la selección")
        assertTrue("x[" in contexto, "no da las cotas de la selección: $contexto")
        assertTrue("3 ops" in contexto, "no dice el tope de edición")
    }

    // ------------------------------------------------------------------ colocación

    @Test
    fun `colocar apila una pieza justo encima de otra`() {
        val editor = editorConModelo()
        val respuesta = """
            {"resumen":"Caja con tapa","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"cuerpo","nombre":"Cuerpo",
               "parametros":{"anchura":60,"altura":20,"profundidad":40}},
              {"op":"crear","tipo":"CAJA","alias":"tapa","nombre":"Tapa",
               "parametros":{"anchura":60,"altura":4,"profundidad":40}},
              {"op":"colocar","objetivo":"tapa","referencia":"cuerpo","cara":"arriba"},
              {"op":"asentar"}
            ]}
        """.trimIndent()

        val leido = editor.interpretarPlan(respuesta)
        assertTrue(leido.aceptado, leido.motivoDelRechazo ?: "")
        val resultado = editor.aplicarPlan(leido.plan!!)
        assertTrue(resultado.exito, resultado.resumen + " " + resultado.omitidas)

        // El conjunto mide 24 mm de alto y descansa en el plato.
        assertTrue(abs(editor.cotaMinima[1]) < 0.01f, "no está asentado: ${editor.cotaMinima[1]}")
        assertTrue(abs(editor.cotaMaxima[1] - 24f) < 0.01f, "altura total ${editor.cotaMaxima[1]}")
    }

    @Test
    fun `colocar respeta la holgura pedida`() {
        val editor = editorConModelo()
        val plan = editor.interpretarPlan(
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"a","parametros":{"anchura":20,"altura":10,"profundidad":20}},
              {"op":"crear","tipo":"CAJA","alias":"b","parametros":{"anchura":20,"altura":10,"profundidad":20}},
              {"op":"colocar","objetivo":"b","referencia":"a","cara":"arriba","holgura":5},
              {"op":"asentar"}
            ]}
            """.trimIndent(),
        )
        assertTrue(editor.aplicarPlan(plan.plan!!).exito)
        // 10 + 5 de hueco + 10 = 25 de altura total.
        assertTrue(abs(editor.cotaMaxima[1] - 25f) < 0.01f, "altura ${editor.cotaMaxima[1]}")
    }

    @Test
    fun `colocar funciona en las cuatro caras laterales`() {
        // La base ocupa 40 mm y la pieza colocada 20: pegada por la derecha, el
        // conjunto llega a 20 + 20 = 40 mm por ese lado.
        data class Caso(val cara: String, val eje: Int, val extremoMaximo: Boolean, val esperado: Float)
        for (caso in listOf(
            Caso("derecha", 0, true, 40f),
            Caso("izquierda", 0, false, -40f),
            Caso("delante", 2, true, 40f),
            Caso("detras", 2, false, -40f),
        )) {
            val editor = editorConModelo()
            val plan = editor.interpretarPlan(
                """
                {"reemplazar":true,"operaciones":[
                  {"op":"crear","tipo":"CAJA","alias":"a","parametros":{"anchura":40,"altura":10,"profundidad":40}},
                  {"op":"crear","tipo":"CAJA","alias":"b","parametros":{"anchura":20,"altura":10,"profundidad":20}},
                  {"op":"colocar","objetivo":"b","referencia":"a","cara":"${caso.cara}"}
                ]}
                """.trimIndent(),
            )
            assertTrue(editor.aplicarPlan(plan.plan!!).exito, "falló con cara ${caso.cara}")
            val extremo = if (caso.extremoMaximo) editor.cotaMaxima else editor.cotaMinima
            val medido = extremo[caso.eje].toFloat()
            assertTrue(
                abs(medido - caso.esperado) < 0.01f,
                "cara ${caso.cara}: medí $medido y esperaba ${caso.esperado}",
            )
        }
    }

    @Test
    fun `colocar sobre una pieza girada usa sus cotas reales`() {
        val editor = editorConModelo()
        val plan = editor.interpretarPlan(
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"base","parametros":{"anchura":40,"altura":10,"profundidad":20}},
              {"op":"girar","objetivo":"base","x":90},
              {"op":"crear","tipo":"CAJA","alias":"encima","parametros":{"anchura":10,"altura":10,"profundidad":10}},
              {"op":"colocar","objetivo":"encima","referencia":"base","cara":"arriba"}
            ]}
            """.trimIndent(),
        )
        assertTrue(editor.aplicarPlan(plan.plan!!).exito)
        // Al girar 90° en X, los 20 mm de fondo pasan a ser la altura: la base llega
        // a +10 y el cubo de encima ocupa de 10 a 20.
        assertTrue(abs(editor.cotaMaxima[1] - 20f) < 0.01f, "cima en ${editor.cotaMaxima[1]}")
    }

    @Test
    fun `colocar una pieza respecto a su propio hijo se rechaza`() {
        val editor = editorConModelo()
        val plan = editor.interpretarPlan(
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"UNION","alias":"grupo"},
              {"op":"crear","tipo":"CAJA","alias":"dentro","padre":"grupo"},
              {"op":"colocar","objetivo":"grupo","referencia":"dentro","cara":"arriba"}
            ]}
            """.trimIndent(),
        )
        val resultado = editor.aplicarPlan(plan.plan!!)
        // Las dos creaciones valen; la colocación imposible se omite con motivo.
        assertEquals(2, resultado.aplicadas)
        assertTrue(resultado.omitidas.any { "contiene" in it }, resultado.omitidas.toString())
    }

    @Test
    fun `alinear centra una pieza con otra en un eje`() {
        val editor = editorConModelo()
        val plan = editor.interpretarPlan(
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"a","parametros":{"anchura":40,"altura":10,"profundidad":10}},
              {"op":"crear","tipo":"CAJA","alias":"b","parametros":{"anchura":10,"altura":10,"profundidad":10}},
              {"op":"mover","objetivo":"b","x":100,"absoluto":true},
              {"op":"alinear","objetivo":"b","referencia":"a","eje":"X","modo":"maximo"}
            ]}
            """.trimIndent(),
        )
        assertTrue(editor.aplicarPlan(plan.plan!!).exito)
        assertTrue(abs(editor.cotaMaxima[0] - 20f) < 0.01f, "borde derecho en ${editor.cotaMaxima[0]}")
    }

    // ------------------------------------------------------------------ transacción

    @Test
    fun `un plan entero es un unico punto de deshacer`() {
        val editor = editorConModelo()
        val plan = editor.interpretarPlan(
            """
            {"reemplazar":false,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"a"},
              {"op":"crear","tipo":"ESFERA","alias":"b"},
              {"op":"crear","tipo":"CILINDRO","alias":"c"},
              {"op":"colocar","objetivo":"b","referencia":"a","cara":"arriba"}
            ]}
            """.trimIndent(),
        )
        assertTrue(editor.aplicarPlan(plan.plan!!).exito)
        assertEquals(4, editor.filas().size, "raíz más tres piezas")

        assertTrue(editor.deshacer())
        assertEquals(1, editor.filas().size, "debería quedar solo la raíz")
        assertTrue(!editor.puedeDeshacer, "un plan no puede dejar varios puntos de deshacer")

        assertTrue(editor.rehacer())
        assertEquals(4, editor.filas().size)
    }

    @Test
    fun `un plan que no puede aplicar nada deja el documento intacto`() {
        val editor = editorConModelo()
        val antes = editor.aJson()
        val plan = editor.interpretarPlan(
            """
            {"operaciones":[
              {"op":"fijar","objetivo":"pieza-que-no-existe","clave":"radio","valor":5},
              {"op":"eliminar","objetivo":"tampoco-existe"}
            ]}
            """.trimIndent(),
        )
        val resultado = editor.aplicarPlan(plan.plan!!)
        assertTrue(!resultado.exito)
        assertEquals(antes, editor.aJson(), "el documento cambió pese a fallar el plan")
        assertTrue(!editor.puedeDeshacer, "un plan fallido no debe dejar punto de deshacer")
    }

    @Test
    fun `un parametro inventado no tira abajo el resto del plan`() {
        val editor = editorConModelo()
        val plan = editor.interpretarPlan(
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"ESFERA","alias":"bola","parametros":{"radio":10,"chirimbolo":3}}
            ]}
            """.trimIndent(),
        )
        val resultado = editor.aplicarPlan(plan.plan!!)
        assertTrue(resultado.exito, resultado.resumen)
        assertTrue(
            resultado.omitidas.any { "chirimbolo" in it },
            "debería anotar el parámetro desconocido: ${resultado.omitidas}",
        )
    }

    @Test
    fun `reemplazar destruye el documento y no reemplazar lo conserva`() {
        val editor = editorConModelo()
        editor.aplicarPlan(editor.interpretarPlan("""{"reemplazar":true,"operaciones":[{"op":"crear","tipo":"CAJA"}]}""").plan!!)
        assertEquals(2, editor.filas().size)

        editor.aplicarPlan(editor.interpretarPlan("""{"reemplazar":false,"operaciones":[{"op":"crear","tipo":"ESFERA","padre":"raiz"}]}""").plan!!)
        assertEquals(3, editor.filas().size, "añadir no debería borrar lo anterior")

        editor.aplicarPlan(editor.interpretarPlan("""{"reemplazar":true,"operaciones":[{"op":"crear","tipo":"TORO"}]}""").plan!!)
        assertEquals(2, editor.filas().size, "reemplazar debería dejar solo lo nuevo")
    }

    // ------------------------------------------------------------------ contexto

    @Test
    fun `el contexto describe cada pieza con sus cotas en el mundo`() {
        val editor = editorConModelo()
        editor.aplicarPlan(
            editor.interpretarPlan(
                """
                {"reemplazar":true,"operaciones":[
                  {"op":"crear","tipo":"CAJA","alias":"base","nombre":"Base",
                   "parametros":{"anchura":60,"altura":8,"profundidad":35}},
                  {"op":"asentar"}
                ]}
                """.trimIndent(),
            ).plan!!,
        )
        val contexto = editor.contextoParaModelo()
        assertTrue("Base" in contexto, contexto)
        assertTrue("CAJA" in contexto, contexto)
        assertTrue("anchura=60" in contexto, contexto)
        assertTrue("x[-30..30]" in contexto, contexto)
        assertTrue("apoyado en el plato" in contexto, contexto)
    }

    @Test
    fun `el contexto de un documento vacio lo dice claramente`() {
        assertTrue("vacío" in Editor(Documento.vacio()).contextoParaModelo())
    }

    @Test
    fun `las instrucciones se generan desde el catalogo real del kernel`() {
        val texto = Vocabulario.instrucciones()
        for (tipo in yunkil.doc.TipoPieza.entries) {
            assertTrue(tipo.name in texto, "el catálogo no menciona ${tipo.name}")
        }
        for (op in Vocabulario.OPERACIONES) {
            assertTrue("\"$op\"" in texto || "\"op\":\"$op\"" in texto, "falta la operación $op")
        }
        // Un parámetro que existe en el kernel tiene que estar ofrecido.
        assertTrue("radioSuperior" in texto)
    }

    @Test
    fun `las cotas en el mundo tienen en cuenta a los padres`() {
        val editor = editorConModelo()
        val plan = editor.interpretarPlan(
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"UNION","alias":"grupo"},
              {"op":"mover","objetivo":"grupo","x":100,"absoluto":true},
              {"op":"crear","tipo":"ESFERA","alias":"bola","padre":"grupo","parametros":{"radio":5}}
            ]}
            """.trimIndent(),
        )
        val resultado = editor.aplicarPlan(plan.plan!!)
        assertTrue(resultado.exito, resultado.resumen)
        val idDeLaBola = assertNotNull(resultado.alias["bola"])
        val cotas = assertNotNull(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString(Documento.serializer(), editor.aJson())
                .cotasEnMundoDe(idDeLaBola),
        )
        assertTrue(abs(cotas.center.x - 100f) < 0.01f, "centro en ${cotas.center.x}, esperaba 100")
    }
}
