package cattivole.spectra.platform

import cattivole.spectra.core.LocalCa
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** What a root install or removal of the Spectra CA reported. */
enum class SystemCertResult {
    Installed,
    Removed,
    NoRoot,
    NoNsenter,
    NoStore,
    CopyFailed,
    MountFailed,
    NoZygote,
    Failed,
}

/**
 * Puts the Spectra CA into the system trust store for this boot.
 *
 * The private key is never written out. On Android 14 and newer the store
 * apps actually read lives in the Conscrypt APEX module, which is mounted
 * privately in every process, so the copy is bind-mounted into zygote and
 * the processes it already started. The mount is in memory and disappears
 * on reboot. Apps keep the old trust list until they are opened again.
 */
object SystemCertificate {
    fun install(authority: LocalCa): SystemCertResult {
        val pem = pem(authority.certificateDer())
        val script = installScript(pem, authority.subjectHash(), authority.spkiSha256())
        return result(runAsRoot(script))
    }

    fun remove(): SystemCertResult = result(runAsRoot(REMOVE_SCRIPT))

    internal fun installScript(pem: String, hash: String, spki: String): String {
        val encoded = Base64.getEncoder().encodeToString(pem.toByteArray(Charsets.US_ASCII))
        return """
        SYS=/system/etc/security/cacerts
        APEX=/apex/com.android.conscrypt/cacerts
        COPY=/data/local/tmp/spectra-ca-copy
        HASH=$hash
        SPKI='$spki'
        unmount_all() {
          i=0
          while [ "${'$'}i" -lt 40 ]; do
            grep -q " ${'$'}1 " /proc/self/mountinfo || return 0
            umount -l "${'$'}1" 2>/dev/null || return 0
            i=${'$'}((i + 1))
          done
        }
        rollback() {
          umount -l "${'$'}APEX" 2>/dev/null || true
          unmount_all "${'$'}SYS"
        }
        if [ ! -d "${'$'}APEX" ] && [ ! -d "${'$'}SYS" ]; then
          echo "spectra: no-store"
          exit 0
        fi
        unmount_all "${'$'}APEX"
        unmount_all "${'$'}SYS"
        rm -rf "${'$'}COPY"
        mkdir -p "${'$'}COPY"
        chmod 700 "${'$'}COPY"
        if [ -d "${'$'}APEX" ]; then
          cp "${'$'}APEX"/* "${'$'}COPY"/ 2>/dev/null || true
        fi
        n=${'$'}(ls -1 "${'$'}COPY" 2>/dev/null | wc -l | tr -d ' ')
        if [ "${'$'}{n:-0}" -lt 10 ] && [ -d "${'$'}SYS" ]; then
          cp "${'$'}SYS"/* "${'$'}COPY"/ 2>/dev/null || true
          n=${'$'}(ls -1 "${'$'}COPY" 2>/dev/null | wc -l | tr -d ' ')
        fi
        if [ "${'$'}{n:-0}" -lt 10 ]; then
          rm -rf "${'$'}COPY"
          echo "spectra: copy-failed"
          exit 0
        fi
        if ! mount -t tmpfs tmpfs "${'$'}SYS"; then
          rm -rf "${'$'}COPY"
          echo "spectra: mount-failed"
          exit 0
        fi
        if ! cp "${'$'}COPY"/* "${'$'}SYS"/; then
          rollback
          rm -rf "${'$'}COPY"
          echo "spectra: copy-failed"
          exit 0
        fi
        rm -rf "${'$'}COPY"
        echo '$encoded' | base64 -d > "${'$'}SYS/${'$'}HASH.0" || {
          rollback
          echo "spectra: copy-failed"
          exit 0
        }
        if [ ! -s "${'$'}SYS/${'$'}HASH.0" ]; then
          rollback
          echo "spectra: copy-failed"
          exit 0
        fi
        chown root:root "${'$'}SYS" "${'$'}SYS"/* && chmod 755 "${'$'}SYS" && chmod 644 "${'$'}SYS"/* || {
          rollback
          echo "spectra: mount-failed"
          exit 0
        }
        enforce=${'$'}(getenforce 2>/dev/null || true)
        if [ "${'$'}enforce" = "Enforcing" ]; then
          chcon u:object_r:system_file:s0 "${'$'}SYS" && chcon u:object_r:system_file:s0 "${'$'}SYS"/* || {
            rollback
            echo "spectra: mount-failed"
            exit 0
          }
        else
          chcon u:object_r:system_file:s0 "${'$'}SYS" 2>/dev/null || true
          chcon u:object_r:system_file:s0 "${'$'}SYS"/* 2>/dev/null || true
        fi
        sdk=${'$'}(getprop ro.build.version.sdk 2>/dev/null || echo 0)
        case "${'$'}sdk" in
          ''|*[!0-9]*) sdk=0 ;;
        esac
        if [ "${'$'}sdk" -ge 34 ] && [ -d "${'$'}APEX" ]; then
          if ! command -v nsenter >/dev/null 2>&1; then
            rollback
            echo "spectra: no-nsenter"
            exit 0
          fi
          REBIND='i=0
        while [ "${'$'}i" -lt 20 ]; do
          umount -l /apex/com.android.conscrypt/cacerts 2>/dev/null || break
          i=${'$'}((i + 1))
        done
        mount --rbind /system/etc/security/cacerts /apex/com.android.conscrypt/cacerts || mount --bind /system/etc/security/cacerts /apex/com.android.conscrypt/cacerts'
          sh -c "${'$'}REBIND" || true
          zygotes=${'$'}(pidof zygote zygote64 webview_zygote webview_zygote64 2>/dev/null || true)
          injected=0
          for pid in ${'$'}zygotes; do
            if nsenter --mount=/proc/${'$'}pid/ns/mnt -- sh -c "${'$'}REBIND"; then
              injected=${'$'}((injected + 1))
            fi
          done
          if [ "${'$'}injected" -eq 0 ]; then
            rollback
            echo "spectra: no-zygote"
            exit 0
          fi
          for status in /proc/[0-9]*/status; do
            ppid=${'$'}(sed -n 's/^PPid:[ \t]*//p' "${'$'}status" 2>/dev/null) || continue
            case " ${'$'}zygotes " in
              *" ${'$'}ppid "*)
                pid=${'$'}{status#/proc/}
                pid=${'$'}{pid%/status}
                nsenter --mount=/proc/${'$'}pid/ns/mnt -- sh -c "${'$'}REBIND" >/dev/null 2>&1 &
                ;;
            esac
          done
          wait
        fi
        printf '%s\n' "chrome --ignore-certificate-errors-spki-list=${'$'}SPKI" > /data/local/tmp/chrome-command-line 2>/dev/null || true
        chmod 644 /data/local/tmp/chrome-command-line 2>/dev/null || true
        echo "spectra: ok"
        """.trimIndent()
    }

    private fun result(output: String): SystemCertResult {
        val line = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("spectra:") }
        if (line == null) {
            return if (output.contains("denied", ignoreCase = true)) SystemCertResult.NoRoot else SystemCertResult.Failed
        }
        return when (line.removePrefix("spectra:").trim()) {
            "ok" -> SystemCertResult.Installed
            "removed" -> SystemCertResult.Removed
            "no-root" -> SystemCertResult.NoRoot
            "no-nsenter" -> SystemCertResult.NoNsenter
            "no-store" -> SystemCertResult.NoStore
            "copy-failed" -> SystemCertResult.CopyFailed
            "mount-failed" -> SystemCertResult.MountFailed
            "no-zygote" -> SystemCertResult.NoZygote
            else -> SystemCertResult.Failed
        }
    }

    private fun runAsRoot(script: String): String {
        var missing: IOException? = null
        for (binary in SU_BINARIES) {
            try {
                return exec(binary, script)
            } catch (_: IOException) {
                missing = IOException(binary)
            }
        }
        return if (missing != null) "spectra: no-root" else "spectra: failed"
    }

    private fun exec(binary: String, script: String): String {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        val process = ProcessBuilder(binary, "-c", "echo $encoded | base64 -d | sh")
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "spectra: failed"
        }
        return process.inputStream.bufferedReader().use { it.readText() }
    }

    private fun pem(der: ByteArray): String {
        val body = java.util.Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----"
    }
}

private val SU_BINARIES = listOf("su", "/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su", "/sbin/su")

private val REMOVE_SCRIPT = """
    SYS=/system/etc/security/cacerts
    APEX=/apex/com.android.conscrypt/cacerts
    drop() {
      i=0
      while [ "${'$'}i" -lt 40 ]; do
        grep -q " ${'$'}1 " /proc/self/mountinfo || return 0
        umount -l "${'$'}1" 2>/dev/null || return 0
        i=${'$'}((i + 1))
      done
    }
    REBIND='i=0
    while [ "${'$'}i" -lt 20 ]; do
      umount -l /apex/com.android.conscrypt/cacerts 2>/dev/null || break
      i=${'$'}((i + 1))
    done'
    drop "${'$'}APEX"
    if command -v nsenter >/dev/null 2>&1; then
      zygotes=${'$'}(pidof zygote zygote64 webview_zygote webview_zygote64 2>/dev/null || true)
      for pid in ${'$'}zygotes; do
        nsenter --mount=/proc/${'$'}pid/ns/mnt -- sh -c "${'$'}REBIND" >/dev/null 2>&1 || true
      done
      for status in /proc/[0-9]*/status; do
        ppid=${'$'}(sed -n 's/^PPid:[ \t]*//p' "${'$'}status" 2>/dev/null) || continue
        case " ${'$'}zygotes " in
          *" ${'$'}ppid "*)
            pid=${'$'}{status#/proc/}
            pid=${'$'}{pid%/status}
            nsenter --mount=/proc/${'$'}pid/ns/mnt -- sh -c "${'$'}REBIND" >/dev/null 2>&1 &
            ;;
        esac
      done
      wait
    fi
    drop "${'$'}SYS"
    rm -f /data/local/tmp/chrome-command-line
    echo "spectra: removed"
""".trimIndent()
