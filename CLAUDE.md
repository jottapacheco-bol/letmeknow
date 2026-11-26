# LetMeKnow: lo que todo hilo debe saber

- El pastor habla español: todo lo que se ve en las apps va en español sencillo; el código y sus comentarios, en inglés.
- **Sin Android Studio.** Se compila como Levite (`jottapacheco-bol/bol-levite`): runner propio de Windows en la computadora del pastor; APKs a `D:\Levite\Compilados` (main) y `D:\Levite\Revisados` (PR). En el contenedor no hay Android SDK: revise con cuidado. Se puede revisar el Kotlin contra `org.robolectric:android-all` (Maven Central) con un proyecto Kotlin/JVM aparte y un `R`/`BuildConfig` de mentira.
- Las pantallas se hacen en código (sin layouts XML); los íconos son vectores en `radio/src/main/res/drawable`, coloreados en código.
- Lo que hablan las dos apps vive en `shared/` y entra en las dos (`sourceSets`). Si cambia el protocolo, suba `Protocol.VERSION` y cambie las dos apps a la vez.
- **Todo cambio de una app sube su versión** (`versionName` y `versionCode` en su `build.gradle.kts`) y agrega una sección arriba en su `CHANGELOG.md`.
- El aspecto sigue los dibujos aprobados (https://claude.ai/artifact/CEdaZd9vw4ctmNFUdvTFZE): paneles de vidrio, naranja para lo principal, celeste para la voz, verde para enviar o terminar; muchos íconos y animaciones, poca letra (va manejando). Todo cambio de lo que se ve, primero con dibujo.
- Las claves nunca en GitHub: salen de `D:\Levite\claves.properties` al compilar (`TTS_KEY_LEVITE`).
