package com.cairn.app.vpn

/**
 * 假 VPN 服务器列表 — 用于主页伪装的"选线路"功能。
 *
 * 这些数据完全是装饰性的，不会建立任何真实连接。
 * 选择的服务器会持久化到设置里，下次启动恢复。
 *
 * 设计为常见 VPN App 风格：地区国旗 emoji + 城市 + 延迟 + 等级标签。
 */
object VpnServerData {

    data class Server(
        val id: String,
        val country: String,
        val city: String,
        val flag: String,       // emoji 国旗
        val ping: Int,          // 假延迟 ms
        val tier: Tier,         // 等级（Free/Premium）
        val load: Int           // 假负载 0-100
    )

    enum class Tier(val displayName: String, val color: Long) {
        FREE("FREE", 0xFF94A3B8),
        PREMIUM("PREMIUM", 0xFFEAB308),
        PLUS("PLUS", 0xFF7BB3F0)
    }

    val SERVERS: List<Server> = listOf(
        Server("auto",    "Auto",          "智能选择",  "⚡", 0,   Tier.PLUS,    35),
        Server("hk-1",    "Hong Kong",     "香港 1",   "🇭🇰", 12,  Tier.PREMIUM, 42),
        Server("hk-2",    "Hong Kong",     "香港 2",   "🇭🇰", 18,  Tier.PREMIUM, 67),
        Server("jp-tokyo","Japan",         "东京",     "🇯🇵", 45,  Tier.PREMIUM, 28),
        Server("jp-osaka","Japan",         "大阪",     "🇯🇵", 52,  Tier.PREMIUM, 19),
        Server("sg-1",    "Singapore",     "新加坡",   "🇸🇬", 68,  Tier.PREMIUM, 35),
        Server("us-la",   "United States", "洛杉矶",   "🇺🇸", 145, Tier.PLUS,    81),
        Server("us-ny",   "United States", "纽约",     "🇺🇸", 198, Tier.PLUS,    72),
        Server("uk-ldn",  "United Kingdom","伦敦",     "🇬🇧", 215, Tier.PLUS,    55),
        Server("de-fra",  "Germany",       "法兰克福", "🇩🇪", 234, Tier.PLUS,    48),
        Server("nl-ams",  "Netherlands",   "阿姆斯特丹","🇳🇱", 245, Tier.PLUS,    33),
        Server("kr-seoul","South Korea",   "首尔",     "🇰🇷", 38,  Tier.FREE,    91),
        Server("tw-tpe",  "Taiwan",        "台北",     "🇹🇼", 22,  Tier.FREE,    87),
    )

    fun byId(id: String): Server = SERVERS.firstOrNull { it.id == id } ?: SERVERS[0]

    /**
     * 假的实时统计 — 每次调用返回略有波动的"流量"
     */
    fun fakeStats(connectedMs: Long): FakeStats {
        if (connectedMs == 0L) return FakeStats(0, 0, 0, 0)
        val seconds = connectedMs / 1000
        // 模拟波动的下载/上传速率
        val rand = (System.currentTimeMillis() / 500) % 100
        val downKbps = 850 + (rand * 12)        // 850-2050 KB/s
        val upKbps = 120 + (rand * 3)           // 120-420 KB/s
        val totalDownMb = (seconds * 1.2).toLong()
        val totalUpMb = (seconds * 0.3).toLong()
        return FakeStats(downKbps, upKbps, totalDownMb, totalUpMb)
    }

    data class FakeStats(
        val downKbps: Long,
        val upKbps: Long,
        val totalDownMb: Long,
        val totalUpMb: Long
    )
}
