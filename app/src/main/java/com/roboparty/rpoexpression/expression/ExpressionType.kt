/*
 * SPDX-FileCopyrightText: 2026 Roboparty Camp
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.roboparty.rpoexpression.expression

/**
 * 机器人表情类型枚举
 * 纯眼部表情体系：功能类、情绪类、系统状态类三大类
 * 优先级规则：系统告警 > 说话 > 应答反馈/情绪类 > 识别中 > 思考 > 待机/系统状态
 * 高优先级可以抢占低优先级，低优先级无法抢占高优先级
 */
enum class ExpressionType(
    val priority: Int,
    val defaultDuration: Long,
    val isAutoFallback: Boolean,
    val category: ExpressionCategory,
    val description: String
) {
    // ==================== 功能类表情 ====================
    /**
     * 待机状态：默认状态，循环低幅度微动 + 自动眨眼
     * 优先级最低，其他表情结束后自动回落至此
     */
    IDLE(
        priority = 0,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        category = ExpressionCategory.FUNCTIONAL,
        description = "待机"
    ),

    /**
     * 眨眼状态：待机附属状态，快速眨眼动画
     * 内部自动触发，不对外暴露
     */
    BLINK(
        priority = 0,
        defaultDuration = 150L,
        isAutoFallback = true,
        category = ExpressionCategory.FUNCTIONAL,
        description = "眨眼"
    ),

    /**
     * 说话状态：语音播报时触发，瞳孔微动 + 眼睑随语速颤动
     * 优先级最高
     */
    SPEAKING(
        priority = 5,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        category = ExpressionCategory.FUNCTIONAL,
        description = "说话"
    ),

    /**
     * 思考状态：ASR准备/任务处理时触发，瞳孔缓慢游走
     * 优先级高于待机
     */
    THINKING(
        priority = 1,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        category = ExpressionCategory.FUNCTIONAL,
        description = "思考"
    ),

    /**
     * 识别中状态：语音识别/人脸识别工作时触发，瞳孔聚焦 + 扫描光效
     * 优先级高于思考
     * 设置30秒自动回落，防止异常常驻（正常识别不会超过15秒）
     */
    RECOGNIZING(
        priority = 2,
        defaultDuration = 30000L,
        isAutoFallback = true,
        category = ExpressionCategory.FUNCTIONAL,
        description = "识别中"
    ),

    /**
     * 应答反馈状态：识别成功/指令执行完成时触发，持续1s后回落
     * 优先级高于识别/思考，低于说话，不会打断播报
     */
    RESPONSE(
        priority = 4,
        defaultDuration = 1500L,
        isAutoFallback = true,
        category = ExpressionCategory.FUNCTIONAL,
        description = "应答反馈"
    ),

    // ==================== 情绪类表情 ====================
    /**
     * 开心表情：正向反馈/完成任务时触发，眼尾上扬 + 瞳孔放大
     * 持续1.8s后回落
     */
    HAPPY(
        priority = 4,
        defaultDuration = 1800L,
        isAutoFallback = true,
        category = ExpressionCategory.EMOTIONAL,
        description = "开心"
    ),

    /**
     * 疑惑表情：识别失败/理解困难时触发，眉头微蹙 + 瞳孔斜视
     * 持续1.5s后回落
     */
    CONFUSED(
        priority = 3,
        defaultDuration = 1500L,
        isAutoFallback = true,
        category = ExpressionCategory.EMOTIONAL,
        description = "疑惑"
    ),

    /**
     * 惊讶表情：收到意外指令/触发惊喜效果时触发，眼睑张大 + 瞳孔缩小
     * 持续1.2s后回落
     */
    SURPRISED(
        priority = 4,
        defaultDuration = 1200L,
        isAutoFallback = true,
        category = ExpressionCategory.EMOTIONAL,
        description = "惊讶"
    ),

    /**
     * 委屈/难过表情：执行出错/无法完成指令时触发，眼尾下垂 + 眼睑半合
     * 持续1.8s后回落
     */
    SAD(
        priority = 4,
        defaultDuration = 1800L,
        isAutoFallback = true,
        category = ExpressionCategory.EMOTIONAL,
        description = "委屈/难过"
    ),

    /**
     * 生气表情：指令错误/触发警告时触发，眼睑收紧 + 瞳孔缩小 + 红光效
     * 持续1.5s后回落
     */
    ANGRY(
        priority = 4,
        defaultDuration = 1500L,
        isAutoFallback = true,
        category = ExpressionCategory.EMOTIONAL,
        description = "生气"
    ),

    /**
     * 困倦表情：低电量/长时间无操作时触发，眼睑下垂 + 瞳孔涣散
     * 持续状态
     */
    SLEEPY(
        priority = 1,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        category = ExpressionCategory.EMOTIONAL,
        description = "困倦"
    ),

    /**
     * 害羞表情：收到夸奖/触发羞涩效果时触发，眼睑微垂 + 眼周粉光
     * 持续1.2s后回落
     */
    SHY(
        priority = 4,
        defaultDuration = 1200L,
        isAutoFallback = true,
        category = ExpressionCategory.EMOTIONAL,
        description = "害羞"
    ),

    // ==================== 系统状态类表情 ====================
    /**
     * 低电量状态：电量低于20%时触发，眼睑半合 + 黄色闪烁光效
     * 持续状态
     */
    LOW_BATTERY(
        priority = 1,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        category = ExpressionCategory.SYSTEM,
        description = "低电量"
    ),

    /**
     * 充电中状态：插入充电器时触发，瞳孔呼吸 + 绿色充电光效
     * 持续状态
     */
    CHARGING(
        priority = 1,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        category = ExpressionCategory.SYSTEM,
        description = "充电中"
    ),

    /**
     * 报告告警状态：系统错误/异常触发时触发，眼睑张大 + 红色闪烁告警
     * 持续状态直到手动清除
     */
    ALERT(
        priority = 6,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        category = ExpressionCategory.SYSTEM,
        description = "报告告警"
    ),

    /**
     * 休眠状态：长时间无操作/手动休眠时触发，眼睑闭合 + 呼吸灯
     * 持续状态
     */
    SLEEPING(
        priority = 0,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        category = ExpressionCategory.SYSTEM,
        description = "休眠"
    ),

    /**
     * 关机状态：长按额头4秒，完全停止摄像头/麦克风
     * 只有再次长按额头4秒才能唤醒
     */
    POWER_OFF(
        priority = 7,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        category = ExpressionCategory.SYSTEM,
        description = "关机"
    ),

    /**
     * 固件升级状态：OTA升级时触发，进度条动画 + 蓝色光效
     * 持续状态直到升级完成
     */
    UPDATING(
        priority = 6,
        defaultDuration = Long.MAX_VALUE,
        isAutoFallback = false,
        category = ExpressionCategory.SYSTEM,
        description = "固件升级"
    );

    companion object {
        /**
         * 根据指令字符串获取表情类型
         */
        fun fromCommand(command: String): ExpressionType? {
            return when (command.lowercase()) {
                "idle", "待机" -> IDLE
                "blink", "眨眼" -> BLINK
                "speaking", "speak", "说话" -> SPEAKING
                "thinking", "think", "思考" -> THINKING
                "recognizing", "recognize", "识别中" -> RECOGNIZING
                "response", "reply", "应答" -> RESPONSE
                "happy", "开心" -> HAPPY
                "confused", "疑惑" -> CONFUSED
                "surprised", "惊讶" -> SURPRISED
                "sad", "委屈", "难过" -> SAD
                "angry", "生气" -> ANGRY
                "sleepy", "困倦" -> SLEEPY
                "shy", "害羞" -> SHY
                "low_battery", "低电量" -> LOW_BATTERY
                "charging", "充电中" -> CHARGING
                "alert", "告警" -> ALERT
                "sleeping", "休眠" -> SLEEPING
                "power_off", "关机" -> POWER_OFF
                "updating", "升级" -> UPDATING
                else -> null
            }
        }
    }
}

/**
 * 表情分类枚举
 */
enum class ExpressionCategory {
    FUNCTIONAL,  // 功能类
    EMOTIONAL,   // 情绪类
    SYSTEM       // 系统状态类
}
