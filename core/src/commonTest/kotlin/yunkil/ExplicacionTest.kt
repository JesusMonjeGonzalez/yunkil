package yunkil

import yunkil.fabricacion.AjusteDeTaladro
import yunkil.ia.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La explicación es lo único que el usuario lee antes de aceptar un plan, así que
 * lo que se comprueba aquí no es que salga texto: es que el texto diga los números
 * del plan y nombre las piezas como se llaman.
 */
class ExplicacionTest {

    @Test
    fun `una caja se cuenta con sus tres cotas y su nombre`() {
        val plan = PlanDeModelado(
            operaciones = listOf(
                Crear(
                    tipo = "CAJA",
                    alias = "b",
                    nombre = "Base",
                    parametros = mapOf("anchura" to 60f, "altura" to 20f, "profundidad" to 40f),
                ),
            ),
        )
        val linea = plan.explicar().single().texto
        assertEquals("Crea Base de 60 × 20 × 40 mm", linea)
    }

    @Test
    fun `el alias se sustituye por el nombre legible`() {
        val plan = PlanDeModelado(
            operaciones = listOf(
                Crear(tipo = "CAJA", alias = "b", nombre = "Base"),
                Crear(tipo = "CAJA", alias = "t", nombre = "Tapa"),
                Colocar(objetivo = "t", referencia = "b"),
                Filete(objetivo = "t", contra = "b", radio = 1.5f),
            ),
        )
        val lineas = plan.explicar()
        assertEquals("Apoya Tapa arriba de Base", lineas[2].texto)
        assertEquals("Redondea el canto entre Tapa y Base con radio 1,5 mm", lineas[3].texto)
    }

    @Test
    fun `las dependencias apuntan a la operacion que creo el alias`() {
        val plan = PlanDeModelado(
            operaciones = listOf(
                Crear(tipo = "CAJA", alias = "b", nombre = "Base"),
                Crear(tipo = "CAJA", alias = "t", nombre = "Tapa"),
                Colocar(objetivo = "t", referencia = "b"),
            ),
        )
        val lineas = plan.explicar()
        assertEquals(emptyList(), lineas[0].depende)
        // Colocar necesita las dos: sin cualquiera de ellas no significa nada.
        assertEquals(listOf(0, 1), lineas[2].depende)
    }

    @Test
    fun `un taladro dice la rosca, el ajuste y donde cae`() {
        val plan = PlanDeModelado(
            operaciones = listOf(
                Crear(tipo = "CAJA", alias = "b", nombre = "Base"),
                Taladro(
                    objetivo = "b",
                    designacion = "M3",
                    ajuste = AjusteDeTaladro.PASANTE,
                    desplazamiento = listOf(10f, -10f),
                ),
            ),
        )
        val linea = plan.explicar()[1].texto
        assertEquals("Taladra Base: agujero M3 de paso a (10, -10) mm del centro", linea)
    }

    @Test
    fun `la pared sin grosor dice que lo pone el perfil`() {
        val plan = PlanDeModelado(
            operaciones = listOf(
                Crear(tipo = "CAJA", alias = "b", nombre = "Caja"),
                Pared(objetivo = "b"),
            ),
        )
        assertContains(plan.explicar()[1].texto, "el perfil activo")
    }

    @Test
    fun `la holgura negativa se cuenta como solape y no como separacion`() {
        val plan = PlanDeModelado(
            operaciones = listOf(
                Crear(tipo = "CAJA", alias = "a", nombre = "A"),
                Crear(tipo = "CAJA", alias = "b", nombre = "B"),
                Colocar(objetivo = "b", referencia = "a", holgura = -0.4f),
            ),
        )
        val linea = plan.explicar()[2].texto
        assertContains(linea, "solapando 0,4 mm")
    }

    @Test
    fun `renombrar cambia como se llaman las operaciones siguientes`() {
        val plan = PlanDeModelado(
            operaciones = listOf(
                Crear(tipo = "CAJA", alias = "b", nombre = "Base"),
                Renombrar(objetivo = "b", nombre = "Soporte"),
                Taladro(objetivo = "b", designacion = "M4"),
            ),
        )
        val lineas = plan.explicar()
        assertEquals("Renombra Base a «Soporte»", lineas[1].texto)
        assertContains(lineas[2].texto, "Taladra Soporte")
    }

    @Test
    fun `la nota del modelo se marca como su motivo`() {
        val plan = PlanDeModelado(
            operaciones = listOf(
                Crear(tipo = "CAJA", nombre = "Base", nota = "el cuerpo que se atornilla"),
            ),
        )
        assertContains(plan.explicar().single().texto, "— el cuerpo que se atornilla")
    }

    /**
     * Una explicación que se salta operaciones es peor que no tenerla: el usuario
     * acepta creyendo que ve el plan entero. Esta prueba se rompe el día que se
     * añada una operación al vocabulario y nadie la cuente.
     */
    @Test
    fun `todas las operaciones del vocabulario se cuentan`() {
        val todas: List<Operacion> = listOf(
            Crear(tipo = "CAJA", alias = "a", nombre = "A"),
            Envolver(objetivo = "a", tipo = "DIFERENCIA"),
            Fijar(objetivo = "a", clave = "anchura", valor = 10f),
            Mover(objetivo = "a", x = 1f),
            Girar(objetivo = "a", z = 90f),
            Escalar(objetivo = "a", factor = 2f),
            Acotar(objetivo = "a", medida = 80f),
            Renombrar(objetivo = "a", nombre = "B"),
            Duplicar(objetivo = "a"),
            Colocar(objetivo = "a", referencia = "a"),
            Alinear(objetivo = "a", referencia = "a", eje = EjeNombrado.X),
            DefinirPerfil(objetivo = "a", forma = "RECTANGULO"),
            Taladro(objetivo = "a", designacion = "M3"),
            Patron(objetivo = "a", estandar = "VESA_100"),
            Pared(objetivo = "a", grosor = 2f),
            Asentar(),
            Filete(objetivo = "a", radio = 1f),
            Apoyar(objetivo = "a"),
            Seleccionar(objetivo = "a"),
            Eliminar(objetivo = "a"),
        )
        // Una por cada nombre del vocabulario, para que la lista no se quede corta.
        assertEquals(Vocabulario.OPERACIONES.size, todas.size)

        val lineas = PlanDeModelado(operaciones = todas).explicar()
        assertEquals(todas.size, lineas.size)
        // El `when` sobre la interfaz sellada ya lo garantiza en compilación; lo que
        // esto caza es lo otro: una rama que devuelve el volcado del objeto en vez de
        // una frase, que compila igual de bien.
        for (linea in lineas) {
            assertTrue(linea.texto.isNotBlank(), "línea vacía en ${linea.indice}")
            assertTrue(
                !linea.texto.contains("null") &&
                    !linea.texto.contains("kotlin.") &&
                    !linea.texto.contains("yunkil.ia."),
                "línea sin traducir: «${linea.texto}»",
            )
        }
    }

    @Test
    fun `explicar es determinista`() {
        val plan = PlanDeModelado(
            operaciones = listOf(
                Crear(tipo = "CILINDRO", alias = "c", parametros = mapOf("radio" to 8f, "altura" to 30f)),
                Taladro(objetivo = "c", designacion = "M3"),
            ),
        )
        assertEquals(Explicacion.texto(plan), Explicacion.texto(plan))
    }

    @Test
    fun `los milimetros se escriben con coma decimal`() {
        val plan = PlanDeModelado(
            operaciones = listOf(Fijar(objetivo = "raiz", clave = "fusion", valor = 2.5f)),
        )
        assertContains(plan.explicar().single().texto, "2,5 mm")
    }
}
