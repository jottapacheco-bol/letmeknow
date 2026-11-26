# LetMeKnow

Las notificaciones del teléfono, en grande, en el radio del carro.

- **LetMeKnow del teléfono** (`telefono/`, `org.bolcr.letmeknow`): escucha las notificaciones de las apps que usted escoja (WhatsApp ya viene encendido) y las manda al radio por la Wi-Fi del teléfono. Si en el radio toca «Teléfono», en el teléfono pasa lo mismo que si tocara la notificación. Las respuestas dictadas y las rápidas se mandan con el botón «Responder» de WhatsApp.
- **LetMeKnow del radio** (`radio/`, `org.bolcr.letmeknow.radio`, Atoto S8 Gen2 con Android 10): una burbuja por conversación flota encima de las apps. Al tocarla se abre una ventana flotante con el mensaje en letra grande y cinco botones: escuchar, responder dictando, respuesta rápida, abrir en el teléfono y listo. La campana sirve para silenciar por un rato, y ahí mismo están el sonido al llegar, la lectura automática y el tamaño del texto.

Dibujos aprobados (26 nov. 2026): https://claude.ai/artifact/CEdaZd9vw4ctmNFUdvTFZE

## Cómo se conectan

Cuando el teléfono se conecta al Bluetooth del carro, la rutina del teléfono enciende el punto de acceso y el radio se conecta a esa Wi-Fi. El teléfono espera al radio en el puerto **47800**. El radio lo busca solo: primero por su nombre en la red (NSD), después en la puerta de enlace de la Wi-Fi (con el punto de acceso, esa es el teléfono) y por último en una dirección escrita a mano en la pantalla del radio. Se mandan una línea JSON por mensaje (`shared/.../Protocol.kt`).

## Compilar

Igual que Levite: sin Android Studio. Cada cambio en `main` compila en la computadora del pastor (runner propio de GitHub, `self-hosted, Windows`) y deja los APK en `D:\Levite\Compilados\LetMeKnow-Telefono-<versión>.apk` y `LetMeKnow-Radio-<versión>.apk`. Cada PR deja los suyos en `D:\Levite\Revisados` con `-pr<número>`.

**Una sola vez:** este repositorio necesita su propio runner en la computadora (GitHub no comparte los runners de una cuenta personal entre repositorios). En GitHub: *Settings › Actions › Runners › New self-hosted runner*, Windows, y en la computadora instálelo en una carpeta aparte (por ejemplo `D:\actions-runner-letmeknow`) como servicio, igual que los de Levite.

## La voz de Google (opcional)

El radio lee con la voz de Levite (Google Chirp 3 HD, Charon) si al compilar encuentra `TTS_KEY_LEVITE=<la clave>` en `D:\Levite\claves.properties`. Es la misma clave «Levite TTS» del proyecto de bolcr.org. Hay que agregarle en Google Cloud la app Android `org.bolcr.letmeknow.radio` con el SHA-1 de la firma de CI (`92:6F:DC:66:5B:29:BC:B9:24:05:18:42:65:1A:4F:45:DA:EE:DD:D4`). Sin clave, lee la voz del propio radio.

## Primera vez en cada aparato

- **Teléfono:** abrir LetMeKnow y dar los tres permisos que pide: leer notificaciones, mostrar sobre otras apps (para abrir WhatsApp desde el radio) y batería sin restricciones.
- **Radio:** abrir LetMeKnow, dar «Mostrar burbujas encima de las apps» y el micrófono, y tocar «Probar».
