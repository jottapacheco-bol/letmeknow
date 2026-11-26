# LetMeKnow

Las notificaciones del teléfono, en grande, en el radio del carro.

- **LetMeKnow del teléfono** (`telefono/`, `org.bolcr.letmeknow`): escucha las notificaciones de las apps que usted escoja (WhatsApp ya viene encendido) y las manda al radio por la Wi-Fi del teléfono. Si en el radio toca «Teléfono», en el teléfono pasa lo mismo que si tocara la notificación. Las respuestas dictadas y las rápidas se mandan con el botón «Responder» de WhatsApp.
- **LetMeKnow del radio** (`radio/`, `org.bolcr.letmeknow.radio`, Atoto S8 Gen2 con Android 10): una burbuja por conversación flota encima de las apps. Al tocarla se abre una ventana flotante con el mensaje en letra grande y cinco botones: escuchar, responder dictando, respuesta rápida, abrir en el teléfono y listo. La campana sirve para silenciar por un rato, y ahí mismo están el sonido al llegar, la lectura automática y el tamaño del texto.

Dibujos aprobados (26 nov. 2026): https://claude.ai/artifact/CEdaZd9vw4ctmNFUdvTFZE

## Cómo se conectan

Cuando el teléfono se conecta al Bluetooth del carro, la rutina del teléfono enciende el punto de acceso y el radio se conecta a esa Wi-Fi. El teléfono espera al radio en el puerto **47800**. El radio lo busca solo: primero por su nombre en la red (NSD), después en la puerta de enlace de la Wi-Fi (con el punto de acceso, esa es el teléfono) y por último en una dirección escrita a mano en la pantalla del radio. Se mandan una línea JSON por mensaje (`shared/.../Protocol.kt`).

## Compilar

Sin Android Studio, en el runner gratuito de GitHub (lo escogió el pastor, 26 nov. 2026). Cada cambio en `main` publica una **Release** con `LetMeKnow-Telefono-<versión>.apk` y `LetMeKnow-Radio-<versión>.apk` (se guardan las últimas 3). Cada PR deja los suyos en la pre-release `pr-<número>` para probarlos antes de unir. Se bajan desde *Releases* en GitHub, en el teléfono o en la computadora.

**Una sola vez:** en GitHub, *Settings › Secrets and variables › Actions › New repository secret*, nombre `SIGNING_KEYSTORE`, con el texto de la firma que le dio Claude. Así todas las versiones tienen la misma firma y cada una se instala encima de la anterior. Guarde ese texto en un lugar seguro: si se pierde, hay que desinstalar y volver a instalar.

## La voz de Google (opcional)

El radio lee con la voz de Levite (Google Chirp 3 HD, Charon) si al compilar encuentra la clave: el secreto `TTS_KEY_LEVITE` del repositorio (la misma clave «Levite TTS» del proyecto de bolcr.org). Hay que agregarle en Google Cloud la app Android `org.bolcr.letmeknow.radio` con el SHA-1 de la firma de LetMeKnow, `1E:7C:AE:BB:48:0D:55:87:36:A6:BF:85:04:F5:57:DD:B5:AE:80:B0`. Sin clave, lee la voz del propio radio.

## Primera vez en cada aparato

- **Teléfono:** abrir LetMeKnow y dar los tres permisos que pide: leer notificaciones, mostrar sobre otras apps (para abrir WhatsApp desde el radio) y batería sin restricciones.
- **Radio:** abrir LetMeKnow, dar «Mostrar burbujas encima de las apps» y el micrófono, y tocar «Probar».
