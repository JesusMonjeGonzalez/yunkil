import Foundation
import simd

/// Arnés del rayo de la cámara.
///
/// El picking se decide en el núcleo, pero el rayo lo construye Swift a partir del
/// píxel, y ahí un signo invertido no da error: da otra pieza. Es el mismo motivo por
/// el que existe `tools/paridad`, y se comprueba igual —con `swiftc` y sin abrir la
/// aplicación— porque el objetivo es que un fallo salte antes de que alguien pinche.
///
///     swiftc -O tools/rayo/main.swift apps/mac/Sources/Camara.swift -o build/rayo
///     ./build/rayo

var fallos = 0

func comprobar(_ condicion: Bool, _ que: String) {
    if condicion {
        print("  ok  \(que)")
    } else {
        print("FALLO  \(que)")
        fallos += 1
    }
}

var camara = CamaraOrbital()
camara.objetivo = SIMD3<Float>(10, 5, -3)
camara.distancia = 200
camara.azimut = 0.9
camara.elevacion = 0.35

let aspecto: Float = 16.0 / 9.0
let base = camara.empaquetar(
    resolucion: SIMD2<Float>(1600, 900),
    escalaPasos: 1,
    epsilonRelativo: 1e-4
)

// El rayo del centro de la pantalla tiene que apuntar al objetivo de la órbita: si no,
// la pieza que se señala no es la que está en el medio de la vista.
let centro = camara.rayo(uv: SIMD2<Float>(0, 0), aspecto: aspecto)
let haciaElObjetivo = simd_normalize(camara.objetivo - camara.posicion)
comprobar(
    simd_length(centro.direccion - haciaElObjetivo) < 1e-5,
    "el rayo del centro apunta al objetivo"
)
comprobar(
    simd_length(centro.origen - camara.posicion) < 1e-5,
    "el rayo sale de la posición de la cámara"
)

// Los ejes de pantalla. Este es el fallo que se busca: con la Y invertida, pinchar
// arriba selecciona lo de abajo y nadie lo ve venir mirando el código.
let derecha = SIMD3<Float>(base.derecha.x, base.derecha.y, base.derecha.z)
let arriba = SIMD3<Float>(base.arriba.x, base.arriba.y, base.arriba.z)

let aLaDerecha = camara.rayo(uv: SIMD2<Float>(0.5, 0), aspecto: aspecto)
comprobar(simd_dot(aLaDerecha.direccion, derecha) > 0, "u positiva va a la derecha")

let arribaEnPantalla = camara.rayo(uv: SIMD2<Float>(0, 0.5), aspecto: aspecto)
comprobar(simd_dot(arribaEnPantalla.direccion, arriba) > 0, "v positiva va hacia arriba")

let aLaIzquierda = camara.rayo(uv: SIMD2<Float>(-0.5, 0), aspecto: aspecto)
comprobar(simd_dot(aLaIzquierda.direccion, derecha) < 0, "u negativa va a la izquierda")

// La apertura. El borde horizontal tiene que abrirse el campo de visión por el
// aspecto, que es lo que hace que un círculo no salga ovalado.
let borde = camara.rayo(uv: SIMD2<Float>(1, 0), aspecto: aspecto)
let anguloDelBorde = acos(min(max(simd_dot(borde.direccion, centro.direccion), -1), 1))
let esperado = atan(tan(camara.campoDeVision * 0.5) * aspecto)
comprobar(
    abs(anguloDelBorde - esperado) < 1e-4,
    "el borde abre \(String(format: "%.4f", anguloDelBorde)) rad, esperado \(String(format: "%.4f", esperado))"
)

let bordeVertical = camara.rayo(uv: SIMD2<Float>(0, 1), aspecto: aspecto)
let anguloVertical = acos(min(max(simd_dot(bordeVertical.direccion, centro.direccion), -1), 1))
comprobar(
    abs(anguloVertical - camara.campoDeVision * 0.5) < 1e-4,
    "el borde vertical abre medio campo de visión"
)

// Todos los rayos salen normalizados: el trazado del núcleo lo normaliza igual, pero
// una dirección de longitud rara aquí significa que la base dejó de ser ortonormal.
for uv in [SIMD2<Float>(-1, -1), SIMD2<Float>(1, -1), SIMD2<Float>(-1, 1), SIMD2<Float>(1, 1)] {
    let r = camara.rayo(uv: uv, aspecto: aspecto)
    comprobar(abs(simd_length(r.direccion) - 1) < 1e-5, "la esquina \(uv.x),\(uv.y) sale normalizada")
}

// El arrastre de una cara. El signo de la Y es el fallo que se busca: AppKit entrega
// deltaY positivo hacia abajo, así que tomarlo tal cual invierte el gesto y empujar
// hacia arriba encoge la pieza.
print("— arrastre de cara —")

let alturaVista: Float = 900
let distancia: Float = 200

func avance(_ normal: SIMD3<Float>, dx: Float, dy: Float) -> Float {
    camara.avanceDeArrastre(
        normal: normal,
        deltaX: dx,
        deltaY: dy,
        distanciaAlImpacto: distancia,
        alturaEnPuntos: alturaVista
    )
}

comprobar(avance(derecha, dx: 10, dy: 0) > 0, "arrastrar a la derecha empuja la cara que mira a la derecha")
comprobar(avance(derecha, dx: -10, dy: 0) < 0, "y arrastrar a la izquierda la mete")
comprobar(avance(arriba, dx: 0, dy: -10) > 0, "arrastrar hacia arriba empuja la cara de arriba")
comprobar(avance(arriba, dx: 0, dy: 10) < 0, "y hacia abajo la mete")
comprobar(abs(avance(arriba, dx: 10, dy: 0)) < 1e-4, "un arrastre horizontal no mueve la cara de arriba")
comprobar(
    abs(avance(centro.direccion, dx: 20, dy: 20)) < 1e-4,
    "una cara vista de canto no se mueve por mucho que se arrastre"
)
comprobar(abs(avance(derecha, dx: 0, dy: 0)) < 1e-9, "sin arrastre no hay avance")

// La escala: arrastrar la altura entera de la vista mueve la cara lo que mide la vista
// a esa distancia, que es lo que hace que la cara siga al cursor.
let esperadoDeEscala = 2 * tan(camara.campoDeVision * 0.5) * distancia
comprobar(
    abs(avance(derecha, dx: alturaVista, dy: 0) - esperadoDeEscala) < 1e-2,
    "arrastrar una altura de vista mueve \(String(format: "%.2f", avance(derecha, dx: alturaVista, dy: 0))) mm, esperado \(String(format: "%.2f", esperadoDeEscala))"
)
// Y con el punto arrastrado al doble de lejos, el mismo arrastre mueve el doble.
let cercaDeLaCara = camara.avanceDeArrastre(normal: derecha, deltaX: 10, deltaY: 0, distanciaAlImpacto: 100, alturaEnPuntos: alturaVista)
let lejosDeLaCara = camara.avanceDeArrastre(normal: derecha, deltaX: 10, deltaY: 0, distanciaAlImpacto: 200, alturaEnPuntos: alturaVista)
comprobar(abs(lejosDeLaCara - 2 * cercaDeLaCara) < 1e-4, "al doble de distancia, el doble de recorrido")

// Proyección paralela. Es donde un signo mal puesto deja el picking apuntando a otro
// sitio del que se ve, y no da ningún error: solo selecciona la pieza equivocada.
print("— proyección paralela —")

var paralela = camara
paralela.ortografica = true

let centroParalelo = paralela.rayo(uv: SIMD2<Float>(0, 0), aspecto: aspecto)
comprobar(
    simd_length(centroParalelo.direccion - haciaElObjetivo) < 1e-5,
    "el rayo del centro sigue apuntando al objetivo"
)
comprobar(
    simd_length(centroParalelo.origen - camara.posicion) < 1e-5,
    "y sale de la cámara, como en perspectiva"
)

let esquinaParalela = paralela.rayo(uv: SIMD2<Float>(1, 1), aspecto: aspecto)
comprobar(
    simd_length(esquinaParalela.direccion - centroParalelo.direccion) < 1e-5,
    "en paralelo todos los rayos van en la misma dirección"
)
comprobar(
    simd_length(esquinaParalela.origen - centroParalelo.origen) > 1,
    "lo que cambia es de dónde salen"
)
comprobar(
    simd_dot(esquinaParalela.origen - centroParalelo.origen, derecha) > 0,
    "la esquina de la derecha sale por la derecha"
)
comprobar(
    simd_dot(esquinaParalela.origen - centroParalelo.origen, arriba) > 0,
    "y la de arriba, por arriba"
)

// Y lo que ve el shader tiene que decir lo mismo: `params.w` a cero es perspectiva.
let empaquetadaEnPerspectiva = camara.empaquetar(
    resolucion: SIMD2<Float>(1600, 900), escalaPasos: 1, epsilonRelativo: 1e-4
)
let empaquetadaEnParalelo = paralela.empaquetar(
    resolucion: SIMD2<Float>(1600, 900), escalaPasos: 1, epsilonRelativo: 1e-4
)
comprobar(empaquetadaEnPerspectiva.params.w == 0, "con perspectiva el shader recibe cero")
comprobar(empaquetadaEnParalelo.params.w == paralela.distancia, "en paralelo recibe la distancia")

// Vistas predefinidas: la planta mira desde arriba y el alzado de frente.
var conVistas = camara
conVistas.mirarDesde(.planta)
comprobar(conVistas.rayo(uv: .zero, aspecto: 1).direccion.y < -0.98, "la planta mira hacia abajo")
conVistas.mirarDesde(.alzado)
comprobar(abs(conVistas.rayo(uv: .zero, aspecto: 1).direccion.y) < 0.02, "el alzado mira a la horizontal")
conVistas.mirarDesde(.perfil)
let perfil = conVistas.rayo(uv: .zero, aspecto: 1).direccion
comprobar(abs(perfil.y) < 0.02 && abs(perfil.z) < 0.02, "el perfil mira por el eje X: \(perfil)")

if fallos == 0 {
    print("\nRayo de cámara: todo correcto.")
    exit(0)
} else {
    print("\nRayo de cámara: \(fallos) fallo(s).")
    exit(1)
}
