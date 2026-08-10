package yunkil

import yunkil.kernel.Vec3
import yunkil.organico.FormaOrganica
import yunkil.organico.MotorOrganico
import yunkil.organico.ParteOrganica
import yunkil.organico.RolOrganico
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * La curva semántica: una cola es una cola, no seis cápsulas.
 *
 * Esta es la primera de las prioridades del README y arregla algo concreto y medible.
 * Con solo esferas, cápsulas y troncos, una cola curvada obligaba a la IA a encadenar
 * seis o siete partes rectas, y eso tenía tres consecuencias que se veían en el banco:
 *
 *  1. El presupuesto de partes se iba en la cola, y a la figura le faltaban las orejas.
 *  2. El grosor solo cambiaba de tramo a tramo, así que el afilado quedaba escalonado.
 *  3. Cada tramo tenía que colocarse a mano en el sitio exacto donde acababa el anterior,
 *     y bastaba que un extremo se despistara un milímetro para partir la cola en dos.
 *
 * Lo que se comprueba aquí es que la curva **pasa por donde el modelo dice** —que es lo
 * que hace que las coordenadas de la IA signifiquen algo— y que lo que llega mal escrito
 * se rechaza con un motivo, en vez de compilar una figura rota en silencio.
 */
class CurvaOrganicaTest {

    private fun cuerpo() = ParteOrganica(
        id = "cuerpo",
        rol = RolOrganico.CUERPO,
        forma = FormaOrganica.CAPSULA,
        a = listOf(0f, 10f, 0f),
        b = listOf(0f, 34f, 0f),
        radio = 12f,
    )

    private fun cabeza() = ParteOrganica(
        id = "cabeza",
        rol = RolOrganico.CABEZA,
        forma = FormaOrganica.ESFERA,
        centro = listOf(0f, 46f, 0f),
        radio = 11f,
        unidoA = "cuerpo",
    )

    private fun cola(
        puntos: List<Float> = listOf(0f, 26f, 6f, 8f, 34f, 16f, 6f, 44f, 22f, -4f, 48f, 16f),
        radios: List<Float> = listOf(5f, 3.5f, 2f, 0.4f),
    ) = ParteOrganica(
        id = "cola",
        rol = RolOrganico.COLA,
        forma = FormaOrganica.CURVA,
        puntos = puntos,
        radios = radios,
        unidoA = "cuerpo",
    )

    private fun contrato(cola: ParteOrganica): String = """
        {"esquema":"yunkil.organico.v1","nombre":"gato","unidades":"mm","fusionMm":2,
         "partes":[
          {"id":"cuerpo","rol":"CUERPO","forma":"CAPSULA","a":[0,10,0],"b":[0,34,0],"radio":12},
          {"id":"cabeza","rol":"CABEZA","forma":"ESFERA","centro":[0,46,0],"radio":11,"unidoA":"cuerpo"},
          {"id":"cola","rol":"COLA","forma":"CURVA","puntos":[${cola.puntos.joinToString(",")}],
           "radios":[${cola.radios.joinToString(",")}],"unidoA":"cuerpo"}
         ]}
    """.trimIndent()

    @Test
    fun `una figura con cola de curva se acepta y compila`() {
        val leido = MotorOrganico.interpretar(contrato(cola()))
        assertTrue(leido.aceptado, "rechazada: ${leido.motivo}")
        val nodo = MotorOrganico.nodoDeContrato(assertNotNull(leido.contratoCanonico))
        assertNotNull(nodo, "el contrato aceptado no compiló")
    }

    @Test
    fun `la curva pasa por los puntos que dijo el modelo`() {
        // Catmull-Rom interpola: la superficie tiene que envolver cada punto de control,
        // no pasar cerca. Si esto fallara, las coordenadas del contrato serían una
        // sugerencia y la IA no podría colocar nada con precisión.
        val leido = MotorOrganico.interpretar(contrato(cola()))
        val nodo = assertNotNull(MotorOrganico.nodoDeContrato(assertNotNull(leido.contratoCanonico)))

        val controles = listOf(
            Vec3(0f, 26f, 6f),
            Vec3(8f, 34f, 16f),
            Vec3(6f, 44f, 22f),
        )
        for (c in controles) {
            assertTrue(nodo.evaluar(c) < 0f, "el punto de control $c se quedó fuera del material")
        }
    }

    @Test
    fun `la curva se curva de verdad`() {
        // El contraste que importa: si el suavizado no existiera y la cola fuera la recta
        // entre el primer y el último control, el codo del medio estaría vacío. Se mide
        // justo ahí, lejos de la cuerda.
        val leido = MotorOrganico.interpretar(contrato(cola()))
        val nodo = assertNotNull(MotorOrganico.nodoDeContrato(assertNotNull(leido.contratoCanonico)))

        val codo = Vec3(8f, 34f, 16f)
        val arranque = Vec3(0f, 26f, 6f)
        val punta = Vec3(-4f, 48f, 16f)
        val enLaCuerda = (arranque + punta) * 0.5f

        assertTrue(nodo.evaluar(codo) < 0f, "el codo de la cola no tiene material")
        assertTrue(
            (codo - enLaCuerda).length() > 6f,
            "el caso de prueba es malo: el codo no se aparta de la cuerda",
        )
    }

    @Test
    fun `la punta se afila hasta desaparecer`() {
        // Radio 0.4 en el último control. La sección al final tiene que ser mucho más
        // fina que en el arranque: es lo que una cadena de cápsulas no sabía hacer.
        val leido = MotorOrganico.interpretar(contrato(cola()))
        val nodo = assertNotNull(MotorOrganico.nodoDeContrato(assertNotNull(leido.contratoCanonico)))

        val punta = Vec3(-4f, 48f, 16f)
        assertTrue(nodo.evaluar(punta + Vec3(1.5f, 0f, 0f)) > 0f, "la punta sigue gorda")
        val arranque = Vec3(0f, 26f, 6f)
        assertTrue(nodo.evaluar(arranque + Vec3(3f, 0f, 0f)) < 0f, "el arranque debería ser grueso")
    }

    @Test
    fun `dos puntos de control no gastan vertices de mas`() {
        // Una recta suavizada sigue siendo la misma recta. Gastar nueve vértices en
        // decirlo solo encarece el bucle del shader por nada.
        val recta = ParteOrganica(
            id = "cable",
            rol = RolOrganico.DETALLE,
            forma = FormaOrganica.CURVA,
            puntos = listOf(0f, 0f, 0f, 0f, 20f, 0f),
            radios = listOf(3f, 1f),
        )
        assertTrue(MotorOrganico.cordonDe(recta).puntos.size == 2)

        val curva = cola()
        val cordon = MotorOrganico.cordonDe(curva)
        assertTrue(cordon.puntos.size > 4, "una curva de 4 controles debe muestrearse")
        assertTrue(cordon.puntos.size <= 64, "el cordón se pasó del tope del shader")
        // Y el grosor sale interpolado, no a saltos: entre 5 y 3.5 hay valores intermedios.
        assertTrue(
            cordon.radios.any { abs(it - 4.2f) < 0.7f },
            "los radios saltan en vez de interpolar: ${cordon.radios}",
        )
    }

    @Test
    fun `una curva mal escrita se rechaza con su motivo`() {
        // Cada uno de estos es un error que un modelo de lenguaje comete de verdad:
        // coordenadas sueltas, radios descuadrados, un punto repetido al copiar y pegar.
        val malas = listOf(
            "tripletes" to cola(puntos = listOf(0f, 26f, 6f, 8f, 34f)),
            "radios" to cola(radios = listOf(5f, 3f)),
            "repetido" to cola(
                puntos = listOf(0f, 26f, 6f, 0f, 26f, 6f, 6f, 44f, 22f, -4f, 48f, 16f),
            ),
            "negativo" to cola(radios = listOf(5f, 3.5f, -2f, 0.4f)),
            "gigante" to cola(radios = listOf(5f, 3.5f, 2f, 900f)),
            "invisible" to cola(radios = listOf(0.1f, 0.05f, 0.05f, 0f)),
        )
        for ((nombre, mala) in malas) {
            val leido = MotorOrganico.interpretar(contrato(mala))
            assertFalse(leido.aceptado, "la curva '$nombre' se aceptó sin más")
            assertTrue(!leido.motivo.isNullOrBlank(), "la curva '$nombre' se rechazó sin motivo")
        }
    }

    @Test
    fun `un contrato sin curvas sigue valiendo palabra por palabra`() {
        // Los campos nuevos son opcionales: los contratos ya guardados en `.yunkil` y las
        // respuestas de un modelo que no conoce CURVA tienen que compilar igual que ayer.
        val viejo = """
            {"esquema":"yunkil.organico.v1","nombre":"muñeco","unidades":"mm","fusionMm":2,
             "partes":[
              {"id":"cuerpo","rol":"CUERPO","forma":"CAPSULA","a":[0,8,0],"b":[0,38,0],"radio":14},
              {"id":"cabeza","rol":"CABEZA","forma":"ESFERA","centro":[0,52,0],"radio":13,"unidoA":"cuerpo"},
              {"id":"oreja","rol":"OREJA","forma":"TRONCO","a":[6,60,0],"b":[9,68,0],"radioA":4,"radioB":1,"unidoA":"cabeza"}
             ]}
        """.trimIndent()
        val leido = MotorOrganico.interpretar(viejo)
        assertTrue(leido.aceptado, "rechazado: ${leido.motivo}")
        assertNotNull(MotorOrganico.nodoDeContrato(assertNotNull(leido.contratoCanonico)))
    }

    @Test
    fun `el catalogo del prompt enseña la curva`() {
        // El nodo no sirve de nada si el modelo no sabe que existe. El prompt es la única
        // vía por la que se entera, así que el ejemplo tiene que estar y ser válido.
        val texto = MotorOrganico.instrucciones()
        assertTrue(texto.contains("CURVA"), "el prompt no menciona la forma nueva")
        assertTrue(texto.contains("\"radios\""), "el prompt no explica los radios por punto")
        assertTrue(texto.contains("COLA"), "el prompt debería sugerir usarla para la cola")
    }
}
