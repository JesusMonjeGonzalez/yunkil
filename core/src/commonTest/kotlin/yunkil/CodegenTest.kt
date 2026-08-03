package yunkil

import yunkil.kernel.Axis
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
        // Una repetición de 5 esferas tiene un solo radio, no cinco.
        val fila = Repeticion(Esfera(2f), cuenta = 5, paso = 10f)
        assertEquals(2, generador.generar(fila).numeroDeUniforms, "paso + radio")
    }

    @Test
    fun `todo bucle emitido lleva un tope constante`() {
        val fuente = generador.generar(modeloDePrueba()).fuente
        val bucles = Regex("""for\s*\(([^)]*)\)""").findAll(fuente).map { it.groupValues[1] }.toList()
        assertTrue(bucles.isNotEmpty(), "el raymarcher debería tener bucles")
        for (b in bucles) {
            assertTrue(
                b.contains("YK_MAX_PASOS") || b.contains("YK_PASOS_AO") || b.contains("i < 3"),
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
