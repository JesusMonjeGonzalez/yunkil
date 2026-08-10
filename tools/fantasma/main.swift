// Arnés de la previsualización fantasma.
//
// La paridad ya comprueba que los dos árboles del shader devuelven los números que
// devuelve Kotlin. Lo que **no** comprueba es lo único que el usuario ve: que lo que se
// añade sale verde, lo que se quita sale rojo, y que ninguna de las dos cosas aparece
// cuando no toca. Eso vive entero en el fragmento, y un signo cambiado ahí no da error
// de compilación: da una propuesta pintada al revés justo antes de aceptarla.
//
// Así que aquí se dibuja de verdad —mismo shader, misma cámara y misma escena que la
// aplicación— contra una textura fuera de pantalla, y se cuentan los píxeles.
//
//     swiftc -O tools/fantasma/main.swift apps/mac/Sources/Camara.swift -o build/fantasma
//     ./build/fantasma core/build/paridad

import Foundation
import Metal
import simd

struct EscenaGPU {
    var plato: SIMD4<Float>
    var plano: SIMD4<Float>
    var planoN: SIMD4<Float>
    var planoR: SIMD4<Float>
}

func fallar(_ mensaje: String) -> Never {
    FileHandle.standardError.write("error: \(mensaje)\n".data(using: .utf8)!)
    exit(2)
}

/// Lo que hace falta de un caso volcado: el shader, su buffer y la nube de puntos, que
/// aquí solo sirve para encuadrar la cámara sobre la pieza.
struct Caso {
    let fuente: String
    let uniforms: [Float]
    let minimo: SIMD3<Float>
    let maximo: SIMD3<Float>
}

func leerCaso(_ directorio: URL) -> Caso {
    guard let fuente = try? String(contentsOf: directorio.appendingPathComponent("shader.metal"), encoding: .utf8),
          let datos = try? String(contentsOf: directorio.appendingPathComponent("datos.txt"), encoding: .utf8)
    else { fallar("no se pudo leer \(directorio.lastPathComponent) — ejecuta antes ./gradlew :core:volcarParidad") }

    var lineas = datos.split(separator: "\n").makeIterator()
    guard let cabeceraU = lineas.next(), cabeceraU.hasPrefix("uniforms ") else { fallar("falta uniforms") }
    let cuantos = Int(cabeceraU.dropFirst("uniforms ".count))!
    guard let fila = lineas.next() else { fallar("faltan los valores de uniforms") }
    let uniforms = cuantos > 0 ? fila.split(separator: " ").map { Float($0)! } : []

    guard let cabeceraC = lineas.next(), cabeceraC.hasPrefix("campos ") else { fallar("falta campos") }
    let campos = Int(cabeceraC.dropFirst("campos ".count))!
    guard campos == 0 else { fallar("este arnés no monta texturas: usa un caso sin malla") }
    guard let cabeceraF = lineas.next(), cabeceraF.hasPrefix("fantasma 1") else {
        fallar("\(directorio.lastPathComponent) no lleva fantasma")
    }
    guard let cabeceraP = lineas.next(), cabeceraP.hasPrefix("puntos ") else { fallar("falta puntos") }
    let numeroPuntos = Int(cabeceraP.dropFirst("puntos ".count))!

    // El encuadre sale de los puntos que el volcado dejó **sobre la superficie** de
    // cualquiera de los dos árboles: es la caja de la unión, que es justo lo que hay que
    // ver entero para juzgar el color.
    var minimo = SIMD3<Float>(repeating: .greatestFiniteMagnitude)
    var maximo = SIMD3<Float>(repeating: -.greatestFiniteMagnitude)
    for _ in 0..<numeroPuntos {
        guard let fila = lineas.next() else { fallar("se acabaron los puntos") }
        let c = fila.split(separator: " ").map { Float($0)! }
        guard c.count >= 5 else { fallar("a los puntos les falta la columna del fantasma") }
        if min(abs(c[3]), abs(c[4])) > 0.5 { continue }
        let p = SIMD3<Float>(c[0], c[1], c[2])
        minimo = simd_min(minimo, p)
        maximo = simd_max(maximo, p)
    }
    guard minimo.x < maximo.x else { fallar("no hay puntos de superficie para encuadrar") }
    return Caso(fuente: fuente, uniforms: uniforms, minimo: minimo, maximo: maximo)
}

/// Cuántos píxeles del dibujo son verde de «se añade» y rojo de «se quita».
///
/// Los umbrales son de proporción y no de valor absoluto porque la luz multiplica los
/// tres canales por el mismo número: un verde en sombra sigue siendo tres veces más
/// verde que rojo. Y son exigentes a propósito, para que ni el plato —que tira a
/// turquesa, con el verde y el azul iguales— ni su borde naranja cuenten como nada.
func contar(_ pixeles: [UInt8], _ n: Int) -> (verdes: Int, rojos: Int) {
    var verdes = 0
    var rojos = 0
    for i in 0..<n {
        // bgra8Unorm
        let b = Float(pixeles[i * 4]) / 255
        let g = Float(pixeles[i * 4 + 1]) / 255
        let r = Float(pixeles[i * 4 + 2]) / 255
        if g > 0.10, g > r * 1.6, g > b * 1.6 { verdes += 1 }
        if r > 0.10, r > g * 2.0, r > b * 2.0 { rojos += 1 }
    }
    return (verdes, rojos)
}

func dibujar(
    _ caso: Caso,
    dispositivo: MTLDevice,
    cola: MTLCommandQueue,
    lado: Int,
    seccion: Bool = false
) -> [UInt8] {
    let biblioteca: MTLLibrary
    do {
        biblioteca = try dispositivo.makeLibrary(source: caso.fuente, options: nil)
    } catch {
        fallar("el MSL no compila — \(error)")
    }

    let descriptorPipeline = MTLRenderPipelineDescriptor()
    descriptorPipeline.vertexFunction = biblioteca.makeFunction(name: "yk_vertex")
    descriptorPipeline.fragmentFunction = biblioteca.makeFunction(name: "yk_fragment")
    descriptorPipeline.colorAttachments[0].pixelFormat = .bgra8Unorm
    guard let pipeline = try? dispositivo.makeRenderPipelineState(descriptor: descriptorPipeline) else {
        fallar("no se pudo crear el pipeline")
    }

    let dt = MTLTextureDescriptor.texture2DDescriptor(
        pixelFormat: .bgra8Unorm, width: lado, height: lado, mipmapped: false)
    dt.usage = [.renderTarget, .shaderRead]
    dt.storageMode = .shared
    guard let destino = dispositivo.makeTexture(descriptor: dt) else { fallar("sin textura de destino") }

    let paso = MTLRenderPassDescriptor()
    paso.colorAttachments[0].texture = destino
    paso.colorAttachments[0].loadAction = .clear
    paso.colorAttachments[0].storeAction = .store
    paso.colorAttachments[0].clearColor = MTLClearColor(red: 0.04, green: 0.045, blue: 0.055, alpha: 1)

    var camara = CamaraOrbital()
    camara.encuadrar(minimo: caso.minimo, maximo: caso.maximo)
    var camaraGPU = camara.empaquetar(
        resolucion: SIMD2<Float>(Float(lado), Float(lado)),
        escalaPasos: 1,
        epsilonRelativo: 0.0006
    )
    // El plato, lejos y por debajo, para que no tape la pieza ni meta color: aquí se
    // está juzgando el material, no el suelo.
    var escena = EscenaGPU(
        plato: SIMD4<Float>(128, 128, caso.minimo.y - 200, 10),
        plano: SIMD4<Float>(0, 0, 0, 0),
        planoN: SIMD4<Float>(0, 1, 0, 0.8),
        planoR: SIMD4<Float>(80, 0, 0, 0)
    )
    if seccion {
        // Corte por el centro, quitando el lado que mira a la cámara: es el gesto con
        // el que se mira dentro de una pieza, y con una propuesta encima es la única
        // forma de ver lo que se va a vaciar.
        let centro = (caso.minimo + caso.maximo) * 0.5
        escena.plano = SIMD4<Float>(centro.x, centro.y, centro.z, 1)
        escena.planoN = SIMD4<Float>(0, 0, 1, 0.8)
        escena.planoR = SIMD4<Float>(0, 0, 0, 0)
    }

    var buffer = caso.uniforms
    let bytesU = max(buffer.count, 1) * MemoryLayout<Float>.stride
    let bufU = dispositivo.makeBuffer(bytes: &buffer, length: bytesU, options: .storageModeShared)!

    let cmd = cola.makeCommandBuffer()!
    let enc = cmd.makeRenderCommandEncoder(descriptor: paso)!
    enc.setRenderPipelineState(pipeline)
    enc.setFragmentBytes(&camaraGPU, length: MemoryLayout<CamaraGPU>.stride, index: 0)
    enc.setFragmentBuffer(bufU, offset: 0, index: 1)
    enc.setFragmentBytes(&escena, length: MemoryLayout<EscenaGPU>.stride, index: 2)
    enc.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    enc.endEncoding()
    cmd.commit()
    cmd.waitUntilCompleted()
    if let err = cmd.error { fallar("la GPU falló — \(err)") }

    var pixeles = [UInt8](repeating: 0, count: lado * lado * 4)
    pixeles.withUnsafeMutableBytes { destinoBytes in
        destino.getBytes(
            destinoBytes.baseAddress!,
            bytesPerRow: lado * 4,
            from: MTLRegionMake2D(0, 0, lado, lado),
            mipmapLevel: 0
        )
    }
    return pixeles
}

// ------------------------------------------------------------------------------

let raiz = URL(fileURLWithPath: CommandLine.arguments.count >= 2
    ? CommandLine.arguments[1]
    : "core/build/paridad")
guard let dispositivo = MTLCreateSystemDefaultDevice(), let cola = dispositivo.makeCommandQueue() else {
    fallar("no hay dispositivo Metal disponible")
}
print("Dispositivo: \(dispositivo.name)\n")

let LADO = 256
var fallos = 0

func comprobar(_ condicion: Bool, _ que: String) {
    if condicion { print("  ok  \(que)") } else { print("FALLO  \(que)"); fallos += 1 }
}

// El caso que añade material: una torre encima de una caja. Tiene que verse verde, y
// **nada** en rojo: una propuesta que solo añade no puede pintar de rojo lo que se
// queda, que es la forma de decirle a alguien que va a perder trabajo.
do {
    let caso = leerCaso(raiz.appendingPathComponent("fantasma_anade"))
    let (verdes, rojos) = contar(dibujar(caso, dispositivo: dispositivo, cola: cola, lado: LADO), LADO * LADO)
    print("— la propuesta añade una torre —")
    comprobar(verdes > 200, "lo que se añade se ve en verde (\(verdes) píxeles)")
    comprobar(rojos == 0, "no se pinta de rojo nada que se quede (\(rojos) píxeles)")
}

// El caso que quita material: un taladro pasante. Lo que se va tiene que verse rojo
// —las bocas del agujero, que es lo único que asoma desde fuera— y no puede haber verde.
do {
    let caso = leerCaso(raiz.appendingPathComponent("fantasma_quita"))
    let (verdes, rojos) = contar(dibujar(caso, dispositivo: dispositivo, cola: cola, lado: LADO), LADO * LADO)
    print("— la propuesta taladra un agujero —")
    // El número es pequeño y lo es de verdad: desde fuera de un taladro pasante solo
    // asoma la boca, y la de un agujero de 5 mm en isométrica ocupa eso. Es el mismo
    // límite que ya está escrito para el crítico visual —cuatro vistas exteriores no
    // ven el interior— y por eso existe la comprobación siguiente.
    comprobar(rojos > 30, "el material que se va se ve en rojo (\(rojos) píxeles)")
    comprobar(verdes == 0, "no se pinta de verde nada que no se añada (\(verdes) píxeles)")

    let conCorte = contar(
        dibujar(caso, dispositivo: dispositivo, cola: cola, lado: LADO, seccion: true), LADO * LADO)
    comprobar(
        conCorte.rojos > rojos * 4,
        "cortando se ve el agujero entero: \(conCorte.rojos) píxeles contra \(rojos)"
    )
}

// Y el control, que es lo que separa «funciona» de «siempre dice que sí»: el mismo
// dibujo sin fantasma no puede tener ni un píxel de ninguno de los dos colores.
do {
    let dir = raiz.appendingPathComponent("compuesto")
    guard let fuente = try? String(contentsOf: dir.appendingPathComponent("shader.metal"), encoding: .utf8),
          let datos = try? String(contentsOf: dir.appendingPathComponent("datos.txt"), encoding: .utf8)
    else { fallar("falta el caso de control") }
    var lineas = datos.split(separator: "\n").makeIterator()
    _ = lineas.next()
    let uniforms = lineas.next()!.split(separator: " ").map { Float($0)! }
    _ = lineas.next(); _ = lineas.next()
    guard let cabeceraP = lineas.next(), cabeceraP.hasPrefix("puntos ") else { fallar("falta puntos") }
    var minimo = SIMD3<Float>(repeating: .greatestFiniteMagnitude)
    var maximo = SIMD3<Float>(repeating: -.greatestFiniteMagnitude)
    for _ in 0..<Int(cabeceraP.dropFirst("puntos ".count))! {
        let c = lineas.next()!.split(separator: " ").map { Float($0)! }
        if abs(c[3]) > 0.5 { continue }
        minimo = simd_min(minimo, SIMD3<Float>(c[0], c[1], c[2]))
        maximo = simd_max(maximo, SIMD3<Float>(c[0], c[1], c[2]))
    }
    let caso = Caso(fuente: fuente, uniforms: uniforms, minimo: minimo, maximo: maximo)
    let (verdes, rojos) = contar(dibujar(caso, dispositivo: dispositivo, cola: cola, lado: LADO), LADO * LADO)
    print("— control: la misma pieza sin propuesta —")
    comprobar(verdes == 0 && rojos == 0, "sin fantasma no hay color (\(verdes) verdes, \(rojos) rojos)")
}

print("")
if fallos == 0 {
    print("FANTASMA CORRECTO")
    exit(0)
} else {
    print("FANTASMA ROTO — \(fallos) comprobaciones fallaron")
    exit(1)
}
