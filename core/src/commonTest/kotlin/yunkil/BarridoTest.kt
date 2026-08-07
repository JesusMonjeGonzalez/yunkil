package yunkil

import yunkil.doc.Documento
import yunkil.doc.Editor
import yunkil.kernel.Barrido
import yunkil.kernel.Perfil2D
import yunkil.kernel.Punto2
import yunkil.kernel.Vec3
import yunkil.malla.ContorneadoDual
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `BARRIDO`: una sección circular recorriendo un camino.
 *
 * Es la familia que no se podía hacer de ninguna manera —tubos doblados, marcos,
 * aros, asas, canaletas— y la que peor se aproximaba encadenando cilindros a mano:
 * salían codos con canto vivo y coordenadas calculadas a ojo.
 */
class BarridoTest {

    private fun camino(vararg puntos: Pair<Float, Float>, cerrado: Boolean, radio: Float) =
        Barrido(
            perfil = Perfil2D.poligono(puntos.map { Punto2(it.first, it.second) }),
            radio = radio,
            cerrado = cerrado,
        )

    @Test
    fun `un tramo recto da un tubo de seccion circular exacta`() {
        // Camino de (0,0) a (40,0) en XZ, sección de 3 mm.
        val tubo = camino(0f to 0f, 40f to 0f, cerrado = false, radio = 3f)

        // En el eje, a media distancia: dentro justo el radio.
        assertTrue(abs(tubo.evaluar(Vec3(20f, 0f, 0f)) + 3f) < 1e-4f, "el eje debería medir −3")

        // La sección es un círculo, no un cuadrado: da igual por dónde se salga.
        for (p in listOf(Vec3(20f, 3f, 0f), Vec3(20f, 0f, 3f), Vec3(20f, -3f, 0f))) {
            assertTrue(abs(tubo.evaluar(p)) < 1e-4f, "la superficie en $p mide ${tubo.evaluar(p)}")
        }

        // Y a 45°, que es donde una sección cuadrada se delataría.
        val diagonal = 3f / kotlin.math.sqrt(2f)
        val d = tubo.evaluar(Vec3(20f, diagonal, diagonal))
        assertTrue(abs(d) < 1e-4f, "la diagonal de la sección mide $d, debería ser 0")
    }

    @Test
    fun `un camino cerrado deja el hueco del marco`() {
        // Cuadrado de 40 de lado con un tubo de 2: el centro tiene que quedar al aire,
        // que es lo que distingue un marco de una placa.
        val marco = camino(
            0f to 0f, 40f to 0f, 40f to 40f, 0f to 40f,
            cerrado = true, radio = 2f,
        )

        assertTrue(marco.evaluar(Vec3(20f, 0f, 20f)) > 0f, "el centro del marco debería estar vacío")
        assertTrue(marco.evaluar(Vec3(0f, 0f, 20f)) < 0f, "el lado izquierdo debería ser material")
        assertTrue(marco.evaluar(Vec3(20f, 0f, 0f)) < 0f, "el lado inferior debería ser material")

        // Abierto, el cuarto lado desaparece y el hueco se comunica con el exterior.
        val abierto = camino(
            0f to 0f, 40f to 0f, 40f to 40f, 0f to 40f,
            cerrado = false, radio = 2f,
        )
        assertTrue(abierto.evaluar(Vec3(0f, 0f, 20f)) > 0f, "sin cerrar, el cuarto lado no existe")
    }

    @Test
    fun `las cotas contienen todo el material`() {
        // La invariante que el mallador da por supuesta. Declararla de menos es lo
        // que abría agujeros en las extrusiones redondeadas, y aquí el radio de la
        // sección sobresale del camino por los cuatro lados y por arriba y abajo.
        val pieza = camino(
            0f to 0f, 40f to 0f, 40f to 25f,
            cerrado = false, radio = 4f,
        )
        val c = pieza.cotas()
        var peor = Float.POSITIVE_INFINITY

        val pasos = 20
        for (i in 0..pasos) for (j in 0..pasos) {
            val u = i.toFloat() / pasos
            val v = j.toFloat() / pasos
            val x = c.min.x + (c.max.x - c.min.x) * u
            val y = c.min.y + (c.max.y - c.min.y) * u
            val yv = c.min.y + (c.max.y - c.min.y) * v
            val z = c.min.z + (c.max.z - c.min.z) * v

            for (p in listOf(
                Vec3(c.min.x, y, z), Vec3(c.max.x, y, z),
                Vec3(x, c.min.y, z), Vec3(x, c.max.y, z),
                Vec3(x, yv, c.min.z), Vec3(x, yv, c.max.z),
            )) peor = minOf(peor, pieza.evaluar(p))
        }

        assertTrue(peor >= -1e-3f, "hay material fuera de las cotas: $peor mm")
    }

    @Test
    fun `un tubo doblado se malla cerrado`() {
        val codo = camino(
            0f to 0f, 30f to 0f, 30f to 25f,
            cerrado = false, radio = 4f,
        )
        val topologia = ContorneadoDual(codo, 0.25f).generar().revisarTopologia()

        assertTrue(topologia.esCerrada, "malla abierta: ${topologia.aristasAbiertas} aristas")
        assertTrue(topologia.estaBienOrientada, "${topologia.aristasInvertidas} aristas invertidas")
    }

    @Test
    fun `un barrido recien anadido ya se ve`() {
        // Si la pieza recién creada no compilase, el botón de la paleta dejaría el
        // viewport igual que estaba y parecería roto. El contorno por defecto tiene
        // que dar algo mirable sin tocar nada.
        val editor = Editor()
        val raiz = editor.filas().first().id

        assertTrue(editor.anadir("BARRIDO", raiz), "no se pudo añadir un BARRIDO")
        assertTrue(!editor.estaVacio, "un BARRIDO recién añadido no compila a nada")

        val id = assertNotNull(editor.seleccionado)
        assertTrue(editor.tienePerfil(id), "un BARRIDO tiene que dejar editar su camino")
    }

    @Test
    fun `un modelo puede pedir un tubo por su nombre y acotarlo`() {
        // La prueba que importa para el puente: «tubo» no está en el catálogo, pero es
        // lo que escribe cualquiera. Y el camino se da con la misma operación "perfil"
        // que ya usan las extrusiones, sin vocabulario nuevo que aprender.
        val editor = Editor(Documento.vacio())
        val leido = editor.interpretarPlan(
            """
            {"resumen":"Asa en U","reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"TUBO","alias":"asa","nombre":"Asa",
               "parametros":{"radio":3,"cerrado":0}},
              {"op":"perfil","objetivo":"asa","forma":"LIBRE",
               "puntos":[[0,0],[0,30],[50,30],[50,0]]},
              {"op":"acotar","objetivo":"modelo","eje":"X","medida":120}
            ]}
            """.trimIndent()
        )
        val plan = assertNotNull(leido.plan, "no se interpretó: ${leido.motivoDelRechazo}")
        val resultado = editor.aplicarPlan(plan, null)

        assertTrue(resultado.exito, "no se aplicó: ${resultado.resumen}")
        assertTrue(resultado.omitidas.isEmpty(), "se cayeron operaciones: ${resultado.omitidas}")
        assertTrue(editor.filas().any { it.tipo == "BARRIDO" }, "«TUBO» debería haber dado un BARRIDO")

        val ancho = editor.cotaMaxima[0] - editor.cotaMinima[0]
        assertTrue(abs(ancho - 120f) < 0.2f, "ancho $ancho, se pidió 120")
    }
}
