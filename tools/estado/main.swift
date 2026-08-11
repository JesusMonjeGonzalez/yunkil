// Arnés de la aplicación: conduce `EstadoDeLaApp` como lo haría un usuario.
//
// Hasta aquí lo comprobado era el núcleo —donde vive toda la geometría— y la aritmética de
// cámara de los arneses de paridad, rayo y gizmo. En medio quedaba la capa que decide *qué*
// se le pide al núcleo y *cuándo*: qué se selecciona, qué gesto abre un punto de deshacer,
// cuándo se ofrece un asa y cuándo no. Son unas dos mil líneas de Swift y no tenían ni una
// prueba, así que un cambio de refactor podía dejar el gizmo mudo o el deshacer partido en
// veinte pasos sin que nada se pusiera rojo.
//
// No se abre ventana: `EstadoDeLaApp` no la necesita para existir. Sin renderizador, la
// cámara no está, así que aquí se comprueba todo lo que no depende de píxeles —que es donde
// están las reglas— y los píxeles siguen siendo cosa de los otros arneses.
//
//     swiftc -O -D PRUEBAS tools/estado/main.swift apps/mac/Sources/*.swift \
//         -F <frameworks> -framework YunkilCore -o build/estado-arnes
//     ./build/estado-arnes

import Foundation
import YunkilCore
import simd

var fallos = 0

func comprobar(_ condicion: Bool, _ que: String) {
    if condicion { print("  ok  \(que)") } else { print("FALLO  \(que)"); fallos += 1 }
}

func casi(_ a: Float, _ b: Float, _ tolerancia: Float = 1e-3) -> Bool { abs(a - b) <= tolerancia }

@MainActor
func conUnaCaja() -> (EstadoDeLaApp, String) {
    let estado = EstadoDeLaApp()
    estado.anadir("CAJA")
    return (estado, estado.seleccion)
}

/// Un documento con una sola caja pequeña: el examen malla a resolución de boquilla, así
/// que medir el ejemplo de partida entero costaría más que todo el resto del arnés junto.
@MainActor
func piezaPequena() -> EstadoDeLaApp {
    let estado = EstadoDeLaApp()
    estado.cargarEjemplo("vacio")
    estado.anadir("CAJA")
    for (clave, valor) in [("anchura", Float(14)), ("altura", 10), ("profundidad", 12)] {
        estado.fijarParametro(clave, valor)
    }
    return estado
}

@MainActor
func arnes() {

    // ------------------------------------------------------------ selección y árbol

    print("— el árbol y la selección —")
    do {
        // La aplicación arranca con un documento de ejemplo, así que todo se cuenta
        // relativo: fijar el número de filas de partida sería fijar el ejemplo.
        let estado = EstadoDeLaApp()
        let departida = estado.filas.count
        estado.anadir("CAJA")

        comprobar(!estado.seleccion.isEmpty, "añadir deja la pieza seleccionada")
        comprobar(estado.filas.count == departida + 1, "y en el árbol (\(estado.filas.count) filas)")
        comprobar(estado.tipoSeleccionado == "CAJA", "el inspector sabe qué tipo es")

        estado.duplicar()
        comprobar(estado.filas.count == departida + 2, "duplicar añade una hermana")
        estado.eliminar()
        comprobar(estado.filas.count == departida + 1, "y eliminar la quita")
    }

    // ------------------------------------------------------------ el gizmo

    print("\n— cuándo sale el gizmo —")
    do {
        let estado = EstadoDeLaApp()
        estado.seleccionar(estado.raizId)
        comprobar(estado.centroDelGizmo == nil, "con la raíz seleccionada no hay gizmo")

        estado.anadir("CAJA")
        comprobar(estado.centroDelGizmo != nil, "con una pieza seleccionada sí")

        estado.seleccionar(estado.raizId)
        comprobar(estado.centroDelGizmo == nil, "volver a la raíz lo quita: mover el documento no significa nada")
    }

    do {
        let (estado, _) = conUnaCaja()
        estado.modoBrochaOrganica = "ALISAR"
        comprobar(
            estado.centroDelGizmo == nil,
            "con una brocha activa el gizmo se aparta: el clic es del pincel"
        )
        estado.modoBrochaOrganica = "NINGUNA"
        comprobar(estado.centroDelGizmo != nil, "y vuelve al soltar la brocha")
    }

    print("\n— arrastrar el gizmo —")
    do {
        let (estado, _) = conUnaCaja()
        let centro = estado.centroDelGizmo!

        estado.empezarGestoDelGizmo()
        for _ in 0..<10 { estado.moverConGizmo(eje: SIMD3<Float>(1, 0, 0), milimetros: 1.2) }

        comprobar(casi(estado.posX, 12), "diez fotogramas de 1,2 mm dejan la pieza en 12 (\(estado.posX))")
        comprobar(
            casi(estado.centroDelGizmo!.x - centro.x, 12),
            "y el gizmo se ha ido con ella"
        )
        comprobar(casi(estado.posY, 0) && casi(estado.posZ, 0), "sin arrastrarla en los otros dos ejes")

        // Lo que de verdad decide si el gesto se siente bien: un arrastre es **un** ⌘Z.
        estado.deshacer()
        comprobar(casi(estado.posX, 0), "un solo deshacer devuelve el arrastre entero (\(estado.posX))")
        estado.rehacer()
        comprobar(casi(estado.posX, 12), "y rehacer lo trae de vuelta")
    }

    do {
        let (estado, _) = conUnaCaja()
        estado.empezarGestoDelGizmo()
        estado.moverConGizmo(eje: SIMD3<Float>(1, 0, 0), milimetros: 40)
        let centro = estado.centroDelGizmo!

        estado.empezarGestoDelGizmo()
        estado.girarConGizmo(eje: SIMD3<Float>(0, 1, 0), grados: 90)

        comprobar(casi(estado.giroY, 90, 0.1), "girar 90° escribe el giro en el inspector (\(estado.giroY))")
        let despues = estado.centroDelGizmo!
        comprobar(
            casi(despues.x, centro.x, 0.05) && casi(despues.z, centro.z, 0.05),
            "y la pieza gira sobre su sitio, no describiendo un arco (\(centro) → \(despues))"
        )
    }

    do {
        // Una dirección que no apunta a ninguna parte no puede mover nada. Llega del
        // gizmo solo si algo va mal, y lo que no puede hacer es dejar la pieza en NaN.
        let (estado, _) = conUnaCaja()
        estado.empezarGestoDelGizmo()
        estado.moverConGizmo(eje: SIMD3<Float>(0, 0, 0), milimetros: 10)
        comprobar(casi(estado.posX, 0) && casi(estado.posY, 0) && casi(estado.posZ, 0), "un eje nulo no mueve la pieza")
        comprobar(estado.aviso != nil, "y se dice por qué")
    }

    // ------------------------------------------------------------ el inspector

    print("\n— el inspector y el documento no discrepan —")
    do {
        let (estado, caja) = conUnaCaja()
        estado.posX = 7
        estado.posY = -3
        estado.giroZ = 45
        estado.aplicarTransform()

        // Releer del documento tiene que devolver lo escrito: si el inspector y el
        // documento se separan, el usuario edita una copia que no existe.
        estado.seleccionar(estado.raizId)
        estado.seleccionar(caja)
        comprobar(casi(estado.posX, 7) && casi(estado.posY, -3), "la posición vuelve del documento")
        comprobar(casi(estado.giroZ, 45), "y el giro también")
    }

    do {
        let (estado, _) = conUnaCaja()
        estado.fijarParametro("anchura", 62)
        comprobar(
            estado.parametros.first { $0.clave == "anchura" }.map { casi($0.valor, 62) } ?? false,
            "cambiar una cota se ve en el inspector"
        )
        comprobar(!estado.recompiloElUltimoCambio, "y no recompila el shader: es un buffer de uniforms")
    }

    // ------------------------------------------------------------ importar

    print("\n— traer geometría de fuera —")
    do {
        let estado = EstadoDeLaApp()
        let departida = estado.filas.count
        let basura = NSTemporaryDirectory() + "yunkil-arnes-no-es-un-stl.stl"
        try? "esto no es un STL".write(toFile: basura, atomically: true, encoding: .utf8)
        defer { try? FileManager.default.removeItem(atPath: basura) }

        estado.importarMallaDesde(ruta: basura)
        comprobar(estado.importandoMalla, "hornear arranca en otro hilo y la ventana sigue viva")

        esperarA({ !estado.importandoMalla }, "el horneado termina")
        comprobar(estado.filas.count == departida, "un archivo que no es un STL no mete pieza en el árbol")
        comprobar((estado.aviso ?? "").contains("STL"), "y el motivo lo dice: \(estado.aviso ?? "sin aviso")")
    }

    print("\n— abrir un proyecto con mallas —")
    do {
        // El caso entero y con archivos de verdad: se exporta un STL, se importa, se guarda
        // el proyecto y se vuelve a abrir. Los campos horneados no viajan dentro del
        // `.yunkil` —son megas—, así que al abrir hay que rasterizarlos otra vez, y eso es
        // lo que congelaba la ventana.
        let carpeta = NSTemporaryDirectory() + "yunkil-arnes-abrir/"
        try? FileManager.default.createDirectory(atPath: carpeta, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(atPath: carpeta) }

        let stl = carpeta + "pieza.stl"
        let proyecto = URL(fileURLWithPath: carpeta + "proyecto.yunkil")

        let estado = EstadoDeLaApp()
        comprobar(estado.editor.exportarStl(ruta: stl, resolucion: 0.8, alAvanzar: nil) != nil, "sale un STL de partida")

        estado.importarMallaDesde(ruta: stl)
        esperarA({ !estado.importandoMalla }, "la malla de partida se hornea")
        comprobar(estado.filas.contains { $0.tipo == "MALLA" }, "y entra en el árbol")

        try? estado.editor.aJson().write(to: proyecto, atomically: true, encoding: .utf8)

        let reabierto = EstadoDeLaApp()
        reabierto.abrirDesde(proyecto)
        comprobar(reabierto.filas.contains { $0.tipo == "MALLA" }, "el proyecto se abre con su malla en el árbol")
        comprobar(reabierto.importandoMalla, "y el horneado arranca fuera del hilo: la ventana no se queda muerta")

        esperarA({ !reabierto.importandoMalla }, "los campos acaban de caer")
        comprobar(!reabierto.editor.estaVacio, "y la pieza importada vuelve a tener material que dibujar")
    }

    print("\n— un informe no puede ser de otra pieza —")
    do {
        // Sin esperar un examen entero a propósito: medir una caja de 14 mm cuesta nueve
        // segundos con el núcleo en release y treinta y cinco en depuración, y este guion se
        // lanza a cada rato. Lo que puede romperse al refactorizar es **la decisión** de
        // tirar un informe caducado, así que se comprueba esa, con el mismo predicado que
        // usa la tarea de verdad.
        let (estado, _) = conUnaCaja()
        let copia = estado.editor.aJson()
        let perfil = estado.perfilDeFabricacion

        comprobar(
            estado.elInformeSigueValiendo(documento: copia, perfil: perfil),
            "sin tocar nada, lo medido sigue siendo lo que hay delante"
        )

        estado.fijarParametro("anchura", 71)
        comprobar(
            !estado.elInformeSigueValiendo(documento: copia, perfil: perfil),
            "mover una cota mientras se examina invalida el informe que venía"
        )

        let otro = estado.perfilesDisponibles.first { $0 != perfil }!
        let despues = estado.editor.aJson()
        estado.fijarPerfil(otro)
        comprobar(
            !estado.elInformeSigueValiendo(documento: despues, perfil: perfil),
            "y cambiar de impresora también: el informe cita umbrales de la anterior"
        )
    }

    do {
        // Y que la tarea arranca de verdad fuera del hilo, que es la otra mitad.
        let (estado, _) = conUnaCaja()
        estado.analizarFabricacion()
        comprobar(estado.analizando, "el examen arranca en otro hilo")
    }

    // ------------------------------------------------------------ calibrar

    print("\n— calibrar la máquina —")
    do {
        // A una carpeta temporal: un arnés que escribiera en Application Support borraría
        // la calibración de quien lo ejecute.
        let almacen = NSTemporaryDirectory() + "yunkil-arnes-perfiles.json"
        try? FileManager.default.removeItem(atPath: almacen)
        EstadoDeLaApp.rutaDePerfiles = almacen
        CatalogoDePerfiles.shared.vaciar()
        defer {
            CatalogoDePerfiles.shared.vaciar()
            try? FileManager.default.removeItem(atPath: almacen)
        }

        let estado = EstadoDeLaApp()
        let deFabrica = estado.perfilDeFabricacion
        let cuantos = estado.perfilesDisponibles.count

        let estaciones = estado.estacionesDelCupon
        comprobar(estaciones.count == 8, "el cupón ofrece ocho estaciones (\(estaciones.count))")
        comprobar(
            estaciones.first.map { $0.diametro > 8 } ?? false,
            "y cada una es más ancha que el pasador de 8 mm"
        )

        estado.generarCuponDeCalibracion()
        comprobar(!estado.editor.estaVacio, "el cupón entra en el documento")

        // La estación 5, digamos, es la que se tragó el pasador.
        estado.guardarCalibracion(estacion: 4, nombre: "Arnés · mi máquina")
        comprobar(estado.perfilDeFabricacion == "Arnés · mi máquina", "el perfil calibrado queda activo")
        comprobar(estado.perfilActivoEsPropio, "y consta como propio")
        comprobar(
            casi(estado.editor.holguraDelPerfil(), estaciones[4].holgura),
            "con la holgura de la estación elegida (\(estado.editor.holguraDelPerfil()) vs \(estaciones[4].holgura))"
        )
        comprobar(estado.perfilesDisponibles.count == cuantos + 1, "y sale en la lista de perfiles")

        // Lo que estaba roto de raíz: el examen corre en un editor aparte que solo recibe
        // el **nombre** del perfil. Si el nombre no se resuelve, se analiza con el de
        // fábrica y nadie se entera.
        comprobar(
            PerfilFabricacion.companion.porNombre(nombre: "Arnés · mi máquina") != nil,
            "cualquier editor puede resolver el perfil por su nombre"
        )

        // Y sigue ahí al volver a abrir.
        let reabierta = EstadoDeLaApp()
        comprobar(
            reabierta.perfilesDisponibles.contains("Arnés · mi máquina"),
            "al abrir otra vez la aplicación el perfil calibrado sigue estando"
        )

        estado.olvidarPerfil("Arnés · mi máquina")
        comprobar(
            estado.perfilDeFabricacion == deFabrica,
            "olvidarlo devuelve al perfil del que salió, no al primero de la lista (\(estado.perfilDeFabricacion))"
        )
        comprobar(estado.perfilesDisponibles.count == cuantos, "y lo quita de la lista")
    }

    print("\n— cambiar de impresora mueve las cotas —")
    do {
        let estado = EstadoDeLaApp()
        let antes = estado.editor.holguraDelPerfil()
        let otro = estado.perfilesDisponibles.first { $0 != estado.perfilDeFabricacion }!

        estado.fijarPerfil(otro)

        comprobar(estado.editor.perfilDeTrabajo() == otro, "elegir en el desplegable cambia el perfil del editor")
        comprobar(
            estado.editor.holguraDelPerfil() != antes,
            "y con él la holgura con la que se derivan los encajes (\(antes) → \(estado.editor.holguraDelPerfil()))"
        )
    }

    print("")
    if fallos == 0 {
        print("Estado de la aplicación: todo correcto.")
        exit(0)
    } else {
        print("Estado de la aplicación: \(fallos) comprobaciones fallaron")
        exit(1)
    }
}

/// Espera a que se cumpla algo que termina en el hilo principal, moviendo el `RunLoop`.
///
/// Hace falta porque lo que se está comprobando es justo que el trabajo caro **no** ocurre
/// aquí: la tarea de horneado vuelve por `MainActor.run`, y sin nadie que atienda el hilo
/// principal esa vuelta no llegaría nunca.
@MainActor
func esperarA(_ condicion: () -> Bool, _ que: String, segundos: TimeInterval = 20) {
    let limite = Date().addingTimeInterval(segundos)
    while !condicion(), Date() < limite {
        RunLoop.main.run(until: Date().addingTimeInterval(0.02))
    }
    comprobar(condicion(), que)
}

MainActor.assumeIsolated { arnes() }
