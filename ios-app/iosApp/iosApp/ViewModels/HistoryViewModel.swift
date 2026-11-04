import Foundation
import CoatiCheckShared

@MainActor
final class HistoryViewModel: ObservableObject {
    @Published var records: [AttendanceRecordInfo] = []

    private let sharedApp = AppDependencies.shared.sharedApp

    func refresh() {
        records = sharedApp.getRecentAttendance(limit: 50)
        AppState.shared.refreshPending()
    }

    func formattedDate(_ epochMillis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMillis) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .medium
        return formatter.string(from: date)
    }
}
