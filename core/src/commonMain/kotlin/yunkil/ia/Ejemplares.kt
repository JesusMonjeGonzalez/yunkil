package yunkil.ia

/**
 * Un modelo de referencia, verificado, en el propio DSL de planes.
 *
 * `plan` es el JSON exacto que emitiría el modelo: nada de estructuras Kotlin que
 * haya que volver a serializar. Así el ejemplar se puede pegar tal cual en el
 * prompt como few-shot, y el test que lo verifica lo interpreta con el mismo
 * camino —`interpretarPlan` → `aplicarPlan` → `revisarPlan`— por el que pasaría la
 * respuesta real de un modelo.
 */
data class Ejemplar(
    val nombre: String,
    val peticion: String,
    val etiquetas: List<String>,
    val plan: String,
)

/**
 * Biblioteca de modelos de referencia para inyectar como few-shot.
 *
 * La razón de ser de esto es que un modelo de lenguaje adapta un ejemplo cercano
 * mucho mejor que compone una pieza desde primitivas sueltas: "como este pero más
 * alto" es una tarea que un modelo pequeño resuelve bien, y "una escuadra con dos
 * agujeros" partiendo de cero es la tarea en la que se inventa coordenadas y
 * olvida que el taladro tiene que atravesar. Cada ejemplar de aquí usa `colocar`
 * en vez de coordenadas calculadas y `taladro` para cualquier agujero de tornillo,
 * que son las dos operaciones que evitan esos errores.
 *
 * Todos los ejemplares están verificados por `EjemplaresTest`: se interpretan, se
 * aplican sin ninguna operación omitida y pasan `revisarPlan` —lo que incluye que
 * ninguna pieza quede flotando y que ninguna resta se quede sin cortar—. Un
 * ejemplar que no pasara esa criba enseñaría a modelar exactamente el error que el
 * resto del sistema existe para evitar, así que no entra en el catálogo.
 */
object Ejemplares {

    fun relevantes(peticion: String, cuantos: Int = 2): List<Ejemplar> {
        if (CATALOGO.isEmpty() || cuantos <= 0) return emptyList()

        val palabrasDeLaPeticion = palabrasSignificativas(peticion)
        if (palabrasDeLaPeticion.isEmpty()) return CATALOGO.take(cuantos)

        // Las etiquetas puntúan el doble que el solapamiento con la petición de
        // ejemplo: son la curación humana de qué es este ejemplar, y una palabra
        // suelta que coincide por casualidad en la redacción no debería pesar
        // igual que una etiqueta puesta a propósito.
        return CATALOGO
            .map { it to puntuar(it, palabrasDeLaPeticion) }
            .sortedByDescending { (_, puntuacion) -> puntuacion }
            .take(cuantos)
            .map { (ejemplar, _) -> ejemplar }
    }

    private fun puntuar(ejemplar: Ejemplar, palabrasDeLaPeticion: Set<String>): Int {
        val palabrasDeEtiquetas = ejemplar.etiquetas.flatMap { palabrasSignificativas(it) }.toSet()
        val palabrasDelEjemplo = palabrasSignificativas(ejemplar.peticion)
        val porEtiqueta = palabrasDeEtiquetas.count { it in palabrasDeLaPeticion }
        val porPeticion = palabrasDelEjemplo.count { it in palabrasDeLaPeticion }
        return porEtiqueta * 2 + porPeticion
    }

    /**
     * Palabras en minúsculas, sin acentos y sin las vacías del castellano que no
     * distinguen una familia de pieza de otra ("de", "la", "un", "para"...). Sin
     * dependencias ni embeddings: es solapamiento de palabras, a propósito.
     */
    private fun palabrasSignificativas(texto: String): Set<String> =
        sinAcentos(texto.lowercase())
            .split(SEPARADORES)
            .filter { it.length > 2 && it !in PALABRAS_VACIAS }
            .toSet()

    private fun sinAcentos(texto: String): String =
        buildString(texto.length) {
            for (c in texto) append(TILDES[c] ?: c)
        }

    private val SEPARADORES = Regex("[^a-z0-9ñ]+")

    private val TILDES = mapOf(
        'á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u',
    )

    private val PALABRAS_VACIAS = setOf(
        "de", "del", "la", "el", "los", "las", "un", "una", "unos", "unas",
        "para", "con", "por", "que", "y", "o", "a", "en", "al", "se", "su", "sus",
        "es", "ser", "como", "mas", "más", "sin", "sobre", "entre", "lo", "le",
        "les", "mi", "mis", "tu", "tus", "este", "esta", "esto", "estos", "estas",
        "hazme", "hazlo", "haz", "quiero", "necesito", "dame", "crea", "crear",
        "creame", "diseña", "diseñame", "diseñar", "porfavor", "favor", "puedes",
        "podrias", "quisiera", "algo", "cosa", "pieza", "modelo", "objeto",
    )

    val CATALOGO: List<Ejemplar> = listOf(
        // ------------------------------------------------------------ soporte / apoyo
        Ejemplar(
            nombre = "Soporte de móvil",
            peticion = "hazme un soporte de mesa para el móvil, que quede algo inclinado",
            etiquetas = listOf("soporte", "movil", "telefono", "apoyo", "inclinado", "atril"),
            plan = """
            {"resumen":"Soporte de móvil con respaldo inclinado y labio delantero",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"base","nombre":"Base",
               "parametros":{"anchura":90,"altura":8,"profundidad":70}},
              {"op":"crear","tipo":"CAJA","alias":"respaldo","nombre":"Respaldo",
               "parametros":{"anchura":90,"altura":70,"profundidad":8}},
              {"op":"girar","objetivo":"respaldo","x":-15},
              {"op":"colocar","objetivo":"respaldo","referencia":"base","cara":"arriba","holgura":-2,"centrar":true},
              {"op":"alinear","objetivo":"respaldo","referencia":"base","eje":"Z","modo":"minimo"},
              {"op":"crear","tipo":"CAJA","alias":"labio","nombre":"Labio",
               "parametros":{"anchura":90,"altura":14,"profundidad":12}},
              {"op":"colocar","objetivo":"labio","referencia":"base","cara":"arriba","centrar":true},
              {"op":"alinear","objetivo":"labio","referencia":"base","eje":"Z","modo":"maximo"}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Soporte de auriculares",
            peticion = "quiero un soporte de escritorio para colgar mis auriculares",
            etiquetas = listOf("soporte", "auriculares", "cascos", "apoyo", "escritorio"),
            plan = """
            {"resumen":"Soporte de auriculares con copa ensanchada y base pesada",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CILINDRO","alias":"base","nombre":"Base",
               "parametros":{"radio":45,"altura":8}},
              {"op":"crear","tipo":"CONO","alias":"copa","nombre":"Copa",
               "parametros":{"radioInferior":8,"radioSuperior":38,"altura":70}},
              {"op":"colocar","objetivo":"copa","referencia":"base","cara":"arriba","centrar":true}
            ]}
            """,
        ),

        // ------------------------------------------------------------ contenedores
        Ejemplar(
            nombre = "Caja con tapa",
            peticion = "necesito una caja con tapa para guardar tornillos",
            etiquetas = listOf("caja", "tapa", "contenedor", "guardar", "cofre"),
            plan = """
            {"resumen":"Caja hueca con tapa que apoya en el borde",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"DIFERENCIA","alias":"cuerpo","nombre":"Cuerpo"},
              {"op":"crear","tipo":"CAJA","alias":"exterior","padre":"cuerpo","nombre":"Exterior",
               "parametros":{"anchura":60,"altura":30,"profundidad":45}},
              {"op":"crear","tipo":"CAJA","alias":"hueco","padre":"cuerpo","nombre":"Hueco",
               "parametros":{"anchura":54,"altura":28,"profundidad":39}},
              {"op":"mover","objetivo":"hueco","y":2},
              {"op":"crear","tipo":"CAJA","alias":"tapa","nombre":"Tapa",
               "parametros":{"anchura":62,"altura":6,"profundidad":47}},
              {"op":"colocar","objetivo":"tapa","referencia":"cuerpo","cara":"arriba","centrar":true}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Organizador de escritorio",
            peticion = "un organizador de escritorio con divisiones para separar rotuladores y clips",
            etiquetas = listOf("organizador", "divisiones", "escritorio", "bandeja", "separadores"),
            plan = """
            {"resumen":"Bandeja hueca con dos divisores que llegan al borde",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"DIFERENCIA","alias":"cuerpo","nombre":"Cuerpo"},
              {"op":"crear","tipo":"CAJA","alias":"exterior","padre":"cuerpo","nombre":"Exterior",
               "parametros":{"anchura":120,"altura":40,"profundidad":70}},
              {"op":"crear","tipo":"CAJA","alias":"hueco","padre":"cuerpo","nombre":"Hueco",
               "parametros":{"anchura":112,"altura":36,"profundidad":62}},
              {"op":"mover","objetivo":"hueco","y":4},
              {"op":"crear","tipo":"CAJA","alias":"divisor1","nombre":"Divisor 1",
               "parametros":{"anchura":110,"altura":40,"profundidad":3}},
              {"op":"mover","objetivo":"divisor1","z":-10,"absoluto":true},
              {"op":"duplicar","objetivo":"divisor1","alias":"divisor2"},
              {"op":"mover","objetivo":"divisor2","z":20}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Portalápices",
            peticion = "hazme un portalápices cilíndrico para el escritorio",
            etiquetas = listOf("portalapices", "organizador", "bote", "escritorio", "vaso"),
            plan = """
            {"resumen":"Vaso hueco con floor y boca abierta",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"DIFERENCIA","alias":"vaso","nombre":"Portalápices"},
              {"op":"crear","tipo":"CILINDRO","alias":"exterior","padre":"vaso","nombre":"Exterior",
               "parametros":{"radio":35,"altura":100}},
              {"op":"crear","tipo":"CILINDRO","alias":"interior","padre":"vaso","nombre":"Interior",
               "parametros":{"radio":31,"altura":96}},
              {"op":"mover","objetivo":"interior","y":6}
            ]}
            """,
        ),

        // ------------------------------------------------------------ normalizados
        //
        // Estos tres existen para que `patron` se use. Un modelo no echa mano de una
        // operación que solo ha visto nombrada en una lista: la usa cuando la ha visto
        // aplicada. Y aquí no da igual, porque son justo las piezas donde «parecerse»
        // no sirve de nada: o los agujeros caen en la norma o la pieza no entra.
        Ejemplar(
            nombre = "Oreja de rack",
            peticion = "unas orejas para montar algo en mi rack de 19 pulgadas",
            etiquetas = listOf("rack", "oreja", "19", "servidor", "armario", "montaje", "1u", "bastidor"),
            plan = """
            {"resumen":"Oreja de 1U con los tres agujeros M6 a la norma EIA-310",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"oreja","nombre":"Oreja",
               "parametros":{"anchura":30,"altura":43.66,"profundidad":4,"redondeo":2},
               "nota":"43,66 es 1U menos la holgura para no rozar con el panel de arriba"},
              {"op":"patron","objetivo":"oreja","estandar":"RACK_19_OREJA","unidades":1,"eje":"Z"},
              {"op":"asentar"}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Adaptador VESA",
            peticion = "un adaptador para colgar un monitor con agujeros VESA de 100",
            etiquetas = listOf("vesa", "monitor", "pantalla", "adaptador", "colgar", "soporte", "brazo"),
            plan = """
            {"resumen":"Placa VESA 100 con los cuatro agujeros M4 a la norma",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"placa","nombre":"Placa",
               "parametros":{"anchura":130,"altura":6,"profundidad":130,"redondeo":4}},
              {"op":"patron","objetivo":"placa","estandar":"VESA_100"},
              {"op":"asentar"}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Base para Raspberry Pi",
            peticion = "una base para atornillar mi Raspberry Pi 5",
            etiquetas = listOf("raspberry", "pi", "placa", "base", "carcasa", "soporte", "electronica"),
            plan = """
            {"resumen":"Base del tamaño de la placa con sus cuatro agujeros M2.5",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"base","nombre":"Base",
               "parametros":{"anchura":85,"altura":4,"profundidad":56,"redondeo":3},
               "nota":"85x56 es la placa: el patrón se mide respecto a ese tamaño"},
              {"op":"patron","objetivo":"base","estandar":"RASPBERRY_PI","ajuste":"ROSCA"},
              {"op":"asentar"}
            ]}
            """,
        ),

        // ------------------------------------------------------------ orgánicas
        Ejemplar(
            nombre = "Figura de animal",
            peticion = "una figurita de un animal de cuatro patas para imprimir",
            etiquetas = listOf(
                "animal", "figura", "muñeco", "dinosaurio", "perro", "gato",
                "organico", "escultura", "juguete", "bicho",
            ),
            plan = """
            {"resumen":"Cuerpo, cuello, cabeza, cola y cuatro patas fundidos en una sola masa",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"UNION","alias":"bicho","nombre":"Figura",
               "parametros":{"fusion":7}},
              {"op":"crear","tipo":"CAPSULA","alias":"cuerpo","padre":"bicho","nombre":"Cuerpo",
               "parametros":{"radio":14,"altura":46}},
              {"op":"girar","objetivo":"cuerpo","z":90},
              {"op":"mover","objetivo":"cuerpo","y":34,"absoluto":true},
              {"op":"crear","tipo":"CAPSULA","alias":"cuello","padre":"bicho","nombre":"Cuello",
               "parametros":{"radio":8,"altura":30}},
              {"op":"girar","objetivo":"cuello","z":35},
              {"op":"mover","objetivo":"cuello","x":26,"y":50,"absoluto":true},
              {"op":"crear","tipo":"ESFERA","alias":"cabeza","padre":"bicho","nombre":"Cabeza",
               "parametros":{"radio":11}},
              {"op":"mover","objetivo":"cabeza","x":38,"y":62,"absoluto":true},
              {"op":"crear","tipo":"CAPSULA","alias":"cola","padre":"bicho","nombre":"Cola",
               "parametros":{"radio":6,"altura":34}},
              {"op":"girar","objetivo":"cola","z":115},
              {"op":"mover","objetivo":"cola","x":-32,"y":40,"absoluto":true},
              {"op":"crear","tipo":"CAPSULA","alias":"pata1","padre":"bicho","nombre":"Pata",
               "parametros":{"radio":5,"altura":30}},
              {"op":"mover","objetivo":"pata1","x":14,"y":15,"z":10,"absoluto":true},
              {"op":"duplicar","objetivo":"pata1","alias":"pata2"},
              {"op":"mover","objetivo":"pata2","x":14,"y":15,"z":-10,"absoluto":true},
              {"op":"duplicar","objetivo":"pata1","alias":"pata3"},
              {"op":"mover","objetivo":"pata3","x":-14,"y":15,"z":10,"absoluto":true},
              {"op":"duplicar","objetivo":"pata1","alias":"pata4"},
              {"op":"mover","objetivo":"pata4","x":-14,"y":15,"z":-10,"absoluto":true},
              {"op":"asentar"}
            ]}
            """,
        ),

        // ------------------------------------------------------------ barridos
        Ejemplar(
            nombre = "Asa en U",
            peticion = "un asa para atornillar a un cajón",
            etiquetas = listOf("asa", "tirador", "handle", "agarre", "tubo", "doblado"),
            plan = """
            {"resumen":"Asa en U de tubo de 5 mm con dos patillas",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"BARRIDO","alias":"asa","nombre":"Asa",
               "parametros":{"radio":5,"cerrado":0}},
              {"op":"perfil","objetivo":"asa","forma":"LIBRE",
               "puntos":[[0,0],[0,25],[90,25],[90,0]]},
              {"op":"asentar"}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Marco rectangular",
            peticion = "un marco rectangular de tubo para colgar una tela",
            etiquetas = listOf("marco", "frame", "aro", "bastidor", "cerrado", "perimetro"),
            plan = """
            {"resumen":"Marco cerrado de 160x110 con tubo de 4 mm",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"BARRIDO","alias":"marco","nombre":"Marco",
               "parametros":{"radio":4,"cerrado":1}},
              {"op":"perfil","objetivo":"marco","forma":"LIBRE",
               "puntos":[[0,0],[160,0],[160,110],[0,110]]},
              {"op":"asentar"}
            ]}
            """,
        ),

        // ------------------------------------------------------------ estructural / montaje
        Ejemplar(
            nombre = "Escuadra de montaje",
            peticion = "una escuadra de montaje en L con dos agujeros para atornillar a la pared",
            etiquetas = listOf("escuadra", "montaje", "bracket", "refuerzo", "angular"),
            plan = """
            {"resumen":"Escuadra en L extruida con un taladro en cada ala",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"EXTRUSION","alias":"escuadra","nombre":"Escuadra",
               "parametros":{"altura":6}},
              {"op":"perfil","objetivo":"escuadra","forma":"ELE",
               "parametros":{"anchoPerfil":50,"altoPerfil":50,"grosorPerfil":10}},
              {"op":"taladro","objetivo":"escuadra","designacion":"M4","desplazamiento":[10,-20]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M4","desplazamiento":[-20,10]}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Brida circular con agujeros",
            peticion = "una brida circular con agujeros para atornillar a un motor",
            etiquetas = listOf("brida", "flange", "agujeros", "motor", "circular"),
            plan = """
            {"resumen":"Brida con taladro central y cuatro agujeros de sujeción",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CILINDRO","alias":"brida","nombre":"Brida",
               "parametros":{"radio":35,"altura":6}},
              {"op":"taladro","objetivo":"brida","diametro":20},
              {"op":"taladro","objetivo":"seleccion","designacion":"M5","desplazamiento":[22,0]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M5","desplazamiento":[-22,0]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M5","desplazamiento":[0,22]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M5","desplazamiento":[0,-22]}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Separador roscado",
            peticion = "necesito un casquillo separador con un agujero pasante para un tornillo M3",
            etiquetas = listOf("separador", "casquillo", "espaciador", "standoff", "tubo"),
            plan = """
            {"resumen":"Casquillo cilíndrico con taladro pasante M3",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CILINDRO","alias":"exterior","nombre":"Casquillo",
               "parametros":{"radio":6,"altura":15}},
              {"op":"taladro","objetivo":"exterior","designacion":"M3"}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Placa con insertos roscados",
            peticion = "una placa plana con cuatro agujeros roscados M4 en las esquinas",
            etiquetas = listOf("placa", "roscado", "agujeros", "insertos", "tornillos"),
            plan = """
            {"resumen":"Placa con cuatro agujeros para roscar M4",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"placa","nombre":"Placa",
               "parametros":{"anchura":60,"altura":8,"profundidad":40}},
              {"op":"taladro","objetivo":"placa","designacion":"M4","ajuste":"ROSCA","desplazamiento":[20,12]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M4","ajuste":"ROSCA","desplazamiento":[-20,12]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M4","ajuste":"ROSCA","desplazamiento":[20,-12]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M4","ajuste":"ROSCA","desplazamiento":[-20,-12]}
            ]}
            """,
        ),

        // ------------------------------------------------------------ torneadas / adaptadores
        Ejemplar(
            nombre = "Pomo torneado",
            peticion = "un pomo redondeado para un cajón, torneado, con su vástago",
            etiquetas = listOf("pomo", "tirador", "revolucion", "torneado", "cajon"),
            plan = """
            {"resumen":"Pomo por revolución con vástago de montaje",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"REVOLUCION","alias":"pomo","nombre":"Pomo"},
              {"op":"perfil","objetivo":"pomo","forma":"RECTANGULO",
               "parametros":{"anchoPerfil":24,"altoPerfil":22,"radioEsquina":8}},
              {"op":"crear","tipo":"CILINDRO","alias":"vastago","nombre":"Vástago",
               "parametros":{"radio":4,"altura":10}},
              {"op":"colocar","objetivo":"vastago","referencia":"pomo","cara":"abajo","centrar":true}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Adaptador de diámetros",
            peticion = "un adaptador para reducir de un tubo de 30 mm a uno de 20 mm",
            etiquetas = listOf("adaptador", "reductor", "tubo", "manguera", "diametros"),
            plan = """
            {"resumen":"Adaptador troncocónico hueco entre dos diámetros",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"DIFERENCIA","alias":"cuerpo","nombre":"Adaptador"},
              {"op":"crear","tipo":"CONO","alias":"exterior","padre":"cuerpo","nombre":"Exterior",
               "parametros":{"radioInferior":15,"radioSuperior":10,"altura":30}},
              {"op":"crear","tipo":"CONO","alias":"interior","padre":"cuerpo","nombre":"Interior",
               "parametros":{"radioInferior":12,"radioSuperior":7,"altura":40}}
            ]}
            """,
        ),

        // ------------------------------------------------------------ herrajes pequeños
        Ejemplar(
            nombre = "Gancho de pared",
            peticion = "un gancho sencillo para colgar en la pared, con dos tornillos",
            etiquetas = listOf("gancho", "colgador", "pared", "percha", "hook"),
            plan = """
            {"resumen":"Placa de pared con clavija cónica y dos taladros",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"placa","nombre":"Placa",
               "parametros":{"anchura":45,"altura":6,"profundidad":35}},
              {"op":"taladro","objetivo":"placa","designacion":"M4","desplazamiento":[0,12]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M4","desplazamiento":[0,-12]},
              {"op":"crear","tipo":"CONO","alias":"clavija","nombre":"Clavija",
               "parametros":{"radioInferior":8,"radioSuperior":3,"altura":35}},
              {"op":"colocar","objetivo":"clavija","referencia":"placa","cara":"arriba","centrar":true}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Tope de cajón",
            peticion = "un tope pequeño para atornillar al suelo del armario y que no se cierre del todo el cajón",
            etiquetas = listOf("tope", "cajon", "puerta", "bloqueo", "stop"),
            plan = """
            {"resumen":"Bloque de tope con un taladro central",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"CAJA","alias":"tope","nombre":"Tope",
               "parametros":{"anchura":30,"altura":18,"profundidad":22,"redondeo":4}},
              {"op":"taladro","objetivo":"tope","designacion":"M4"}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Clip sujetacables",
            peticion = "un clip para sujetar un cable a un panel, tipo abrazadera abierta",
            etiquetas = listOf("clip", "sujetacables", "abrazadera", "cable", "pinza"),
            plan = """
            {"resumen":"Anillo abierto sobre una base con taladro de montaje",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"DIFERENCIA","alias":"anillo","nombre":"Anillo"},
              {"op":"crear","tipo":"CILINDRO","alias":"exterior","padre":"anillo","nombre":"Exterior",
               "parametros":{"radio":10,"altura":8}},
              {"op":"crear","tipo":"CILINDRO","alias":"interior","padre":"anillo","nombre":"Interior",
               "parametros":{"radio":6,"altura":12}},
              {"op":"crear","tipo":"CAJA","alias":"corte","padre":"anillo","nombre":"Corte",
               "parametros":{"anchura":6,"altura":12,"profundidad":8}},
              {"op":"mover","objetivo":"corte","z":10,"absoluto":true},
              {"op":"crear","tipo":"CAJA","alias":"base","nombre":"Base",
               "parametros":{"anchura":20,"altura":6,"profundidad":14}},
              {"op":"colocar","objetivo":"base","referencia":"anillo","cara":"abajo","centrar":true},
              {"op":"taladro","objetivo":"base","designacion":"M3"}
            ]}
            """,
        ),
        Ejemplar(
            nombre = "Tirador de cajón",
            peticion = "un tirador ovalado para un cajón, plano, con dos agujeros para atornillar",
            etiquetas = listOf("tirador", "asa", "cajon", "manija", "handle"),
            plan = """
            {"resumen":"Asa ovalada plana con dos taladros de montaje",
             "reemplazar":true,"operaciones":[
              {"op":"crear","tipo":"DIFERENCIA","alias":"cuerpo","nombre":"Cuerpo"},
              {"op":"crear","tipo":"EXTRUSION","alias":"exterior","padre":"cuerpo","nombre":"Exterior",
               "parametros":{"altura":8}},
              {"op":"perfil","objetivo":"exterior","forma":"RANURA","parametros":{"largoPerfil":70,"anchoPerfil":20}},
              {"op":"crear","tipo":"EXTRUSION","alias":"interior","padre":"cuerpo","nombre":"Interior",
               "parametros":{"altura":14}},
              {"op":"perfil","objetivo":"interior","forma":"RANURA","parametros":{"largoPerfil":50,"anchoPerfil":8}},
              {"op":"taladro","objetivo":"cuerpo","designacion":"M4","desplazamiento":[30,0]},
              {"op":"taladro","objetivo":"seleccion","designacion":"M4","desplazamiento":[-30,0]}
            ]}
            """,
        ),
    )
}
