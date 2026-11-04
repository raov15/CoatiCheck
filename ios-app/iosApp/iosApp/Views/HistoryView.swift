import SwiftUI
import CoatiCheckShared

/// Historial de asistencias registradas localmente.
struct HistoryView: View {
    @StateObject private var viewModel = HistoryViewModel()

    var body: some View {
        NavigationView {
            List(viewModel.records, id: \.idLocal) { record in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(eventName(record.eventType))
                            .font(.body).bold()
                        Spacer()
                        syncBadge(record.syncStatus)
                    }
                    Text(viewModel.formattedDate(record.occurredAt))
                        .font(.caption)
                        .foregroundColor(.secondary)
                    if let latitude = record.latitude?.doubleValue,
                       let longitude = record.longitude?.doubleValue {
                        Text(String(format: "Ubicación: %.5f, %.5f", latitude, longitude))
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
                .padding(.vertical, 2)
            }
            .listStyle(.plain)
            .overlay {
                if viewModel.records.isEmpty {
                    Text("Aún no hay asistencias registradas")
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("Historial")
            .refreshable {
                viewModel.refresh()
            }
            .onAppear {
                viewModel.refresh()
            }
        }
        .navigationViewStyle(.stack)
    }

    private func eventName(_ raw: String) -> String {
        switch raw {
        case "CLOCK_IN": return "Entrada"
        case "CLOCK_OUT": return "Salida"
        default: return raw
        }
    }

    @ViewBuilder
    private func syncBadge(_ status: String) -> some View {
        let (text, color): (String, Color) = {
            switch status {
            case "SYNCED": return ("Sincronizado", .green)
            case "PENDING": return ("Pendiente", .orange)
            case "ERROR": return ("Error", .red)
            default: return (status, .secondary)
            }
        }()
        Text(text)
            .font(.caption2)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(color.opacity(0.15))
            .foregroundColor(color)
            .clipShape(Capsule())
    }
}

struct HistoryView_Previews: PreviewProvider {
    static var previews: some View {
        HistoryView()
    }
}
