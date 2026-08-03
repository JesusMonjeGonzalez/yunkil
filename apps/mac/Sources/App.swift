import AppKit
import MetalKit
import SwiftUI
import UniformTypeIdentifiers
import YunkilCore

// MARK: - Vista Metal con gestos

/// `MTKView` que además atiende ratón y trackpad.
///
/// Los eventos se manejan aquí y no en SwiftUI porque orbitar necesita el delta
/// crudo del dispositivo: pasarlo por un `DragGesture` pierde precisión y añade
/// latencia perceptible al arrastrar.
final class VistaMetalInteractiva: MTKView {

    var renderizador: Renderizador?

    override var acceptsFirstResponder: Bool { true }

    override func mouseDragged(with evento: NSEvent) {
        guard let renderizador else { return }
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

    func makeNSView(context: Context) -> VistaMetalInteractiva {
        let vista = VistaMetalInteractiva()
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

    let editor = Editor(inicial: ModelosDemo.shared.soporte())

    @Published private(set) var filas: [Fila] = []
    @Published private(set) var parametros: [Parametro] = []
    @Published var seleccion: String = ""
    @Published var estadoDelRender = "iniciando…"
    @Published var aviso: String?
    @Published private(set) var recompiloElUltimoCambio = false

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

    var puedeDeshacer: Bool { editor.puedeDeshacer }
    var puedeRehacer: Bool { editor.puedeRehacer }
    var tipoSeleccionado: String { filas.first { $0.id == seleccion }?.tipo ?? "" }

    init() { refrescar(recompilo: true) }

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

    func anadir(_ tipo: String) {
        let recompilo = editor.anadir(tipoNombre: tipo, padreId: seleccion.isEmpty ? nil : seleccion)
        refrescar(recompilo: recompilo)
        renderizador?.encuadrar()
    }

    func eliminar() {
        guard !seleccion.isEmpty else { return }
        refrescar(recompilo: editor.eliminar(id: seleccion))
    }

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

    func deshacer() { if editor.deshacer() { refrescar(recompilo: true) } }
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
            try editor.aJson().write(to: url, atomically: true, encoding: .utf8)
        } catch {
            aviso = "No se pudo guardar: \(error.localizedDescription)"
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

private let operaciones: [(String, String, String)] = [
    ("UNION", "Unión", "plus.circle"),
    ("DIFERENCIA", "Diferencia", "minus.circle"),
    ("INTERSECCION", "Intersección", "circle.circle"),
    ("VACIADO", "Vaciado", "square.on.square.dashed"),
    ("SIMETRIA", "Simetría", "arrow.left.and.right"),
    ("REPETICION", "Repetición", "square.grid.3x1.below.line.grid.1x2"),
]

struct VistaPrincipal: View {

    @StateObject private var estado = EstadoDeLaApp()

    var body: some View {
        HSplitView {
            panelIzquierdo.frame(minWidth: 240, idealWidth: 265, maxWidth: 340)

            ZStack(alignment: .bottomLeading) {
                VisorMetal(editor: estado.editor) { estado.registrar($0) }
                barraDeEstado
            }
            .frame(minWidth: 420, minHeight: 420)

            inspector.frame(minWidth: 250, idealWidth: 280, maxWidth: 360)
        }
        .frame(minWidth: 1080, minHeight: 640)
        .toolbar { barraDeHerramientas }
    }

    // MARK: Barra de herramientas

    @ToolbarContentBuilder
    private var barraDeHerramientas: some ToolbarContent {
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
            Button { estado.encuadrar() } label: { Image(systemName: "viewfinder") }
                .help("Encuadrar el modelo")

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
    }

    private var paleta: some View {
        VStack(alignment: .leading, spacing: 10) {
            grupoDeBotones("Añadir primitiva", primitivas) { estado.anadir($0) }
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

    private var inspector: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
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
                        .onSubmit { estado.aplicarNombre() }

                        Text(estado.tipoSeleccionado.capitalized)
                            .font(.system(size: 10)).foregroundStyle(.tertiary)
                    }

                    if !estado.parametros.isEmpty {
                        seccion("Medidas") {
                            ForEach(estado.parametros) { p in
                                deslizador(p)
                            }
                        }
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

                seccion("Diagnóstico") {
                    fila("Uniforms", "\(estado.editor.numeroDeUniforms)")
                    fila("Último cambio", estado.recompiloElUltimoCambio ? "recompiló" : "solo uniforms")
                    if let aviso = estado.aviso {
                        Text(aviso)
                            .font(.system(size: 10)).foregroundStyle(.orange)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                Spacer(minLength: 0)
            }
            .padding(14)
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

@main
struct YunkilApp: App {
    var body: some Scene {
        WindowGroup("Yunkil") { VistaPrincipal() }
    }
}
