package com.localagent.agent;

import java.util.Map;

/**
 * Agent -> UI 事件回调（Swing 实现方需自行切回 EDT 刷新界面）。
 */
public interface UiCallback {
    /** 忙碌状态切换。 */
    void onBusy(boolean busy);
    /** 助手流式输出（增量文本）。 */
    void onAssistantDelta(String chunk);
    /** 助手单轮输出结束（finalText 为累计全文）。 */
    void onAssistantDone(String finalText, java.util.Map<String, Long> stats);
    /**
     * 单次模型请求已发出、首字尚未返回时的中性等待提示（"正在回复…"）。
     * 与是否开启深度思考无关，用于覆盖本地模型 9~15 秒的首字等待空窗；
     * 收到思考流时由 {@link #onThinking(boolean)} 升级为"正在思考…"。
     */
    void onWaiting();
    /**
     * 推理模型思考阶段状态切换。
     * @param active true=模型正在输出思考过程（尚无正式回答）；false=开始正式回答或已结束
     */
    void onThinking(boolean active);
    /**
     * 回合内发生需要让用户感知的错误（持久化失败、请求异常等）。
     * @param message 面向用户的中文错误说明
     */
    void onError(String message);
    /** 工具卡片状态更新（pending/running/done/blocked/rejected/error）。 */
    void onTool(ToolCard card);
    /** 会话列表变化（新建/删除/重命名）。 */
    void onConversationList();
    /** 提醒触发。 */
    void onReminder(Map<String, Object> reminder);
}
