package dev.hostyn.messages.otp

/**
 * Finds the one-time code in a verification message ("Your code is 123456", "G-123456 es tu
 * código de Google", "Clave: 4821"...). Only looks when the text talks about a code, so order
 * numbers, amounts or phone numbers in ordinary messages aren't mistaken for one.
 */
object OtpDetector {
    /** Words that verification messages use, in English and Spanish (with and without accents). */
    private val keyword = Regex(
        "(?i)(c[oó]digo|\\bcode|\\botp\\b|\\bpin\\b|\\bclave|contrase[nñ]a|password|passcode|" +
            "verificaci[oó]n|verification|verify|verificar|\\b2fa\\b|\\btoken|autenticaci[oó]n|authentication|" +
            "\\blogin|\\bsign[- ]?in|inicio de sesi[oó]n|iniciar sesi[oó]n|one[- ]time|un solo uso|" +
            "confirmaci[oó]n|confirmation|confirm|security|seguridad|acceso|access)",
    )

    /**
     * A code: 4–8 digits (maybe split as "123 456" or "123-456"), or 5–8 capital letters and
     * digits mixed ("A7K29Q"), optionally after a short prefix like "G-". Not part of a longer
     * number, an amount, a time, a date, a URL or a phone number.
     */
    private val candidate = Regex(
        "(?<![\\p{L}\\p{N}€$£+/@#_]|\\d[.,:/-]|[.,:/-]\\d)" +
            "(?:[A-Z]{1,3}-)?" +
            "(\\d{3,4}[ -]\\d{3,4}|\\d{4,8}|(?=[A-Z0-9]*\\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{5,8})" +
            "(?![\\p{L}\\p{N}€$£%_@]|[.,:/-]\\d|\\s?(?:€|eur|euros|usd|\\$|%|min|h\\b))",
    )

    private val year = Regex("(19|20)\\d\\d")

    fun find(text: String): String? {
        if (text.length > 600) return null
        val keywords = keyword.findAll(text).map { it.range }.toList()
        if (keywords.isEmpty()) return null
        return candidate.findAll(text)
            .mapNotNull { m ->
                val group = m.groups[1] ?: return@mapNotNull null
                val raw = group.value
                val code = raw.replace(" ", "").replace("-", "")
                // "123 456" is fine, but "600 123" from a phone number "600 123 456" isn't.
                if (raw.contains(' ') && Regex("\\d{3}[ -]\\d{3}[ -]\\d{3}").containsMatchIn(text.substring(m.range.first))) {
                    return@mapNotNull null
                }
                val distance = keywords.minOf { k ->
                    when {
                        group.range.first > k.last -> group.range.first - k.last // Code after the keyword: the usual case.
                        else -> (k.first - group.range.last) * 2 // Before it ("123456 is your code"): a bit less likely.
                    }
                }
                var score = distance
                if (code.length == 4 && year.matches(code)) score += 80
                if (code.all { it.isDigit() } && code.length in 5..8 && code.length != 7) score -= 5
                if (!code.all { it.isDigit() }) score += 15
                code to score
            }
            .minByOrNull { it.second }
            ?.first
    }
}
