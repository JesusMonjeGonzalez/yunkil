// Arnés del gizmo de mover y girar.
//
// Esto se escribió **antes** que `Gizmo.swift`, como contrato del que arrancar. Ya pasa, y
// ya está enganchado a `scripts/comprobar.sh`.
//
// El gizmo no se dibuja en el shader —el renderizador no tiene tubería de vértices—
// sino proyectando a pantalla, así que toda su geometría es aritmética de cámara que se
// puede comprobar sin abrir la aplicación. Y hace falta comprobarla: aquí un signo
// invertido no da error, da un gizmo que mueve la pieza al revés de como se arrastra,
// que es de las pocas cosas que un usuario no perdona.
//
//     swiftc -O tools/gizmo/main.swift apps/mac/Sources/Camara.swift apps/mac/Sources/Gizmo.swift -o build/gizmo
//     ./build/gizmo

import Foundation
import simd

var fallos = 0

func comprobar(_ condicion: Bool, _ que: String) {
    if condicion { print("  ok  \(que)") } else { print("FALLO  \(que)"); fallos += 1 }
}

func casi(_ a: Float, _ b: Float, _ tolerancia: Float = 1e-3) -> Bool { abs(a - b) <= tolerancia }

let ASPECTO: Float = 1.6

func camaraDePrueba() -> CamaraOrbital {
    var c = CamaraOrbital()
    c.objetivo = SIMD3<Float>(0, 0, 0)
    c.distancia = 120
    c.azimut = 0.7
    c.elevacion = 0.45
    return c
}

// ---------------------------------------------------------------- proyectar

print("— proyectar es la vuelta de rayo —")
do {
    let camara = camaraDePrueba()
    // La ida y la vuelta tienen que cerrar: si `proyectar` no fuera exactamente la
    // inversa de `rayo`, el gizmo se dibujaría en un sitio y respondería en otro.
    for uv in [SIMD2<Float>(0, 0), SIMD2<Float>(0.5, -0.3), SIMD2<Float>(-0.8, 0.7)] {
        let r = camara.rayo(uv: uv, aspecto: ASPECTO)
        let punto = r.origen + r.direccion * 100
        guard let vuelta = camara.proyectar(punto, aspecto: ASPECTO) else {
            comprobar(false, "un punto delante de la cámara se proyecta")
            continue
        }
        comprobar(
            casi(vuelta.x, uv.x) && casi(vuelta.y, uv.y),
            "(\(uv.x), \(uv.y)) va y vuelve: (\(vuelta.x), \(vuelta.y))"
        )
    }
}

do {
    var camara = camaraDePrueba()
    let detras = camara.posicion + (camara.posicion - camara.objetivo)
    comprobar(camara.proyectar(detras, aspecto: ASPECTO) == nil, "lo que queda detrás no se proyecta")

    // Y en paralela, donde no hay punto de fuga y la cuenta es otra.
    camara.ortografica = true
    let r = camara.rayo(uv: SIMD2<Float>(0.4, -0.2), aspecto: ASPECTO)
    let punto = r.origen + r.direccion * 100
    guard let vuelta = camara.proyectar(punto, aspecto: ASPECTO) else {
        comprobar(false, "en paralela también se proyecta")
        exit(1)
    }
    comprobar(casi(vuelta.x, 0.4) && casi(vuelta.y, -0.2), "en paralela la ida y la vuelta también cierran")
}

// ---------------------------------------------------------------- agarrar

print("\n— agarrar un asa —")
do {
    let camara = camaraDePrueba()
    let gizmo = Gizmo(centro: SIMD3<Float>(0, 0, 0), radio: 20)
    let ejes = gizmo.ejes(camara: camara, aspecto: ASPECTO)
    comprobar(ejes.count == 3, "salen los tres ejes")

    guard let ejeX = ejes.first(where: { $0.eje == .x }) else {
        comprobar(false, "hay eje X"); exit(1)
    }
    // Justo encima de la punta de la flecha X: eso se agarra para mover en X.
    let asa = gizmo.agarrar(uv: ejeX.punta, camara: camara, aspecto: ASPECTO)
    comprobar(asa == .mover(.x), "sobre la flecha X se agarra mover en X, y salió \(String(describing: asa))")

    // Lejos de todo no se agarra nada: si el gizmo se quedara con el clic, orbitar
    // dejaría de funcionar en media pantalla.
    comprobar(
        gizmo.agarrar(uv: SIMD2<Float>(0.95, 0.95), camara: camara, aspecto: ASPECTO) == nil,
        "lejos del gizmo no se agarra nada"
    )
}

do {
    // Un eje que apunta a la cámara se proyecta en un punto: no se puede ni ver ni
    // arrastrar, y ofrecerlo es prometer un gesto que no responde. Con la cámara en el
    // eje X puro, el asa de X no se ofrece y las otras dos sí.
    var camara = camaraDePrueba()
    camara.azimut = 0
    camara.elevacion = 0
    let gizmo = Gizmo(centro: SIMD3<Float>(0, 0, 0), radio: 20)
    let ejes = gizmo.ejes(camara: camara, aspecto: ASPECTO)
    comprobar(!ejes.contains { $0.eje == .x }, "el eje que mira a la cámara no se ofrece")
    comprobar(ejes.count == 2, "y los otros dos sí (\(ejes.count))")
}

// ---------------------------------------------------------------- escalar

print("\n— el asa de escala —")
do {
    let camara = camaraDePrueba()
    let gizmo = Gizmo(centro: SIMD3<Float>(0, 0, 0), radio: 20)
    guard let asa = gizmo.asaDeEscala(camara: camara, aspecto: ASPECTO) else {
        comprobar(false, "el asa de escala se proyecta"); exit(1)
    }
    let base = camara.proyectar(SIMD3<Float>(0, 0, 0), aspecto: ASPECTO)!
    comprobar(asa.y > base.y && asa.x > base.x, "va arriba y a la derecha en la pantalla, no sobre un eje del mundo")

    // Y no se pisa con ninguna punta de flecha, orbite como orbite: dos dianas encima la
    // una de la otra son una lotería.
    for grados in stride(from: 0.0, to: 6.28, by: 0.35) {
        var c = camaraDePrueba()
        c.azimut = Float(grados)
        c.elevacion = Float(grados) * 0.2 - 0.6
        guard let e = gizmo.asaDeEscala(camara: c, aspecto: ASPECTO) else { continue }
        for eje in gizmo.ejes(camara: c, aspecto: ASPECTO) {
            let d = simd_length(SIMD2<Float>((e.x - eje.punta.x) * ASPECTO, e.y - eje.punta.y))
            if d <= Gizmo.toleranciaDeAgarre * 2 {
                comprobar(false, "el asa de escala se pisa con la flecha \(eje.eje) a \(grados) rad")
            }
        }
    }
    comprobar(gizmo.agarrar(uv: asa, camara: camara, aspecto: ASPECTO) == .escalar, "y se agarra")

    // Girando la vista sigue arriba: es lo que la hace encontrable sin buscarla.
    var otra = camaraDePrueba()
    otra.azimut = 2.4
    otra.elevacion = -0.6
    let movida = gizmo.asaDeEscala(camara: otra, aspecto: ASPECTO)!
    comprobar(movida.y > otra.proyectar(SIMD3<Float>(0, 0, 0), aspecto: ASPECTO)!.y, "y sigue arriba al orbitar")
    comprobar(true, "sin pisarse con ninguna flecha en toda la vuelta")
}

do {
    let camara = camaraDePrueba()
    let gizmo = Gizmo(centro: SIMD3<Float>(0, 0, 0), radio: 20)
    let base = camara.proyectar(SIMD3<Float>(0, 0, 0), aspecto: ASPECTO)!

    // Alejarse del centro agranda; acercarse encoge. Es el único gesto que se entiende
    // sin que nadie lo explique, y el signo que lo hace inservible si se invierte.
    let cerca = base + SIMD2<Float>(0.2, 0)
    let lejos = base + SIMD2<Float>(0.4, 0)
    let crece = gizmo.factorDeEscala(desde: cerca, hasta: lejos, camara: camara, aspecto: ASPECTO)
    comprobar(casi(crece, 2), "doblar la distancia dobla la pieza (\(crece))")

    let encoge = gizmo.factorDeEscala(desde: lejos, hasta: cerca, camara: camara, aspecto: ASPECTO)
    comprobar(casi(encoge, 0.5), "y a la mitad, la mitad (\(encoge))")

    // Pasar por el centro daría un factor cero y la pieza se esfumaría de un tirón.
    let enElCentro = gizmo.factorDeEscala(desde: lejos, hasta: base, camara: camara, aspecto: ASPECTO)
    comprobar(enElCentro >= 0.5, "cruzar el centro no hace desaparecer la pieza (\(enElCentro))")
}

// ---------------------------------------------------------------- mover

print("\n— arrastrar para mover —")
do {
    let camara = camaraDePrueba()
    let gizmo = Gizmo(centro: SIMD3<Float>(0, 0, 0), radio: 20)
    // Mover por el eje reutiliza la misma cuenta que empujar una cara: la pieza solo
    // puede ir a lo largo del eje, así que se proyecta el eje sobre la pantalla y se
    // toma la componente del arrastre que va en esa dirección.
    let avance = gizmo.avance(
        eje: .x, camara: camara, deltaX: 40, deltaY: 0, alturaEnPuntos: 800
    )
    let esperado = camara.avanceDeArrastre(
        normal: SIMD3<Float>(1, 0, 0), deltaX: 40, deltaY: 0,
        distanciaAlImpacto: camara.distancia, alturaEnPuntos: 800
    )
    comprobar(casi(avance, esperado), "mover por X usa la misma cuenta que empujar una cara")
    comprobar(avance != 0, "y no es cero (\(avance) mm)")

    let alReves = gizmo.avance(eje: .x, camara: camara, deltaX: -40, deltaY: 0, alturaEnPuntos: 800)
    comprobar(casi(alReves, -avance), "arrastrar al revés mueve al revés")
}

// ---------------------------------------------------------------- girar

print("\n— arrastrar para girar —")
do {
    // Cámara desde arriba: el eje Y apunta a la cámara, así que un giro positivo
    // alrededor de Y —regla de la mano derecha— se ve antihorario en la pantalla.
    var camara = camaraDePrueba()
    camara.elevacion = CamaraOrbital.elevacionMaxima
    camara.azimut = -.pi / 2
    let gizmo = Gizmo(centro: SIMD3<Float>(0, 0, 0), radio: 20)

    let centro = camara.proyectar(SIMD3<Float>(0, 0, 0), aspecto: ASPECTO)!
    let derecha = centro + SIMD2<Float>(0.3, 0)
    let arriba = centro + SIMD2<Float>(0, 0.3)

    let grados = gizmo.grados(eje: .y, desde: derecha, hasta: arriba, camara: camara, aspecto: ASPECTO)
    comprobar(casi(grados, 90, 0.5), "un cuarto de vuelta antihorario son +90° en Y, y salieron \(grados)")

    let alReves = gizmo.grados(eje: .y, desde: arriba, hasta: derecha, camara: camara, aspecto: ASPECTO)
    comprobar(casi(alReves, -90, 0.5), "y al revés, −90° (\(alReves))")

    // Mirando desde abajo, el mismo gesto en pantalla gira al otro lado. Es el signo
    // que se equivoca siempre y el que hace que el gizmo se sienta roto.
    camara.elevacion = -CamaraOrbital.elevacionMaxima
    let desdeAbajo = gizmo.grados(eje: .y, desde: derecha, hasta: arriba, camara: camara, aspecto: ASPECTO)
    comprobar(casi(desdeAbajo, -90, 0.5), "desde abajo el mismo arrastre gira al otro lado (\(desdeAbajo))")
}

do {
    // El salto de vuelta: cruzar el ±180 no puede dar un giro de media vuelta.
    let camara = camaraDePrueba()
    let gizmo = Gizmo(centro: SIMD3<Float>(0, 0, 0), radio: 20)
    let centro = camara.proyectar(SIMD3<Float>(0, 0, 0), aspecto: ASPECTO)!
    let casiPi = centro + SIMD2<Float>(-0.3, 0.01)
    let pasadoPi = centro + SIMD2<Float>(-0.3, -0.01)
    let grados = gizmo.grados(eje: .y, desde: casiPi, hasta: pasadoPi, camara: camara, aspecto: ASPECTO)
    comprobar(abs(grados) < 10, "cruzar el ±180 no da media vuelta (\(grados)°)")
}

// ---------------------------------------------------------------- ajustar

print("\n— ajustar el arrastre con ⇧ —")
do {
    // Sin ajuste se entrega tal cual, fotograma a fotograma.
    var libre = AcumuladorDeGesto()
    var total: Float = 0
    for _ in 0..<10 { total += libre.entregar(0.37, incremento: nil) }
    comprobar(casi(total, 3.7), "sin ⇧ se entrega todo lo que pide el ratón (\(total))")
}

do {
    // Con ajuste, un ratón que entrega décimas tiene que acabar moviendo la pieza: si se
    // redondeara cada fotograma por separado, cada décima daría cero y no se movería nunca.
    var ajustado = AcumuladorDeGesto()
    var total: Float = 0
    var entregas = 0
    for _ in 0..<30 {
        let d = ajustado.entregar(0.1, incremento: 1)
        if d != 0 { entregas += 1 }
        total += d
    }
    comprobar(casi(total, 3), "treinta décimas con ⇧ son tres milímetros exactos (\(total))")
    comprobar(entregas == 3, "entregados en tres escalones y no en treinta (\(entregas))")
}

do {
    // Y volver sobre lo andado deshace escalones, no los suma.
    var ida = AcumuladorDeGesto()
    var total: Float = 0
    for _ in 0..<20 { total += ida.entregar(0.5, incremento: 1) }
    for _ in 0..<10 { total += ida.entregar(-0.5, incremento: 1) }
    comprobar(casi(total, 5), "diez arriba y cinco abajo dejan cinco (\(total))")
    comprobar(casi(ida.pedido, 5), "y lo pedido y lo entregado coinciden al cruzar un escalón")
}

do {
    // Un ángulo también, con su escalón de quince grados.
    var giro = AcumuladorDeGesto()
    var total: Float = 0
    for _ in 0..<8 { total += giro.entregar(4, incremento: 15) }
    comprobar(casi(total, 30), "treinta y dos grados pedidos se quedan en treinta (\(total))")
}

print("")
if fallos == 0 {
    print("Gizmo: todo correcto.")
    exit(0)
} else {
    print("Gizmo: \(fallos) comprobaciones fallaron")
    exit(1)
}
