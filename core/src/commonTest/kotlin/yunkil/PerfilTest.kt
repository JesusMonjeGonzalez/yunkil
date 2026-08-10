package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.doc.FormaDePerfil
import yunkil.doc.Pieza
import yunkil.doc.TipoPieza
import yunkil.doc.compilar
import yunkil.fabricacion.PerfilFabricacion
import yunkil.kernel.Extrusion
import yunkil.kernel.Perfil2D
import yunkil.kernel.Punto2
import yunkil.kernel.Revolucion
import yunkil.kernel.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PerfilTest {

    @Test
    fun `un perfil que se cruza se rechaza antes de llegar al sdf`() {
        val editor = Editor(Documento.vacio())
        editor.anadir("EXTRUSION", null)
        val id = assertNotNull(editor.seleccionado)
        val antes = editor.aJson()

        assertTrue(!editor.fijarPuntosDelPerfil(id, listOf(0f, 0f, 20f, 20f, 0f, 20f, 20f, 0f)))
        assertTrue(editor.ultimoError?.contains("cruza") == true)
        assertEquals(antes, editor.aJson())
    }

    @Test
    fun `el fantasma usa exactamente el perfil con el que se aplicara`() {
        val fuente = Editor(Documento.vacio())
        val plan = assertNotNull(
            fuente.interpretarPlan(
                """
                {"reemplazar":true,"operaciones":[
                  {"op":"crear","tipo":"CAJA","alias":"caja","parametros":{"anchura":30,"altura":20,"profundidad":25}},
                  {"op":"pared","objetivo":"caja"}
                ]}
                """.trimIndent(),
            ).plan,
        )
        val perfil = PerfilFabricacion.VERIFICADOS.first { it.material == "PETG" }

        assertTrue(fuente.previsualizar(plan, null, perfil.nombre))
        val aplicado = Editor(Documento.vacio())
        assertTrue(aplicado.aplicarPlan(plan, perfil.nombre).exito)

        val uniformsAplicados = aplicado.uniforms()
        assertEquals(
            uniformsAplicados,
            fuente.uniforms().takeLast(uniformsAplicados.size),
            "el fantasma no representa la geometría que dejará el perfil activo",
        )
    }

    // ------------------------------------------------------------------ 2D

    @Test
    fun `un rectangulo mide lo que se le pide`() {
        val perfil = Perfil2D.rectangulo(40f, 30f, redondeo = 0f)
        val (lo, hi) = perfil.cotas()
        assertTrue(abs(hi.x - lo.x - 40f) < 0.01f, "ancho ${hi.x - lo.x}")
        assertTrue(abs(hi.y - lo.y - 30f) < 0.01f, "alto ${hi.y - lo.y}")
        assertTrue(abs(perfil.area() - 1200f) < 1f, "área ${perfil.area()}")
    }

    @Test
    fun `el redondeo no cambia la medida exterior`() {
        // Es la trampa clásica: redondear un rectángulo con un radio del campo lo
        // agranda salvo que se encoja el contorno la misma cantidad.
        val perfil = Perfil2D.rectangulo(40f, 30f, redondeo = 5f)
        val (lo, hi) = perfil.cotas()
        assertTrue(abs(hi.x - lo.x - 40f) < 0.01f, "ancho con redondeo ${hi.x - lo.x}")
        assertTrue(abs(hi.y - lo.y - 30f) < 0.01f, "alto con redondeo ${hi.y - lo.y}")
    }

    @Test
    fun `la distancia con signo es negativa dentro y positiva fuera`() {
        val perfil = Perfil2D.rectangulo(20f, 20f)
        assertTrue(perfil.evaluar(Punto2(0f, 0f)) < 0f, "el centro está dentro")
        assertTrue(perfil.evaluar(Punto2(30f, 0f)) > 0f, "fuera por la derecha")
        assertTrue(abs(perfil.evaluar(Punto2(10f, 0f))) < 0.01f, "el borde vale cero")
        assertTrue(abs(perfil.evaluar(Punto2(15f, 0f)) - 5f) < 0.01f, "a 5 mm del borde")
    }

    @Test
    fun `el signo no depende del sentido de giro del contorno`() {
        // El mismo cuadrado escrito al derecho y al revés debe dar lo mismo: contar
        // cruces en vez de mirar el sentido es lo que lo garantiza.
        val horario = Perfil2D.poligono(
            listOf(Punto2(-10f, -10f), Punto2(-10f, 10f), Punto2(10f, 10f), Punto2(10f, -10f)),
        )
        val antihorario = Perfil2D.poligono(
            listOf(Punto2(-10f, -10f), Punto2(10f, -10f), Punto2(10f, 10f), Punto2(-10f, 10f)),
        )
        assertTrue(horario.evaluar(Punto2.CERO) < 0f)
        assertTrue(antihorario.evaluar(Punto2.CERO) < 0f)
        assertTrue(abs(horario.evaluar(Punto2(3f, 4f)) - antihorario.evaluar(Punto2(3f, 4f))) < 1e-4f)
    }

    @Test
    fun `un circulo teselado se acerca al area exacta`() {
        val perfil = Perfil2D.circulo(10f)
        val exacta = (PI * 100).toFloat()
        // Con el radio compensado el error de área queda repartido a los dos lados
        // en vez de acumularse siempre por defecto.
        assertTrue(abs(perfil.area() - exacta) / exacta < 0.001f, "área ${perfil.area()} frente a $exacta")

        // Y la separación real respecto al círculo se mantiene bajo la tolerancia
        // por los dos lados, que es lo que de verdad importa para una pieza.
        var maximaSeparacion = 0f
        val puntos = perfil.poligono
        for (i in puntos.indices) {
            val a = puntos[i]
            val b = puntos[(i + 1) % puntos.size]
            maximaSeparacion = maxOf(
                maximaSeparacion,
                abs(a.longitud() - 10f),
                abs(((a + b) * 0.5f).longitud() - 10f),
            )
        }
        assertTrue(maximaSeparacion <= Perfil2D.TOLERANCIA * 1.1f, "separación $maximaSeparacion")
    }

    @Test
    fun `la desviacion del teselado se mide y es despreciable frente a la boquilla`() {
        val ranura = Perfil2D.ranura(30f, 8f)
        assertTrue(ranura.desviacionMaxima <= Perfil2D.TOLERANCIA + 1e-5f, "desviación ${ranura.desviacionMaxima}")
        assertTrue(ranura.desviacionMaxima < 0.4f / 20f, "debería estar muy por debajo de la boquilla")
    }

    @Test
    fun `un contorno nunca supera el tope de vertices del shader`() {
        // El shader desenrolla el polígono: pasarse del tope colgaría al compilador
        // de Metal, así que el tope tiene que sostenerse aunque se pidan curvas finas.
        val estrella = Perfil2D.estrella(40, 100f, 40f)
        assertTrue(estrella.poligono.size <= Perfil2D.MAXIMO_DE_VERTICES)
        val circulo = Perfil2D.circulo(500f, lados = 4000)
        assertTrue(circulo.poligono.size <= Perfil2D.MAXIMO_DE_VERTICES)
    }

    @Test
    fun `la ranura tiene los extremos redondeados`() {
        val ranura = Perfil2D.ranura(30f, 8f)
        assertTrue(ranura.evaluar(Punto2(0f, 0f)) < 0f, "el centro es material")
        assertTrue(ranura.evaluar(Punto2(14f, 0f)) < 0f, "la punta también")
        // La esquina del rectángulo circunscrito queda fuera porque el extremo es
        // un semicírculo, no una esquina viva.
        assertTrue(ranura.evaluar(Punto2(14.5f, 3.5f)) > 0f, "la esquina queda fuera")
    }

    // ------------------------------------------------------------------ 3D

    @Test
    fun `una extrusion es el perfil levantado`() {
        val solido = Extrusion(Perfil2D.rectangulo(20f, 10f), altura = 6f)
        assertTrue(solido.evaluar(Vec3(0f, 0f, 0f)) < 0f, "el centro es material")
        assertTrue(solido.evaluar(Vec3(0f, 4f, 0f)) > 0f, "por encima de la tapa")
        assertTrue(solido.evaluar(Vec3(11f, 0f, 0f)) > 0f, "fuera del contorno")

        val c = solido.cotas()
        assertTrue(abs(c.size.x - 20f) < 0.02f, "ancho ${c.size.x}")
        assertTrue(abs(c.size.y - 6f) < 0.02f, "altura ${c.size.y}")
        assertTrue(abs(c.size.z - 10f) < 0.02f, "fondo ${c.size.z}")
    }

    @Test
    fun `una revolucion de un rectangulo separado del eje es un anillo`() {
        val anillo = Revolucion(Perfil2D.rectangulo(4f, 4f), desplazamiento = 20f)
        assertTrue(anillo.evaluar(Vec3(20f, 0f, 0f)) < 0f, "el radio medio es material")
        assertTrue(anillo.evaluar(Vec3(0f, 0f, 0f)) > 0f, "el centro está hueco")
        assertTrue(anillo.evaluar(Vec3(0f, 0f, 20f)) < 0f, "y también por el otro lado")
        val c = anillo.cotas()
        assertTrue(abs(c.size.x - 44f) < 0.1f, "diámetro exterior ${c.size.x}")
    }

    @Test
    fun `el volumen de una extrusion coincide con area por altura`() {
        // Comprobación independiente: el campo se malla y se mide, y tiene que dar
        // lo mismo que la geometría analítica del contorno.
        val perfil = Perfil2D.rectangulo(30f, 20f)
        val solido = Extrusion(perfil, altura = 5f)
        val malla = yunkil.malla.ContorneadoDual(solido, 0.4f).generar()
        val esperado = perfil.area() * 5f
        val medido = abs(malla.volumen())
        assertTrue(abs(medido - esperado) / esperado < 0.02f, "volumen $medido frente a $esperado")
    }

    // ------------------------------------------------------------------ documento

    @Test
    fun `una pieza de extrusion se compila y responde a sus cotas`() {
        val editor = Editor(Documento.vacio())
        assertTrue(editor.anadir("EXTRUSION", null) || editor.ultimoError == null)
        val id = assertNotNull(editor.seleccionado)

        assertTrue(editor.tienePerfil(id))
        assertEquals(FormaDePerfil.RECTANGULO.name, editor.formaDe(id))
        assertTrue(editor.contornoDe(id).isNotEmpty())

        editor.fijarParametro(id, "anchoPerfil", 50f)
        editor.fijarParametro(id, "altoPerfil", 25f)
        editor.fijarParametro(id, "altura", 8f)
        assertTrue(abs(editor.cotaMaxima[0] - 25f) < 0.1f, "medio ancho ${editor.cotaMaxima[0]}")
        assertTrue(abs(editor.cotaMaxima[1] - 4f) < 0.1f, "media altura ${editor.cotaMaxima[1]}")
    }

    @Test
    fun `cambiar de forma conserva las cotas que compartan nombre`() {
        val editor = Editor(Documento.vacio())
        editor.anadir("EXTRUSION", null)
        val id = assertNotNull(editor.seleccionado)
        editor.fijarParametro(id, "anchoPerfil", 12f)

        assertTrue(editor.fijarForma(id, "RANURA") || editor.ultimoError == null)
        assertEquals(FormaDePerfil.RANURA.name, editor.formaDe(id))
        val ancho = editor.parametrosDe(id).firstOrNull { it.clave == "anchoPerfil" }
        assertNotNull(ancho)
        assertEquals(12f, ancho.valor, "el ancho ya acotado debería sobrevivir al cambio de forma")
    }

    @Test
    fun `un contorno libre entra por la IA y produce solido`() {
        val editor = Editor(Documento.vacio())
        val plan = editor.interpretarPlan(
            """
            {"reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"EXTRUSION","alias":"escuadra","parametros":{"altura":5}},
              {"op":"perfil","objetivo":"escuadra","forma":"LIBRE",
               "puntos":[[0,0],[40,0],[40,6],[6,6],[6,40],[0,40]]},
              {"op":"asentar"}
            ]}
            """.trimIndent(),
        )
        assertTrue(plan.aceptado, plan.motivoDelRechazo ?: "")
        val resultado = editor.aplicarPlan(plan.plan!!)
        assertTrue(resultado.exito, resultado.resumen + " " + resultado.omitidas)

        val id = assertNotNull(resultado.alias["escuadra"])
        assertEquals(FormaDePerfil.LIBRE.name, editor.formaDe(id))
        assertTrue(abs(editor.cotaMaxima[0] - 40f) < 0.2f, "ancho ${editor.cotaMaxima[0]}")
        assertTrue(abs(editor.cotaMaxima[1] - 5f) < 0.2f, "altura ${editor.cotaMaxima[1]}")
    }

    @Test
    fun `un contorno degenerado se rechaza sin tocar el documento`() {
        val editor = Editor(Documento.vacio())
        editor.anadir("EXTRUSION", null)
        val id = assertNotNull(editor.seleccionado)
        // Tres puntos alineados no encierran nada.
        assertTrue(!editor.fijarPuntosDelPerfil(id, listOf(0f, 0f, 10f, 0f, 20f, 0f)))
        assertNotNull(editor.ultimoError)
        assertTrue(!editor.fijarPuntosDelPerfil(id, listOf(0f, 0f, 10f, 0f)))
        assertNotNull(editor.ultimoError)
    }

    @Test
    fun `el shader del perfil declara los vertices como uniforms`() {
        val editor = Editor(Documento.vacio())
        editor.anadir("EXTRUSION", null)
        val id = assertNotNull(editor.seleccionado)
        val fuente = editor.fuenteMsl
        assertTrue("yk_perfil" in fuente, "el preludio debería traer la función de perfil")
        assertTrue("YK_MAX_VERTICES" in fuente, "el bucle debe llevar tope constante")

        // Mover una cota no puede cambiar la topología: es lo que permite arrastrar
        // un deslizador sin recompilar el shader.
        val huella = editor.huellaTopologica
        editor.fijarParametro(id, "anchoPerfil", 55f)
        assertEquals(huella, editor.huellaTopologica)

        // Cambiar de forma sí cambia el número de vértices, y por tanto el shader.
        editor.fijarForma(id, "CIRCULO")
        assertTrue(editor.huellaTopologica != huella, "cambiar de forma debería recompilar")
    }

    @Test
    fun `los uniforms del perfil coinciden con lo que espera el shader`() {
        // Si el empaquetado y el generador se desalinean, el sólido sale deformado
        // en la GPU y correcto en la CPU. Contarlos es la defensa barata.
        val editor = Editor(Documento.vacio())
        editor.anadir("EXTRUSION", null)
        assertEquals(editor.numeroDeUniforms, editor.uniforms().size)

        editor.anadir("REVOLUCION", null)
        assertEquals(editor.numeroDeUniforms, editor.uniforms().size)
    }

    @Test
    fun `el analizador entiende una extrusion como cualquier otra pieza`() {
        val editor = Editor(Documento.vacio())
        editor.anadir("EXTRUSION", null)
        val id = assertNotNull(editor.seleccionado)
        editor.fijarParametro(id, "altura", 3f)
        editor.fijarParametro(id, "anchoPerfil", 60f)
        editor.fijarParametro(id, "altoPerfil", 40f)
        editor.asentarEnPlato()

        val informe = assertNotNull(editor.analizarFabricacion())
        assertTrue(informe.metricas.areaDeContacto > 1500f, "base ${informe.metricas.areaDeContacto}")
        assertTrue(informe.aptoParaImprimir, informe.hallazgos.map { it.titulo }.toString())
    }
}
