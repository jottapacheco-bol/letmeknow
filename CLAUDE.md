# LetMeKnow: lo que todo hilo debe saber

- El pastor habla español: todo lo que se ve en las apps va en español sencillo; el código y sus comentarios, en inglés.
- **Sin Android Studio.** Se compila en el runner gratuito de GitHub (`ubuntu-latest`, lo escogió el pastor el 26 nov. 2026 en vez del runner de su computadora): main publica una Release con los dos APK (últimas 3), cada PR la pre-release `pr-<n>`. Nada como artifact (el almacenamiento de Actions de la cuenta está lleno). La firma fija es el secreto `SIGNING_KEYSTORE` (SHA-1 `1E:7C:AE:BB:48:0D:55:87:36:A6:BF:85:04:F5:57:DD:B5:AE:80:B0`). Los minutos gratis son limitados: no agregue pasos pesados. En el contenedor no hay Android SDK: revise con cuidado. Se puede revisar el Kotlin contra `org.robolectric:android-all` (Maven Central) con un proyecto Kotlin/JVM aparte y un `R`/`BuildConfig` de mentira.
- Las pantallas se hacen en código (sin layouts XML); los íconos son vectores en `radio/src/main/res/drawable`, coloreados en código.
- Lo que hablan las dos apps vive en `shared/` y entra en las dos (`sourceSets`). Si cambia el protocolo, suba `Protocol.VERSION` y cambie las dos apps a la vez.
- **Todo cambio de una app sube su versión** (`versionName` y `versionCode` en su `build.gradle.kts`) y agrega una sección arriba en su `CHANGELOG.md`.
- El aspecto sigue los dibujos aprobados (https://claude.ai/artifact/CEdaZd9vw4ctmNFUdvTFZE): paneles de vidrio, naranja para lo principal, celeste para la voz, verde para enviar o terminar; muchos íconos y animaciones, poca letra (va manejando). Todo cambio de lo que se ve, primero con dibujo.
- Las claves nunca en el código: salen de los secretos del repositorio al compilar (`TTS_KEY_LEVITE`), o de `claves.properties` en una compilación local.
