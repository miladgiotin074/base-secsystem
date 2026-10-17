package com.base.realtime.access

import com.base.realtime.config.AppConfig

/** فلگ‌های دیباگ / تست auto-grant. */
internal object AutoGrantDebug {
    /**
     * true = Cover و ظاهر Play روی AutoGrantActivity خاموش.
     * محصول: false وقتی Cover آماده است.
     */
    @JvmField
    var hideCover: Boolean = false

    /**
     * از config.json (`autoGrantUseAi`):
     * true = فقط AI نقاط کلیک را شناسایی/می‌زند (تپر قطعی خاموش).
     * false = تپر قطعی اول؛ AI فقط بعد از miss.
     */
    @JvmField
    var useAi: Boolean = false

    fun apply(config: AppConfig) {
        useAi = config.autoGrantUseAi
    }
}
