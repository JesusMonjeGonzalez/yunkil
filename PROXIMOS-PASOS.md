# Yunkil — próximos pasos

Estado a 3 de agosto de 2026. Para cómo funciona lo que ya existe, ver
[docs/ARQUITECTURA.md](docs/ARQUITECTURA.md).

## Dónde estamos

El **subproyecto 1 está terminado y funcionando**: kernel SDF, documento
paramétrico con historial, generador de MSL, editor completo en macOS y paridad
CPU/GPU verificada sobre 19 000 puntos.

Lo que hay se puede usar para modelar de verdad, pero **no para producir nada**:
no exporta. Ese es el siguiente hueco y el más urgente.

---

## Subproyecto 2 — analizador de fabricación y exportación

Es lo que convierte el juguete en herramienta. Sin esto, Yunkil es un visor
bonito.

### 2.1 Perfiles 2D *(primero, porque sin esto no se pueden hacer piezas reales)*

Polígonos, arcos y bézier tienen SDF 2D exacto; se extruyen o se revolucionan a
3D. Es la respuesta a la carencia de bocetos.

**Decidido**: acotación numérica directa sobre cada segmento más imanes de
ortogonalidad e igualdad. **Nada de solucionador de restricciones geométricas**:
es otro subsistema entero y las órdenes en lenguaje natural del subproyecto 3
cubren el caso que no llegue.

### 2.2 Mallado y exportación

- Contorneado dual sobre octree adaptativo, refinando cerca de la superficie y de
  las aristas vivas. Es el código más delicado que queda.
- Contorneado consciente de primitivas: los cilindros y planos exactos deben
  clavar sus vértices sobre la superficie analítica. **Los cilindros son el caso
  crítico**, porque son los agujeros de los encajes.
- **Verificación obligatoria antes de entregar el archivo**: cerrado y manifold,
  sin auto-intersecciones, normales coherentes, desviación máxima medida contra el
  campo exacto, y volumen de la malla contrastado con el volumen analítico. Si
  algo no cuadra, refinar y reintentar; si sigue fallando, decir qué y dónde. **No
  se entrega una malla que no pasa el examen.**
- El diálogo de exportar muestra la desviación: `desviación máx. 0,014 mm`.
  Convierte un riesgo invisible en un número visible.
- Formatos: STL binario y 3MF.

### 2.3 El analizador

Reglas sobre el campo, no sobre triángulos. Cada una cita su umbral y de qué
perfil sale.

| Regla | Cómo se calcula |
|---|---|
| Grosor de pared | Lectura directa: el campo por dos |
| Voladizos | Ángulo entre la normal (gradiente) y la dirección de impresión |
| Detalle mínimo | Comparación con el diámetro de boquilla |
| Base de apoyo | Área de contacto con el plato |
| Tolerancia de encaje | Sobre pares de superficies declaradas por el usuario |
| Estanqueidad | Garantizada por construcción: se informa, no se comprueba |

**Cada aviso lleva una corrección ejecutable.** «Voladizo 62° → [Achaflanar 5 mm]
[Rotar 40°]», y pulsarla aplica una operación real al documento. Como el
analizador ve el árbol paramétrico y no triángulos, sabe *qué cilindro* hizo ese
agujero y puede ajustarle el diámetro. Un laminador jamás podrá hacerlo. **Ahí
está el foso.**

Severidades honestas: `fallará` / `probable` / `mejorable`. Nunca gritar.
Silenciable por pieza, con memoria.

### 2.4 Perfiles y calibración

Perfiles de fabricación versionados y editables (boquilla, altura de capa,
material, impresora), con un conjunto verificado de partida.

**Modelo de calibración imprimible**: torre de voladizos y pasadores de
tolerancia que se imprimen, se miden y ajustan el perfil a la impresora real. Es
como ya trabaja la comunidad maker, y convierte los umbrales de «lo que dijo la
app» a «lo que mide tu máquina». Sin esto, un solo aviso falso destruye la
confianza para siempre.

### 2.5 Orientación óptima

Calcular la orientación de impresión que minimiza voladizos y maximiza la base, y
**dejarla aplicada en el 3MF exportado**, con una puntuación de imprimibilidad.
Es la función que la gente enseña a otros.

---

## Subproyecto 3 — puente LLM

El SDF se serializa a un DSL diminuto que un modelo puede escribir con fiabilidad,
cosa que no ocurre con B-rep ni con mallas. Y el analizador le da lo que ningún
text-to-CAD tiene: **un crítico con verdad de campo**.

```
usuario: "un soporte de móvil, 8 mm de grueso, inclinado 60°"
   → el modelo emite operaciones sobre el documento
   → el núcleo las valida y aplica
   → el analizador: "pared 0,9 mm < 1,2 mm mínimo; voladizo 71°"
   → los hallazgos vuelven al modelo automáticamente
   → corrige → converge (máximo 3 vueltas)
```

Reglas que no se negocian:

- **El modelo nunca ejecuta código.** Solo emite operaciones declarativas que el
  núcleo valida contra esquema antes de aplicar. La infraestructura ya está: son
  los mismos métodos de `Editor` que usa la interfaz.
- **Todo cambio entra en el historial**, así que deshacer siempre funciona.

Agnóstico de proveedor: interfaz `LlmClient` sobre Ktor en `commonMain`, con
adaptador compatible con OpenAI (cubre el stack local en `:9292`/`:9300`, Ollama,
LM Studio, vLLM) y adaptador Anthropic. **Por defecto el modelo local**: gratis,
privado y sin internet, que es un argumento de venta frente a todo lo que es nube
obligatoria.

Después, casi gratis: exponer las operaciones como servidor MCP para que
cualquier agente externo pueda modelar.

---

## Subproyecto 4 — puente Bambu Lab

- 3MF listo para producción con la orientación óptima aplicada y la placa montada.
- Envío por LAN a la impresora y seguimiento del estado en vivo dentro de Yunkil.
- En Mac, además: laminar invocando el CLI de Bambu Studio u Orca y mandar a
  imprimir sin salir de la app. En iPad, entrega del 3MF a Bambu Handy.

**Fuera de alcance, y quede dicho: no se escribe un laminador propio.** Es un
subsistema mayor que los otros cuatro juntos y hundiría el proyecto.

*Pendiente: confirmar los modelos exactos de impresora (¿P1S? ¿P1P? ¿X1C? ¿A1?).*

---

## iPad — lo que falta

El núcleo ya compila para `iosArm64` e `iosSimulatorArm64`. Lo que falta:

1. Objetivo de app iOS y XCFramework universal.
2. **Despacho por tiles.** Es el trabajo de verdad. Si un command buffer tarda
   demasiado, el vigilante de la GPU termina la aplicación. Sin trocear, la app se
   cae en iPad; no es una hipótesis.
3. Gestos táctiles: dos dedos para orbitar, pinza para acercar, tres para
   desplazar.
4. Interfaz adaptada: la piel táctil comparte el núcleo pero no el diseño de
   paneles. Objetivos de toque de 44 pt o más.
5. `xcodebuild -downloadPlatform iOS` para tener runtimes de simulador.

---

## Deudas de la aplicación de escritorio

Ordenadas por lo que más estorba al usar la app hoy:

- **Gizmos en el viewport.** Mover una pieza es teclear números; debería poder
  arrastrarse. Requiere selección por rayo contra el campo, que es fácil: se
  raymarchea el rayo del cursor.
- **Selección desde el viewport**: pulsar una pieza y que se marque en el árbol.
- **Reparentar arrastrando** en el árbol.
- **Duplicar pieza** (⌘D) y copiar/pegar.
- **Rejilla y plato de referencia** con escala en milímetros. Ahora no hay
  ninguna referencia de tamaño en pantalla, que para una herramienta de cotas es
  una carencia seria.
- Vistas predefinidas (planta, alzado, perfil) y proyección ortográfica.
- Renombrar desde el árbol con doble clic.
- Aviso al cerrar con cambios sin guardar.

## Deudas técnicas

- **Los identificadores de pieza usan un contador global** en `Pieza.companion`.
  Funciona con un documento a la vez y un solo hilo; hay que revisarlo antes de
  abrir varios documentos o de tocar el árbol desde otro hilo.
- `Editor` recompila el árbol entero en cada consulta de uniforms y de cotas. Con
  piezas grandes habrá que cachear por versión del documento.
- El arnés de paridad se compila a mano con `swiftc`. Debería ser un test XCTest
  ejecutable con un solo comando.
- Falta una prueba automática de presupuesto de fotograma que falle si se supera
  el umbral, en Mac y en iPad.
