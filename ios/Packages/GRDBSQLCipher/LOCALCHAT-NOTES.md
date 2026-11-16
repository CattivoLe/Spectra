# GRDB с SQLCipher

Копия GRDB 7.12.0 (MIT, см. LICENSE) с изменённым `Package.swift`: вместо системной SQLite подключён SQLCipher 4.20.0
(пакет sqlcipher/SQLCipher.swift). Это штатный способ из README GRDB для SwiftPM, который требует форка.
Удалены тесты, документация и сборочные файлы. Исходники GRDB не менялись.
