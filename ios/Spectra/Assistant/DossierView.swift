import SwiftUI

struct DossierView: View {
    @Bindable var memory: AssistantMemory
    @Environment(\.dismiss) private var dismiss

    /// Only devices seen often enough to have a usual shape; the rest are noise in a list.
    private var known: [AssistantMemory.Baseline] {
        memory.baselines.values.filter(\.isEstablished).sorted { $0.sightings > $1.sightings }
    }

    var body: some View {
        NavigationStack {
            Group {
                if memory.notes.isEmpty && known.isEmpty {
                    ContentUnavailableView(
                        "No notes yet",
                        systemImage: "doc.text.magnifyingglass",
                        description: Text("The assistant saves important findings here during chats: suspicious devices, weak passwords, conclusions.")
                    )
                } else {
                    List {
                        Section {
                            ForEach(memory.notes) { note in
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(note.text)
                                    HStack(spacing: 6) {
                                        if let subject = note.subject {
                                            Text(subject)
                                                .font(.caption2.monospaced())
                                                .padding(.horizontal, 5).padding(.vertical, 1)
                                                .background(.quaternary, in: .capsule)
                                        }
                                        Text(note.createdAt, format: .relative(presentation: .named))
                                            .font(.caption).foregroundStyle(.tertiary)
                                    }
                                }
                            }
                            .onDelete { offsets in
                                for index in offsets { memory.remove(id: memory.notes[index].id) }
                            }
                        } header: {
                            if !memory.notes.isEmpty { Text("Notes") }
                        } footer: {
                            if memory.notes.contains(where: { $0.subject != nil }) {
                                Text("A note filed under an address comes back only when that device is around.")
                            }
                        }

                        if !known.isEmpty {
                            Section {
                                ForEach(known, id: \.subject) { base in
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(base.label)
                                        Text("\(base.subject) · seen \(base.sightings)×")
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            } header: {
                                Text("What Spectra has learned")
                            } footer: {
                                Text("How each device usually looks — its ports and signal — so an unusual one stands out.")
                            }
                        }
                    }
                }
            }
            .navigationTitle("Dossier")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                if !memory.notes.isEmpty || !known.isEmpty {
                    ToolbarItem(placement: .destructiveAction) {
                        Button("Clear all", role: .destructive) { memory.removeAll() }
                    }
                }
            }
        }
    }
}
