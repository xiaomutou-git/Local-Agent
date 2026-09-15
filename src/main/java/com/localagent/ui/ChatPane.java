package com.localagent.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天消息区（替代原 JTextPane HTML 方案，改用真实 Swing 组件自绘）。
 *
 * 组成：
 * - 用户消息：右对齐主色圆角气泡（白字）
 * - 助手消息：左对齐白色圆角卡片（深色字、浅描边）
 * - 工具条目：左对齐浅灰圆角条（彩色状态点 + 工具名 + 状态；待确认/执行中显示参数摘要）
 * - 无消息时：居中欢迎引导
 *
 * 流式输出时复用同一个助手气泡，仅更新文本。
 */
public final class ChatPane extends JPanel {
    private static final long serialVersionUID = 1L;

    private static final int BUBBLE_CONTENT_WIDTH = 520;
    private static final Color USER_BUBBLE = new Color(0xEDEFF2);  // 中性浅灰（非彩色实心）
    private static final Color ASSIST_BUBBLE = new Color(0, 0, 0, 0); // 助手消息：无底，纯文字
    private static final Color CHIP = new Color(0, 0, 0, 0); // 工具条目无底，纯文字行

    // 纯运行期 UI 状态，Swing 组件从不做 Java 序列化：标 transient 表明意图
    private final transient Map<String, ChipPanel> chips = new LinkedHashMap<>();
    private BubblePanel streamingBubble;
    private JComponent streamingWrap;
    private boolean welcomeShown = true;
    private static final Color ERROR_TEXT = new Color(0xD92D20);

    // 流式增量缓冲：r1:8b 每秒可吐数十个 token，逐 token revalidate 会拖慢界面，
    // 按 40ms 节拍合并成一次布局/重绘/滚动
    private final StringBuilder pendingDelta = new StringBuilder();
    private final Timer streamTimer = new Timer(40, e -> flushPending());
    { streamTimer.setRepeats(false); }

    // 等待/思考占位的省略号动画（1~3 点循环），占位期间运行，克制不抢视线
    private final Timer waitTimer = new Timer(500, null);
    private int waitDots;
    // 占位文案前缀，随等待状态切换："正在回复"（首字等待）/ "正在思考"（深度思考流）
    private String placeholderBase = "正在回复";

    public ChatPane() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(UiTheme.WINDOW);
        setBorder(new EmptyBorder(12, 18, 12, 18));
        waitTimer.addActionListener(e -> tickPlaceholder());
        showWelcome();
    }

    /** 清空并显示欢迎空状态。 */
    public void reset() {
        streamTimer.stop();
        waitTimer.stop();
        pendingDelta.setLength(0);
        removeAll();
        chips.clear();
        streamingBubble = null;
        streamingWrap = null;
        welcomeShown = true;
        showWelcome();
        revalidate();
        repaint();
    }

    /** 极简空状态：一行灰色提示，无彩色 logo/大标题。 */
    private void showWelcome() {
        add(Box.createVerticalGlue());
        JLabel hint = new JLabel("开始一段新对话");
        hint.setFont(UiTheme.font(13));
        hint.setForeground(UiTheme.TEXT_FAINT);
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        JPanel wrap = new JPanel(new BorderLayout()) {
            @Override public Dimension getMaximumSize() {
                Dimension d = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, d.height);
            }
        };
        wrap.setOpaque(false);
        wrap.add(hint, BorderLayout.CENTER);
        add(wrap);
        add(Box.createVerticalGlue());
    }

    private void clearWelcomeIfNeeded() {
        if (welcomeShown) {
            removeAll();
            welcomeShown = false;
        }
    }

    /** 追加一条用户消息（右对齐浅灰气泡，深字）。 */
    public void addUser(String text) {
        clearWelcomeIfNeeded();
        add(Box.createVerticalStrut(12));
        BubblePanel b = new BubblePanel(USER_BUBBLE, null, true);
        b.setContent(text);
        add(align(b, true));
        add(Box.createVerticalStrut(2));
        revalidate();
        scrollToBottom();
    }

    /** 历史会话中的助手消息（左对齐纯文本，无卡片边框）。 */
    public void addAssistantFinal(String text) {
        clearWelcomeIfNeeded();
        add(Box.createVerticalStrut(4));
        BubblePanel b = new BubblePanel(ASSIST_BUBBLE, null, false);
        b.setContent(text);
        add(align(b, false));
        add(Box.createVerticalStrut(8));
        revalidate();
        scrollToBottom();
    }

    /**
     * 流式输出：增量先入缓冲，按固定节拍合并刷新（所有调用均在 EDT）。
     * @param chunk 模型本次返回的文本增量，可为空串（直接忽略）
     */
    public void appendAssistantDelta(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        clearWelcomeIfNeeded();
        pendingDelta.append(chunk);
        if (!streamTimer.isRunning()) streamTimer.restart();
    }

    /** 把缓冲的增量一次性落到气泡并刷新布局（Timer 回调，EDT 执行）。 */
    private void flushPending() {
        if (pendingDelta.length() == 0) return;
        ensureStreamingBubble();
        streamingBubble.appendText(pendingDelta.toString());
        pendingDelta.setLength(0);
        revalidate();
        scrollToBottom();
    }

    /** 懒创建流式气泡及其外层包装，返回是否为本次新建。 */
    private void ensureStreamingBubble() {
        if (streamingBubble != null) return;
        add(Box.createVerticalStrut(4));
        streamingBubble = new BubblePanel(ASSIST_BUBBLE, null, false);
        streamingWrap = align(streamingBubble, false);
        add(streamingWrap);
        add(Box.createVerticalStrut(8));
    }

    /**
     * 显示中性等待占位"正在回复…"。
     * 请求发出后首字返回前（本地 8B 模型约 9~15 秒）调用，与深度思考开关无关；
     * 复用流式气泡显示浅色占位文字，正式内容到达时原位替换，避免布局跳动。
     */
    public void showWaiting() {
        // 等待状态可能与上一轮残留缓冲同时到达，先落屏避免时序错乱
        if (pendingDelta.length() > 0) flushPending();
        clearWelcomeIfNeeded();
        ensureStreamingBubble();
        if (streamingBubble.isPlaceholderOnly()) {
            placeholderBase = "正在回复";
            waitDots = 1;
            streamingBubble.showPlaceholder(placeholderBase + ".".repeat(waitDots));
            waitTimer.restart();
        }
        revalidate();
        repaint();
    }

    /**
     * 切换推理模型的"思考中"占位提示（仅在用户开启深度思考时由 Agent 上抛）。
     * 思考阶段复用流式气泡显示浅色占位文字，正式内容到达时原位替换，避免布局跳动。
     * @param active true=占位切换为"正在思考…"；false=思考流结束，纯占位时回退为"正在回复…"
     */
    public void showThinking(boolean active) {
        // 思考状态可能与首批增量几乎同时到达，先把缓冲落屏避免时序错乱
        if (pendingDelta.length() > 0) flushPending();
        if (streamingBubble != null && streamingBubble.isPlaceholderOnly()) {
            placeholderBase = active ? "正在思考" : "正在回复";
            waitDots = 1;
            streamingBubble.showPlaceholder(placeholderBase + ".".repeat(waitDots));
            // 思考结束到首字到达之间可能仍有空窗，回退后保持省略号动画继续提示
            waitTimer.restart();
        }
        revalidate();
        repaint();
    }

    /** 占位省略号动画帧：1~3 个点循环，仅刷新仍停留在占位态的气泡。 */
    private void tickPlaceholder() {
        if (streamingBubble == null || !streamingBubble.isPlaceholderOnly()) {
            waitTimer.stop();
            return;
        }
        waitDots = waitDots >= 3 ? 1 : waitDots + 1;
        streamingBubble.showPlaceholder(placeholderBase + ".".repeat(waitDots));
        revalidate();
    }

    /**
     * 移除仍停留在占位态的气泡并停止动画（错误上屏/整轮无正文收尾时调用）。
     * 若气泡已含正式内容则原样保留，只停止动画。
     */
    private void dismissPlaceholder() {
        waitTimer.stop();
        if (streamingBubble != null && streamingBubble.isPlaceholderOnly() && streamingWrap != null) {
            remove(streamingWrap);
            streamingBubble = null;
            streamingWrap = null;
        }
    }

    /**
     * 显示一条左对齐错误提示（红色文字气泡）。
     * @param text 错误说明文本（可为 null，按空串处理）
     */
    public void addError(String text) {
        // 错误气泡上屏前撤掉等待/思考占位，避免两个提示同时残留
        dismissPlaceholder();
        clearWelcomeIfNeeded();
        add(Box.createVerticalStrut(4));
        BubblePanel b = new BubblePanel(ASSIST_BUBBLE, null, false);
        b.setError(true);
        b.setContent(text == null ? "" : text);
        add(align(b, false));
        add(Box.createVerticalStrut(8));
        revalidate();
        scrollToBottom();
    }

    /** 一轮助手输出结束：先冲掉缓冲增量，再解除流式气泡引用。 */
    public void finishAssistant() {
        streamTimer.stop();
        waitTimer.stop();
        if (pendingDelta.length() > 0) flushPending();
        // 本轮只有占位没有任何正文（纯工具调用轮、纯思考轮）：移除占位气泡，
        // 避免"正在回复…"永久残留在消息流中
        if (streamingBubble != null && streamingBubble.isPlaceholderOnly()
                && streamingWrap != null) {
            remove(streamingWrap);
            revalidate();
            repaint();
        }
        streamingBubble = null;
        streamingWrap = null;
    }

    /** 新增/更新一个工具条目。 */
    public void updateTool(String id, String tool, String status, String argsPreview) {
        clearWelcomeIfNeeded();
        ChipPanel chip = chips.get(id);
        if (chip == null) {
            add(Box.createVerticalStrut(4));
            chip = new ChipPanel();
            chips.put(id, chip);
            add(align(chip, false));
            add(Box.createVerticalStrut(2));
        }
        chip.update(tool, status, argsPreview);
        revalidate();
        scrollToBottom();
    }

    /** 历史会话中只读的工具结果条目。 */
    public void addToolHistory(String tool, String preview) {
        clearWelcomeIfNeeded();
        add(Box.createVerticalStrut(4));
        ChipPanel chip = new ChipPanel();
        chip.update(tool, "done", preview);
        add(align(chip, false));
        add(Box.createVerticalStrut(2));
        revalidate();
        scrollToBottom();
    }

    /**
     * 左/右对齐包装（气泡自身宽度由内容决定）。
     * 关键：纵向 BoxLayout 会把容器剩余高度分配给 maximumSize 高度无限大的子组件，
     * 消息较少时会把一行字的气泡拉伸成超高色块，故把包装层最大高度锁为首选高度。
     */
    private JComponent align(JComponent bubble, boolean right) {
        JPanel wrap = new JPanel(new BorderLayout()) {
            @Override public Dimension getMaximumSize() {
                Dimension d = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, d.height);
            }
        };
        wrap.setOpaque(false);
        wrap.add(bubble, right ? BorderLayout.EAST : BorderLayout.WEST);
        return wrap;
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> scrollRectToVisible(
                new Rectangle(0, getHeight() - 1, getWidth(), 1)));
    }

    // ================= 气泡 =================
    private static final Font PROBE = UiTheme.font(14);
    private static final FontMetrics PROBE_FM = new JLabel().getFontMetrics(PROBE);

    private static class BubblePanel extends RoundedPanel {
        private static final long serialVersionUID = 1L;

        private final JLabel label = new JLabel();
        private final StringBuilder text = new StringBuilder();
        private final boolean user;
        private int contentWidth = 300;
        private boolean placeholder;
        private boolean errorStyle;

        BubblePanel(Color fill, Color stroke, boolean user) {
            super(new BorderLayout(), 8, fill, stroke, 1);
            this.user = user;
            // 用户消息是浅灰圆角气泡；助手消息无底无内边距（纯文本留白）
            setBorder(user ? new EmptyBorder(8, 13, 8, 13) : new EmptyBorder(0, 0, 0, 0));
            label.setVerticalAlignment(SwingConstants.TOP);
            add(label, BorderLayout.CENTER);
        }

        void setContent(String t) {
            placeholder = false;
            text.setLength(0);
            if (t != null) text.append(t);
            refresh();
        }

        void appendText(String t) {
            // 首块正式内容到达时原位替换"正在思考…"占位
            if (placeholder) { text.setLength(0); placeholder = false; }
            if (t != null) text.append(t);
            refresh();
        }

        /** 显示浅色占位文字（首字等待/深度思考阶段）。 */
        void showPlaceholder(String t) {
            placeholder = true;
            text.setLength(0);
            if (t != null) text.append(t);
            refresh();
        }

        /** 是否仍停留在占位态（尚无任何正式内容）。 */
        boolean isPlaceholderOnly() { return placeholder; }

        /** 切换错误文字配色。 */
        void setError(boolean error) { this.errorStyle = error; refresh(); }

        private void refresh() {
            // 按最长行实际像素决定内容宽（短文本气泡不撑满，长文本封顶 540）
            int maxLine = 0;
            boolean multiline = text.indexOf("\n") >= 0;
            for (String line : text.toString().split("\n", -1))
                maxLine = Math.max(maxLine, PROBE_FM.stringWidth(line));
            if (!multiline && maxLine <= BUBBLE_CONTENT_WIDTH) {
                contentWidth = Math.max(24, maxLine);
            } else {
                contentWidth = BUBBLE_CONTENT_WIDTH;
            }
            String esc = Html.esc(text.toString());
            label.setText("<html><body style='width:" + contentWidth + "px'>" + esc.replace("\n", "<br>") + "</body></html>");
            label.setFont(PROBE);
            // 错误用警示红；等待/思考占位用弱化灰；正常消息用正文色
            label.setForeground(errorStyle ? ERROR_TEXT
                    : (placeholder ? UiTheme.TEXT_FAINT : UiTheme.TEXT));
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            // 无底气泡（助手消息）按内容宽度；用户浅灰气泡留内边距
            int pad = user ? 28 : 0;
            return new Dimension(Math.min(d.width, contentWidth + pad), d.height);
        }

        @Override
        public Dimension getMaximumSize() {
            // 高度跟随首选尺寸，避免在 BoxLayout 中被纵向拉伸
            return new Dimension(BUBBLE_CONTENT_WIDTH + 40, getPreferredSize().height);
        }
    }

    // ================= 工具条目 =================
    private static class ChipPanel extends RoundedPanel {
        private static final long serialVersionUID = 1L;

        private final JLabel nameLabel = new JLabel();
        private final JLabel previewLabel = new JLabel();
        private String status = "";

        ChipPanel() {
            super(new BorderLayout(8, 1), 0, CHIP, null, 0);
            setBorder(new EmptyBorder(3, 2, 3, 2));
            JPanel top = new JPanel(new BorderLayout(8, 0));
            top.setOpaque(false);
            nameLabel.setFont(UiTheme.font(12));
            top.add(nameLabel, BorderLayout.WEST);
            add(top, BorderLayout.NORTH);
            previewLabel.setFont(UiTheme.font(11));
            previewLabel.setForeground(UiTheme.TEXT_FAINT);
            add(previewLabel, BorderLayout.SOUTH);
        }

        void update(String tool, String status, String preview) {
            this.status = status == null ? "" : status;
            // HTML 内联着色：彩色状态点 + 工具名 + 状态文字
            nameLabel.setText("<html><span style='color:" + statusHex(this.status) + "'>●</span> "
                    + Html.esc(tool) + " <span style='color:" + statusHex(this.status) + "'>· " + Html.esc(statusText(this.status)) + "</span></html>");
            boolean showArgs = ("pending".equals(this.status) || "running".equals(this.status))
                    && preview != null && !preview.isBlank();
            if (showArgs) {
                previewLabel.setText(preview.length() > 90 ? preview.substring(0, 90) + "…" : preview);
                previewLabel.setVisible(true);
            } else {
                previewLabel.setVisible(false);
            }
        }

        private static String statusHex(String s) {
            return switch (s) {
                case "blocked", "rejected", "error" -> "#D92D20";
                case "running", "pending" -> "#C77700";
                case "done" -> "#12805C";
                default -> "#9AA4B2";
            };
        }

        private static String statusText(String s) {
            return switch (s) {
                case "running" -> "执行中";
                case "pending" -> "等待确认";
                case "done" -> "完成";
                case "blocked" -> "已阻止";
                case "rejected" -> "已拒绝";
                case "error" -> "失败";
                default -> s;
            };
        }
    }
}
