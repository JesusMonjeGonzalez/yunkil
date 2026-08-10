package yunkil

import yunkil.kernel.Axis
import yunkil.kernel.Barrido
import yunkil.kernel.Extrusion
import yunkil.kernel.Perfil2D
import yunkil.kernel.Punto2
import yunkil.kernel.Revolucion
import yunkil.kernel.Caja
import yunkil.kernel.Cilindro
import yunkil.kernel.Diferencia
import yunkil.kernel.Esfera
import yunkil.kernel.Quat
import yunkil.kernel.Repeticion
import yunkil.kernel.SdfNode
import yunkil.kernel.Simetria
import yunkil.kernel.Transform
import yunkil.kernel.Transformado
import yunkil.kernel.Union
import yunkil.kernel.Vaciado
import yunkil.kernel.Vec3
import yunkil.kernel.empaquetarUniforms
import yunkil.kernel.preorden
import yunkil.msl.MslGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Modelo con al menos un nodo de cada tipo, para que las cuentas no sean triviales. */
internal fun modeloDePrueba(): SdfNode = Diferencia(
    Union(
        Vaciado(
            Caja(Vec3(30f, 12f, 20f), redondeo = 3f),
            grosor = 2.4f,
        ),
        Simetria(
            Transformado(
                Cilindro(radio = 4f, altura = 30f, redondeo = 0.5f),
                Transform(
                    rotation = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), 0.6f),
                    translation = Vec3(20f, 6f, 0f),
                    scale = 1.25f,
                ),
            ),
            eje = Axis.X,
        ),
        fusion = 2f,
    ),
    Repeticion(
        Transformado(Esfera(3.5f), Transform(translation = Vec3(0f, 8f, 0f))),
        cuenta = 4,
        paso = 12f,
        eje = Axis.Z,
    ),
)

class CodegenTest {

    private val generador = MslGenerator()

    @Test
    fun `el generador reserva exactamente tantos uniforms como empaqueta el arbol`() {
        val modelo = modeloDePrueba()
        assertEquals(
            modelo.empaquetarUniforms().size,
            generador.generar(modelo).numeroDeUniforms,
            "el shader leería fuera del buffer o dejaría huecos",
        )
    }

    @Test
    fun `los nodos con perfil tambien cuadran el buffer de uniforms`() {
        // Son los únicos con un bloque de longitud variable —los vértices del
        // contorno— y por eso los únicos que pueden desalinear el buffer. El fixture
        // general no los tiene, así que hasta ahora nadie comprobaba justamente los
        // que sí podían fallar. Un desfase de un solo hueco no da error: da una pieza
        // con otras cotas, o el viewport en negro.
        val contorno = Perfil2D.poligono(
            listOf(Punto2(0f, 0f), Punto2(40f, 0f), Punto2(40f, 25f), Punto2(0f, 25f)),
        )
        val casos = listOf(
            "extrusión" to Extrusion(contorno, altura = 10f, redondeo = 1.5f),
            "revolución" to Revolucion(contorno, desplazamiento = 6f),
            "barrido cerrado" to Barrido(contorno, radio = 3f, cerrado = true),
            "barrido abierto" to Barrido(contorno, radio = 3f, cerrado = false),
        )

        for ((nombre, nodo) in casos) {
            assertEquals(
                nodo.empaquetarUniforms().size,
                generador.generar(nodo).numeroDeUniforms,
                "el buffer no cuadra en $nombre",
            )
        }

        // Y combinados, que es donde el cursor de un nodo arrastra al siguiente.
        val juntos = casos.map { it.second }.reduce { a, b -> Union(a, b, 0f) }
        assertEquals(
            juntos.empaquetarUniforms().size,
            generador.generar(juntos).numeroDeUniforms,
            "el buffer no cuadra al encadenar nodos con perfil",
        )
    }

    @Test
    fun `abrir o cerrar un barrido cambia la huella topologica`() {
        // El número de tramos gobierna el bucle del shader, así que no puede viajar
        // como uniform: si compartieran huella, cerrar un marco no recompilaría y el
        // cuarto lado no aparecería nunca.
        val contorno = Perfil2D.poligono(
            listOf(Punto2(0f, 0f), Punto2(30f, 0f), Punto2(30f, 20f)),
        )
        assertNotEquals(
            generador.generar(Barrido(contorno, 3f, cerrado = true)).huellaTopologica,
            generador.generar(Barrido(contorno, 3f, cerrado = false)).huellaTopologica,
        )
    }

    @Test
    fun `ningun valor numerico se cuela como literal en el shader`() {
        // Si un parámetro se emitiera como literal, cambiarlo obligaría a recompilar.
        val fuente = generador.generar(
            Esfera(radio = 37.25f),
        ).fuente
        assertFalse(fuente.contains("37.25"), "el radio se emitió como literal")
        assertTrue(fuente.contains("u[0]"), "el radio debería leerse del buffer")
    }

    @Test
    fun `cambiar un parametro no cambia la huella topologica`() {
        val a = Union(Esfera(5f), Caja(Vec3(1f, 2f, 3f)), fusion = 1f)
        val b = Union(Esfera(99f), Caja(Vec3(7f, 7f, 7f)), fusion = 4f)
        assertEquals(
            generador.generar(a).huellaTopologica,
            generador.generar(b).huellaTopologica,
        )
        // Y por tanto el shader emitido es idéntico: se reutiliza tal cual.
        assertEquals(generador.generar(a).fuente, generador.generar(b).fuente)
    }

    @Test
    fun `cambiar la estructura si cambia la huella`() {
        val a = Union(Esfera(5f), Caja(Vec3(1f, 1f, 1f)))
        val b = Diferencia(Esfera(5f), Caja(Vec3(1f, 1f, 1f)))
        assertNotEquals(
            generador.generar(a).huellaTopologica,
            generador.generar(b).huellaTopologica,
        )
    }

    @Test
    fun `la cuenta de una repeticion es topologia, el paso es parametro`() {
        val tres = Repeticion(Esfera(2f), cuenta = 3, paso = 10f)
        val cuatro = Repeticion(Esfera(2f), cuenta = 4, paso = 10f)
        val tresOtroPaso = Repeticion(Esfera(2f), cuenta = 3, paso = 25f)

        assertNotEquals(
            generador.generar(tres).huellaTopologica,
            generador.generar(cuatro).huellaTopologica,
            "cambiar la cuenta debe regenerar el shader",
        )
        assertEquals(
            generador.generar(tres).huellaTopologica,
            generador.generar(tresOtroPaso).huellaTopologica,
            "cambiar el paso no debe regenerar el shader",
        )
    }

    @Test
    fun `las copias de una repeticion comparten los uniforms del hijo`() {
        // Una repetición de 5 esferas tiene un solo radio, no cinco. A los escalares
        // (paso + radio) se suman las cajas de los dos nodos: 6 floats por nodo.
        val fila = Repeticion(Esfera(2f), cuenta = 5, paso = 10f)
        assertEquals(14, generador.generar(fila).numeroDeUniforms, "paso + radio + 2 cajas")
    }

    @Test
    fun `todo bucle emitido lleva un tope constante`() {
        val fuente = generador.generar(modeloDePrueba()).fuente
        val bucles = Regex("""for\s*\(([^)]*)\)""").findAll(fuente).map { it.groupValues[1] }.toList()
        assertTrue(bucles.isNotEmpty(), "el raymarcher debería tener bucles")
        for (b in bucles) {
            assertTrue(
                b.contains("YK_MAX_PASOS") || b.contains("YK_PASOS_AO") ||
                    b.contains("YK_MAX_VERTICES") || b.contains("YK_MAX_GROSOR") ||
                    b.contains("YK_MAX_CORDON") || b.contains("i < 3"),
                "bucle sin tope constante: for ($b)",
            )
        }
    }

    @Test
    fun `el shader declara los puntos de entrada y los buffers esperados`() {
        val fuente = generador.generar(modeloDePrueba()).fuente
        assertTrue(fuente.contains("vertex YkVertice yk_vertex"), "falta el vertex shader")
        assertTrue(fuente.contains("fragment float4 yk_fragment"), "falta el fragment shader")
        assertTrue(fuente.contains("constant YkCamara &cam [[buffer(0)]]"), "falta la cámara")
        assertTrue(fuente.contains("constant float *u [[buffer(1)]]"), "faltan los uniforms")
    }
}

/**
 * La marcha podada tiene que leer los mismos uniforms que el campo exacto.
 *
 * La poda no cambia el valor del campo: solo se salta ramas que no pueden ganar.
 * Así que `yk_marcha` y `yk_map` describen la misma geometría con los mismos
 * números, y si los índices de uniforms de una no coinciden con los de la otra es
 * que un nodo está leyendo los datos de otro. Eso no da error de compilación: da
 * otra pieza, y solo en el cuerpo que dibuja la pantalla.
 */
class ParidadDeCuerposTest {

    private fun indices(cuerpo: String, tope: Int): Set<Int> =
        Regex("""u\[(\d+)]""").findAll(cuerpo)
            .map { it.groupValues[1].toInt() }
            .filter { it < tope }
            .toSet()

    private fun cuerpo(fuente: String, funcion: String): String {
        val inicio = fuente.indexOf("float $funcion(float3 p, constant float *u) {")
        assertTrue(inicio >= 0, "no se encontró $funcion en el shader")
        val fin = fuente.indexOf("\n}\n", inicio)
        return fuente.substring(inicio, fin)
    }

    private fun comprobar(raiz: SdfNode, que: String) {
        val generado = MslGenerator().generar(raiz)
        val escalares = raiz.preorden().sumOf { it.escalares.size }
        val exacto = indices(cuerpo(generado.fuente, "yk_map"), escalares)
        val podado = indices(cuerpo(generado.fuente, "yk_marcha"), escalares)

        assertEquals(exacto, podado, "$que: la marcha podada lee otros uniforms que el campo exacto")
    }

    @Test
    fun `el acuerdo local podado lee los uniforms de su propia rama`() {
        // El caso que se rompió: la caja de la poda reservaba los escalares de la
        // rama y quien la emitía después volvía a contarlos, así que el nodo leía
        // catorce huecos más allá de los suyos. Medido en la GPU: hasta 20 mm de
        // diferencia entre la marcha y el campo, que es un rayo saltándose la pieza.
        comprobar(
            yunkil.kernel.AcuerdoLocal(
                a = Caja(Vec3(20f, 10f, 15f), 0f),
                b = Transformado(
                    Caja(Vec3(8f, 8f, 8f), 1f),
                    Transform(translation = Vec3(18f, 0f, 0f)),
                ),
                modo = yunkil.kernel.ModoDeAcuerdo.UNION,
                centro = Vec3(20f, 0f, 0f),
                radio = 6f,
                fusion = 3f,
            ),
            "acuerdo local en unión",
        )
    }

    @Test
    fun `las booleanas podadas leen los uniforms de su propia rama`() {
        comprobar(
            Union(
                Caja(Vec3(20f, 10f, 15f), 0f),
                Transformado(Esfera(7f), Transform(translation = Vec3(30f, 0f, 0f))),
                0f,
            ),
            "unión",
        )
        comprobar(
            Diferencia(
                Caja(Vec3(20f, 10f, 15f), 0f),
                Transformado(Esfera(7f), Transform(translation = Vec3(10f, 0f, 0f))),
                0f,
            ),
            "diferencia",
        )
        comprobar(modeloDePrueba(), "el modelo con un nodo de cada tipo")
    }
}
