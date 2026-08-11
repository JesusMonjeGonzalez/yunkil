import Foundation
import simd

/// El gizmo de mover y girar: tres flechas y tres anillos alrededor de la pieza.
///
/// **No se dibuja en el shader.** El renderizador raymarchea un campo y no tiene tubería
/// de vértices, así que meter el gizmo dentro obligaría a compilarlo en el MSL generado y
/// a que cada asa costara pasos de trazado. Va como capa 2D encima del viewport, y toda su
/// geometría es entonces aritmética de cámara: proyectar el centro y los extremos de los
/// ejes, medir distancias en pantalla y convertir un arrastre en milímetros o en grados.
///
/// Eso lo hace comprobable sin abrir la aplicación, que es justo lo que hace falta aquí:
/// un signo invertido no da error ni excepción, da un gizmo que mueve la pieza al revés de
/// como se arrastra. `tools/gizmo` lo mide.
///
/// Los dos criterios de visibilidad son opuestos a propósito:
///
///   - una **flecha** vista de punta se proyecta en un punto y no se puede arrastrar;
///   - un **anillo** visto de canto se proyecta en un segmento y el ángulo de pantalla
///     deja de decir cuánto se ha girado.
///
/// Así que un eje que mira a la cámara ofrece anillo y no flecha, y uno perpendicular a la
/// vista ofrece flecha y no anillo. Ofrecer un asa que no responde al gesto es peor que no
/// ofrecerla.
struct Gizmo {

    enum Eje: CaseIterable, Equatable {
        case x, y, z

        var direccion: SIMD3<Float> {
            switch self {
            case .x: return SIMD3<Float>(1, 0, 0)
            case .y: return SIMD3<Float>(0, 1, 0)
            case .z: return SIMD3<Float>(0, 0, 1)
            }
        }

        /// Dos direcciones perpendiculares al eje, para trazar su anillo.
        var plano: (SIMD3<Float>, SIMD3<Float>) {
            switch self {
            case .x: return (SIMD3<Float>(0, 1, 0), SIMD3<Float>(0, 0, 1))
            case .y: return (SIMD3<Float>(0, 0, 1), SIMD3<Float>(1, 0, 0))
            case .z: return (SIMD3<Float>(1, 0, 0), SIMD3<Float>(0, 1, 0))
            }
        }
    }

    /// Lo que se agarra al pulsar. El editor decide qué transacción abre con esto.
    enum Asa: Equatable {
        case mover(Eje)
        case girar(Eje)
        /// Escalar es **uno solo** y no tres: la escala del sistema es uniforme a propósito
        /// —una por ejes deformaría el campo y las distancias dejarían de ser distancias—,
        /// así que ofrecer un asa por eje prometería algo que el documento no sabe guardar.
        case escalar
    }

    struct EjeEnPantalla {
        let eje: Eje
        /// Centro del gizmo proyectado: de aquí sale la flecha.
        let base: SIMD2<Float>
        /// Punta de la flecha, que es lo que se agarra.
        let punta: SIMD2<Float>
    }

    var centro: SIMD3<Float>
    var radio: Float

    init(centro: SIMD3<Float>, radio: Float) {
        self.centro = centro
        self.radio = max(radio, 0.001)
    }

    /// Radio del anillo de giro, en veces el de las flechas. Separado lo justo para que la
    /// punta de una flecha nunca caiga dentro de la tolerancia de un anillo vecino.
    static let factorDelAnillo: Float = 1.28

    /// Cuánto se puede fallar el clic, en unidades de **media altura** de la vista.
    /// A 800 puntos de alto son unos 14 puntos, que es el tamaño de diana habitual.
    static let toleranciaDeAgarre: Float = 0.035

    /// Un eje más alineado que esto con la vista no ofrece flecha: coseno 0,97 son 14°.
    static let cosenoMaximoDeFlecha: Float = 0.97

    /// Y menos alineado que esto no ofrece anillo: coseno 0,35 son 70°.
    static let cosenoMinimoDeAnillo: Float = 0.35

    /// A qué altura de la pantalla va el asa de escala, en veces el radio. Por encima del
    /// anillo para que no se pisen las dianas.
    static let alturaDeLaEscala: Float = 1.62

    /// Cuántos tramos tiene el anillo trazado. Con 96 el error de dibujo es medio píxel a
    /// tamaño de pantalla completa, y la distancia se mide contra los segmentos, no contra
    /// los puntos, así que agarrar no depende de este número.
    static let tramosDelAnillo = 96

    // MARK: - Lo que hay en pantalla

    /// Las flechas que se ofrecen, ya proyectadas.
    func ejes(camara: CamaraOrbital, aspecto: Float) -> [EjeEnPantalla] {
        let mirada = direccionDeVista(camara)
        guard let base = camara.proyectar(centro, aspecto: aspecto) else { return [] }

        return Eje.allCases.compactMap { eje in
            guard abs(simd_dot(eje.direccion, mirada)) < Self.cosenoMaximoDeFlecha else { return nil }
            guard let punta = camara.proyectar(
                centro + eje.direccion * radio, aspecto: aspecto
            ) else { return nil }
            return EjeEnPantalla(eje: eje, base: base, punta: punta)
        }
    }

    /// Los anillos que se ofrecen, cada uno como la polilínea que hay que dibujar.
    /// Un anillo puede salir partido —parte del círculo detrás de la cámara—, y por eso
    /// devuelve tramos y no un solo lazo.
    func anillos(camara: CamaraOrbital, aspecto: Float) -> [(eje: Eje, tramos: [[SIMD2<Float>]])] {
        let mirada = direccionDeVista(camara)
        return Eje.allCases.compactMap { eje in
            guard abs(simd_dot(eje.direccion, mirada)) > Self.cosenoMinimoDeAnillo else { return nil }
            let tramos = tramosDelAnillo(eje: eje, camara: camara, aspecto: aspecto)
            return tramos.isEmpty ? nil : (eje, tramos)
        }
    }

    /// El centro del gizmo proyectado, que es de donde sale todo lo que se dibuja.
    func centroEnPantalla(camara: CamaraOrbital, aspecto: Float) -> SIMD2<Float>? {
        camara.proyectar(centro, aspecto: aspecto)
    }

    /// Dónde se dibuja el asa de escala, o `nil` si el centro no está en pantalla.
    ///
    /// Va **arriba y a la derecha en la pantalla**, no sobre un eje del mundo: como el gesto
    /// que dispara es uniforme y no tiene dirección, atarla a un eje sugeriría que estira por
    /// él. En la diagonal, además, no se pisa con ninguna flecha por mucho que se orbite, y
    /// siguiendo la base de la cámara se queda siempre donde se la busca.
    func asaDeEscala(camara: CamaraOrbital, aspecto: Float) -> SIMD2<Float>? {
        let base = camara.baseOrtonormal()
        let sitio = (base.arriba + base.derecha) * (radio * Self.alturaDeLaEscala * 0.707)
        return camara.proyectar(centro + sitio, aspecto: aspecto)
    }

    // MARK: - Agarrar

    /// Qué asa hay bajo el cursor, si hay alguna.
    ///
    /// Las flechas se miran antes que los anillos: son la diana pequeña y la que el usuario
    /// apunta a propósito. Y fuera de la tolerancia no se agarra **nada**, para que orbitar
    /// siga funcionando en el resto de la pantalla; un gizmo que se queda con el clic de
    /// media vista se siente roto mucho antes de que se note que es cómodo.
    func agarrar(uv: SIMD2<Float>, camara: CamaraOrbital, aspecto: Float) -> Asa? {
        var mejor: (asa: Asa, distancia: Float)?

        for eje in ejes(camara: camara, aspecto: aspecto) {
            let d = distanciaEnPantalla(uv, eje.punta, aspecto: aspecto)
            if d <= Self.toleranciaDeAgarre, d < (mejor?.distancia ?? .infinity) {
                mejor = (.mover(eje.eje), d)
            }
        }
        if let escala = asaDeEscala(camara: camara, aspecto: aspecto) {
            let d = distanciaEnPantalla(uv, escala, aspecto: aspecto)
            if d <= Self.toleranciaDeAgarre, d < (mejor?.distancia ?? .infinity) {
                mejor = (.escalar, d)
            }
        }
        if let mejor { return mejor.asa }

        for anillo in anillos(camara: camara, aspecto: aspecto) {
            for tramo in anillo.tramos {
                for i in 1..<max(tramo.count, 1) {
                    let d = distanciaAlSegmento(uv, tramo[i - 1], tramo[i], aspecto: aspecto)
                    if d <= Self.toleranciaDeAgarre, d < (mejor?.distancia ?? .infinity) {
                        mejor = (.girar(anillo.eje), d)
                    }
                }
            }
        }
        return mejor?.asa
    }

    // MARK: - Arrastrar

    /// Milímetros que avanza la pieza al arrastrar por una flecha.
    ///
    /// Es **la misma cuenta que empujar una cara** —proyectar la dirección permitida sobre
    /// la pantalla y quedarse con la componente del arrastre que va en ella—, y lo es a
    /// propósito: si mover con el gizmo y empujar una cara respondieran distinto al mismo
    /// gesto, el usuario no sabría cuál de los dos está aprendiendo.
    ///
    /// - Parameter deltaY: positivo **hacia abajo**, que es como lo entrega AppKit.
    func avance(
        eje: Eje,
        camara: CamaraOrbital,
        deltaX: Float,
        deltaY: Float,
        alturaEnPuntos: Float
    ) -> Float {
        camara.avanceDeArrastre(
            normal: eje.direccion,
            deltaX: deltaX,
            deltaY: deltaY,
            distanciaAlImpacto: simd_length(camara.posicion - centro),
            alturaEnPuntos: alturaEnPuntos
        )
    }

    /// Grados que gira la pieza al arrastrar por un anillo, de un punto de pantalla a otro.
    ///
    /// El giro es el ángulo barrido alrededor del centro del gizmo, y el signo sale de
    /// **por qué lado se está mirando el eje**: con el eje apuntando hacia la cámara, la
    /// regla de la mano derecha se ve antihoraria; desde el otro lado, el mismo arrastre en
    /// pantalla gira al revés. Ese es el signo que se equivoca siempre.
    ///
    /// - Parameters:
    ///   - desde: posición anterior del cursor, en las coordenadas de `proyectar`.
    ///   - hasta: posición actual.
    /// - Returns: el incremento en grados, ya sin el salto al cruzar el ±180.
    func grados(
        eje: Eje,
        desde: SIMD2<Float>,
        hasta: SIMD2<Float>,
        camara: CamaraOrbital,
        aspecto: Float
    ) -> Float {
        guard let base = camara.proyectar(centro, aspecto: aspecto) else { return 0 }

        // El ángulo se mide sobre la pantalla de verdad, no sobre las coordenadas
        // normalizadas: sin corregir el aspecto, un cuarto de vuelta no daría 90°.
        let a = angulo(hasta - base, aspecto: aspecto) - angulo(desde - base, aspecto: aspecto)
        let sinSalto = atan2(sin(a), cos(a))

        // Hacia la cámara: `frente` va de la cámara al objetivo, así que un eje que apunta
        // a quien mira tiene producto escalar negativo con ella.
        let haciaLaCamara = simd_dot(eje.direccion, direccionDeVista(camara)) < 0
        return sinSalto * (180 / .pi) * (haciaLaCamara ? 1 : -1)
    }

    /// Cuánto crece o encoge la pieza al arrastrar el asa de escala.
    ///
    /// Es la razón entre lo que dista el cursor del centro ahora y lo que distaba antes:
    /// alejarse agranda y acercarse encoge, que es el único gesto que se entiende sin
    /// explicación. Se acota por arriba y por abajo porque un fotograma que pasara justo por
    /// el centro daría un factor cero y la pieza desaparecería de un tirón.
    ///
    /// - Returns: el factor de este fotograma, que se **multiplica** al anterior.
    func factorDeEscala(
        desde: SIMD2<Float>,
        hasta: SIMD2<Float>,
        camara: CamaraOrbital,
        aspecto: Float
    ) -> Float {
        guard let base = camara.proyectar(centro, aspecto: aspecto) else { return 1 }
        let antes = distanciaEnPantalla(desde, base, aspecto: aspecto)
        let ahora = distanciaEnPantalla(hasta, base, aspecto: aspecto)
        guard antes > 1e-4 else { return 1 }
        return (ahora / antes).clamped(0.5, 2)
    }

    // MARK: - Cuentas

    private func direccionDeVista(_ camara: CamaraOrbital) -> SIMD3<Float> {
        simd_normalize(camara.objetivo - camara.posicion)
    }

    private func angulo(_ v: SIMD2<Float>, aspecto: Float) -> Float {
        atan2(v.y, v.x * aspecto)
    }

    private func distanciaEnPantalla(
        _ a: SIMD2<Float>, _ b: SIMD2<Float>, aspecto: Float
    ) -> Float {
        simd_length(SIMD2<Float>((a.x - b.x) * aspecto, a.y - b.y))
    }

    private func distanciaAlSegmento(
        _ p: SIMD2<Float>, _ a: SIMD2<Float>, _ b: SIMD2<Float>, aspecto: Float
    ) -> Float {
        let pe = SIMD2<Float>(p.x * aspecto, p.y)
        let ae = SIMD2<Float>(a.x * aspecto, a.y)
        let be = SIMD2<Float>(b.x * aspecto, b.y)
        let ab = be - ae
        let largo = simd_length_squared(ab)
        guard largo > 1e-12 else { return simd_length(pe - ae) }
        let t = min(max(simd_dot(pe - ae, ab) / largo, 0), 1)
        return simd_length(pe - (ae + ab * t))
    }

    private func tramosDelAnillo(
        eje: Eje, camara: CamaraOrbital, aspecto: Float
    ) -> [[SIMD2<Float>]] {
        let (u, v) = eje.plano
        let r = radio * Self.factorDelAnillo
        var tramos: [[SIMD2<Float>]] = []
        var actual: [SIMD2<Float>] = []

        for i in 0...Self.tramosDelAnillo {
            let a = Float(i) / Float(Self.tramosDelAnillo) * 2 * .pi
            let punto = centro + (u * cos(a) + v * sin(a)) * r
            if let uv = camara.proyectar(punto, aspecto: aspecto) {
                actual.append(uv)
            } else if actual.count > 1 {
                tramos.append(actual)
                actual = []
            } else {
                actual = []
            }
        }
        if actual.count > 1 { tramos.append(actual) }
        return tramos
    }
}

private extension Float {
    func clamped(_ minimo: Float, _ maximo: Float) -> Float { min(max(self, minimo), maximo) }
}

/// Lleva la cuenta de un arrastre para poder ajustarlo por incrementos.
///
/// Con ⇧ el gesto avanza de milímetro en milímetro o de quince en quince grados, y eso no
/// se puede hacer redondeando cada fotograma por separado: un ratón entrega décimas, cada
/// una redondearía a cero y la pieza no se movería nunca. Hay que acumular lo que se pide y
/// entregar solo al cruzar cada escalón, guardando el resto.
///
/// Vive aquí, y no en la vista que atiende el ratón, porque es aritmética pura y porque
/// «se queda corto» es un fallo que nadie ve mirando: se ve midiendo.
struct AcumuladorDeGesto {

    /// Todo lo que ha pedido el ratón desde que se agarró el asa.
    private(set) var pedido: Float = 0
    /// Lo que se le ha entregado al documento, que con ⇧ va por escalones.
    private(set) var entregado: Float = 0

    /// - Parameter incremento: el escalón del ajuste, o `nil` para entregar todo tal cual.
    /// - Returns: lo que hay que aplicar **ahora**, que puede ser cero.
    mutating func entregar(_ paso: Float, incremento: Float?) -> Float {
        guard paso.isFinite else { return 0 }
        pedido += paso
        let objetivo: Float
        if let incremento, incremento > 0 {
            objetivo = (pedido / incremento).rounded() * incremento
        } else {
            objetivo = pedido
        }
        let delta = objetivo - entregado
        entregado = objetivo
        return delta
    }
}
