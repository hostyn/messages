package dev.hostyn.messages.otp

import org.junit.Assert.assertEquals
import org.junit.Test

class OtpDetectorTest {
    private fun assertCode(expected: String?, text: String) = assertEquals(text, expected, OtpDetector.find(text))

    @Test
    fun englishMessages() {
        assertCode("123456", "Your verification code is 123456")
        assertCode("123456", "G-123456 is your Google verification code.")
        assertCode("482913", "482913 is your Instagram code. Don't share it.")
        assertCode("7731", "Your WhatsApp code: 7731. Don't share this code with others")
        assertCode("123456", "Your Amazon OTP is 123-456. Do not share it with anyone.")
        assertCode("847291", "[Microsoft] Use 847291 as Microsoft account security code")
        assertCode("39581", "Telegram code: 39581\n\nYou can also tap on this link to log in: https://t.me/login/39581")
        assertCode("620183", "Your Uber code: 620183. Never share this code. Reply STOP ALL to 89203 to unsubscribe.")
        assertCode("A7K29Q", "Your login code is A7K29Q")
        assertCode("123456", "Use code 123 456 to sign in")
    }

    @Test
    fun spanishMessages() {
        assertCode("123456", "Tu código de verificación es 123456")
        assertCode("123456", "G-123456 es tu código de verificación de Google.")
        assertCode("4821", "BBVA: Clave para operar: 4821. No la compartas con nadie.")
        assertCode("93847261", "Santander: Tu clave de firma para la operación de 150,00 EUR es 93847261")
        assertCode("572910", "CaixaBank: Codigo de seguridad 572910 para confirmar la compra en AMAZON por importe de 23,99€")
        assertCode("118274", "Código de un solo uso: 118274. Caduca en 5 min.")
        assertCode("660129", "660129 es tu código de acceso a Bizum. No lo compartas.")
        assertCode("884213", "Tu PIN temporal es 884213")
        assertCode("51273", "Introduce 51273 para iniciar sesión en Wallapop")
    }

    @Test
    fun ignoresAmountsTimesAndDates() {
        assertCode("572910", "Pago de 1250,00 € con tarjeta. Codigo 572910")
        assertCode("4488", "Tu clave es 4488. Valida hasta las 14:30 del 12/05/2025")
        assertCode("193847", "Código 193847 válido hasta 2025")
    }

    @Test
    fun ignoresMessagesWithoutCodes() {
        assertCode(null, "Quedamos a las 8 en la plaza?")
        assertCode(null, "Te llamo enseguida, estoy en el 600 123 456")
        assertCode(null, "Tu pedido 123456789 ha sido enviado")
        assertCode(null, "Feliz 2025!! Nos vemos el 3 de enero")
        assertCode(null, "Tu factura de 45,90€ ya está disponible")
        assertCode(null, "Your code is ready to review on GitHub")
        assertCode(null, "Llama al +34 600123456 para confirmar la cita")
    }
}
