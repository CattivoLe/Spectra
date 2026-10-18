import MapKit
import SwiftUI

/// A live, follow-the-walk map for war-driving: the phone's current position,
/// a breadcrumb track of where you have been, and the located scans already in
/// History as coverage points. The track is kept only for the session.
struct LiveMapView: View {
    @Environment(LocationService.self) private var location
    @Environment(HistoryStore.self) private var history
    @Environment(\.dismiss) private var dismiss
    @State private var track = TrackRecorder()
    @State private var camera: MapCameraPosition = .userLocation(fallback: .automatic)

    /// One point per located session, drawn as a small coverage dot.
    private var coverage: [CoveragePoint] {
        history.entries.compactMap { entry in
            guard let lat = entry.latitude, let lon = entry.longitude else { return nil }
            return CoveragePoint(
                id: entry.id,
                coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon),
                kind: entry.kind
            )
        }
    }

    var body: some View {
        NavigationStack {
            Map(position: $camera) {
                UserAnnotation()
                ForEach(coverage) { point in
                    Annotation("", coordinate: point.coordinate, anchor: .center) {
                        Image(systemName: point.kind.systemImage)
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 20, height: 20)
                            .background(.tint, in: Circle())
                            .overlay(Circle().stroke(.white, lineWidth: 1.5))
                    }
                }
                if track.points.count > 1 {
                    MapPolyline(coordinates: track.points)
                        .stroke(.blue, style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
                }
            }
            .mapControls {
                MapUserLocationButton()
                MapCompass()
            }
            .navigationTitle("Live map")
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: location.coordinate) { _, coordinate in
                guard let coordinate else { return }
                track.note(CLLocationCoordinate2D(latitude: coordinate.latitude, longitude: coordinate.longitude))
            }
            .safeAreaInset(edge: .bottom) {
                controls
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private var controls: some View {
        HStack(spacing: 12) {
            Button {
                track.isRecording.toggle()
                if track.isRecording, let here = location.coordinate {
                    track.note(CLLocationCoordinate2D(latitude: here.latitude, longitude: here.longitude))
                }
            } label: {
                Label(
                    track.isRecording ? "Recording" : "Record track",
                    systemImage: track.isRecording ? "stop.circle.fill" : "record.circle"
                )
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(track.isRecording ? .red : .accentColor)

            if !track.points.isEmpty {
                Button {
                    track.clear()
                } label: {
                    Label("Clear", systemImage: "trash")
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
    }
}

/// A located scan shown as a coverage dot on the live map.
private struct CoveragePoint: Identifiable {
    let id: UUID
    let coordinate: CLLocationCoordinate2D
    let kind: HistoryEntry.Kind
}
