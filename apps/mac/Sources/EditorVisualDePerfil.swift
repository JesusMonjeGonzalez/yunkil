import SwiftUI

struct EditorVisualDePerfil: View {
    let puntosIniciales: [CGPoint]
    let alGuardar: ([CGPoint]) -> String?
    let alCerrar: () -> Void

    @State private var puntos: [CGPoint]
    @State private var rejilla: CGFloat = 5
    @State private var indiceArrastrado: Int?
    @State private var error: String?

    init(
        puntosIniciales: [CGPoint],
        alGuardar: @escaping ([CGPoint]) -> String?,
        alCerrar: @escaping () -> Void
    ) {
        self.puntosIniciales = puntosIniciales
        self.alGuardar = alGuardar
        self.alCerrar = alCerrar
        _puntos = State(initialValue: puntosIniciales)
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Editor de perfil").font(.headline)
                    Text("Haz clic para añadir puntos; arrastra uno existente para moverlo.")
                        .font(.caption).foregroundStyle(Tinta.cota)
                }
                Spacer()
                Picker("Rejilla", selection: $rejilla) {
                    Text("1 mm").tag(CGFloat(1))
                    Text("2 mm").tag(CGFloat(2))
                    Text("5 mm").tag(CGFloat(5))
                    Text("10 mm").tag(CGFloat(10))
                }
                .pickerStyle(.segmented).frame(width: 250)
            }
            .padding(16)

            Divider()
            lienzo.frame(minWidth: 720, minHeight: 520)
            Divider()

            HStack {
                Text("\(puntos.count) vértices")
                    .font(.caption).foregroundStyle(Tinta.cota)
                if let error {
                    Text(error).font(.caption).foregroundStyle(.red)
                }
                Spacer()
                Button("Vaciar") { puntos.removeAll(); error = nil }
                    .disabled(puntos.isEmpty)
                Button("Deshacer punto") { _ = puntos.popLast(); error = nil }
                    .disabled(puntos.isEmpty)
                Button("Cancelar", action: alCerrar)
                Button("Guardar perfil") {
                    error = alGuardar(puntos)
                    if error == nil { alCerrar() }
                }
                .buttonStyle(.borderedProminent).tint(.mint)
                .disabled(puntos.count < 3)
            }
            .padding(14)
        }
        .frame(minWidth: 760, minHeight: 640)
    }

    private var lienzo: some View {
        GeometryReader { geo in
            let escala = escalaPara(geo.size)
            let centro = CGPoint(x: geo.size.width / 2, y: geo.size.height / 2)

            Canvas { contexto, tamano in
                dibujarRejilla(contexto: &contexto, tamano: tamano, centro: centro, escala: escala)
                dibujarPerfil(contexto: &contexto, centro: centro, escala: escala)
            }
            .background(Color(nsColor: .controlBackgroundColor))
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { valor in
                        if indiceArrastrado == nil {
                            indiceArrastrado = indiceCercano(
                                a: valor.startLocation, centro: centro, escala: escala
                            )
                        }
                        if let i = indiceArrastrado {
                            puntos[i] = ajustar(
                                mundo(valor.location, centro: centro, escala: escala),
                                ignorando: i
                            )
                        }
                    }
                    .onEnded { valor in
                        if indiceArrastrado == nil {
                            let nuevo = ajustar(mundo(valor.location, centro: centro, escala: escala))
                            if puntos.count < 3 || nuevo != puntos.first { puntos.append(nuevo) }
                        }
                        indiceArrastrado = nil
                        error = nil
                    }
            )
        }
    }

    private func escalaPara(_ tamano: CGSize) -> CGFloat {
        let alcance = max(
            60,
            puntos.flatMap { [abs($0.x), abs($0.y)] }.max() ?? 0 + 15
        )
        return min(tamano.width, tamano.height) / (alcance * 2)
    }

    private func pantalla(_ p: CGPoint, centro: CGPoint, escala: CGFloat) -> CGPoint {
        CGPoint(x: centro.x + p.x * escala, y: centro.y - p.y * escala)
    }

    private func mundo(_ p: CGPoint, centro: CGPoint, escala: CGFloat) -> CGPoint {
        CGPoint(x: (p.x - centro.x) / escala, y: (centro.y - p.y) / escala)
    }

    private func ajustar(_ p: CGPoint, ignorando: Int? = nil) -> CGPoint {
        var q = CGPoint(
            x: (p.x / rejilla).rounded() * rejilla,
            y: (p.y / rejilla).rounded() * rejilla
        )
        let referencia = puntos.indices.reversed().first { $0 != ignorando }.map { puntos[$0] }
        if let referencia {
            if abs(q.x - referencia.x) <= rejilla { q.x = referencia.x }
            if abs(q.y - referencia.y) <= rejilla { q.y = referencia.y }
        }
        if puntos.count >= 3, ignorando == nil, let primero = puntos.first,
           hypot(q.x - primero.x, q.y - primero.y) <= rejilla {
            q = primero
        }
        return q
    }

    private func indiceCercano(a ubicacion: CGPoint, centro: CGPoint, escala: CGFloat) -> Int? {
        puntos.indices.min { a, b in
            distancia(pantalla(puntos[a], centro: centro, escala: escala), ubicacion) <
                distancia(pantalla(puntos[b], centro: centro, escala: escala), ubicacion)
        }.flatMap {
            distancia(pantalla(puntos[$0], centro: centro, escala: escala), ubicacion) <= 12 ? $0 : nil
        }
    }

    private func distancia(_ a: CGPoint, _ b: CGPoint) -> CGFloat {
        hypot(a.x - b.x, a.y - b.y)
    }

    private func dibujarRejilla(
        contexto: inout GraphicsContext,
        tamano: CGSize,
        centro: CGPoint,
        escala: CGFloat
    ) {
        let paso = rejilla * escala
        guard paso >= 3 else { return }
        var menores = Path()
        var x = centro.x.truncatingRemainder(dividingBy: paso)
        while x < tamano.width { menores.move(to: CGPoint(x: x, y: 0)); menores.addLine(to: CGPoint(x: x, y: tamano.height)); x += paso }
        var y = centro.y.truncatingRemainder(dividingBy: paso)
        while y < tamano.height { menores.move(to: CGPoint(x: 0, y: y)); menores.addLine(to: CGPoint(x: tamano.width, y: y)); y += paso }
        contexto.stroke(menores, with: .color(.secondary.opacity(0.12)), lineWidth: 0.5)

        var ejes = Path()
        ejes.move(to: CGPoint(x: 0, y: centro.y)); ejes.addLine(to: CGPoint(x: tamano.width, y: centro.y))
        ejes.move(to: CGPoint(x: centro.x, y: 0)); ejes.addLine(to: CGPoint(x: centro.x, y: tamano.height))
        contexto.stroke(ejes, with: .color(.secondary.opacity(0.55)), lineWidth: 1)
    }

    private func dibujarPerfil(
        contexto: inout GraphicsContext,
        centro: CGPoint,
        escala: CGFloat
    ) {
        guard let primero = puntos.first else { return }
        var camino = Path()
        camino.move(to: pantalla(primero, centro: centro, escala: escala))
        for punto in puntos.dropFirst() {
            camino.addLine(to: pantalla(punto, centro: centro, escala: escala))
        }
        if puntos.count >= 3 { camino.closeSubpath() }
        contexto.fill(camino, with: .color(.mint.opacity(0.16)))
        contexto.stroke(camino, with: .color(.mint), lineWidth: 2)

        for (i, punto) in puntos.enumerated() {
            let p = pantalla(punto, centro: centro, escala: escala)
            let rect = CGRect(x: p.x - 5, y: p.y - 5, width: 10, height: 10)
            contexto.fill(Path(ellipseIn: rect), with: .color(i == indiceArrastrado ? .orange : .white))
            contexto.stroke(Path(ellipseIn: rect), with: .color(.mint), lineWidth: 2)
        }
    }
}
