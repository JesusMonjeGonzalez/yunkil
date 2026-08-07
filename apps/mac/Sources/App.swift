import AppKit
import MetalKit
import SwiftUI
import UniformTypeIdentifiers
@preconcurrency import YunkilCore

// MARK: - Vista Metal con gestos

/// `MTKView` que además atiende ratón y trackpad.
///
/// Los eventos se manejan aquí y no en SwiftUI porque orbitar necesita el delta
/// crudo del dispositivo: pasarlo por un `DragGesture` pierde precisión y añade
/// latencia perceptible al arrastrar.
final class VistaMetalInteractiva: MTKView {

    var renderizador: Renderizador?

    /// Se avisa con el rayo que sale del cursor cuando el clic no fue un arrastre.
    var alPinchar: ((SIMD3<Float>, SIMD3<Float>) -> Void)?

    /// Empieza a tirar de una cara: se le pasa el rayo y decide el núcleo.
    /// Devuelve la normal de la cara y la distancia al impacto, o `nil` si no dio en nada.
    var alEmpezarAEmpujar: ((SIMD3<Float>, SIMD3<Float>) -> (normal: SIMD3<Float>, distancia: Float)?)?

    /// Cada fotograma del arrastre, en milímetros del mundo.
    var alEmpujar: ((Float) -> Void)?

    /// Agarra el plano de sección para arrastrarlo: devuelve su normal y la distancia
    /// al cruce del rayo, o `nil` si el clic no cae en el rectángulo visible.
    var alEmpezarAMoverPlano: ((SIMD3<Float>, SIMD3<Float>) -> (normal: SIMD3<Float>, distancia: Float)?)?
    /// Cada fotograma del arrastre del plano, en milímetros a lo largo de su normal.
    var alMoverPlano: ((Float) -> Void)?

    /// Un arrastre orbita, un clic señala. Se distinguen por recorrido y no por
    /// tiempo: soltar el ratón un poco más tarde no debe cambiar lo que hace.
    private var recorridoDelArrastre: CGFloat = 0

    /// Cara que se está empujando, mientras dure el arrastre.
    private var caraEnArrastre: (normal: SIMD3<Float>, distancia: Float)?

    /// Plano de sección que se está arrastrando, mientras dure el gesto.
    private var planoEnArrastre: (normal: SIMD3<Float>, distancia: Float)?

    override var acceptsFirstResponder: Bool { true }

    /// Se avisa con la ruta de un STL soltado sobre la vista.
    var alSoltarStl: ((String) -> Void)?

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        return rutaDeStl(en: sender) != nil ? .copy : []
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        guard let alSoltarStl, let ruta = rutaDeStl(en: sender) else { return false }
        alSoltarStl(ruta)
        return true
    }

    /// La ruta del primer `.stl` que trae el arrastre, o `nil` si no viene ninguno.
    ///
    /// Se mira la extensión del archivo y no el UTI que declara el portapapeles: la
    /// mayoría de los navegadores y gestores de archivos entregan un STL como
    /// `public.data` genérico, y filtrar por UTI dejaría fuera casi todos los casos
    /// reales. La comprobación anterior preguntaba por el tipo del portapapeles
    /// convirtiéndolo a `String`, y esa conversión nunca puede tener éxito —
    /// `PasteboardType` es una estructura, no un `NSString` puenteado—, así que la
    /// vista rechazaba en silencio todo lo que se soltara encima.
    private func rutaDeStl(en sender: NSDraggingInfo) -> String? {
        let opciones: [NSPasteboard.ReadingOptionKey: Any] = [.urlReadingFileURLsOnly: true]
        let urls = sender.draggingPasteboard.readObjects(
            forClasses: [NSURL.self], options: opciones
        ) as? [URL] ?? []
        return urls.first { $0.pathExtension.lowercased() == "stl" }?.path
    }

    override func mouseDown(with evento: NSEvent) {
        recorridoDelArrastre = 0
        caraEnArrastre = nil
        planoEnArrastre = nil

        // El plano de sección se agarra sin modificador: es el gizmo del punto 1,
        // el mismo patrón de «grab the surface» con el que se empujan las caras.
        // Si el clic cae en el rectángulo visible del plano, se arrastra el plano.
        if let alEmpezarAMoverPlano,
           let rayo = rayoDelCursor(evento),
           let agarre = alEmpezarAMoverPlano(rayo.origen, rayo.direccion) {
            planoEnArrastre = agarre
            return
        }

        // ⌘ y arrastrar tira de la cara que hay debajo del cursor. No hay flechas que
        // dibujar: se agarra la propia superficie, que es como se siente en Plasticity y
        // lo único posible mientras el renderizador no tenga tubería de vértices.
        guard evento.modifierFlags.contains(.command),
              let alEmpezarAEmpujar,
              let rayo = rayoDelCursor(evento) else { return }
        caraEnArrastre = alEmpezarAEmpujar(rayo.origen, rayo.direccion)
    }

    override func mouseUp(with evento: NSEvent) {
        caraEnArrastre = nil
        planoEnArrastre = nil
        guard recorridoDelArrastre < 3 else { return }
        guard let alPinchar, let rayo = rayoDelCursor(evento) else { return }
        alPinchar(rayo.origen, rayo.direccion)
    }

    /// El rayo que sale del cursor, en coordenadas del mundo.
    private func rayoDelCursor(_ evento: NSEvent) -> (origen: SIMD3<Float>, direccion: SIMD3<Float>)? {
        guard let renderizador else { return nil }
        let punto = convert(evento.locationInWindow, from: nil)
        let tamano = bounds.size
        guard tamano.width > 0, tamano.height > 0 else { return nil }

        // La vista no está volteada, así que su Y ya crece hacia arriba como la del
        // espacio de recorte: no hay que invertirla.
        let uv = SIMD2<Float>(
            Float(punto.x / tamano.width) * 2 - 1,
            Float(punto.y / tamano.height) * 2 - 1
        )
        return renderizador.camara.rayo(uv: uv, aspecto: Float(tamano.width / tamano.height))
    }

    override func mouseDragged(with evento: NSEvent) {
        recorridoDelArrastre += abs(evento.deltaX) + abs(evento.deltaY)
        guard let renderizador else { return }

        // Arrastrando el plano de sección no se orbita: el plano avanza a lo largo
        // de su normal, igual que una cara se empuja a lo largo de la suya.
        if let plano = planoEnArrastre, let alMoverPlano {
            let avance = renderizador.camara.avanceDeArrastre(
                normal: plano.normal,
                deltaX: Float(evento.deltaX),
                deltaY: Float(evento.deltaY),
                distanciaAlImpacto: plano.distancia,
                alturaEnPuntos: Float(bounds.height)
            )
            alMoverPlano(avance)
            return
        }

        // Tirando de una cara no se orbita: sería imposible apuntar.
        if let cara = caraEnArrastre, let alEmpujar {
            let avance = renderizador.camara.avanceDeArrastre(
                normal: cara.normal,
                deltaX: Float(evento.deltaX),
                deltaY: Float(evento.deltaY),
                distanciaAlImpacto: cara.distancia,
                alturaEnPuntos: Float(bounds.height)
            )
            alEmpujar(avance)
            return
        }

        // Con Option se desplaza en lugar de orbitar: es la convención de casi
        // todas las herramientas 3D y quien venga de ellas la tendrá en los dedos.
        if evento.modifierFlags.contains(.option) {
            renderizador.camara.desplazar(deltaX: Float(evento.deltaX), deltaY: Float(evento.deltaY))
        } else {
            renderizador.camara.orbitar(
                deltaX: Float(evento.deltaX) * 0.008,
                deltaY: Float(evento.deltaY) * 0.008
            )
        }
    }

    override func rightMouseDragged(with evento: NSEvent) {
        renderizador?.camara.desplazar(deltaX: Float(evento.deltaX), deltaY: Float(evento.deltaY))
    }

    override func scrollWheel(with evento: NSEvent) {
        guard let renderizador else { return }
        let paso = Float(evento.scrollingDeltaY) * (evento.hasPreciseScrollingDeltas ? 0.002 : 0.05)
        renderizador.camara.acercar(factor: 1 - paso)
    }

    override func magnify(with evento: NSEvent) {
        renderizador?.camara.acercar(factor: Float(1 - evento.magnification))
    }
}

struct VisorMetal: NSViewRepresentable {

    let editor: Editor
    let alCrear: (Renderizador) -> Void
    let alPinchar: (SIMD3<Float>, SIMD3<Float>) -> Void
    let alEmpezarAEmpujar: (SIMD3<Float>, SIMD3<Float>) -> (normal: SIMD3<Float>, distancia: Float)?
    let alEmpujar: (Float) -> Void
    let alEmpezarAMoverPlano: (SIMD3<Float>, SIMD3<Float>) -> (normal: SIMD3<Float>, distancia: Float)?
    let alMoverPlano: (Float) -> Void
    let alSoltarStl: (String) -> Void

    func makeNSView(context: Context) -> VistaMetalInteractiva {
        let vista = VistaMetalInteractiva()
        vista.alPinchar = alPinchar
        vista.alEmpezarAEmpujar = alEmpezarAEmpujar
        vista.alEmpujar = alEmpujar
        vista.alEmpezarAMoverPlano = alEmpezarAMoverPlano
        vista.alMoverPlano = alMoverPlano
        vista.alSoltarStl = alSoltarStl
        // Sin esto la vista no recibe ni un solo mensaje de arrastre, y las tres
        // funciones de arriba no llegan a ejecutarse nunca: registrarse por el tipo
        // que se acepta es lo que mete a la vista en la cadena del arrastre.
        vista.registerForDraggedTypes([.fileURL])
        // La cadencia objetivo la manda la pantalla, no una constante optimista:
        // pedir 120 en un monitor de 60 haría creer al gobernador que va tarde
        // cuando en realidad solo está esperando al vsync.
        vista.preferredFramesPerSecond = NSScreen.main?.maximumFramesPerSecond ?? 60
        vista.isPaused = false
        vista.enableSetNeedsDisplay = false

        guard let renderizador = Renderizador(vista: vista, editor: editor) else {
            NSLog("Yunkil: no hay Metal disponible en este equipo")
            return vista
        }
        vista.delegate = renderizador
        vista.renderizador = renderizador
        alCrear(renderizador)
        return vista
    }

    func updateNSView(_ vista: VistaMetalInteractiva, context: Context) {}
}

// MARK: - Vista aplanada del árbol

/// Copia inmutable de una fila del documento, con identidad estable para SwiftUI.
struct Fila: Identifiable, Equatable {
    let id: String
    let nombre: String
    let tipo: String
    let etiquetaTipo: String
    let profundidad: Int
    let visible: Bool
    let esOperacion: Bool
    let numeroDeHijos: Int

    init(_ f: FilaArbol) {
        id = f.id
        nombre = f.nombre
        tipo = f.tipo
        etiquetaTipo = f.etiquetaTipo
        profundidad = Int(f.profundidad)
        visible = f.visible
        esOperacion = f.esOperacion
        numeroDeHijos = Int(f.numeroDeHijos)
    }
}

struct Parametro: Identifiable, Equatable {
    var id: String { clave }
    let clave: String
    let etiqueta: String
    let valor: Float
    let minimo: Float
    let maximo: Float
    let unidad: String

    init(_ p: ParametroVisible) {
        clave = p.clave
        etiqueta = p.etiqueta
        valor = p.valor
        minimo = p.minimo
        maximo = p.maximo
        unidad = p.unidad
    }
}

// MARK: - Estado de la aplicación

@MainActor
final class EstadoDeLaApp: ObservableObject {

    let editor = Editor(inicial: ModelosDemo.shared.esferaSuelta())
    let aiSettings = AISettings()

    @Published private(set) var filas: [Fila] = []
    @Published private(set) var parametros: [Parametro] = []
    @Published var seleccion: String = ""
    @Published var estadoDelRender = "iniciando…"
    @Published var aviso: String?
    @Published private(set) var recompiloElUltimoCambio = false
    @Published var resolucionExportacion: Float = 0.5
    @Published private(set) var exportando = false
    @Published private(set) var progresoExportacion: Float = 0
    @Published var resultadoExportacion: String?
    @Published var peticionIA = ""
    @Published private(set) var iaTrabajando = false
    @Published var resultadoIA: String?

    /// La propuesta que está en pantalla esperando decisión. Nada se aplica sin esto.
    @Published var propuestaPendiente: PropuestaPendiente?

    /// Imagen que acompaña a la petición, y la cota real que la pone a escala.
    ///
    /// Van juntas porque por separado no sirven: la imagen da la forma y las
    /// proporciones, pero una foto no tiene escala —el mismo soporte puede ser de
    /// móvil o de camión— y sin una medida el modelo se inventaría los milímetros.
    @Published private(set) var imagenDeReferencia: ImagenDeReferencia?
    @Published private(set) var nombreDeLaImagen: String?
    @Published var medidaDeReferencia = ""

    /// La última propuesta aplicada, viva solo hasta que se sabe si se deshace.
    private var ultimaPropuestaAplicada: Propuesta?
    private var momentoDeAplicar: Date?

    // Analizador de fabricación.
    @Published var perfilDeFabricacion = UserDefaults.standard.string(forKey: "fab.perfil")
        ?? PerfilFabricacion.companion.PREDETERMINADO.nombre
    @Published var analizarAlCrear = UserDefaults.standard.object(forKey: "fab.auto") as? Bool ?? true
    @Published private(set) var informe: InformeDeFabricacion?
    @Published private(set) var analizando = false
    @Published private(set) var orientaciones: [OrientacionEvaluada] = []

    // Transformación de la pieza seleccionada, en milímetros y grados.
    @Published var posX: Float = 0
    @Published var posY: Float = 0
    @Published var posZ: Float = 0
    @Published var giroX: Float = 0
    @Published var giroY: Float = 0
    @Published var giroZ: Float = 0
    @Published var escala: Float = 1
    @Published var eje: String = "X"
    @Published var cuenta: Int = 3
    @Published var nombre: String = ""

    private weak var renderizador: Renderizador?
    private var tareaIA: Task<Void, Never>?

    var puedeDeshacer: Bool { editor.puedeDeshacer }
    var puedeRehacer: Bool { editor.puedeRehacer }
    var tipoSeleccionado: String { filas.first { $0.id == seleccion }?.tipo ?? "" }
    var celdasDeExportacion: Int64 { editor.celdasEstimadas(resolucion: resolucionExportacion) }

    init() {
        resolucionExportacion = editor.resolucionSugerida()
        refrescar(recompilo: true)
    }

    func registrar(_ r: Renderizador) {
        renderizador = r
        r.alActualizarEstado = { [weak self] texto in
            Task { @MainActor in self?.estadoDelRender = texto }
        }
    }

    // MARK: Sincronización

    private func refrescar(recompilo: Bool) {
        filas = editor.filas().map(Fila.init)
        seleccion = editor.seleccionado ?? filas.first?.id ?? ""
        recompiloElUltimoCambio = recompilo
        aviso = editor.ultimoError
        refrescarInspector()
        renderizador?.sincronizar(recompilar: recompilo)
    }

    private func refrescarInspector() {
        guard !seleccion.isEmpty else { parametros = []; return }
        parametros = editor.parametrosDe(id: seleccion).map(Parametro.init)

        let t = editor.transformDe(id: seleccion).map { $0.floatValue }
        if t.count == 7 {
            posX = t[0]; posY = t[1]; posZ = t[2]
            giroX = t[3]; giroY = t[4]; giroZ = t[5]
            escala = t[6]
        }
        eje = editor.ejeDe(id: seleccion)
        cuenta = Int(editor.cuentaDe(id: seleccion))
        nombre = editor.nombreDe(id: seleccion)
    }

    // MARK: Acciones

    func seleccionar(_ id: String) {
        editor.seleccionar(id: id)
        seleccion = id
        refrescarInspector()
    }

    /// Pinchar en el viewport selecciona la pieza que hay debajo del cursor.
    ///
    /// Decide el núcleo, con el mismo campo que exporta y analiza, así que lo que se
    /// selecciona es exactamente lo que se ve. Si el rayo no da en nada la selección se
    /// queda como estaba: vaciarla dejaría el inspector en blanco y obligaría a volver
    /// al árbol para recuperar el trabajo, que es peor que no hacer nada.
    func senalar(origen: SIMD3<Float>, direccion: SIMD3<Float>) {
        guard let impacto = editor.senalar(
            ox: origen.x, oy: origen.y, oz: origen.z,
            dx: direccion.x, dy: direccion.y, dz: direccion.z
        ) else { return }
        ultimoPunto = (impacto.x, impacto.y, impacto.z)
        ultimaNormal = (impacto.nx, impacto.ny, impacto.nz)
        seleccionar(impacto.piezaId)
    }

    /// Último punto señalado en el viewport, en milímetros del mundo.
    /// Es lo que convierte «este canto» en coordenadas sin teclear ninguna.
    private(set) var ultimoPunto: (x: Float, y: Float, z: Float)?

    var puedeFiletear: Bool { ultimoPunto != nil }

    /**
     Pone un filete en el último punto señalado.

     El alcance sale del tamaño del filete: una esfera de influencia del doble del radio
     deja el canto redondeado y la booleana exacta a partir de ahí. Pedir las dos cosas
     por separado sería exponer un detalle del campo que a nadie le importa.
     */
    func filetearEnElPuntoSenalado(_ radio: Float) {
        guard let p = ultimoPunto else {
            aviso = "Pincha primero el canto que quieras redondear"
            return
        }
        guard !seleccion.isEmpty else { return }
        let recompilo = editor.filetear(
            id: seleccion,
            x: p.x, y: p.y, z: p.z,
            radio: max(radio * 2, 0.2),
            tamano: radio
        )
        refrescar(recompilo: recompilo)
    }

    /// Quita el filete del booleano seleccionado.
    func quitarFilete() {
        guard !seleccion.isEmpty else { return }
        refrescar(recompilo: editor.quitarFilete(id: seleccion))
    }

    var seleccionTieneFilete: Bool {
        !seleccion.isEmpty && editor.tieneFilete(id: seleccion)
    }

    var esBooleanaSeleccionada: Bool {
        ["UNION", "DIFERENCIA", "INTERSECCION"].contains(tipoSeleccionado)
    }

    var radioDelFilete: Float {
        parametros.first { $0.clave == "fusion" }?.valor ?? 0
    }

    /// Cara que se está empujando con ⌘ y arrastrar.
    private var asaEnArrastre: (piezaId: String, asa: String)?

    /// Agarra la cara que hay bajo el cursor.
    ///
    /// Decide el núcleo qué cara es: la interfaz solo tiene una normal del mundo, y pasar
    /// de ahí al mando que le corresponde es geometría —hay que llevar la normal al
    /// espacio local de la pieza— y por tanto no es asunto de la vista.
    func empezarAEmpujar(
        origen: SIMD3<Float>,
        direccion: SIMD3<Float>
    ) -> (normal: SIMD3<Float>, distancia: Float)? {
        guard let impacto = editor.senalar(
            ox: origen.x, oy: origen.y, oz: origen.z,
            dx: direccion.x, dy: direccion.y, dz: direccion.z
        ) else { return nil }

        guard let asa = editor.asaParaNormal(
            id: impacto.piezaId,
            nx: impacto.nx, ny: impacto.ny, nz: impacto.nz
        ) else {
            seleccionar(impacto.piezaId)
            aviso = "Esta pieza no tiene caras que empujar; cambia sus medidas en el inspector"
            return nil
        }

        seleccionar(impacto.piezaId)
        asaEnArrastre = (impacto.piezaId, asa)
        // El punto de deshacer se marca aquí, así que todo el arrastre es un solo ⌘Z.
        editor.confirmarEdicionContinua()
        return (SIMD3<Float>(impacto.nx, impacto.ny, impacto.nz), impacto.distancia)
    }

    /// Un fotograma del arrastre: reescribe la cota y no anota historial.
    func empujar(milimetros: Float) {
        guard let arrastre = asaEnArrastre, milimetros != 0 else { return }
        let recompilo = editor.empujar(
            id: arrastre.piezaId,
            asaNombre: arrastre.asa,
            milimetros: milimetros,
            registrarEnHistorial: false
        )
        recompiloElUltimoCambio = recompilo
        aviso = editor.ultimoError
        refrescarInspector()
        renderizador?.sincronizar(recompilar: recompilo)
    }

    func anadir(_ tipo: String) {
        let recompilo = editor.anadir(tipoNombre: tipo, padreId: seleccion.isEmpty ? nil : seleccion)
        refrescar(recompilo: recompilo)
        renderizador?.encuadrar()
    }

    func eliminar() {
        guard !seleccion.isEmpty else { return }
        refrescar(recompilo: editor.eliminar(id: seleccion))
    }

    func duplicar() {
        guard !seleccion.isEmpty else { return }
        refrescar(recompilo: editor.duplicar(id: seleccion))
    }

    // MARK: Interacciones de modelado

    var raizId: String { editor.raizId }

    /// Lo copiado, como texto. Va en el estado y no en el portapapeles del sistema para no
    /// pisar lo que el usuario tenga copiado fuera de la aplicación.
    private var portapapeles: String?
    var hayAlgoQuePegar: Bool { portapapeles != nil }

    func copiar(_ id: String) {
        portapapeles = editor.copiar(id: id)
        aviso = editor.ultimoError
        objectWillChange.send()
    }

    func pegar(en destino: String?) {
        guard let portapapeles else { return }
        refrescar(recompilo: editor.pegar(texto: portapapeles, destinoId: destino))
    }

    func aislar(_ id: String) {
        refrescar(recompilo: editor.aislar(id: id))
    }

    func mostrarTodo() {
        refrescar(recompilo: editor.mostrarTodo())
    }

    func extraer(_ id: String) {
        refrescar(recompilo: editor.extraer(id: id))
    }

    /// Apoya en el plato la cara que se pinchó por última vez.
    ///
    /// Es la orientación para imprimir hecha con el cursor en vez de con tres campos de
    /// grados: se señala la cara que va abajo y la pieza se gira y baja al plato.
    func apoyarLaCaraSenalada() {
        guard let n = ultimaNormal, !seleccion.isEmpty else {
            aviso = "Pincha primero la cara que quieras apoyar"
            return
        }
        refrescar(recompilo: editor.apoyarEnElPlato(id: seleccion, nx: n.x, ny: n.y, nz: n.z))
    }

    /// Última normal señalada en el viewport, en el mundo.
    private(set) var ultimaNormal: (x: Float, y: Float, z: Float)?

    func mirarDesde(_ vista: CamaraOrbital.Vista) {
        renderizador?.camara.mirarDesde(vista)
    }

    func alternarOrtografica() {
        renderizador?.camara.ortografica.toggle()
        objectWillChange.send()
    }

    var esOrtografica: Bool { renderizador?.camara.ortografica ?? false }

    // MARK: Sección en vivo

    /// El plano de sección, cuando está activo. Es estado de vista: no entra en el
    /// documento ni en el historial, igual que la cámara.
    @Published private(set) var planoDeSeccion: PlanoDeSeccion?
    var seccionActiva: Bool { planoDeSeccion != nil }

    /// Activa la sección con un plano que corta por el centro del modelo y mira
    /// hacia la cámara: es lo que enseña el corte de inmediato, sin teclear nada.
    func alternarSeccion() {
        if seccionActiva {
            planoDeSeccion = nil
            renderizador?.planoDeSeccion = nil
            objectWillChange.send()
            return
        }
        let mn = editor.cotaMinima.map { $0.floatValue }
        let mx = editor.cotaMaxima.map { $0.floatValue }
        guard mn.count == 3, mx.count == 3 else {
            aviso = "No hay modelo que cortar"
            return
        }
        let centro = SIMD3<Float>(
            (mn[0] + mx[0]) * 0.5, (mn[1] + mx[1]) * 0.5, (mn[2] + mx[2]) * 0.5
        )
        // La normal apunta a la cámara: el corte se ve de frente la primera vez.
        let normal = simd_normalize(renderizador?.camara.objetivo ?? SIMD3<Float>(0, 0, 1)
            - (renderizador?.camara.posicion ?? SIMD3<Float>(0, 100, 200)))
        planoDeSeccion = PlanoDeSeccion(
            punto: centro,
            normal: normal,
            grosorMinimoPared: editor.grosorMinimoDePared(nombrePerfil: perfilDeFabricacion)
        )
        renderizador?.planoDeSeccion = planoDeSeccion
        objectWillChange.send()
    }

    /// Coloca el plano de sección sobre el último punto pinchado y mirando a la
    /// cámara. Es «sección por este plano» del clic derecho: el corte pasa por
    /// donde el usuario señaló.
    func seccionPorElPuntoSenalado() {
        guard let p = ultimoPunto else {
            aviso = "Pincha primero el punto por donde quieras cortar"
            return
        }
        if !seccionActiva { alternarSeccion() }
        guard var plano = planoDeSeccion else { return }
        plano.punto = SIMD3<Float>(p.x, p.y, p.z)
        planoDeSeccion = plano
        renderizador?.planoDeSeccion = plano
        objectWillChange.send()
    }

    /// ¿Cae el rayo en el rectángulo visible del plano? Si sí, devuelve la normal
    /// y la distancia al cruce para arrastrarlo. Es la versión del plano de
    /// `empezarAEmpujar`: el mismo patrón de agarrar la superficie.
    func empezarAMoverPlano(
        origen: SIMD3<Float>,
        direccion: SIMD3<Float>
    ) -> (normal: SIMD3<Float>, distancia: Float)? {
        guard let plano = planoDeSeccion else { return nil }
        let denom = simd_dot(direccion, plano.normal)
        guard abs(denom) > 1e-5 else { return nil }
        let t = simd_dot(plano.punto - origen, plano.normal) / denom
        guard t > 0 else { return nil }
        return (plano.normal, t)
    }

    /// Un fotograma del arrastre del plano: se desplaza a lo largo de su normal.
    func moverPlano(milimetros: Float) {
        guard var plano = planoDeSeccion, milimetros != 0 else { return }
        plano.punto += plano.normal * milimetros
        planoDeSeccion = plano
        renderizador?.planoDeSeccion = plano
    }

    /// Encuadra solo la pieza seleccionada. Es la tecla F de cualquier herramienta 3D.
    func enfocarSeleccion() {
        guard !seleccion.isEmpty, let centro = editor.centroEnElMundo(id: seleccion) else { return }
        renderizador?.camara.objetivo = SIMD3<Float>(
            centro[0].floatValue, centro[1].floatValue, centro[2].floatValue
        )
    }

    /// Encuadra el modelo entero. Es ⇧F en cualquier herramienta 3D.
    func encuadrarTodo() { renderizador?.encuadrar() }

    func envolver(_ tipo: String) {
        guard !seleccion.isEmpty else { return }
        refrescar(recompilo: editor.envolver(id: seleccion, tipoNombre: tipo))
    }

    func desplazar(haciaArriba: Bool) {
        guard !seleccion.isEmpty else { return }
        refrescar(recompilo: editor.desplazar(id: seleccion, haciaArriba: haciaArriba))
    }

    func alternarVisibilidad(_ id: String) {
        let visible = editor.esVisible(id: id)
        refrescar(recompilo: editor.fijarVisible(id: id, visible: !visible))
    }

    /// Se llama en cada fotograma del arrastre: no debe recompilar ni anotar
    /// historial, solo reescribir uniforms.
    func fijarParametro(_ clave: String, _ valor: Float) {
        guard !seleccion.isEmpty else { return }
        let recompilo = editor.fijarParametro(id: seleccion, clave: clave, valor: valor)
        parametros = editor.parametrosDe(id: seleccion).map(Parametro.init)
        recompiloElUltimoCambio = recompilo
        renderizador?.sincronizar(recompilar: recompilo)
    }

    func empezarEdicionContinua() { editor.confirmarEdicionContinua() }

    func aplicarTransform() {
        guard !seleccion.isEmpty else { return }
        let recompilo = editor.fijarTransform(
            id: seleccion,
            x: posX, y: posY, z: posZ,
            giroX: giroX, giroY: giroY, giroZ: giroZ,
            escala: max(escala, 0.01)
        )
        recompiloElUltimoCambio = recompilo
        aviso = editor.ultimoError
        renderizador?.sincronizar(recompilar: recompilo)
    }

    func aplicarNombre() {
        guard !seleccion.isEmpty else { return }
        _ = editor.renombrar(id: seleccion, nombre: nombre)
        filas = editor.filas().map(Fila.init)
        aviso = editor.ultimoError
    }

    func aplicarEje(_ nuevo: String) {
        eje = nuevo
        refrescar(recompilo: editor.fijarEje(id: seleccion, ejeNombre: nuevo))
    }

    func aplicarCuenta(_ nueva: Int) {
        cuenta = nueva
        refrescar(recompilo: editor.fijarCuenta(id: seleccion, cuenta: Int32(nueva)))
    }

    func deshacer() {
        // Un deshacer inmediato sobre lo que acaba de proponer la IA es la señal más
        // limpia de que la propuesta no valía: el usuario la aceptó por el diálogo,
        // la vio y se arrepintió. Vale mucho más que un descarte, donde solo llegó a
        // leer el resumen, así que se corrige el asiento antes de deshacer.
        if let propuesta = ultimaPropuestaAplicada,
           let aplicada = momentoDeAplicar,
           Date().timeIntervalSince(aplicada) < Self.ventanaDeArrepentimiento {
            anotar(propuesta, desenlace: "DESHECHO")
        }
        ultimaPropuestaAplicada = nil
        momentoDeAplicar = nil
        if editor.deshacer() { refrescar(recompilo: true) }
    }
    func rehacer() { if editor.rehacer() { refrescar(recompilo: true) } }

    func cargarEjemplo(_ cual: String) {
        let doc: Documento
        switch cual {
        case "esfera": doc = ModelosDemo.shared.esferaSuelta()
        case "rejilla": doc = ModelosDemo.shared.rejilla()
        case "vacio": doc = Documento.companion.vacio()
        default: doc = ModelosDemo.shared.soporte()
        }
        editor.reemplazarDocumento(nuevo: doc)
        refrescar(recompilo: true)
        renderizador?.encuadrar()
    }

    func encuadrar() { renderizador?.encuadrar() }

    // MARK: Archivo

    func guardar() {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "modelo.yunkil"
        panel.allowedContentTypes = [UTType(filenameExtension: "yunkil") ?? .json]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let backup = url.appendingPathExtension("bak")
            if FileManager.default.fileExists(atPath: url.path) {
                try? FileManager.default.removeItem(at: backup)
                try FileManager.default.copyItem(at: url, to: backup)
            }
            try editor.aJson().write(to: url, atomically: true, encoding: .utf8)
        } catch {
            aviso = "No se pudo guardar: \(error.localizedDescription)"
        }
    }

    func exportarPieza() {
        let panel = NSSavePanel()
        panel.title = "Exportar pieza imprimible"
        panel.prompt = "Exportar"
        // El 3MF primero, y por eso es el nombre que aparece escrito: un STL no
        // declara en qué unidades está, y de esa omisión sale el clásico modelo que
        // entra en el laminador a 1/25 de su tamaño. El formato lo decide la
        // extensión que quede en el nombre, que es donde el usuario ya está
        // eligiendo; preguntarlo otra vez en un menú aparte sobra.
        panel.nameFieldStringValue = "pieza.3mf"
        panel.allowedContentTypes = [
            UTType(filenameExtension: "3mf") ?? .data,
            UTType(filenameExtension: "stl") ?? .data,
        ]
        guard panel.runModal() == .OK, let url = panel.url else { return }

        exportando = true
        progresoExportacion = 0
        resultadoExportacion = nil
        aviso = nil

        let editor = editor
        let resolucion = resolucionExportacion
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let certificado = editor.exportarPieza(
                ruta: url.path,
                resolucion: resolucion,
                alAvanzar: { avance in
                    DispatchQueue.main.async {
                        self?.progresoExportacion = avance.floatValue
                    }
                }
            )
            DispatchQueue.main.async {
                guard let self else { return }
                self.exportando = false
                self.progresoExportacion = 1
                if let certificado {
                    if certificado.apto && certificado.bytes > 0 {
                        self.resultadoExportacion = certificado.resumen()
                    } else if certificado.apto {
                        self.aviso = "La malla es válida, pero no se pudo escribir el archivo."
                    } else {
                        self.aviso = certificado.resumen()
                    }
                } else {
                    self.aviso = editor.ultimoError ?? "No hay material que exportar."
                }
            }
        }
    }

    /// Máximo de vueltas para que el modelo corrija su propio plan.
    ///
    /// Tres es lo que ha resultado útil: la primera suele fallar por formato, la
    /// segunda lo arregla, y si la tercera tampoco sale es que el modelo no sabe
    /// hacerlo y seguir insistiendo solo gasta tiempo y cuota.
    private static let rondasDeCorreccion = 3

    func adjuntarImagen() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        panel.message = "Una foto, un boceto o una captura de la pieza que quieres"
        guard panel.runModal() == .OK, let url = panel.url else { return }

        guard let imagen = ImagenDeReferencia.desde(url: url) else {
            resultadoIA = "No se pudo leer esa imagen."
            return
        }
        imagenDeReferencia = imagen
        nombreDeLaImagen = url.lastPathComponent
    }

    func quitarImagen() {
        imagenDeReferencia = nil
        nombreDeLaImagen = nil
    }

    func construirConIA() {
        let peticion = peticionIA.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !peticion.isEmpty, !iaTrabajando else { return }
        iaTrabajando = true
        resultadoIA = "Pensando…"
        tareaIA?.cancel()

        // Copiloto vs generador: con una pieza señalada y una petición que no pide
        // crear, el modelo edita lo que hay —con un tope de 3 operaciones, para que
        // la respuesta sea corta y rápida—. Sin selección, genera como siempre.
        let esOrdenDeEdicion = !seleccion.isEmpty && seleccion != raizId &&
            !esPeticionDeCrear(peticion)

        // El sistema y el contexto los redacta el núcleo: el catálogo de piezas y de
        // parámetros sale de la misma fuente que valida el plan, así que el modelo
        // nunca ve una opción que luego se le vaya a rechazar.
        let imagen = imagenDeReferencia
        let medida = medidaDeReferencia.trimmingCharacters(in: .whitespacesAndNewlines)
        // La petición viaja al constructor del prompt para que pueda elegir los
        // ejemplares que vienen a cuento: son planes verificados, y un modelo pequeño
        // usa una operación cuando la ha visto aplicada, no cuando la ha visto listada.
        let sistema = imagen == nil
            ? editor.instruccionesParaModelo(nombrePerfil: perfilDeFabricacion, peticion: peticion)
            : editor.instruccionesParaModeloConImagen(
                nombrePerfil: perfilDeFabricacion,
                medidaConocida: medida.isEmpty ? nil : medida,
                peticion: peticion
            )
        // El documento **y** el hilo. Antes solo iba el documento, así que cada petición
        // nacía sin memoria de la anterior: para decir «más grueso» había que
        // redescribir la pieza entera, porque «más» no se refería a nada.
        //
        // Se lee aquí, antes de anotar nada, porque `contextoConHilo` es también quien
        // declara si la persona ha tocado el modelo a mano desde el último turno, y
        // anotar primero pondría ese contador a cero.
        let contexto = editor.contextoConHilo(presupuestoDelHilo: 1200)
        let peticionCompleta = "Documento actual:\n\(contexto)\n\nPetición:\n\(peticion)"
        let seleccion = aiSettings.selection
        // El presupuesto se midió, no se estimó. Con una imagen y 1.500 tokens, el
        // modelo local con visión razonó el plan entero **y se quedó sin sitio antes
        // de escribir el JSON**: la respuesta se corta a mitad de un pensamiento y
        // desde fuera parece que el modelo no sabe hacerlo, cuando lo sabía.
        //
        // Los modelos de razonamiento gastan el grueso del presupuesto antes de
        // empezar a responder, así que el local sube a 8.000, y a 12.000 cuando hay
        // imagen —describir lo que ve se lleva otra tanda—. Es memoria del contexto,
        // no dinero: en local no lo paga nadie.
        let presupuesto: Int
        if seleccion.provider == .openCodeGo {
            presupuesto = imagen == nil ? 12_000 : 16_000
        } else {
            presupuesto = imagen == nil ? 8_000 : 12_000
        }

        tareaIA = Task {
            do {
                var mensaje = peticionCompleta
                var aceptado: PlanInterpretado?
                var ultimoMotivo = "el modelo no llegó a responder"

                // Lo que el modelo no ve al escribir: qué sale de ejecutar su plan.
                // Se le devuelven las medidas del resultado hasta que la pieza se
                // sostenga, y solo entonces se le enseña la propuesta al usuario.
                var reparos: [String] = []
                var ultimaRespuesta = ""
                var rondasGastadas = 0
                var cosido: PlanDeModelado?

                for ronda in 1...Self.rondasDeCorreccion {
                    rondasGastadas = ronda
                    try Task.checkCancellation()
                    if ronda > 1 { resultadoIA = "Corrigiendo el plan (intento \(ronda))…" }
                    // La imagen viaja en todas las rondas, no solo en la primera: si
                    // se le quitara al corregir, el modelo estaría arreglando de
                    // memoria una geometría que ya no ve, y la ronda 2 saldría peor
                    // que la 1 sin que nada lo explicara.
                    let texto = try await AsistenteLocal.pedirPlan(
                        system: sistema, user: mensaje, selection: seleccion,
                        maxTokens: presupuesto, imagen: imagen
                    )
                    try Task.checkCancellation()
                    ultimaRespuesta = texto

                    let leido = editor.interpretarPlan(respuesta: texto, edicion: esOrdenDeEdicion)
                    guard leido.aceptado else {
                        ultimoMotivo = leido.motivoDelRechazo ?? "formato no reconocido"
                        mensaje = peticionCompleta + "\n\n" + editor.correccionParaModelo(
                            motivo: ultimoMotivo, respuestaAnterior: texto
                        )
                        continue
                    }

                    resultadoIA = "Comprobando la pieza…"
                    let revision = await Self.revisar(
                        respuesta: texto,
                        documento: editor.aJson(),
                        perfil: perfilDeFabricacion,
                        edicion: esOrdenDeEdicion
                    )
                    try Task.checkCancellation()
                    reparos = revision.motivos
                    cosido = revision.cosido

                    aceptado = leido
                    if reparos.isEmpty { break }

                    ultimoMotivo = reparos.joined(separator: " · ")
                    mensaje = peticionCompleta + "\n\n" + editor.revisionParaModelo(
                        motivos: reparos, respuestaAnterior: texto
                    )
                }

                guard let leido = aceptado, let plan = leido.plan else {
                    throw LocalAssistantError.invalidPlan(ultimoMotivo)
                }

                // Aquí acaba el trabajo de la IA y empieza el del usuario. Antes esto
                // era un `NSAlert` de dos botones con el resumen que escribía el propio
                // modelo; ahora la propuesta se queda en pantalla, contada por el núcleo
                // operación a operación, y no se toca el documento hasta que alguien lo
                // diga. Ver `PanelDePropuesta.swift`.
                let aplicable = cosido ?? plan
                let lineas = editor.explicarPlan(plan: aplicable)
                propuestaPendiente = PropuestaPendiente(
                    registro: Propuesta(
                        id: UUID().uuidString,
                        peticion: peticion,
                        plan: ultimaRespuesta,
                        rondas: rondasGastadas,
                        reparos: reparos
                    ),
                    plan: aplicable,
                    lineas: lineas,
                    reemplaza: leido.reemplaza,
                    resumen: leido.resumen.isEmpty ? peticion : leido.resumen,
                    reparos: reparos,
                    avisos: leido.avisos,
                    cosidas: max(0, aplicable.operaciones.count - plan.operaciones.count),
                    aceptadas: Set(lineas.map { Int($0.indice) })
                )
                resultadoIA = nil
            } catch is CancellationError {
                resultadoIA = "Creación cancelada; no se aplicaron cambios incompletos."
            } catch {
                resultadoIA = nil
                aviso = "\(aiSettings.provider.name): \(error.localizedDescription)"
            }
            iaTrabajando = false
        }
    }

    func cancelarIA() { tareaIA?.cancel() }

    // MARK: El hilo

    /// Lo último que se pidió, para enseñarlo encima del campo de texto.
    var hiloEnCurso: String? {
        guard let ultima = editor.conversacion.ultimaPeticion, !ultima.isEmpty else { return nil }
        return ultima
    }

    /// Corta el hilo sin tocar la pieza.
    ///
    /// Hace falta un botón porque no hay forma de deducirlo: seguir con la misma pieza
    /// para otra cosa completamente distinta es normal, y el hilo entonces estorba
    /// —el modelo arrastra una intención que ya no existe— sin que nada lo delate.
    func olvidarElHilo() {
        editor.olvidarConversacion()
        objectWillChange.send()
        resultadoIA = "Hilo olvidado; la pieza no cambió."
    }

    // MARK: Decidir sobre la propuesta

    /// Marca o desmarca una operación, arrastrando lo que dependa de ella.
    ///
    /// Las dos direcciones las resuelve el núcleo y no esta función, porque el grafo
    /// de dependencias sale de leer el plan: al **marcar** se arrastra hacia arriba lo
    /// que hace falta —pedir el taladro de una pieza que aún no se crea es pedir
    /// también su creación—, y al **desmarcar** se arrastra hacia abajo lo que se
    /// quedaría hablando de una pieza que no va a existir. Sin lo segundo el aplicador
    /// no fallaría: omitiría esas operaciones en silencio, que es peor.
    func alternarOperacion(_ indice: Int) {
        guard var p = propuestaPendiente else { return }
        if p.aceptadas.contains(indice) {
            p.aceptadas = Set(
                editor.podarSeleccion(
                    plan: p.plan,
                    marcadas: (p.aceptadas.subtracting([indice])).map { KotlinInt(int: Int32($0)) }
                ).map { Int(truncating: $0) }
            )
        } else {
            p.aceptadas = Set(
                editor.completarSeleccion(
                    plan: p.plan,
                    marcadas: (p.aceptadas.union([indice])).map { KotlinInt(int: Int32($0)) }
                ).map { Int(truncating: $0) }
            )
        }
        propuestaPendiente = p
    }

    func marcarTodasLasOperaciones(_ todas: Bool) {
        guard var p = propuestaPendiente else { return }
        p.aceptadas = todas ? Set(p.lineas.map { Int($0.indice) }) : []
        propuestaPendiente = p
    }

    func descartarPropuesta() {
        guard let p = propuestaPendiente else { return }
        propuestaPendiente = nil
        anotar(p.registro, desenlace: "DESCARTADO", rechazadas: p.lineas.map { $0.texto })
        // Un descarte también es hilo, y del más informativo: «pediste esto, te propuse
        // aquello y lo tiraste entero». Callarlo dejaría al modelo repitiendo en el
        // turno siguiente lo mismo que se acaba de rechazar.
        editor.anotarPeticion(texto: p.registro.peticion)
        editor.anotarRespuesta(
            desenlaceNombre: "DESCARTADO",
            aplicadas: [],
            rechazadas: p.lineas.map { $0.texto }
        )
        resultadoIA = "Propuesta descartada; el documento no cambió."
    }

    /// Aplica lo marcado, como una sola transacción deshacible.
    func aceptarPropuesta() {
        guard let p = propuestaPendiente, !p.ningunaMarcada else { return }
        propuestaPendiente = nil

        let resultado = editor.aplicarParte(
            plan: p.plan,
            aceptadas: p.aceptadas.sorted().map { KotlinInt(int: Int32($0)) },
            nombrePerfil: perfilDeFabricacion
        )
        guard resultado.exito else {
            aviso = resultado.error ?? "no se pudo aplicar la propuesta"
            anotar(p.registro, desenlace: "DESCARTADO")
            return
        }

        // La bitácora distingue aceptar entero de aceptar parte, porque son dos juicios
        // distintos sobre el modelo: «acertó» y «acertó a medias». Mezclarlos borraría
        // justo la señal que hace falta para saber qué se le da mal.
        let aplicadas = p.lineas.filter { p.aceptadas.contains(Int($0.indice)) }.map { $0.texto }
        let rechazadas = p.lineas.filter { !p.aceptadas.contains(Int($0.indice)) }.map { $0.texto }
        let desenlace = p.todasMarcadas ? "APLICADO" : "PARCIAL"
        anotar(p.registro, desenlace: desenlace, rechazadas: rechazadas)
        // Los dos turnos entran juntos y aquí, no al mandar la petición: si entraran al
        // mandarla, un fallo de red dejaría en el hilo una pregunta sin respuesta y el
        // modelo la leería como algo que sí se hizo.
        editor.anotarPeticion(texto: p.registro.peticion)
        editor.anotarRespuesta(
            desenlaceNombre: desenlace,
            aplicadas: aplicadas,
            rechazadas: rechazadas
        )
        // Queda pendiente de arrepentimiento: si el siguiente deshacer llega enseguida,
        // el desenlace se corrige a DESHECHO.
        ultimaPropuestaAplicada = p.registro
        momentoDeAplicar = Date()

        peticionIA = ""
        var texto = p.todasMarcadas
            ? resultado.resumen
            : "\(p.aceptadas.count) de \(p.lineas.count) operaciones aplicadas"
        if !resultado.omitidas.isEmpty {
            texto += " · \(resultado.omitidas.count) omitidas: "
                + resultado.omitidas.prefix(2).joined(separator: "; ")
        }
        resultadoIA = texto
        refrescar(recompilo: true)
        renderizador?.encuadrar()
        if analizarAlCrear { analizarFabricacion() }
    }

    /// ¿Empieza la petición por una orden de creación, no de edición?
    ///
    /// «hazme una carcasa» y «crea un soporte» generan; «achaflana estas aristas» y
    /// «ponle un M4 aquí» editan. Se mira la primera palabra porque es donde la
    /// intención se dice primero, y porque un copiloto que malinterpreta una orden
    /// de edición como generación tiraría la pieza entera.
    private func esPeticionDeCrear(_ peticion: String) -> Bool {
        let primera = peticion.lowercased()
            .trimmingCharacters(in: .punctuationCharacters)
            .split(separator: " ").first.map(String.init) ?? ""
        return ["crea", "crear", "haz", "hacer", "hazme", "haceme", "nuevo", "nueva", "diseña", "diseñar"].contains(primera)
    }

    /// Una propuesta de la IA a la espera de saber en qué acabó.
    struct Propuesta {
        let id: String
        let peticion: String
        let plan: String
        let rondas: Int
        let reparos: [String]
    }

    /// Cuánto se le concede al arrepentimiento para contar como rechazo de la pieza.
    ///
    /// Pasado ese rato, un deshacer ya no habla de la propuesta sino del trabajo que
    /// vino después, y apuntarlo contra la IA emborronaría el registro.
    private static let ventanaDeArrepentimiento: TimeInterval = 45

    /// Dónde vive la bitácora. Junto a los ajustes, no en el documento del usuario:
    /// es telemetría local de la herramienta, no parte de la pieza que está haciendo.
    private static var rutaDeLaBitacora: String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Yunkil", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("propuestas.jsonl").path
    }

    /// Apunta el desenlace de una propuesta. Que falle no puede romper nada.
    ///
    /// `rechazadas` son las operaciones que el usuario desmarcó, en español. Es el
    /// dato que no existía cuando el desenlace era binario: el modelo dejaba de ser
    /// «bueno» o «malo» y pasa a saberse **qué** de lo que propuso sobraba.
    private func anotar(_ propuesta: Propuesta, desenlace: String, rechazadas: [String] = []) {
        _ = editor.registrarDesenlace(
            ruta: Self.rutaDeLaBitacora,
            id: propuesta.id,
            momento: Int64(Date().timeIntervalSince1970),
            peticion: propuesta.peticion,
            plan: propuesta.plan,
            nombrePerfil: perfilDeFabricacion,
            rondas: Int32(propuesta.rondas),
            reparos: propuesta.reparos,
            desenlaceNombre: desenlace,
            rechazadas: rechazadas
        )
    }

    /// Aplica el plan en un núcleo aislado y devuelve lo que le falla a la pieza.
    ///
    /// Va fuera del hilo principal por lo mismo que el analizador: muestrear el campo
    /// para saber si las piezas se tocan cuesta décimas de segundo, y la interfaz no
    /// puede quedarse congelada mientras tanto. Se le pasa el documento serializado
    /// en vez del editor vivo porque así el banco de pruebas no comparte ni un objeto
    /// con el que está editando el usuario.
    private static func revisar(
        respuesta: String,
        documento: String,
        perfil: String,
        edicion: Bool
    ) async -> Revision {
        await Task.detached(priority: .userInitiated) {
            let aislado = Editor(inicial: Documento.companion.vacio())
            _ = aislado.desdeJson(texto: documento)
            guard let plan = aislado.interpretarPlan(respuesta: respuesta, edicion: edicion).plan else {
                return Revision(motivos: [], cosido: nil)
            }
            let revision = aislado.revisarPlan(plan: plan, nombrePerfil: perfil)
            if revision.motivos.isEmpty { return Revision(motivos: [], cosido: nil) }

            // El revisor no solo mide: cuando lo que falla es que dos piezas no se
            // tocan, sabe qué operación las une. Aplicarla aquí ahorra una ronda de
            // inferencia entera y, sobre todo, no depende de que el modelo copie
            // bien una línea de JSON. Si el cosido no arregla nada, `coserPlan`
            // devuelve nil y el fallo sigue su camino hacia la ronda de corrección.
            guard let cosido = aislado.coserPlan(plan: plan, nombrePerfil: perfil) else {
                return Revision(motivos: revision.motivos, cosido: nil)
            }
            return Revision(
                motivos: aislado.revisarPlan(plan: cosido, nombrePerfil: perfil).motivos,
                cosido: cosido
            )
        }.value
    }

    /// Lo que le falla a la pieza y, si Yunkil supo arreglarlo, el plan ya cosido.
    struct Revision {
        let motivos: [String]
        let cosido: PlanDeModelado?
    }

    // MARK: Analizador de fabricación

    var perfilesDisponibles: [String] { editor.perfilesDeFabricacion() }

    /// Examina la pieza contra el perfil de impresora elegido.
    ///
    /// Corre fuera del hilo principal porque malla el modelo a resolución de
    /// boquilla; bloquear la interfaz varios segundos convertiría una función útil
    /// en una que nadie pulsa.
    func analizarFabricacion() {
        guard !analizando, !editor.estaVacio else { return }
        analizando = true
        informe = nil
        let perfil = perfilDeFabricacion
        UserDefaults.standard.set(perfil, forKey: "fab.perfil")
        let copia = editor.aJson()

        Task.detached(priority: .userInitiated) {
            let aislado = Editor(inicial: Documento.companion.vacio())
            _ = aislado.desdeJson(texto: copia)
            let resultado = aislado.analizarFabricacion(nombrePerfil: perfil, alAvanzar: nil)
            let candidatas = aislado.orientacionesDeImpresion(nombrePerfil: perfil)
            await MainActor.run {
                self.informe = resultado
                self.orientaciones = candidatas
                self.analizando = false
                if resultado == nil { self.aviso = aislado.ultimoError }
            }
        }
    }

    /// Ejecuta el arreglo que propone un aviso. Entra en el historial como todo.
    func aplicarCorreccion(_ correccion: Correccion) {
        let cambio = editor.aplicarCorreccion(correccion: correccion)
        if let error = editor.ultimoError {
            aviso = error
            return
        }
        refrescar(recompilo: cambio)
        renderizador?.encuadrar()
        analizarFabricacion()
    }

    func aplicarOrientacion(_ orientacion: OrientacionEvaluada) {
        _ = editor.girarModelo(gradosX: orientacion.gradosX, gradosZ: orientacion.gradosZ)
        refrescar(recompilo: true)
        renderizador?.encuadrar()
        analizarFabricacion()
    }

    func asentarEnPlato() {
        _ = editor.asentarEnPlato(registrarEnHistorial: true)
        refrescar(recompilo: true)
    }

    /// Trae un STL de fuera. Es la puerta por la que entra la geometría que no se
    /// puede escribir: a partir de aquí la malla es una pieza más y se le puede
    /// restar, ahuecar, taladrar, acotar y exportar verificada.
    func importarMalla() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [UTType(filenameExtension: "stl") ?? .data]
        panel.message = "Un STL: descargado, escaneado o exportado por otra herramienta"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        importarMallaDesde(ruta: url.path)
    }

    /// El flujo de una acción del STL de fuera: importar → analizar → arreglos.
    ///
    /// Es lo que convierte la capacidad suelta («importo un STL») en el producto
    /// que se puede enseñar: arrastras el archivo, y en la misma pasada la pieza
    /// queda examinada contra la impresora con cada aviso nombrando la pieza y
    /// trayendo su arreglo ejecutable. Lo único que faltaba era el cable entre lo
    /// que ya existía —`importarMalla` y `analizarFabricacion`—.
    func importarMallaDesde(ruta: String) {
        // Hornear tarda: es rasterizar cada triángulo contra una rejilla. Se avisa
        // antes de bloquear, porque si no parece que la aplicación se ha colgado.
        aviso = "Preparando la malla…"
        if editor.importarMalla(ruta: ruta, resolucion: 0, padreId: nil) {
            // `ultimoError` trae aquí un aviso, no un fallo: la pieza ya está puesta.
            aviso = editor.ultimoError
            refrescar(recompilo: true)
            renderizador?.encuadrar()
            // El análisis corre en segundo plano y el informe sale con la pieza
            // ya en la escena: es la mitad del producto «STL devuelto imprimible».
            if analizarAlCrear { analizarFabricacion() }
        } else {
            aviso = editor.ultimoError ?? "No se pudo importar la malla."
        }
    }

    func abrir() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [UTType(filenameExtension: "yunkil") ?? .json]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let texto = try String(contentsOf: url, encoding: .utf8)
            if editor.desdeJson(texto: texto) {
                // Las mallas importadas no viajan dentro del proyecto —son megas— así
                // que al abrir se vuelven a hornear desde su archivo original. Si
                // alguna se movió de sitio se dice cuál, en vez de dejar un hueco.
                let perdidas = editor.rehornearMallas()
                if !perdidas.isEmpty {
                    aviso = "No se encontraron los archivos de: \(perdidas.joined(separator: ", "))"
                }
                refrescar(recompilo: true)
                renderizador?.encuadrar()
            } else {
                aviso = editor.ultimoError
            }
        } catch {
            aviso = "No se pudo abrir: \(error.localizedDescription)"
        }
    }
}

// MARK: - Interfaz

private let primitivas: [(String, String, String)] = [
    ("ESFERA", "Esfera", "circle"),
    ("CAJA", "Caja", "cube"),
    ("CILINDRO", "Cilindro", "cylinder"),
    ("CONO", "Cono", "cone"),
    ("TORO", "Toro", "torus"),
    ("CAPSULA", "Cápsula", "capsule"),
]

// Las tres piezas que se dibujan con un contorno en vez de acotarse con números.
// Estaban solo al alcance de la IA, que es una forma rara de esconder las piezas
// con las que se hace cualquier cosa que no sea un bloque.
private let contornos: [(String, String, String)] = [
    ("EXTRUSION", "Extrusión", "square.stack.3d.up"),
    ("REVOLUCION", "Revolución", "arrow.triangle.2.circlepath"),
    ("BARRIDO", "Barrido", "point.topleft.down.curvedto.point.bottomright.up"),
]

private let operaciones: [(String, String, String)] = [
    ("UNION", "Unión", "plus.circle"),
    ("DIFERENCIA", "Diferencia", "minus.circle"),
    ("INTERSECCION", "Intersección", "circle.circle"),
    ("VACIADO", "Vaciado", "square.on.square.dashed"),
    ("SIMETRIA", "Simetría", "arrow.left.and.right"),
    ("REPETICION", "Repetición", "square.grid.3x1.below.line.grid.1x2"),
]

struct VistaPrincipal: View {

    @ObservedObject var estado: EstadoDeLaApp
    @State private var mostrarGuia = false
    @State private var queCrear = ""
    @State private var paraQueSirve = ""
    @State private var medidaClave = ""
    @State private var modoAvanzado = false
    /// Radio del próximo filete. 1,5 mm es lo que aguanta una pared impresa normal sin
    /// comerse el canto.
    @State private var radioDeFilete: Float = 1.5
    /// Foco del campo de nombre, para que «Renombrar…» del clic derecho lleve el cursor
    /// donde se escribe en lugar de solo seleccionar la pieza.
    @FocusState private var nombreEnfocado: Bool

    var body: some View {
        HSplitView {
            if modoAvanzado {
                panelIzquierdo.frame(minWidth: 240, idealWidth: 265, maxWidth: 340)
            }

            ZStack {
                VisorMetal(
                    editor: estado.editor,
                    alCrear: { estado.registrar($0) },
                    alPinchar: { origen, direccion in estado.senalar(origen: origen, direccion: direccion) },
                    alEmpezarAEmpujar: { origen, direccion in
                        estado.empezarAEmpujar(origen: origen, direccion: direccion)
                    },
                    alEmpujar: { estado.empujar(milimetros: $0) },
                    alEmpezarAMoverPlano: { origen, direccion in
                        estado.empezarAMoverPlano(origen: origen, direccion: direccion)
                    },
                    alMoverPlano: { estado.moverPlano(milimetros: $0) },
                    alSoltarStl: { estado.importarMallaDesde(ruta: $0) }
                )
                .contextMenu { menuDelViewport }
                VStack(spacing: 0) {
                    creadorVibe
                    // La propuesta se queda debajo del campo donde se pidió, no en una
                    // ventana modal aparte: el usuario tiene que poder orbitar la pieza
                    // y mirar lo que hay mientras decide, y un `NSAlert` se lo impide.
                    if let propuesta = estado.propuestaPendiente {
                        PanelDePropuesta(estado: estado, propuesta: propuesta)
                            .padding(.top, 8)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }
                    Spacer()
                    HStack { barraDeEstado; Spacer() }
                }
                .animation(.easeOut(duration: 0.16), value: estado.propuestaPendiente != nil)
            }
            .frame(minWidth: 420, minHeight: 420)

            inspector.frame(minWidth: 250, idealWidth: 280, maxWidth: 360)
        }
        .frame(minWidth: 1080, minHeight: 640)
        .toolbar { barraDeHerramientas }
        .sheet(isPresented: $mostrarGuia) { guiaDeCreacion }
    }

    // MARK: Barra de herramientas

    @ToolbarContentBuilder
    private var barraDeHerramientas: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            Picker("Modo", selection: $modoAvanzado) {
                Text("Crear").tag(false)
                Text("Taller").tag(true)
            }
            .pickerStyle(.segmented).frame(width: 170)
            .help("Crear simplifica la interfaz; Taller muestra el árbol y operaciones")
        }

        ToolbarItemGroup(placement: .navigation) {
            Button { estado.deshacer() } label: { Image(systemName: "arrow.uturn.backward") }
                .disabled(!estado.puedeDeshacer)
                .keyboardShortcut("z", modifiers: .command)
                .help("Deshacer")

            Button { estado.rehacer() } label: { Image(systemName: "arrow.uturn.forward") }
                .disabled(!estado.puedeRehacer)
                .keyboardShortcut("z", modifiers: [.command, .shift])
                .help("Rehacer")
        }

        ToolbarItemGroup {
            Button { mostrarGuia = true } label: { Image(systemName: "wand.and.sparkles") }
                .help("Diseñar mediante preguntas")

            Button { estado.encuadrar() } label: { Image(systemName: "viewfinder") }
                .help("Encuadrar el modelo")

            Button { estado.alternarSeccion() } label: {
                Image(systemName: estado.seccionActiva ? "cube.transparent.fill" : "cube.transparent")
            }
            .help("Sección en vivo: corta el modelo y tiñe las paredes finas")

            Button { estado.duplicar() } label: { Image(systemName: "plus.square.on.square") }
                .keyboardShortcut("d", modifiers: .command)
                .disabled(estado.seleccion.isEmpty)
                .help("Duplicar pieza")

            Menu {
                Button("Soporte") { estado.cargarEjemplo("soporte") }
                Button("Esfera") { estado.cargarEjemplo("esfera") }
                Button("Rejilla") { estado.cargarEjemplo("rejilla") }
                Divider()
                Button("Documento vacío") { estado.cargarEjemplo("vacio") }
            } label: {
                Image(systemName: "square.stack.3d.up")
            }
            .help("Ejemplos")

            Button { estado.abrir() } label: { Image(systemName: "folder") }
                .keyboardShortcut("o", modifiers: .command)
                .help("Abrir")

            Button { estado.guardar() } label: { Image(systemName: "square.and.arrow.down") }
                .keyboardShortcut("s", modifiers: .command)
                .help("Guardar")

            Button { estado.exportarPieza() } label: { Image(systemName: "printer.filled.and.paper") }
                .keyboardShortcut("e", modifiers: [.command, .shift])
                .disabled(estado.exportando || estado.editor.estaVacio)
                .help("Exportar la pieza para imprimir: 3MF o STL")
        }
    }

    private var creadorVibe: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Label(estado.hiloEnCurso == nil ? "Crea describiendo" : "Seguimos con la pieza",
                      systemImage: "sparkles")
                    .font(.system(size: 12, weight: .semibold))
                Spacer()
                AIModelSelector(settings: estado.aiSettings)
            }
            // Que el hilo se vea. Una memoria invisible es peor que ninguna: el usuario
            // escribe «más grueso» sin saber si eso significa algo, y si no funcionara
            // no tendría forma de saber por qué.
            if let hilo = estado.hiloEnCurso {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.turn.down.right")
                        .font(.system(size: 9))
                        .foregroundStyle(.tertiary)
                    Text(hilo)
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                        .lineLimit(1).truncationMode(.tail)
                    Spacer(minLength: 0)
                    Button("Empezar de cero") { estado.olvidarElHilo() }
                        .buttonStyle(.plain)
                        .font(.system(size: 10))
                        .foregroundStyle(.tertiary)
                        .help("El modelo deja de tener en cuenta lo hablado hasta ahora")
                }
            }
            HStack(spacing: 8) {
                TextField(estado.hiloEnCurso == nil ? "Quiero una pieza que…" : "…y ahora, ¿qué le cambiamos?",
                          text: $estado.peticionIA)
                    .textFieldStyle(.plain)
                    .font(.system(size: 14))
                    .onSubmit { estado.construirConIA() }
                Button { estado.construirConIA() } label: {
                    if estado.iaTrabajando { ProgressView().controlSize(.small) }
                    else { Image(systemName: "arrow.up") }
                }
                .buttonStyle(.borderedProminent).tint(.mint)
                .disabled(estado.peticionIA.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || estado.iaTrabajando)
                if estado.iaTrabajando {
                    Button("Cancelar") { estado.cancelarIA() }.controlSize(.small)
                }
            }
            // La fila de la imagen. La medida no es un extra: es lo único que pone a
            // escala lo que el modelo lee en la foto, así que va al lado y no en un
            // panel aparte donde nadie la encontraría.
            HStack(spacing: 8) {
                if let nombre = estado.nombreDeLaImagen {
                    Label(nombre, systemImage: "photo")
                        .font(.system(size: 10))
                        .lineLimit(1).truncationMode(.middle)
                        .frame(maxWidth: 150, alignment: .leading)
                    Button { estado.quitarImagen() } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.plain).foregroundStyle(.secondary)
                        .help("Quitar la imagen")

                    TextField("Medida real, p. ej. «el ancho son 80 mm»", text: $estado.medidaDeReferencia)
                        .textFieldStyle(.roundedBorder)
                        .font(.system(size: 11))
                        .help("Una foto no tiene escala. Da una cota que conozcas y Yunkil ajusta el resto en proporción.")
                } else {
                    Button {
                        estado.adjuntarImagen()
                    } label: {
                        Label("Partir de una imagen…", systemImage: "photo.badge.plus")
                    }
                    .buttonStyle(.bordered).controlSize(.mini)
                    .disabled(estado.iaTrabajando)
                }
                Spacer(minLength: 0)
            }
            if estado.nombreDeLaImagen != nil, let aviso = estado.aiSettings.avisoDeImagen {
                Label(aviso, systemImage: "exclamationmark.triangle")
                    .font(.system(size: 9)).foregroundStyle(.orange).lineLimit(2)
            }
            if estado.nombreDeLaImagen != nil,
               estado.medidaDeReferencia.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text("Sin una medida real, la pieza saldrá con las proporciones correctas pero a un tamaño cualquiera; Yunkil dirá qué cota necesita.")
                    .font(.system(size: 9)).foregroundStyle(.secondary).lineLimit(2)
            }
            HStack(spacing: 6) {
                Button("Diseñar conmigo…") { mostrarGuia = true }
                Button("Caja a medida") { estado.peticionIA = "Crea una caja hueca imprimible con tapa, pregúntame las medidas que falten" }
                Button("Soporte de móvil") { estado.peticionIA = "Crea un soporte estable para mi móvil, pregúntame sus medidas" }
                Button("Adaptador") { estado.peticionIA = "Crea un adaptador entre dos medidas, pregúntame los diámetros" }
            }
            .buttonStyle(.bordered).controlSize(.mini)
            if let resultado = estado.resultadoIA {
                Text(resultado).font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(2)
            }
            Text(estado.aiSettings.status).font(.system(size: 9)).foregroundStyle(.tertiary)
        }
        .padding(13)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 13))
        .overlay(RoundedRectangle(cornerRadius: 13).stroke(.white.opacity(0.12)))
        .frame(maxWidth: 680)
        .padding(14)
    }

    private var guiaDeCreacion: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Diseñemos tu pieza").font(.system(size: 25, weight: .bold, design: .rounded))
                    Text("No necesitas saber modelado 3D. Cuéntame el problema.").foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "wand.and.stars").font(.system(size: 34)).foregroundStyle(.mint)
            }

            pregunta("1", "¿Qué quieres crear?", "Un soporte para dejar el mando bajo la mesa…", $queCrear)
            pregunta("2", "¿Para qué debe servir o qué debe sujetar?", "Debe aguantar 500 g y atornillarse con dos tornillos…", $paraQueSirve)
            pregunta("3", "¿Qué medida no puede fallar?", "El mando mide 152 × 105 × 62 mm…", $medidaClave)

            HStack {
                Text("Yunkil propondrá geometría fabricable y podrás deshacerla con ⌘Z.")
                    .font(.system(size: 10)).foregroundStyle(.secondary)
                Spacer()
                Button("Cancelar") { mostrarGuia = false }
                Button("Crear primera propuesta", systemImage: "sparkles") {
                    estado.peticionIA = "Quiero crear: \(queCrear). Uso y requisitos: \(paraQueSirve). Medida crítica: \(medidaClave). Si falta un dato imprescindible, haz una propuesta conservadora y explica qué medida debo revisar."
                    mostrarGuia = false
                    estado.construirConIA()
                }
                .buttonStyle(.borderedProminent).tint(.mint)
                .disabled(queCrear.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(28).frame(width: 620)
    }

    private func pregunta(_ numero: String, _ titulo: String, _ ejemplo: String, _ texto: Binding<String>) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(numero).font(.system(size: 13, weight: .bold)).foregroundStyle(.black)
                .frame(width: 26, height: 26).background(.mint, in: Circle())
            VStack(alignment: .leading, spacing: 7) {
                Text(titulo).font(.system(size: 13, weight: .semibold))
                TextField(ejemplo, text: texto, axis: .vertical)
                    .textFieldStyle(.roundedBorder).lineLimit(2...4)
            }
        }
    }

    // MARK: Panel izquierdo — árbol y paleta

    private var panelIzquierdo: some View {
        VStack(spacing: 0) {
            encabezado

            List(selection: Binding(
                get: { estado.seleccion },
                set: { if let id = $0 { estado.seleccionar(id) } }
            )) {
                ForEach(estado.filas) { fila in
                    filaDelArbol(fila).tag(fila.id)
                }
            }
            .listStyle(.sidebar)

            Divider()
            paleta
        }
    }

    private var encabezado: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 24, height: 24)
            Text("Yunkil").font(.system(size: 19, weight: .semibold, design: .rounded))
            Text("sólidos por campo de distancia")
                .font(.system(size: 9)).foregroundStyle(.tertiary)
            Spacer()
        }
        .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 8)
    }

    private func filaDelArbol(_ fila: Fila) -> some View {
        HStack(spacing: 6) {
            // La sangría comunica la jerarquía sin gastar un control desplegable,
            // que en árboles poco profundos estorba más de lo que ayuda.
            if fila.profundidad > 0 {
                Rectangle().fill(.clear).frame(width: CGFloat(fila.profundidad) * 12, height: 1)
            }

            Image(systemName: fila.esOperacion ? "square.on.square" : "cube.fill")
                .font(.system(size: 10))
                .foregroundStyle(fila.esOperacion ? Color.orange : Color.teal)

            Text(fila.nombre)
                .font(.system(size: 12))
                .foregroundStyle(fila.visible ? .primary : .tertiary)
                .strikethrough(!fila.visible, color: .secondary)

            Spacer(minLength: 4)

            if fila.esOperacion && fila.numeroDeHijos == 0 {
                // Una operación sin hijos no aporta material: avisar aquí ahorra
                // buscar por qué no se ve nada.
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 9)).foregroundStyle(.yellow)
                    .help("Operación sin piezas dentro")
            }

            Button { estado.alternarVisibilidad(fila.id) } label: {
                Image(systemName: fila.visible ? "eye" : "eye.slash")
                    .font(.system(size: 10))
                    .foregroundStyle(fila.visible ? .secondary : .tertiary)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 1)
        .contentShape(Rectangle())
        .contextMenu { menuDeLaPieza(fila) }
    }

    /// Clic derecho sobre una pieza del árbol.
    ///
    /// Todo lo de aquí ya existía en el núcleo y no se podía alcanzar. Es la diferencia
    /// entre una demo y una herramienta: nadie va a buscar «extraer del grupo» en una barra
    /// de botones, se busca donde está la pieza.
    @ViewBuilder
    private func menuDeLaPieza(_ fila: Fila) -> some View {
        Button("Renombrar…") { estado.seleccionar(fila.id); nombreEnfocado = true }
        Button("Duplicar") { estado.seleccionar(fila.id); estado.duplicar() }
        Divider()
        Button("Copiar") { estado.copiar(fila.id) }
        Button("Pegar dentro") { estado.pegar(en: fila.id) }
            .disabled(!estado.hayAlgoQuePegar)
        Divider()
        Button("Aislar") { estado.aislar(fila.id) }
        Button(fila.visible ? "Ocultar" : "Mostrar") { estado.alternarVisibilidad(fila.id) }
        Button("Mostrar todo") { estado.mostrarTodo() }
        Divider()
        Button("Sacar del grupo") { estado.extraer(fila.id) }
            .disabled(fila.profundidad < 2)
        Menu("Envolver en") {
            Button("Unión") { estado.seleccionar(fila.id); estado.envolver("UNION") }
            Button("Diferencia") { estado.seleccionar(fila.id); estado.envolver("DIFERENCIA") }
            Button("Intersección") { estado.seleccionar(fila.id); estado.envolver("INTERSECCION") }
            Button("Vaciado") { estado.seleccionar(fila.id); estado.envolver("VACIADO") }
            Button("Simetría") { estado.seleccionar(fila.id); estado.envolver("SIMETRIA") }
            Button("Repetición") { estado.seleccionar(fila.id); estado.envolver("REPETICION") }
        }
        .disabled(fila.id == estado.raizId)
        Divider()
        Button("Subir") { estado.seleccionar(fila.id); estado.desplazar(haciaArriba: true) }
        Button("Bajar") { estado.seleccionar(fila.id); estado.desplazar(haciaArriba: false) }
        Divider()
        Button("Eliminar") { estado.seleccionar(fila.id); estado.eliminar() }
            .disabled(fila.id == estado.raizId)
    }

    private var paleta: some View {
        VStack(alignment: .leading, spacing: 10) {
            grupoDeBotones("Añadir primitiva", primitivas) { estado.anadir($0) }
            grupoDeBotones("Añadir contorno", contornos) { estado.anadir($0) }
            VStack(alignment: .leading, spacing: 3) {
                Text("Traer de fuera").font(.system(size: 10, weight: .semibold)).foregroundStyle(.secondary)
                Button { estado.importarMalla() } label: {
                    Label("Importar STL…", systemImage: "square.and.arrow.down")
                }
                .buttonStyle(.bordered).controlSize(.small)
                .help("Un STL descargado o escaneado. El viewport enseña su envolvente hasta que llegue el enlace de textura; exportar, restar y analizar ya usan la forma real.")
            }
            grupoDeBotones("Añadir operación", operaciones) { estado.anadir($0) }

            HStack(spacing: 6) {
                Button { estado.desplazar(haciaArriba: true) } label: {
                    Image(systemName: "arrow.up")
                }
                Button { estado.desplazar(haciaArriba: false) } label: {
                    Image(systemName: "arrow.down")
                }
                Spacer()
                Button(role: .destructive) { estado.eliminar() } label: {
                    Image(systemName: "trash")
                }
                .keyboardShortcut(.delete, modifiers: [])
                Button { estado.duplicar() } label: {
                    Image(systemName: "plus.square.on.square")
                }
                .keyboardShortcut("d", modifiers: .command)
                .help("Duplicar")
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
        }
        .padding(12)
    }

    private func grupoDeBotones(
        _ titulo: String,
        _ elementos: [(String, String, String)],
        accion: @escaping (String) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(titulo.uppercased())
                .font(.system(size: 9, weight: .bold)).foregroundStyle(.tertiary).tracking(0.6)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 3), spacing: 4) {
                ForEach(elementos, id: \.0) { tipo, etiqueta, _ in
                    Button(etiqueta) { accion(tipo) }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .font(.system(size: 10))
                }
            }
        }
    }

    // MARK: Inspector

    /// Clic derecho en la vista 3D.
    ///
    /// Actúa sobre lo último que se pinchó, que es lo que hace que las órdenes puedan
    /// hablar de «este canto» y «esta cara» sin teclear una coordenada. Con el botón
    /// derecho macOS no cambia la selección, así que hay que pinchar antes con el
    /// izquierdo: es la misma secuencia que en el Finder.
    @ViewBuilder
    private var menuDelViewport: some View {
        if estado.seleccion.isEmpty {
            Text("Pincha una pieza primero")
        } else {
            Text(estado.nombre)
            Divider()
            Button("Filete de \(String(format: "%.1f", radioDeFilete)) mm aquí") {
                estado.filetearEnElPuntoSenalado(radioDeFilete)
            }
            .disabled(!estado.puedeFiletear)
            Button("Apoyar esta cara en el plato") { estado.apoyarLaCaraSenalada() }
                .disabled(!estado.puedeFiletear)
            Divider()
            Button("Enfocar la pieza") { estado.enfocarSeleccion() }
            Button("Aislar") { estado.aislar(estado.seleccion) }
            Button("Mostrar todo") { estado.mostrarTodo() }
            Divider()
            Menu("Vista") {
                Button("Planta") { estado.mirarDesde(.planta) }
                Button("Alzado") { estado.mirarDesde(.alzado) }
                Button("Perfil") { estado.mirarDesde(.perfil) }
                Button("Isométrica") { estado.mirarDesde(.isometrica) }
                Divider()
                Button(estado.esOrtografica ? "Con perspectiva" : "Sin perspectiva") {
                    estado.alternarOrtografica()
                }
                Divider()
                Button(estado.seccionActiva ? "Quitar sección en vivo" : "Sección en vivo") {
                    estado.alternarSeccion()
                }
                Button("Sección por este plano") { estado.seccionPorElPuntoSenalado() }
                    .disabled(!estado.puedeFiletear)
            }
            Divider()
            Button("Duplicar") { estado.duplicar() }
            Button("Copiar") { estado.copiar(estado.seleccion) }
            Button("Eliminar") { estado.eliminar() }
        }
    }

    /// Filete de un canto concreto.
    ///
    /// Las coordenadas del filete no se teclean: salen del último punto pinchado en el
    /// viewport, que es la forma en la que una persona señala «este canto». Los cuatro
    /// parámetros del acuerdo (alcance y centro) existen en el documento y se pueden ver,
    /// pero se quitan de «Medidas» porque nadie quiere tres deslizadores de coordenadas
    /// donde espera cotas.
    @ViewBuilder
    private var seccionDeFilete: some View {
        seccion("Filete") {
            if estado.seleccionTieneFilete {
                Text("Canto redondeado \(String(format: "%.1f", estado.radioDelFilete)) mm")
                    .font(.system(size: 11))
                Button("Quitar filete") { estado.quitarFilete() }
                    .font(.system(size: 11))
            } else if estado.puedeFiletear {
                HStack(spacing: 6) {
                    Text("Radio").font(.system(size: 10)).foregroundStyle(.secondary)
                    TextField("mm", value: $radioDeFilete, format: .number)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 52)
                        .font(.system(size: 11))
                    Button("Aquí") { estado.filetearEnElPuntoSenalado(radioDeFilete) }
                        .font(.system(size: 11))
                }
                Text("Se aplica en el último punto que hayas pinchado en la vista.")
                    .font(.system(size: 10)).foregroundStyle(.tertiary)
            } else {
                Text("Pincha el canto que quieras redondear y vuelve aquí.")
                    .font(.system(size: 10)).foregroundStyle(.tertiary)
            }
        }
    }

    private var inspector: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if !modoAvanzado {
                    seccion("Tu pieza") {
                        Label("Describe arriba lo que necesitas", systemImage: "1.circle.fill")
                        Label("Ajusta aquí sus medidas", systemImage: "2.circle.fill")
                        Label("Exporta la pieza verificada", systemImage: "3.circle.fill")
                        Text("Yunkil mantiene el modelo editable aunque lo haya creado la IA.")
                            .font(.system(size: 10)).foregroundStyle(.secondary)
                    }
                }

                if estado.seleccion.isEmpty {
                    Text("Sin selección").font(.caption).foregroundStyle(.secondary)
                } else {
                    seccion("Pieza") {
                        TextField("Nombre", text: Binding(
                            get: { estado.nombre },
                            set: { estado.nombre = $0 }
                        ))
                        .textFieldStyle(.roundedBorder)
                        .font(.system(size: 12))
                        .focused($nombreEnfocado)
                        .onSubmit { estado.aplicarNombre(); nombreEnfocado = false }

                        Text(estado.tipoSeleccionado.capitalized)
                            .font(.system(size: 10)).foregroundStyle(.tertiary)
                    }

                    if !estado.parametros.isEmpty {
                        seccion("Medidas") {
                            ForEach(estado.parametros.filter { !$0.clave.hasPrefix("acuerdo") }) { p in
                                deslizador(p)
                            }
                        }
                    }

                    if estado.esBooleanaSeleccionada {
                        seccionDeFilete
                    }

                    if estado.tipoSeleccionado == "SIMETRIA" || estado.tipoSeleccionado == "REPETICION" {
                        seccion("Disposición") {
                            Picker("Eje", selection: Binding(
                                get: { estado.eje },
                                set: { estado.aplicarEje($0) }
                            )) {
                                Text("X").tag("X"); Text("Y").tag("Y"); Text("Z").tag("Z")
                            }
                            .pickerStyle(.segmented)

                            if estado.tipoSeleccionado == "REPETICION" {
                                Stepper(
                                    "Copias: \(estado.cuenta)",
                                    value: Binding(get: { estado.cuenta }, set: { estado.aplicarCuenta($0) }),
                                    in: 1...64
                                )
                                .font(.system(size: 11))
                            }
                        }
                    }

                    seccion("Posición y giro") {
                        campoNumerico("X", $estado.posX)
                        campoNumerico("Y", $estado.posY)
                        campoNumerico("Z", $estado.posZ)
                        Divider().padding(.vertical, 2)
                        campoNumerico("Giro X", $estado.giroX, unidad: "°")
                        campoNumerico("Giro Y", $estado.giroY, unidad: "°")
                        campoNumerico("Giro Z", $estado.giroZ, unidad: "°")
                        Divider().padding(.vertical, 2)
                        campoNumerico("Escala", $estado.escala, unidad: "×")
                    }

                    if modoAvanzado {
                        seccion("Envolver en") {
                            HStack(spacing: 5) {
                                ForEach(["VACIADO", "SIMETRIA", "REPETICION"], id: \.self) { t in
                                    Button(t.capitalized) { estado.envolver(t) }
                                        .buttonStyle(.bordered).controlSize(.small)
                                        .font(.system(size: 10))
                                }
                            }
                        }
                    }
                }

                if modoAvanzado {
                    seccion("Diagnóstico") {
                        fila("Uniforms", "\(estado.editor.numeroDeUniforms)")
                        fila("Último cambio", estado.recompiloElUltimoCambio ? "recompiló" : "solo uniforms")
                        if let aviso = estado.aviso {
                            Text(aviso)
                                .font(.system(size: 10)).foregroundStyle(.orange)
                            .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                } else if let aviso = estado.aviso {
                    Text(aviso).font(.system(size: 10)).foregroundStyle(.orange)
                }

                seccion("Fabricar") {
                    HStack {
                        Text("Detalle").font(.system(size: 11))
                        Slider(value: $estado.resolucionExportacion, in: 0.1...1.5, step: 0.05)
                        Text(String(format: "%.2f mm", estado.resolucionExportacion))
                            .font(.system(size: 10, design: .monospaced))
                            .frame(width: 62, alignment: .trailing)
                    }
                    Text("Aproximadamente \(estado.celdasDeExportacion.formatted()) celdas")
                        .font(.system(size: 9)).foregroundStyle(.tertiary)

                    if estado.exportando {
                        ProgressView(value: estado.progresoExportacion) {
                            Text("Generando y verificando la malla…")
                                .font(.system(size: 10))
                        }
                    } else {
                        Button("Exportar pieza…") { estado.exportarPieza() }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.small)
                            .disabled(estado.editor.estaVacio)
                    }

                    if let resultado = estado.resultadoExportacion {
                        Text(resultado)
                            .font(.system(size: 9, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                }

                seccion("Fabricación") { panelDeFabricacion }

                if modoAvanzado { seccion("Crear con IA local") {
                    TextField("Ej. soporte de móvil de 80 mm…", text: $estado.peticionIA, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(2...5)
                        .onSubmit { estado.construirConIA() }
                    Button(estado.iaTrabajando ? "Construyendo…" : "Construir propuesta", systemImage: "sparkles") {
                        estado.construirConIA()
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                    .disabled(estado.peticionIA.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || estado.iaTrabajando)
                    if let resultado = estado.resultadoIA {
                        Text(resultado).font(.system(size: 10)).foregroundStyle(.secondary)
                    }
                    Text("Privado · Hearthia en este Mac · siempre deshacer con ⌘Z")
                        .font(.system(size: 9)).foregroundStyle(.tertiary)
                } }

                Spacer(minLength: 0)
            }
            .padding(14)
        }
    }

    // MARK: Analizador de fabricación

    /// El examen de la pieza contra una impresora concreta.
    ///
    /// Cada aviso enseña lo medido, el umbral con el que se compara y de qué perfil
    /// sale ese umbral. Un aviso que no dice contra qué compara no se puede discutir,
    /// y lo que no se puede discutir se acaba ignorando.
    @ViewBuilder
    private var panelDeFabricacion: some View {
        Picker("Perfil", selection: $estado.perfilDeFabricacion) {
            ForEach(estado.perfilesDisponibles, id: \.self) { Text($0).tag($0) }
        }
        .labelsHidden().controlSize(.small)
        .onChange(of: estado.perfilDeFabricacion) { _, _ in
            if estado.informe != nil { estado.analizarFabricacion() }
        }

        HStack(spacing: 6) {
            Button(estado.analizando ? "Analizando…" : "Analizar pieza", systemImage: "checkmark.seal") {
                estado.analizarFabricacion()
            }
            .buttonStyle(.borderedProminent).controlSize(.small)
            .disabled(estado.analizando || estado.editor.estaVacio)

            Button("Asentar") { estado.asentarEnPlato() }
                .controlSize(.small)
                .help("Baja la pieza hasta apoyarla en el plato")
        }

        Toggle("Analizar al crear con IA", isOn: $estado.analizarAlCrear)
            .font(.system(size: 10)).controlSize(.mini)
            .onChange(of: estado.analizarAlCrear) { _, nuevo in
                UserDefaults.standard.set(nuevo, forKey: "fab.auto")
            }

        if let informe = estado.informe {
            HStack(spacing: 6) {
                Image(systemName: informe.aptoParaImprimir ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(informe.aptoParaImprimir ? .green : .orange)
                Text("Imprimibilidad \(informe.puntuacion)/100")
                    .font(.system(size: 11, weight: .semibold))
                Spacer()
            }
            Text(informe.resumen).font(.system(size: 10)).foregroundStyle(.secondary)

            fila("Volumen", String(format: "%.1f cm³", informe.metricas.volumen / 1000))
            fila("Base", String(format: "%.0f mm²", informe.metricas.areaDeContacto))
            fila("En voladizo", String(format: "%.0f %%", informe.metricas.fraccionEnVoladizo * 100))
            fila("Pared mínima", String(format: "%.2f mm", informe.metricas.espesorMinimo))

            ForEach(Array(informe.hallazgos.enumerated()), id: \.offset) { _, hallazgo in
                tarjetaDeHallazgo(hallazgo)
            }

            if let mejor = estado.orientaciones.first, !mejor.esLaActual {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Mejor orientación").font(.system(size: 10, weight: .semibold))
                    Text("\(mejor.descripcion): \(Int(mejor.fraccionEnVoladizo * 100)) % en voladizo, "
                         + "base de \(Int(mejor.areaDeContacto)) mm².")
                        .font(.system(size: 10)).foregroundStyle(.secondary)
                    Button("Orientar así") { estado.aplicarOrientacion(mejor) }
                        .controlSize(.small)
                }
                .padding(7)
                .background(.blue.opacity(0.09), in: RoundedRectangle(cornerRadius: 6))
            }
        } else if !estado.analizando {
            Text("Sin analizar. El examen mide pared, voladizo, apoyo y encaje contra la impresora elegida.")
                .font(.system(size: 9)).foregroundStyle(.tertiary)
        }
    }

    private func tarjetaDeHallazgo(_ hallazgo: Hallazgo) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 5) {
                Circle().fill(colorDe(hallazgo.severidad)).frame(width: 7, height: 7)
                Text(hallazgo.titulo).font(.system(size: 11, weight: .semibold))
                Spacer()
                Text(hallazgo.severidad.etiqueta)
                    .font(.system(size: 9)).foregroundStyle(colorDe(hallazgo.severidad))
            }
            if let pieza = hallazgo.piezaNombre {
                Text("en «\(pieza)»").font(.system(size: 10)).foregroundStyle(.secondary)
            }
            Text(hallazgo.detalle).font(.system(size: 10)).foregroundStyle(.secondary)

            if !hallazgo.correcciones.isEmpty {
                HStack(spacing: 5) {
                    ForEach(Array(hallazgo.correcciones.enumerated()), id: \.offset) { _, correccion in
                        Button(correccion.etiqueta) { estado.aplicarCorreccion(correccion) }
                            .controlSize(.mini)
                    }
                }
            }
        }
        .padding(7)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colorDe(hallazgo.severidad).opacity(0.09), in: RoundedRectangle(cornerRadius: 6))
    }

    private func colorDe(_ severidad: Severidad) -> Color {
        switch severidad {
        case .fallara: .red
        case .probable: .orange
        default: .blue
        }
    }

    private func deslizador(_ p: Parametro) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack {
                Text(p.etiqueta).font(.system(size: 11))
                Spacer()
                Text(String(format: "%.2f %@", p.valor, p.unidad))
                    .font(.system(size: 10, design: .monospaced)).foregroundStyle(.secondary)
            }
            Slider(
                value: Binding(
                    get: { p.valor },
                    set: { estado.fijarParametro(p.clave, $0) }
                ),
                in: p.minimo...p.maximo
            ) { empezando in
                // El punto de deshacer se marca al empezar el arrastre, no en cada
                // fotograma: si no, el historial quedaría inservible.
                if empezando { estado.empezarEdicionContinua() }
            }
        }
    }

    private func campoNumerico(_ etiqueta: String, _ valor: Binding<Float>, unidad: String = "mm") -> some View {
        HStack {
            Text(etiqueta).font(.system(size: 11)).frame(width: 52, alignment: .leading)
            TextField("", value: valor, format: .number.precision(.fractionLength(0...2)))
                .textFieldStyle(.roundedBorder)
                .font(.system(size: 11, design: .monospaced))
                .multilineTextAlignment(.trailing)
                .onSubmit { estado.aplicarTransform() }
            Text(unidad).font(.system(size: 9)).foregroundStyle(.tertiary).frame(width: 18)
        }
    }

    private var barraDeEstado: some View {
        Text(estado.estadoDelRender)
            .font(.system(size: 11, weight: .medium, design: .monospaced))
            .foregroundStyle(.white.opacity(0.85))
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 7))
            .padding(12)
    }

    @ViewBuilder
    private func seccion<C: View>(_ titulo: String, @ViewBuilder contenido: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(titulo.uppercased())
                .font(.system(size: 9, weight: .bold)).foregroundStyle(.tertiary).tracking(0.6)
            contenido()
        }
    }

    private func fila(_ etiqueta: String, _ valor: String) -> some View {
        HStack {
            Text(etiqueta).font(.system(size: 11))
            Spacer()
            Text(valor).font(.system(size: 10, design: .monospaced)).foregroundStyle(.secondary)
        }
    }
}

private extension Collection {
    subscript(safe index: Index) -> Element? { indices.contains(index) ? self[index] : nil }
}

@main
struct YunkilApp: App {

    /// El estado vive en la escena para que los menús puedan invocarlo.
    ///
    /// Sin esto los atajos no llegan: un `keyboardShortcut` declarado dentro de la vista se
    /// anuncia pero no se registra en la barra de menús, y el usuario pulsa ⌘D y no pasa
    /// nada. Es el mismo fallo que ya se cazó en Editorcito.
    @StateObject private var estado = EstadoDeLaApp()

    var body: some Scene {
        WindowGroup("Yunkil") { VistaPrincipal(estado: estado) }
            .commands { MenusDeYunkil(estado: estado) }
    }
}

/// La barra de menús, con los atajos que tiene en los dedos quien viene de un CAD.
struct MenusDeYunkil: Commands {
    @ObservedObject var estado: EstadoDeLaApp

    var body: some Commands {
        CommandGroup(after: .pasteboard) {
            Divider()
            Button("Duplicar") { estado.duplicar() }
                .keyboardShortcut("d", modifiers: .command)
            Button("Copiar pieza") { estado.copiar(estado.seleccion) }
                .keyboardShortcut("c", modifiers: [.command, .shift])
            Button("Pegar pieza") { estado.pegar(en: estado.seleccion) }
                .keyboardShortcut("v", modifiers: [.command, .shift])
                .disabled(!estado.hayAlgoQuePegar)
        }

        CommandMenu("Pieza") {
            Button("Aislar") { estado.aislar(estado.seleccion) }
                .keyboardShortcut("i", modifiers: [.command, .shift])
            Button("Mostrar todo") { estado.mostrarTodo() }
                .keyboardShortcut("i", modifiers: [.command, .option])
            Button("Sacar del grupo") { estado.extraer(estado.seleccion) }
                .keyboardShortcut(.upArrow, modifiers: [.command, .shift])
            Divider()
            Button("Apoyar la cara señalada en el plato") { estado.apoyarLaCaraSenalada() }
                .keyboardShortcut("p", modifiers: [.command, .shift])
            Divider()
            Button("Eliminar") { estado.eliminar() }
                .keyboardShortcut(.delete, modifiers: [])
        }

        CommandMenu("Vista") {
            // Los números son los de cualquier CAD. Reinventarlos solo obligaría a
            // reaprender lo único que alguien ya sabe hacer sin mirar.
            Button("Planta") { estado.mirarDesde(.planta) }
                .keyboardShortcut("1", modifiers: [])
            Button("Alzado") { estado.mirarDesde(.alzado) }
                .keyboardShortcut("3", modifiers: [])
            Button("Perfil") { estado.mirarDesde(.perfil) }
                .keyboardShortcut("7", modifiers: [])
            Button("Isométrica") { estado.mirarDesde(.isometrica) }
                .keyboardShortcut("9", modifiers: [])
            Divider()
            Button(estado.esOrtografica ? "Con perspectiva" : "Sin perspectiva") {
                estado.alternarOrtografica()
            }
            .keyboardShortcut("5", modifiers: [])
            Divider()
            Button(estado.seccionActiva ? "Quitar sección en vivo" : "Sección en vivo") {
                estado.alternarSeccion()
            }
            .keyboardShortcut("6", modifiers: [])
            Divider()
            Button("Enfocar la pieza") { estado.enfocarSeleccion() }
                .keyboardShortcut("f", modifiers: [])
            Button("Encuadrar todo") { estado.encuadrarTodo() }
                .keyboardShortcut("f", modifiers: .shift)
        }
    }
}
