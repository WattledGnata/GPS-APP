// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

/**
 * 分段成绩（sector split）配色分级纯函数（无副作用，可单测）。
 *
 * round sector-split-coloring：按赛车计时通用规范给圈成绩单的每个 sector 单元格上色。
 *
 * ## 配色规范（user 2026-05-31 锁定，赛车计时通用）
 *
 * - **紫色 [SectorColor.PURPLE]**：该分段全场最快（overall best sector，所有圈里该分段的最快段）。
 * - **绿色 [SectorColor.GREEN]**：比"最快圈（best lap）"对应分段快（improved vs best lap 的该分段），
 *   但不是全场最快。
 * - **白色 [SectorColor.WHITE]**：其余（既非全场最快、也不快于最快圈的该分段）。
 *
 * ## 优先级
 *
 * 紫 > 绿 > 白。即：若某段同时是全场最快，则紫色压过绿色（全场最快段本身也快于/等于 best lap 段，
 * 但 user 规范要求最高荣誉显紫，不降级绿）。
 */
object SectorColorClass {

    /** 分段配色三态（UI 层映射到 TrackTechColors）。 */
    enum class SectorColor { PURPLE, GREEN, WHITE }

    /**
     * 给单个 sector 单元格分级配色。
     *
     * @param sectorMs           当前圈该 sector 的耗时（毫秒，> 0）
     * @param overallBestSectorMs 全场该 sector 的最快段耗时（毫秒，> 0）；无完整圈基准时传 null
     * @param bestLapSectorMs    最快圈（best lap）对应该 sector 的段耗时（毫秒，> 0）；无 best lap 基准时传 null
     * @return 配色三态
     */
    fun sectorColorClass(
        sectorMs: Long,
        overallBestSectorMs: Long?,
        bestLapSectorMs: Long?,
    ): SectorColor {
        // 全场最快段 → 紫（最高优先级）。用 <= 容错并列最快（多圈同段同 ms 都显紫）。
        if (overallBestSectorMs != null && sectorMs <= overallBestSectorMs) {
            return SectorColor.PURPLE
        }
        // 比最快圈对应段快 → 绿（次优先级）。严格快（<），等于不算 improved。
        if (bestLapSectorMs != null && sectorMs < bestLapSectorMs) {
            return SectorColor.GREEN
        }
        return SectorColor.WHITE
    }
}
