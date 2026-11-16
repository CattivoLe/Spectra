@preconcurrency import Darwin
import Foundation

/// Показатели процесса и устройства, по которым видно, «влезает» ли модель.
public enum DeviceMetrics {
    /// Память процесса в том виде, в каком её считает система, решая, не убить ли приложение.
    public static var footprintBytes: UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? info.phys_footprint : 0
    }

    /// Сколько памяти приложение ещё может занять до принудительного завершения. Есть только на iOS.
    public static var availableBytes: UInt64? {
        #if os(iOS)
        return UInt64(os_proc_available_memory())
        #else
        return nil
        #endif
    }

    public static var physicalMemoryBytes: UInt64 {
        ProcessInfo.processInfo.physicalMemory
    }

    public static var thermalState: String {
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: String(localized: "nominal")
        case .fair: String(localized: "warm")
        case .serious: String(localized: "hot")
        case .critical: String(localized: "overheating")
        @unknown default: String(localized: "unknown")
        }
    }

    public static var isCool: Bool {
        ProcessInfo.processInfo.thermalState == .nominal
    }

    public static var machineIdentifier: String {
        var size = 0
        sysctlbyname("hw.machine", nil, &size, nil, 0)
        var buffer = [CChar](repeating: 0, count: size)
        sysctlbyname("hw.machine", &buffer, &size, nil, 0)
        return buffer.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }
    }
}

/// Следит за пиком памяти, пока идёт замер.
public final class FootprintSampler: Sendable {
    private let task: Task<UInt64, Never>

    public init(interval: Duration = .milliseconds(100)) {
        task = Task.detached(priority: .utility) {
            var peak = DeviceMetrics.footprintBytes
            while !Task.isCancelled {
                try? await Task.sleep(for: interval)
                peak = max(peak, DeviceMetrics.footprintBytes)
            }
            return peak
        }
    }

    /// Останавливает наблюдение и возвращает пик в байтах.
    public func stop() async -> UInt64 {
        task.cancel()
        return max(await task.value, DeviceMetrics.footprintBytes)
    }
}
