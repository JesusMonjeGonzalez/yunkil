import AppKit
import SwiftUI
import YunkilCore

/// Una propuesta de la IA esperando a que el usuario decida qué se queda.
///
/// Antes esto era un `NSAlert` con el resumen que escribía el propio modelo y dos
/// botones. Tenía dos problemas y el segundo es el grave: el resumen es una frase
/// del modelo sobre lo que **cree** que hizo —justo el caso que hay que cazar, el
/// plan que dice una cosa y hace otra, es el que ese resumen no distingue—, y era
/// todo o nada, así que cinco operaciones buenas y una mala se descartaban juntas.
///
/// Aquí las líneas las escribe el núcleo leyendo las operaciones, y cada una tiene
/// su casilla.
struct PropuestaPendiente {
    /// Lo que se apunta en la bitácora cuando esto acabe.
    let registro: EstadoDeLaApp.Propuesta
    /// El plan que se aplicaría: el cosido si Yunkil supo unir las piezas, o el suyo.
    let plan: PlanDeModelado
    let lineas: [LineaExplicada]
    let reemplaza: Bool
    let resumen: String
    let reparos: [String]
    let avisos: [String]
    /// Operaciones que añadió Yunkil por su cuenta para unir lo que quedaba suelto.
    let cosidas: Int
    /// Índices marcados. Empiezan todos: la propuesta se acepta entera por omisión y
    /// desmarcar es la excepción, no al revés.
    var aceptadas: Set<Int>

    var todasMarcadas: Bool { aceptadas.count == lineas.count }
    var ningunaMarcada: Bool { aceptadas.isEmpty }
}

/// La propuesta, con una casilla por operación y el motivo de cada una.
struct PanelDePropuesta: View {

    @ObservedObject var estado: EstadoDeLaApp
    let propuesta: PropuestaPendiente

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            cabecera
            Divider()
            operaciones
            if !propuesta.reparos.isEmpty { Divider(); avisosDeGeometria }
            Divider()
            pie
        }
        .frame(width: 420)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(Color.primary.opacity(0.12), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.25), radius: 18, y: 8)
    }

    // MARK: Cabecera

    private var cabecera: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: "sparkles")
                    .foregroundStyle(.tint)
                Text(propuesta.reemplaza ? "Propuesta — sustituye el modelo" : "Propuesta")
                    .font(.system(size: 12, weight: .semibold))
                Spacer()
                Text("\(propuesta.aceptadas.count) de \(propuesta.lineas.count)")
                    .font(.system(size: 11).monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            if !propuesta.resumen.isEmpty {
                Text(propuesta.resumen)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            // Decirlo, y no aplicarlo por detrás: el usuario está mirando una lista y
            // parte de ella no la escribió el modelo.
            if propuesta.cosidas > 0 {
                Label(
                    "Yunkil añadió \(propuesta.cosidas) operación\(propuesta.cosidas == 1 ? "" : "es") para unir las piezas sueltas.",
                    systemImage: "link"
                )
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
            }
            if !propuesta.avisos.isEmpty {
                Text("Se interpretó: " + propuesta.avisos.prefix(3).joined(separator: ", "))
                    .font(.system(size: 11))
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    // MARK: Operaciones

    private var operaciones: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(propuesta.lineas, id: \.indice) { linea in
                    fila(linea)
                    if linea.indice != propuesta.lineas.last?.indice {
                        Divider().padding(.leading, 34)
                    }
                }
            }
        }
        .frame(maxHeight: 260)
    }

    private func fila(_ linea: LineaExplicada) -> some View {
        let marcada = propuesta.aceptadas.contains(Int(linea.indice))
        return Button {
            estado.alternarOperacion(Int(linea.indice))
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 9) {
                Image(systemName: marcada ? "checkmark.square.fill" : "square")
                    .foregroundStyle(marcada ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
                    .font(.system(size: 13))
                Text(linea.texto)
                    .font(.system(size: 12))
                    .foregroundStyle(marcada ? .primary : .secondary)
                    .strikethrough(!marcada, color: .secondary)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Lo que sigue sin cuadrar

    private var avisosDeGeometria: some View {
        VStack(alignment: .leading, spacing: 3) {
            Label("La pieza propuesta tiene problemas sin resolver", systemImage: "exclamationmark.triangle")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(.orange)
            ForEach(propuesta.reparos.prefix(3), id: \.self) { reparo in
                Text("· " + reparo)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }

    // MARK: Pie

    private var pie: some View {
        HStack(spacing: 8) {
            Button(propuesta.todasMarcadas ? "Ninguna" : "Todas") {
                estado.marcarTodasLasOperaciones(!propuesta.todasMarcadas)
            }
            .buttonStyle(.plain)
            .font(.system(size: 11))
            .foregroundStyle(.secondary)

            Spacer()

            Button("Descartar") { estado.descartarPropuesta() }
                .keyboardShortcut(.cancelAction)

            Button(botonDeAceptar) { estado.aceptarPropuesta() }
                .keyboardShortcut(.defaultAction)
                .disabled(propuesta.ningunaMarcada)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    private var botonDeAceptar: String {
        if propuesta.ningunaMarcada { return "Aplicar" }
        if propuesta.todasMarcadas { return propuesta.reemplaza ? "Reemplazar" : "Aplicar" }
        return "Aplicar \(propuesta.aceptadas.count)"
    }
}
