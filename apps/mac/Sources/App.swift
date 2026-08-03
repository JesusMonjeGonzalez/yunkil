import AppKit
import MetalKit
import SwiftUI
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
            renderizador.camara.desplazar(
                deltaX: Float(evento.deltaX),
                deltaY: Float(evento.deltaY)
            )
        } else {
            renderizador.camara.orbitar(
                deltaX: Float(evento.deltaX) * 0.008,
                deltaY: Float(evento.deltaY) * 0.008
            )
        }
    }

    override func rightMouseDragged(with evento: NSEvent) {
        renderizador?.camara.desplazar(
            deltaX: Float(evento.deltaX),
            deltaY: Float(evento.deltaY)
        )
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

// MARK: - Puente con SwiftUI

struct VisorMetal: NSViewRepresentable {

    let escena: Escena
    let alCrear: (Renderizador) -> Void

    func makeNSView(context: Context) -> VistaMetalInteractiva {
        let vista = VistaMetalInteractiva()
        // La cadencia objetivo la manda la pantalla, no una constante optimista:
        // pedir 120 en un monitor de 60 haría creer al gobernador que va tarde
        // cuando en realidad solo está esperando al vsync.
        vista.preferredFramesPerSecond = NSScreen.main?.maximumFramesPerSecond ?? 60
        vista.isPaused = false
        vista.enableSetNeedsDisplay = false

        guard let renderizador = Renderizador(vista: vista, escena: escena) else {
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

// MARK: - Modelo de la interfaz

@MainActor
final class EstadoDeLaApp: ObservableObject {

    @Published var grosor: Float = 3.0
    @Published var radioTaladro: Float = 3.2
    @Published var fusion: Float = 5.0
    @Published var estadoDelRender: String = "iniciando…"
    @Published var ultimaRecompilacion: String = "—"

    let escena = Escena(raizInicial: ModelosDemo.shared.soporte())
    private weak var renderizador: Renderizador?

    func registrar(_ r: Renderizador) {
        renderizador = r
        r.alActualizarEstado = { [weak self] texto in
            Task { @MainActor in self?.estadoDelRender = texto }
        }
    }

    /// Reconstruye el árbol con los valores actuales.
    ///
    /// Como los tres deslizadores solo tocan parámetros y no la estructura, esto
    /// jamás debería recompilar el shader. Se muestra en pantalla precisamente para
    /// poder comprobarlo mientras se arrastra.
    func aplicarParametros() {
        guard let renderizador else { return }
        let nuevo = ModelosDemo.shared.soporteCon(
            grosor: grosor,
            radioTaladro: radioTaladro,
            fusion: fusion
        )
        let huellaAntes = escena.huellaTopologica
        renderizador.reemplazarModelo(nuevo)
        ultimaRecompilacion = escena.huellaTopologica == huellaAntes
            ? "no (solo uniforms)"
            : "sí (cambió la topología)"
    }

    func cargarModelo(_ nuevo: any SdfNode) {
        renderizador?.reemplazarModelo(nuevo)
        ultimaRecompilacion = "sí (cambió la topología)"
    }
}

// MARK: - Interfaz

struct VistaPrincipal: View {

    @StateObject private var estado = EstadoDeLaApp()

    var body: some View {
        HSplitView {
            panelLateral
                .frame(minWidth: 260, idealWidth: 290, maxWidth: 360)

            ZStack(alignment: .bottomLeading) {
                VisorMetal(escena: estado.escena) { estado.registrar($0) }
                barraDeEstado
            }
            .frame(minWidth: 520, minHeight: 420)
        }
        .frame(minWidth: 860, minHeight: 560)
    }

    private var panelLateral: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Yunkil").font(.system(size: 26, weight: .semibold, design: .rounded))
                    Text("Sólidos por campo de distancia")
                        .font(.caption).foregroundStyle(.secondary)
                }

                seccion("Parámetros") {
                    deslizador("Grosor de pared", valor: $estado.grosor, rango: 1.0...8.0, unidad: "mm")
                    deslizador("Radio del taladro", valor: $estado.radioTaladro, rango: 1.0...8.0, unidad: "mm")
                    deslizador("Acuerdo", valor: $estado.fusion, rango: 0.0...12.0, unidad: "mm")
                }

                seccion("Modelo") {
                    HStack(spacing: 8) {
                        Button("Soporte") { estado.cargarModelo(ModelosDemo.shared.soporte()) }
                        Button("Esfera") { estado.cargarModelo(ModelosDemo.shared.esferaSuelta()) }
                        Button("Rejilla") { estado.cargarModelo(ModelosDemo.shared.rejilla()) }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }

                seccion("Diagnóstico") {
                    fila("Uniforms", "\(estado.escena.numeroDeUniforms)")
                    fila("¿Recompiló?", estado.ultimaRecompilacion)
                    Text("Arrastrar un deslizador solo reescribe el buffer de uniforms. Si aquí llega a decir que recompiló, el diseño está roto.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)
            }
            .padding(18)
        }
    }

    private var barraDeEstado: some View {
        Text(estado.estadoDelRender)
            .font(.system(size: 11, weight: .medium, design: .monospaced))
            .foregroundStyle(.white.opacity(0.85))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 7))
            .padding(12)
    }

    // MARK: Piezas reutilizables

    @ViewBuilder
    private func seccion<Contenido: View>(
        _ titulo: String,
        @ViewBuilder contenido: () -> Contenido
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(titulo.uppercased())
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(.secondary)
                .tracking(0.8)
            contenido()
        }
    }

    private func deslizador(
        _ titulo: String,
        valor: Binding<Float>,
        rango: ClosedRange<Float>,
        unidad: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(titulo).font(.system(size: 12))
                Spacer()
                Text(String(format: "%.2f %@", valor.wrappedValue, unidad))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            Slider(value: valor, in: rango) { editando in
                if !editando { estado.aplicarParametros() }
            }
            .onChange(of: valor.wrappedValue) { _, _ in estado.aplicarParametros() }
        }
    }

    private func fila(_ etiqueta: String, _ valor: String) -> some View {
        HStack {
            Text(etiqueta).font(.system(size: 12))
            Spacer()
            Text(valor).font(.system(size: 11, design: .monospaced)).foregroundStyle(.secondary)
        }
    }
}

// MARK: - Entrada

@main
struct YunkilApp: App {
    var body: some Scene {
        WindowGroup("Yunkil") {
            VistaPrincipal()
        }
        .windowStyle(.hiddenTitleBar)
    }
}
