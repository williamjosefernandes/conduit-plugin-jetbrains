package dev.conduit.ide.i18n

import dev.conduit.ide.ConduitBundle
import org.jetbrains.annotations.Nls
import java.text.DateFormat
import java.util.Date

/** Numbers, money and dates as the language of Conduit's interface writes them (`$0.12` / `US$ 0,12`). */
object ConduitFormats {
    /** An amount in US dollars, which is what agents report. */
    @Nls
    fun usd(amount: Double): String = ConduitBundle.message("format.usd", amount)

    @Nls
    fun time(epochMillis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT, locale()).format(Date(epochMillis))

    @Nls
    fun dateTime(epochMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale()).format(Date(epochMillis))

    private fun locale() = ConduitLanguageService.current().locale
}
