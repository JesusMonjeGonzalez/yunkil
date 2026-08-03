import Foundation
import Metal
import MetalKit
import YunkilCore
import simd

/// Dibuja la escena raymarcheando el shader que generó el núcleo Kotlin.
///
/// El reparto de responsabilidades es deliberado: aquí no hay geometría ni reglas,
/// solo el oficio de poner píxeles. Todo lo que sabe de sólidos vive en Kotlin.
final class Renderizador: NSObject, MTKViewDelegate {

    private let dispositivo: MTLDevice
    private let cola: MTLCommandQueue
    private var pipeline: MTLRenderPipelineState?

    private let escena: Escena
    private var bufferDeUniforms: MTLBuffer?
    private var huellaCompilada: String = ""

    var camara = CamaraOrbital()
    private let gobernador: GobernadorDeRecursos

    /// Se publica para la barra de estado. Es una cadena, no un modelo: el HUD no
    /// debe poder influir en el render.
    private(set) var estado: String = ""
    var alActualizarEstado: ((String) -> Void)?

    /// Último error de compilación. Cuando lo hay se conserva el pipeline anterior
    /// y se sigue dibujando: el usuario no debe encontrarse una pantalla negra.
    private(set) var ultimoError: String?

    init?(vista: MTKView, escena: Escena) {
        guard let dispositivo = MTLCreateSystemDefaultDevice(),
              let cola = dispositivo.makeCommandQueue() else { return nil }

        self.dispositivo = dispositivo
        self.cola = cola
        self.escena = escena

        let hz = Double(vista.preferredFramesPerSecond > 0 ? vista.preferredFramesPerSecond : 60)
        self.gobernador = GobernadorDeRecursos(objetivoDeFotograma: 1.0 / hz)

        super.init()

        vista.device = dispositivo
        vista.colorPixelFormat = .bgra8Unorm
        // Sin buffer de profundidad: el raymarcher resuelve la visibilidad por sí
        // mismo, así que pedirlo solo gastaría ancho de banda.
        vista.depthStencilPixelFormat = .invalid
        vista.clearColor = MTLClearColor(red: 0.04, green: 0.045, blue: 0.055, alpha: 1)

        encuadrarModelo()
        recompilarSiHaceFalta(forzar: true)
        actualizarUniforms()
    }

    // MARK: - Escena

    func encuadrarModelo() {
        camara.encuadrar(
            minimo: SIMD3<Float>(escena.cotaMinimaX, escena.cotaMinimaY, escena.cotaMinimaZ),
            maximo: SIMD3<Float>(escena.cotaMaximaX, escena.cotaMaximaY, escena.cotaMaximaZ)
        )
    }

    /// Sustituye el modelo. Solo recompila si cambió la topología: mover un
    /// parámetro debe costar un `memcpy`, no una compilación.
    func reemplazarModelo(_ nuevo: any SdfNode) {
        let recompilar = escena.reemplazar(nueva: nuevo)
        if recompilar { recompilarSiHaceFalta(forzar: false) }
        actualizarUniforms()
        encuadrarModelo()
    }

    private func recompilarSiHaceFalta(forzar: Bool) {
        guard forzar || escena.huellaTopologica != huellaCompilada else { return }

        do {
            let biblioteca = try dispositivo.makeLibrary(source: escena.fuenteMsl, options: nil)
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = biblioteca.makeFunction(name: "yk_vertex")
            descriptor.fragmentFunction = biblioteca.makeFunction(name: "yk_fragment")
            descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm

            pipeline = try dispositivo.makeRenderPipelineState(descriptor: descriptor)
            huellaCompilada = escena.huellaTopologica
            ultimoError = nil
        } catch {
            // Se conserva el pipeline anterior a propósito.
            ultimoError = "\(error)"
            NSLog("Yunkil: el shader no compiló — %@", "\(error)")
        }
    }

    private func actualizarUniforms() {
        let valores = escena.uniforms().map { $0.floatValue }
        let bytes = max(valores.count, 1) * MemoryLayout<Float>.stride

        if bufferDeUniforms == nil || bufferDeUniforms!.length < bytes {
            bufferDeUniforms = dispositivo.makeBuffer(length: bytes, options: .storageModeShared)
        }
        guard let buffer = bufferDeUniforms, !valores.isEmpty else { return }
        valores.withUnsafeBytes {
            buffer.contents().copyMemory(from: $0.baseAddress!, byteCount: bytes)
        }
    }

    // MARK: - MTKViewDelegate

    func mtkView(_ vista: MTKView, drawableSizeWillChange tamano: CGSize) {}

    func draw(in vista: MTKView) {
        guard let pipeline,
              let descriptor = vista.currentRenderPassDescriptor,
              let drawable = vista.currentDrawable,
              let buffer = bufferDeUniforms,
              let comando = cola.makeCommandBuffer(),
              let codificador = comando.makeRenderCommandEncoder(descriptor: descriptor)
        else { return }

        let ancho = Float(vista.drawableSize.width)
        let alto = Float(vista.drawableSize.height)

        var camaraGPU = camara.empaquetar(
            resolucion: SIMD2<Float>(ancho, alto),
            escalaPasos: gobernador.escalaDePasos,
            // El umbral de impacto sigue a la distancia de la cámara: fijo en
            // milímetros daría bordes sucios de lejos y gastaría pasos de cerca.
            epsilonRelativo: 0.0006
        )

        codificador.setRenderPipelineState(pipeline)
        codificador.setFragmentBytes(&camaraGPU, length: MemoryLayout<CamaraGPU>.stride, index: 0)
        codificador.setFragmentBuffer(buffer, offset: 0, index: 1)
        codificador.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        codificador.endEncoding()

        comando.present(drawable)
        comando.commit()

        gobernador.registrarFotograma()
        aplicarEscalaDeRender(vista)

        let texto = gobernador.descripcionDelEstado + (ultimoError == nil ? "" : " · shader con error")
        if texto != estado {
            estado = texto
            alActualizarEstado?(texto)
        }
    }

    /// Ajusta la resolución interna al presupuesto. La capa estira el resultado, que
    /// es mucho más barato que recortar calidad de sombreado.
    private func aplicarEscalaDeRender(_ vista: MTKView) {
        let factorPantalla = vista.window?.backingScaleFactor ?? 2.0
        let nativo = CGSize(
            width: vista.bounds.width * factorPantalla,
            height: vista.bounds.height * factorPantalla
        )
        let deseado = CGSize(
            width: max(round(nativo.width * CGFloat(gobernador.escalaDeRender)), 64),
            height: max(round(nativo.height * CGFloat(gobernador.escalaDeRender)), 64)
        )
        if abs(deseado.width - vista.drawableSize.width) > 1
            || abs(deseado.height - vista.drawableSize.height) > 1 {
            vista.drawableSize = deseado
        }
    }
}
