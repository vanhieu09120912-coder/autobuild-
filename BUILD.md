# AutoBuild (Fabric 1.21.11)

## Build
1. JDK 21. Lay `gradlew`, `gradlew.bat`, thu muc `gradle/` tu Fabric example mod (fabricmc.net/develop) copy vao day, hoac chay `gradle wrapper` neu da cai Gradle.
2. Bo jar Litematica va MaLiLib ban 1.21.11 vao thu muc `libs/` (chi de bien dich).
3. `./gradlew build` -> jar nam trong `build/libs/autobuild-1.0.0.jar`.
4. Bo vao `mods/` cung Fabric API, Litematica, MaLiLib.

## Dung
- Load schematic trong Litematica, cam block (redstone, repeater, observer, piston...) tren tay chinh.
- `/autobuild` bat/tat. `/autobuild status`.
- `/autobuild reach <1-4.5>` (mac dinh 4.0), `/autobuild delay <1-40>` tick giua 2 lan dat (mac dinh 4).
- `/autobuild spoof true` neu muon xoay gia de ep huong (de bi anticheat bat hon).
- Layer mode cua Litematica duoc ton trong. Mod dat tu duoi len tren.
